package com.drones.vision.warehouse.application.asset;

import com.drones.vision.warehouse.domain.model.Asset;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.flight.domain.model.AssetUsage;
import com.drones.vision.identity.domain.model.AuditAction;
import com.drones.vision.identity.domain.model.AuditEntry;
import com.drones.vision.identity.domain.model.AuditTargetType;
import com.drones.vision.kernel.Capability;
import com.drones.vision.kernel.CategoryId;
import com.drones.vision.warehouse.domain.model.Device;
import com.drones.vision.warehouse.domain.model.DeviceCategory;
import com.drones.vision.kernel.DeviceId;
import com.drones.vision.kernel.GeoPosition;
import com.drones.vision.kernel.LifecycleState;
import com.drones.vision.kernel.Ownership;
import com.drones.vision.perception.domain.model.PipelineConfig;
import com.drones.vision.kernel.StreamId;
import com.drones.vision.kernel.UserId;
import com.drones.vision.warehouse.domain.port.AssetRepositoryPort;
import com.drones.vision.flight.domain.port.AssetUsageRepositoryPort;
import com.drones.vision.identity.domain.port.AuditTrailPort;
import com.drones.vision.warehouse.domain.port.CategoryRepositoryPort;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import com.drones.vision.warehouse.application.device.DeviceRegistration;
import com.drones.vision.warehouse.application.device.DeviceService;
import com.drones.vision.identity.application.scope.VisibilityScope;
import com.drones.vision.perception.application.stream.ActiveStream;
import com.drones.vision.perception.application.stream.StreamService;
import com.drones.vision.perception.application.stream.TrackingConfigPatch;

/**
 * The one implementation of {@link AssetService}.
 *
 * <p>Devices are reached through {@link DeviceService}, not the device repository: registering,
 * deleting and restoring a source already carry rules (stop its stream, write an audit line) that
 * would otherwise be duplicated here and drift.
 *
 * <p>Runtime {@link AssetStatus} and {@link LifecycleState} are separate axes on purpose — "not
 * streaming right now" and "withdrawn from service" are different facts, and collapsing them
 * would make a deactivated asset indistinguishable from an idle one.
 *
 * <h2>Threading</h2>
 * Holds no mutable state — all shared state is reached through the injected collaborators.
 */
public final class DefaultAssetService implements AssetService {

    /** How many recent usages {@link #details(AssetId)} includes. */
    private static final int RECENT_USAGES_LIMIT = 20;

    /** Unbounded fetch, used only to count what a deletion is preserving. */
    private static final int ALL_USAGES = Integer.MAX_VALUE;

    private final AssetRepositoryPort assetRepository;
    private final CategoryRepositoryPort categoryRepository;
    private final AssetUsageRepositoryPort usageRepository;
    private final AuditTrailPort auditTrail;
    private final DeviceService deviceService;
    private final StreamService streamService;

    public DefaultAssetService(AssetRepositoryPort assetRepository, CategoryRepositoryPort categoryRepository,
                                AssetUsageRepositoryPort usageRepository, AuditTrailPort auditTrail,
                                DeviceService deviceService, StreamService streamService) {
        this.assetRepository = Objects.requireNonNull(assetRepository, "assetRepository must not be null");
        this.categoryRepository = Objects.requireNonNull(categoryRepository, "categoryRepository must not be null");
        this.usageRepository = Objects.requireNonNull(usageRepository, "usageRepository must not be null");
        this.auditTrail = Objects.requireNonNull(auditTrail, "auditTrail must not be null");
        this.deviceService = Objects.requireNonNull(deviceService, "deviceService must not be null");
        this.streamService = Objects.requireNonNull(streamService, "streamService must not be null");
    }

    // --- Creating ------------------------------------------------------------

