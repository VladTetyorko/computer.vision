package com.drones.vision.warehouse.application.asset;

import com.drones.vision.warehouse.domain.model.Asset;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.warehouse.domain.model.AssetUsage;
import com.drones.vision.platform.AuditAction;
import com.drones.vision.platform.AuditEntry;
import com.drones.vision.platform.AuditTargetType;
import com.drones.vision.kernel.CategoryId;
import com.drones.vision.warehouse.domain.model.Device;
import com.drones.vision.warehouse.domain.model.DeviceCategory;
import com.drones.vision.kernel.DeviceId;
import com.drones.vision.kernel.GeoPosition;
import com.drones.vision.warehouse.domain.model.Identity;
import com.drones.vision.warehouse.domain.model.InventoryStates;
import com.drones.vision.kernel.LifecycleState;
import com.drones.vision.kernel.Ownership;
import com.drones.vision.kernel.StreamDescriptor;
import com.drones.vision.kernel.UserId;
import com.drones.vision.warehouse.domain.port.AssetLiveStatePort;
import com.drones.vision.warehouse.domain.port.AssetRepositoryPort;
import com.drones.vision.warehouse.domain.port.AssetUsageRepositoryPort;
import com.drones.vision.platform.AuditTrailPort;
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
import java.util.UUID;
import com.drones.vision.warehouse.application.device.DeviceEdit;
import com.drones.vision.warehouse.application.device.DeviceRegistration;
import com.drones.vision.warehouse.application.device.DeviceService;
import com.drones.vision.platform.VisibilityScope;