    @Override
    public Asset create(AssetSpec spec, Ownership ownership, UserId actor) {
        Objects.requireNonNull(spec, "spec must not be null");
        Objects.requireNonNull(ownership, "ownership must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        requireCategory(spec.category());

        Set<DeviceId> deviceIds = new LinkedHashSet<>();
        for (DeviceRegistration registration : spec.devices()) {
            deviceIds.add(deviceService.register(registration, actor).id());
        }
        // Existing, already-registered devices assigned in the same act (the "promote to asset"
        // flow) -- same eligibility rules as #assignDevice, just folded into this asset's very
        // first save instead of a separate per-device assign+audit cycle each.
        for (DeviceId existingDeviceId : spec.existingDeviceIds()) {
            deviceIds.add(requireAssignable(existingDeviceId).id());
        }

        Asset saved = assetRepository.save(new Asset(AssetId.random(), spec.displayName(), spec.category(),
                ownership, deviceIds, spec.attributes()));
        audit(actor, AuditAction.CREATED, saved,
                "Created asset " + saved.displayName() + " with " + deviceIds.size() + " source(s)", Map.of());
        return saved;
    }

    // --- Reading -------------------------------------------------------------

    @Override
    public List<AssetSummary> assets(boolean includeDeleted) {
        Set<DeviceId> activeDevices = streamService.activeDeviceIds();
        return assetRepository.findAll().stream()
                .filter(asset -> includeDeleted || !asset.isDeleted())
                .map(asset -> toSummary(asset, activeDevices))
                .toList();
    }

    @Override
    public List<AssetSummary> assets(VisibilityScope scope, boolean includeDeleted) {
        Objects.requireNonNull(scope, "scope must not be null");
        // Reuse the unscoped result and filter: an unbounded scope returns it unchanged (the
        // guardrail), a bounded one keeps only assets the scope includes.
        return assets(includeDeleted).stream()
                .filter(summary -> scope.includes(summary.asset()))
                .toList();
    }

    @Override
    public AssetDetails details(AssetId id) {
        Asset asset = require(id);
        List<Device> devices = asset.devices().stream()
                .map(deviceService::find)
                .flatMap(Optional::stream)
                .toList();
        return new AssetDetails(toSummary(asset, streamService.activeDeviceIds()), devices,
                usageRepository.findRecentByAsset(id, RECENT_USAGES_LIMIT));
    }

    @Override
    public AssetDetails details(VisibilityScope scope, AssetId id) {
        Objects.requireNonNull(scope, "scope must not be null");
        AssetDetails details = details(id); // NoSuchElementException -> 404 for an unknown id
        if (!scope.includes(details.summary().asset())) {
            // Out of scope: report exactly as "unknown" so existence is not revealed (a 404, not a
            // 403). Same message as require()'s so the two cases are indistinguishable to a caller.
            throw new NoSuchElementException("Unknown asset: " + id.value());
        }
        return details;
    }

    // --- Editing -------------------------------------------------------------

    @Override
    public Asset update(AssetId id, AssetEdit edit, UserId actor) {
        Objects.requireNonNull(edit, "edit must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        Asset asset = require(id);

        if (edit.category() != null) {
            requireCategory(edit.category());
        }
        String displayName = edit.displayName() != null ? edit.displayName() : asset.displayName();
        CategoryId category = edit.category() != null ? edit.category() : asset.category();
        Map<String, String> attributes = edit.attributes() != null ? edit.attributes() : asset.attributes();

        Asset saved = assetRepository.save(asset.withDetails(displayName, category, attributes));
        Map<String, String> changes = changes(asset, saved);
        if (!changes.isEmpty()) {
            audit(actor, AuditAction.UPDATED, saved, "Edited asset " + saved.displayName(), changes);
        }
        return saved;
    }

    @Override
    public Asset setState(AssetId id, LifecycleState state, UserId actor) {
        Objects.requireNonNull(state, "state must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        Asset asset = require(id);

        if (asset.state() == state) {
            return asset; // idempotent: no streams disturbed, nothing written
        }
        if (asset.isDeleted() && state == LifecycleState.ACTIVE) {
            throw new IllegalStateException("Asset " + asset.displayName()
                    + " is deleted; restore it before putting it back into service");
        }
        if (state != LifecycleState.ACTIVE) {
            // Leaving service must take effect now; an asset that keeps streaming after being
            // deactivated would make the state a label rather than a rule.
            stopStreamsOf(asset);
        }

        Asset saved = assetRepository.save(asset.withState(state));
        String what = describe(asset.state(), state);
        audit(actor, actionFor(asset.state(), state), saved,
                "Asset " + saved.displayName() + " " + what, Map.of());
        return saved;
    }

    /**
     * Maps a state transition to the audit action that describes it — duplicated from {@link
     * com.drones.vision.warehouse.application.device.DefaultDeviceService}'s own private helper of the same
     * name/shape rather than shared cross-package, since the two feature packages (docs/plans/active/LAYERING-REFACTOR-PLAN.md
     * &sect;4.1) never depend on each other's internals; this pair is small, pure, and has no state
     * of its own to drift.
     */
    private static AuditAction actionFor(LifecycleState from, LifecycleState to) {
        if (from == LifecycleState.DELETED) {
            return AuditAction.RESTORED;
        }
        return switch (to) {
            case ACTIVE -> AuditAction.ACTIVATED;
            case DEACTIVATED -> AuditAction.DEACTIVATED;
            case DELETED -> AuditAction.DELETED;
        };
    }

    private static String describe(LifecycleState from, LifecycleState to) {
        if (from == LifecycleState.DELETED) {
            return "restored";
        }
        return switch (to) {
            case ACTIVE -> "activated";
            case DEACTIVATED -> "deactivated";
            case DELETED -> "deleted";
        };
    }

    @Override
    public AssetDeletion delete(AssetId id, UserId actor) {
        Objects.requireNonNull(actor, "actor must not be null");
        Asset asset = require(id);

        if (asset.isDeleted()) {
            // Idempotent: re-deleting reports the same picture without touching anything.
            return new AssetDeletion(id, asset.displayName(), asset.devices().size(), countUsages(id), 0);
        }

        int streamsStopped = stopStreamsOf(asset);

        // Soft delete: devices are marked, never removed, so the asset keeps satisfying its
        // "at least one device" invariant and every usage row still resolves to a real source.
        int devicesDeleted = 0;
        for (DeviceId deviceId : asset.devices()) {
            Optional<Device> device = deviceService.find(deviceId);
            if (device.isPresent() && !device.get().isDeleted()) {
                deviceService.delete(deviceId, actor);
                devicesDeleted++;
            }
        }

        int usagesRetained = countUsages(id);
        Asset deleted = assetRepository.save(asset.withState(LifecycleState.DELETED));
        audit(actor, AuditAction.DELETED, deleted, "Deleted asset " + deleted.displayName(),
                Map.of("devicesDeleted", String.valueOf(devicesDeleted),
                        "usagesRetained", String.valueOf(usagesRetained),
                        "streamsStopped", String.valueOf(streamsStopped)));

        return new AssetDeletion(id, asset.displayName(), devicesDeleted, usagesRetained, streamsStopped);
    }

    // --- Streaming -----------------------------------------------------------

    @Override
    public StreamId startStream(AssetId id, DeviceId device, PipelineConfig config) {
        return startStream(id, device, config, TrackingConfigPatch.NOTHING);
    }

    @Override
    public StreamId startStream(AssetId id, DeviceId device, PipelineConfig config, TrackingConfigPatch tracking) {
        Objects.requireNonNull(config, "config must not be null");
        Objects.requireNonNull(tracking, "tracking must not be null");
        Asset asset = require(id);
        if (!asset.isActive()) {
            throw new IllegalStateException("Asset is not in service: " + asset.displayName());
        }
        if (device != null && !asset.devices().contains(device)) {
            throw new IllegalArgumentException(
                    "Device " + device.value() + " does not belong to asset " + id.value());
        }
        return streamService.start(device != null ? device : resolveSingleVideoDevice(asset), config, tracking);
    }

    @Override
    public void stopStream(AssetId id) {
        Objects.requireNonNull(id, "id must not be null");
        // Unknown asset: no-op, mirroring StreamService#stop's idempotency for unknown ids.
        assetRepository.findById(id).ifPresent(this::stopStreamsOf);
    }

    /**
     * Picks the asset's single active video-capable device.
     *
     * <p>Deactivated and deleted devices are invisible here: a drone with one retired camera and
     * one working one should just start the working one, not report ambiguity.
     */
    private DeviceId resolveSingleVideoDevice(Asset asset) {
        List<DeviceId> videoCapable = asset.devices().stream()
                .filter(deviceId -> deviceService.find(deviceId)
                        .map(d -> d.isActive() && d.capabilities().contains(Capability.VIDEO))
                        .orElse(false))
                .toList();
        if (videoCapable.isEmpty()) {
            throw new IllegalArgumentException("Asset " + asset.id().value()
                    + " has no active video-capable device; specify one explicitly");
        }
        if (videoCapable.size() > 1) {
            throw new IllegalArgumentException("Asset " + asset.id().value()
                    + " has multiple video-capable devices, specify which one to start: " + videoCapable);
        }
        return videoCapable.get(0);
    }

    /** Stops every running stream belonging to this asset's devices; returns how many. */
    private int stopStreamsOf(Asset asset) {
        int stopped = 0;
        for (ActiveStream active : streamService.streams()) {
            if (asset.devices().contains(active.deviceId())) {
                streamService.stop(active.streamId());
                stopped++;
            }
        }
        return stopped;
    }

    // --- Device assignment (docs/main/CYCLES-PLAN.md §8) ---------------------------

    @Override
    public Asset assignDevice(AssetId id, DeviceId deviceId, UserId actor) {
        Objects.requireNonNull(deviceId, "deviceId must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        Asset asset = require(id);
        Device device = requireAssignable(deviceId);

        Set<DeviceId> devices = new LinkedHashSet<>(asset.devices());
        devices.add(deviceId);
        Asset saved = assetRepository.save(asset.withDevices(devices));
        audit(actor, AuditAction.UPDATED, saved, "Assigned " + device.name() + " to asset " + saved.displayName(),
                Map.of("devices", asset.devices() + " → " + devices));
        return saved;
    }

    @Override
    public Asset unassignDevice(AssetId id, DeviceId deviceId, UserId actor) {
        Objects.requireNonNull(deviceId, "deviceId must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        Asset asset = require(id);

        if (!asset.devices().contains(deviceId)) {
            throw new IllegalArgumentException(
                    "Device " + deviceId.value() + " does not belong to asset " + id.value());
        }
        Set<DeviceId> devices = new LinkedHashSet<>(asset.devices());
        devices.remove(deviceId);
        if (devices.isEmpty()) {
            throw new IllegalStateException(
                    "Asset " + asset.displayName() + " must keep at least one device; unassign refused");
        }

        Asset saved = assetRepository.save(asset.withDevices(devices));
        audit(actor, AuditAction.UPDATED, saved, "Unassigned a device from asset " + saved.displayName(),
                Map.of("devices", asset.devices() + " → " + devices));
        return saved;
    }

    // --- Helpers -------------------------------------------------------------

    private Device requireDevice(DeviceId id) {
        return deviceService.find(id)
                .orElseThrow(() -> new NoSuchElementException("Unknown device: " + id.value()));
    }

    /**
     * The eligibility rules shared by {@link #assignDevice} and {@link #create}'s {@code
     * existingDeviceIds} path: the device must exist, must not be soft-deleted, and must not
     * already belong to any asset (including — for {@code create}'s case — the one being created,
     * which by construction cannot already own anything yet).
     *
     * @throws NoSuchElementException  if {@code deviceId} is unknown (→404)
     * @throws IllegalArgumentException if the device is soft-deleted (→400)
     * @throws IllegalStateException    if the device already belongs to another asset (→409)
     */
    private Device requireAssignable(DeviceId deviceId) {
        Device device = requireDevice(deviceId);
        if (device.isDeleted()) {
            throw new IllegalArgumentException(
                    "Device " + device.name() + " is deleted; restore it before assigning it to an asset");
        }
        assetRepository.findByDeviceId(deviceId).ifPresent(owner -> {
            throw new IllegalStateException(
                    "Device " + device.name() + " already belongs to asset " + owner.displayName());
        });
        return device;
    }

    private int countUsages(AssetId id) {
        return usageRepository.findRecentByAsset(id, ALL_USAGES).size();
    }

    private Asset require(AssetId id) {
        Objects.requireNonNull(id, "id must not be null");
        return assetRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Unknown asset: " + id.value()));
    }

    private void requireCategory(CategoryId category) {
        categoryRepository.findById(category)
                .orElseThrow(() -> new IllegalArgumentException("Unknown category: " + category.slug()));
    }

    private AssetSummary toSummary(Asset asset, Set<DeviceId> activeDevices) {
        String categoryName = categoryRepository.findById(asset.category())
                .map(DeviceCategory::name)
                .orElse(asset.category().slug());
        AssetStatus status = asset.devices().stream().anyMatch(activeDevices::contains)
                ? AssetStatus.STREAMING
                : AssetStatus.OFFLINE;

        List<AssetUsage> mostRecent = usageRepository.findRecentByAsset(asset.id(), 1);
        Instant lastUsedAt = mostRecent.isEmpty() ? null : mostRecent.get(0).startedAt();
        GeoPosition lastKnownPosition = mostRecent.isEmpty() ? null : lastKnownPosition(mostRecent.get(0));

        return new AssetSummary(asset, categoryName, status, lastUsedAt, lastKnownPosition);
    }

    private static GeoPosition lastKnownPosition(AssetUsage usage) {
        return usage.lastPosition() != null ? usage.lastPosition() : usage.startPosition();
    }

    /** The fields an edit actually changed, as {@code before → after} strings. */
    private static Map<String, String> changes(Asset before, Asset after) {
        Map<String, String> changes = new LinkedHashMap<>();
        if (!before.displayName().equals(after.displayName())) {
            changes.put("displayName", before.displayName() + " → " + after.displayName());
        }
        if (!before.category().equals(after.category())) {
            changes.put("category", before.category().slug() + " → " + after.category().slug());
        }
        if (!before.attributes().equals(after.attributes())) {
            changes.put("attributes", before.attributes() + " → " + after.attributes());
        }
        return changes;
    }

    private void audit(UserId actor, AuditAction action, Asset asset, String summary,
                        Map<String, String> details) {
        auditTrail.record(AuditEntry.of(actor, action, AuditTargetType.ASSET,
                asset.id().value().toString(), summary, details));
    }
}