/**
 * The one implementation of {@link AssetService}.
 *
 * <p>Devices are reached through {@link DeviceService}, not the device repository: registering,
 * deleting and restoring a source already carry rules (stop its stream, write an audit line) that
 * would otherwise be duplicated here and drift.
 *
 * <p>Live runtime state — which devices are streaming, stopping a device's stream before it is
 * retired or deleted — is reached through {@link AssetLiveStatePort}, not {@code StreamService}
 * directly (docs/plans/active/DOMAIN-SEPARATION-W1.md &sect;15, W1.6e): this is a CRUD/inventory
 * service, and inventory reads runtime through the port it declares, never the runtime module
 * itself. Starting a stream is not this class's job at all any more — see {@code
 * com.drones.vision.perception.application.stream.AssetStreamService}.
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

    /**
     * Attributes changes this service makes on its own initiative (address self-heal in {@link
     * #syncAddressIfDrifted}) rather than in response to one user's request — mirrors {@code
     * com.drones.vision.map.application.LayerResolver#SYSTEM_USER_ID}; kept local rather than
     * shared since vision-warehouse and vision-map are sibling contexts with no dependency between
     * them.
     */
    private static final UserId SYSTEM_ACTOR = new UserId(new UUID(0, 0));

    private final AssetRepositoryPort assetRepository;
    private final CategoryRepositoryPort categoryRepository;
    private final AssetUsageRepositoryPort usageRepository;
    private final AuditTrailPort auditTrail;
    private final DeviceService deviceService;
    private final AssetLiveStatePort assetLiveStatePort;

    public DefaultAssetService(AssetRepositoryPort assetRepository, CategoryRepositoryPort categoryRepository,
                                AssetUsageRepositoryPort usageRepository, AuditTrailPort auditTrail,
                                DeviceService deviceService, AssetLiveStatePort assetLiveStatePort) {
        this.assetRepository = Objects.requireNonNull(assetRepository, "assetRepository must not be null");
        this.categoryRepository = Objects.requireNonNull(categoryRepository, "categoryRepository must not be null");
        this.usageRepository = Objects.requireNonNull(usageRepository, "usageRepository must not be null");
        this.auditTrail = Objects.requireNonNull(auditTrail, "auditTrail must not be null");
        this.deviceService = Objects.requireNonNull(deviceService, "deviceService must not be null");
        this.assetLiveStatePort = Objects.requireNonNull(assetLiveStatePort, "assetLiveStatePort must not be null");
    }

    // --- Creating ------------------------------------------------------------

    @Override
    public Asset create(AssetSpec spec, Ownership ownership, UserId actor) {
        Objects.requireNonNull(spec, "spec must not be null");
        Objects.requireNonNull(ownership, "ownership must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        DeviceCategory category = requireCategory(spec.category());
        if (category.connected() && spec.devices().isEmpty() && spec.existingDeviceIds().isEmpty()) {
            throw new IllegalArgumentException("Category " + category.id().slug()
                    + " requires at least one device (WAREHOUSE-UX-PLAN D4)");
        }

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

        Asset saved = assetRepository.save(Asset.register(AssetId.random(), spec.displayName(), spec.category(),
                ownership, deviceIds, spec.attributes(), spec.identity(), spec.custody()));
        audit(actor, AuditAction.CREATED, saved,
                "Created asset " + saved.displayName() + " with " + deviceIds.size() + " source(s)", Map.of());
        return saved;
    }

    @Override
    public Asset createFromCandidate(AssetSpec spec, Ownership ownership, UserId actor) {
        Objects.requireNonNull(spec, "spec must not be null");
        Objects.requireNonNull(ownership, "ownership must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        requireCategory(spec.category());

        // Duplicate check first, before anything is written: a candidate that is really the same
        // airframe as an existing device must be refused, naming the asset that already owns it,
        // rather than silently creating a second registration for it.
        for (DeviceRegistration registration : spec.devices()) {
            requireNoDuplicate(registration.stream());
        }
        return create(spec, ownership, actor);
    }

    /**
     * The duplicate check {@link #createFromCandidate} needs: refuses a candidate stream descriptor
     * that already matches an active, registered device on {@code (protocol, uri, sysid)} — keyed
     * this way, not by {@link DeviceId}, since a re-discovered aircraft arrives as a brand-new
     * {@link DeviceRegistration} with no device identity of its own yet.
     *
     * @throws IllegalStateException if an active device already carries this (protocol, uri, sysid)
     */
    private void requireNoDuplicate(StreamDescriptor candidate) {
        matchDevice(candidate).ifPresent(device -> {
            String owner = assetRepository.findByDeviceId(device.id())
                    .map(asset -> " already registered to asset " + asset.displayName())
                    .orElse(" already registered to device " + device.name());
            throw new IllegalStateException(
                    "Candidate " + candidate.protocol() + " " + candidate.uri() + " is" + owner);
        });
    }

    @Override
    public Optional<DuplicateDeviceMatch> findDuplicateDevice(StreamDescriptor candidate) {
        Objects.requireNonNull(candidate, "candidate must not be null");
        return matchDevice(candidate)
                .map(device -> syncAddressIfDrifted(device, candidate))
                .map(device -> new DuplicateDeviceMatch(device.id(),
                        assetRepository.findByDeviceId(device.id()).map(Asset::id).orElse(null)));
    }

    /**
     * The one place that walks every active device looking for an identity match — shared by
     * {@link #requireNoDuplicate} (which throws) and {@link #findDuplicateDevice} (which answers),
     * so the two never drift.
     *
     * <p>Identity-first (docs/plans/active/LINK-PAIRING-PLAN.md §3.3): a MAVLink sysid is the
     * vehicle's identity, not its address, so a candidate carrying one is matched on {@code sysid}
     * alone first — a device re-heard at a new {@code uri} (new ephemeral port, new radio hop) is
     * recognised as the same airframe rather than forking a second one. Only when the candidate
     * carries no sysid does this fall back to the original {@code (protocol, uri)} match.
     *
     * <p>The plan's stated priority also names hardware uid and ONVIF uuid/mediamtx path ahead of
     * address, but neither is reachable from this method's signature: hardware uid is not yet
     * attached to a {@link Device} anywhere a candidate stream could carry it, and the ONVIF/
     * mediamtx identity facts ({@code details["epr"]}/{@code details["path"]}, see {@link
     * com.drones.vision.warehouse.domain.model.DiscoveryCandidate#identityKeyFor}) live on the full
     * {@link com.drones.vision.warehouse.domain.model.DiscoveredDevice}, not on the {@link
     * StreamDescriptor} this method receives — widening this signature was out of scope for this
     * change (docs/plans/active/LINK-PAIRING-PLAN.md §3.3 names this as an edit to the existing
     * method body, not a new method/signature).
     */
    private Optional<Device> matchDevice(StreamDescriptor candidate) {
        String candidateSysid = candidate.options().get("sysid");
        if (candidateSysid != null) {
            for (Device device : deviceService.devices(false)) {
                if (candidateSysid.equals(device.stream().options().get("sysid"))) {
                    return Optional.of(device);
                }
            }
        }
        for (Device device : deviceService.devices(false)) {
            StreamDescriptor existing = device.stream();
            boolean sameAirframe = existing.protocol().equals(candidate.protocol())
                    && existing.uri().equals(candidate.uri())
                    && Objects.equals(existing.options().get("sysid"), candidateSysid);
            if (sameAirframe) {
                return Optional.of(device);
            }
        }
        return Optional.empty();
    }

    /**
     * A known identity heard at a new address updates the device's own stream in place rather than
     * forking a second device (docs/plans/active/LINK-PAIRING-PLAN.md §3.3) — the freshest observed
     * address always wins (CLAUDE.md rule 7). Runs on every {@link #findDuplicateDevice} call (every
     * discovery report and every re-attach attempt), so a device's recorded address self-heals
     * continuously instead of drifting until an operator notices and fixes it by hand.
     *
     * <p>Attributed to {@link #SYSTEM_ACTOR} since this runs on the service's own initiative, on
     * every sighting, not in response to one user's request — mirrors {@code
     * com.drones.vision.map.application.LayerResolver#SYSTEM_USER_ID}.
     */
    private Device syncAddressIfDrifted(Device device, StreamDescriptor observed) {
        if (device.stream().equals(observed)) {
            return device;
        }
        return deviceService.update(device.id(), new DeviceEdit(null, null, observed, null), SYSTEM_ACTOR);
    }

    // --- Reading -------------------------------------------------------------

    @Override
    public List<AssetSummary> assets(boolean includeDeleted) {
        Set<DeviceId> activeDevices = assetLiveStatePort.activeStreamsByDevice().keySet();
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
                .filter(summary -> scope.includes(summary.asset().id(), summary.asset().ownership()))
                .toList();
    }

    @Override
    public AssetDetails details(AssetId id) {
        Asset asset = require(id);
        List<Device> devices = asset.devices().stream()
                .map(deviceService::find)
                .flatMap(Optional::stream)
                .toList();
        return new AssetDetails(toSummary(asset, assetLiveStatePort.activeStreamsByDevice().keySet()), devices,
                usageRepository.findRecentByAsset(id, RECENT_USAGES_LIMIT));
    }

    @Override
    public AssetDetails details(VisibilityScope scope, AssetId id) {
        Objects.requireNonNull(scope, "scope must not be null");
        AssetDetails details = details(id); // NoSuchElementException -> 404 for an unknown id
        if (!scope.includes(details.summary().asset().id(), details.summary().asset().ownership())) {
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
        Identity identity = edit.identity() != null ? edit.identity() : asset.identity();

        Asset saved = assetRepository.save(
                asset.withDetails(displayName, category, attributes).withIdentity(identity, Instant.now()));
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

        Asset saved = assetRepository.save(asset.withState(state).touch(Instant.now()));
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
        Asset deleted = assetRepository.save(asset.withState(LifecycleState.DELETED).touch(Instant.now()));
        audit(actor, AuditAction.DELETED, deleted, "Deleted asset " + deleted.displayName(),
                Map.of("devicesDeleted", String.valueOf(devicesDeleted),
                        "usagesRetained", String.valueOf(usagesRetained),
                        "streamsStopped", String.valueOf(streamsStopped)));

        return new AssetDeletion(id, asset.displayName(), devicesDeleted, usagesRetained, streamsStopped);
    }

    // --- Streaming -----------------------------------------------------------

    @Override
    public void stopStream(AssetId id) {
        Objects.requireNonNull(id, "id must not be null");
        // Unknown asset: no-op, mirroring StreamService#stop's idempotency for unknown ids.
        assetRepository.findById(id).ifPresent(this::stopStreamsOf);
    }

    /** Stops every running stream belonging to this asset's devices; returns how many. */
    private int stopStreamsOf(Asset asset) {
        return assetLiveStatePort.stopStreamsForDevices(asset.devices());
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
        Asset saved = assetRepository.save(asset.withDevices(devices).touch(Instant.now()));
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

        Asset saved = assetRepository.save(asset.withDevices(devices).touch(Instant.now()));
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

    private DeviceCategory requireCategory(CategoryId category) {
        return categoryRepository.findById(category)
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
        boolean hasOpenUsage = usageRepository.findOpenByAsset(asset.id()).isPresent();

        return new AssetSummary(asset, categoryName, status, lastUsedAt, lastKnownPosition,
                InventoryStates.effective(asset, hasOpenUsage), asset.identity(), asset.custody());
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
        if (!before.identity().equals(after.identity())) {
            changes.put("identity", before.identity() + " → " + after.identity());
        }
        return changes;
    }

    private void audit(UserId actor, AuditAction action, Asset asset, String summary,
                        Map<String, String> details) {
        auditTrail.record(AuditEntry.of(actor, action, AuditTargetType.ASSET,
                asset.id().value().toString(), summary, details));
    }
}
