package com.drones.vision.flight.application;

import com.drones.vision.flight.domain.model.ConfigDriftCalculator;
import com.drones.vision.flight.domain.model.FlightPassport;
import com.drones.vision.flight.domain.model.FlightPhase;
import com.drones.vision.flight.domain.model.ParameterDrift;
import com.drones.vision.flight.domain.model.VehicleProfile;
import com.drones.vision.flight.domain.port.VehicleConfigPort;
import com.drones.vision.flight.domain.port.VehicleProfileRepositoryPort;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.UsageId;
import com.drones.vision.kernel.UserId;
import com.drones.vision.platform.AccessDeniedException;
import com.drones.vision.platform.AuditAction;
import com.drones.vision.platform.AuditEntry;
import com.drones.vision.platform.AuditTargetType;
import com.drones.vision.platform.AuditTrailPort;
import com.drones.vision.platform.VisibilityScope;
import com.drones.vision.warehouse.application.asset.AssetDetails;
import com.drones.vision.warehouse.application.asset.AssetService;
import com.drones.vision.warehouse.domain.model.Asset;
import com.drones.vision.warehouse.domain.model.AssetUsage;
import com.drones.vision.warehouse.domain.model.Device;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;

/**
 * The one implementation of {@link VehicleProfileService}.
 *
 * <h2>Authority (docs/plans/active/DRONE-ONBOARDING-PLAN.md section 6.1)</h2>
 * An active probe puts traffic on the aircraft's own link (a {@code REQUEST_MESSAGE}/{@code
 * PARAM_REQUEST_READ} exchange, not a passive listen), so {@link #probe} gates on {@link
 * VisibilityScope#canManage(com.drones.vision.kernel.Ownership)} -- the authority predicate, not
 * {@link VisibilityScope#includes} -- and audits a denial exactly like every other command gate in
 * this module (mirrors {@code DefaultFlightCommandService}). {@link #latestProfile} is a read: it
 * uses the scoped {@link AssetService#details(VisibilityScope, AssetId)} so unknown, out-of-scope
 * and never-probed all collapse to the identical {@link NoSuchElementException} (404) the frozen
 * wire contract asks for, and it is never audited.
 *
 * <h2>Device resolution</h2>
 * Mirrors {@code DefaultFlightCommandService#firstCommandableDevice}: the first active device
 * {@link VehicleConfigPort#supports} claims. Zero matches is a 409 ({@link IllegalStateException}),
 * not an attempt, not audited.
 */
public final class DefaultVehicleProfileService implements VehicleProfileService {

    private static final String COMMAND_PROBE = "PROBE";
    private static final String COMMAND_CAPTURE_SNAPSHOT = "CAPTURE_SNAPSHOT";
    private static final String ATTR_ASSET_ID = "assetId";
    private static final String ATTR_USAGE_ID = "usageId";
    private static final String ATTR_PHASE = "phase";
    private static final String ATTR_COMMAND = "command";
    private static final String ATTR_RESULT = "result";
    private static final String DENIED_OUT_OF_SCOPE = "DENIED:out of scope";
    private static final String RESULT_COMPLETE = "COMPLETE";
    private static final String RESULT_INCOMPLETE_PREFIX = "INCOMPLETE:";

    private final AssetService assetService;
    private final VehicleConfigPort vehicleConfigPort;
    private final VehicleProfileRepositoryPort profileRepository;
    private final AuditTrailPort auditTrail;

    public DefaultVehicleProfileService(AssetService assetService, VehicleConfigPort vehicleConfigPort,
                                         VehicleProfileRepositoryPort profileRepository, AuditTrailPort auditTrail) {
        this.assetService = Objects.requireNonNull(assetService, "assetService must not be null");
        this.vehicleConfigPort = Objects.requireNonNull(vehicleConfigPort, "vehicleConfigPort must not be null");
        this.profileRepository = Objects.requireNonNull(profileRepository, "profileRepository must not be null");
        this.auditTrail = Objects.requireNonNull(auditTrail, "auditTrail must not be null");
    }

    @Override
    public VehicleProfile probe(AssetId assetId, Duration window, UserId actor, VisibilityScope scope) {
        Objects.requireNonNull(assetId, "assetId must not be null");
        Objects.requireNonNull(window, "window must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(scope, "scope must not be null");

        AssetDetails details = assetService.details(assetId); // NoSuchElementException -> 404
        Asset asset = details.summary().asset();
        if (!scope.canManage(asset.ownership())) {
            audit(actor, assetId, COMMAND_PROBE, DENIED_OUT_OF_SCOPE);
            throw new AccessDeniedException(
                    "Asset " + assetId.value() + " is outside your management scope; you may not probe it");
        }
        Device device = firstSupportedDevice(details.devices())
                .orElseThrow(() -> new IllegalStateException(
                        "Asset " + assetId.value() + " has no device this platform can probe"));

        VehicleProfile profile = vehicleConfigPort.probe(linkKeyOf(device), window);
        profileRepository.save(device.id(), profile);
        audit(actor, assetId, COMMAND_PROBE,
                profile.complete() ? RESULT_COMPLETE : RESULT_INCOMPLETE_PREFIX + profile.incompleteReason());
        return profile;
    }

    @Override
    public VehicleProfile latestProfile(AssetId assetId, VisibilityScope scope) {
        Objects.requireNonNull(assetId, "assetId must not be null");
        Objects.requireNonNull(scope, "scope must not be null");

        // Scoped read: unknown OR out-of-scope OR never-probed all collapse to the same 404 -- a
        // scoped read never distinguishes "not yours" from "does not exist" from "nothing to show".
        AssetDetails details = assetService.details(scope, assetId);
        return details.devices().stream()
                .flatMap(device -> profileRepository.findLatest(device.id()).stream())
                .findFirst()
                .orElseThrow(() -> new NoSuchElementException(
                        "Asset " + assetId.value() + " has never been probed"));
    }

    @Override
    public VehicleProfile probeCandidate(String linkKey, Duration window, UserId actor) {
        Objects.requireNonNull(linkKey, "linkKey must not be null");
        Objects.requireNonNull(window, "window must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        return vehicleConfigPort.probe(linkKey, window);
    }

    @Override
    public VehicleProfile captureSnapshot(AssetId assetId, UsageId usageId, FlightPhase phase, Duration window,
                                           UserId actor, VisibilityScope scope) {
        Objects.requireNonNull(assetId, "assetId must not be null");
        Objects.requireNonNull(usageId, "usageId must not be null");
        Objects.requireNonNull(phase, "phase must not be null");
        Objects.requireNonNull(window, "window must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(scope, "scope must not be null");
        if (phase != FlightPhase.PREFLIGHT && phase != FlightPhase.POSTFLIGHT) {
            throw new IllegalArgumentException(
                    "VehicleProfileService.captureSnapshot: phase must be PREFLIGHT or POSTFLIGHT, was " + phase);
        }

        AssetDetails details = assetService.details(assetId); // NoSuchElementException -> 404
        Asset asset = details.summary().asset();
        if (!scope.canManage(asset.ownership())) {
            auditCapture(actor, assetId, usageId, phase, DENIED_OUT_OF_SCOPE);
            throw new AccessDeniedException(
                    "Asset " + assetId.value() + " is outside your management scope; you may not probe it");
        }
        Device device = firstSupportedDevice(details.devices())
                .orElseThrow(() -> new IllegalStateException(
                        "Asset " + assetId.value() + " has no device this platform can probe"));

        VehicleProfile profile = vehicleConfigPort.probe(linkKeyOf(device), window);
        profileRepository.save(device.id(), usageId, phase, profile);
        auditCapture(actor, assetId, usageId, phase,
                profile.complete() ? RESULT_COMPLETE : RESULT_INCOMPLETE_PREFIX + profile.incompleteReason());
        return profile;
    }

    @Override
    public FlightPassport passport(AssetId assetId, UsageId usageId, VisibilityScope scope) {
        Objects.requireNonNull(assetId, "assetId must not be null");
        Objects.requireNonNull(usageId, "usageId must not be null");
        Objects.requireNonNull(scope, "scope must not be null");

        AssetDetails details = assetService.details(scope, assetId); // 404 unknown/out-of-scope
        requireUsageBelongsToAsset(details, assetId, usageId);

        VehicleProfile preflight = profileRepository.findByUsageAndPhase(usageId, FlightPhase.PREFLIGHT)
                .orElse(null);
        VehicleProfile postflight = profileRepository.findByUsageAndPhase(usageId, FlightPhase.POSTFLIGHT)
                .orElse(null);
        return new FlightPassport(usageId, assetId, preflight, postflight);
    }

    @Override
    public List<ParameterDrift> driftFromPreviousFlight(AssetId assetId, UsageId usageId, VisibilityScope scope) {
        Objects.requireNonNull(assetId, "assetId must not be null");
        Objects.requireNonNull(usageId, "usageId must not be null");
        Objects.requireNonNull(scope, "scope must not be null");

        AssetDetails details = assetService.details(scope, assetId); // 404 unknown/out-of-scope
        AssetUsage current = requireUsageBelongsToAsset(details, assetId, usageId);

        // recentUsages() is newest-first (AssetService#details), so the first entry strictly
        // before the current usage's startedAt is the immediately-preceding flight.
        Optional<AssetUsage> previous = details.recentUsages().stream()
                .filter(usage -> usage.startedAt().isBefore(current.startedAt()))
                .findFirst();
        if (previous.isEmpty()) {
            return List.of();
        }

        Optional<VehicleProfile> earlier =
                profileRepository.findByUsageAndPhase(previous.get().id(), FlightPhase.POSTFLIGHT);
        Optional<VehicleProfile> later = profileRepository.findByUsageAndPhase(usageId, FlightPhase.PREFLIGHT);
        if (earlier.isEmpty() || later.isEmpty()) {
            return List.of();
        }
        return ConfigDriftCalculator.diff(earlier.get(), later.get());
    }

    /**
     * {@code usageId} must be one of {@code assetId}'s recent usages -- the check that keeps {@link
     * #passport}/{@link #driftFromPreviousFlight} from leaking another asset's profile data to a
     * caller who only has scope over this one, since {@code VehicleProfileRepositoryPort} keys
     * purely by {@code usageId}, not by asset.
     *
     * <p>Relies on {@code AssetDetails#recentUsages()}'s own cap (the 20 most recent, per {@code
     * DefaultAssetService}) -- same accepted "fetch-then-aggregate" limitation as {@code
     * DefaultAssetStatsService}: a usage older than that window 404s here even though it genuinely
     * belongs to this asset.
     */
    private static AssetUsage requireUsageBelongsToAsset(AssetDetails details, AssetId assetId, UsageId usageId) {
        return details.recentUsages().stream()
                .filter(usage -> usage.id().equals(usageId))
                .findFirst()
                .orElseThrow(() -> new NoSuchElementException(
                        "Usage " + usageId.value() + " is not a recent usage of asset " + assetId.value()));
    }

    private Optional<Device> firstSupportedDevice(List<Device> devices) {
        return devices.stream()
                .filter(Device::isActive)
                .filter(vehicleConfigPort::supports)
                .findFirst();
    }

    /**
     * Derives a {@code linkKey} ({@code "udp://host:port#sysid"}) from a registered device's own
     * stream descriptor -- the candidate identity docs/plans/active/DRONE-ONBOARDING-PLAN.md section
     * 3.1 keys on, reconstructed after registration rather than stored twice.
     */
    private static String linkKeyOf(Device device) {
        String sysid = device.stream().options().get("sysid");
        return sysid == null || sysid.isBlank()
                ? device.stream().uri().toString()
                : device.stream().uri() + "#" + sysid;
    }

    private void audit(UserId actor, AssetId assetId, String command, String result) {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put(ATTR_ASSET_ID, assetId.value().toString());
        attributes.put(ATTR_COMMAND, command);
        attributes.put(ATTR_RESULT, result);
        auditTrail.record(AuditEntry.of(actor, AuditAction.UPDATED, AuditTargetType.ASSET,
                assetId.value().toString(), "Vehicle probe for asset " + assetId.value(), attributes));
    }

    private void auditCapture(UserId actor, AssetId assetId, UsageId usageId, FlightPhase phase, String result) {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put(ATTR_ASSET_ID, assetId.value().toString());
        attributes.put(ATTR_USAGE_ID, usageId.value().toString());
        attributes.put(ATTR_PHASE, phase.name());
        attributes.put(ATTR_COMMAND, COMMAND_CAPTURE_SNAPSHOT);
        attributes.put(ATTR_RESULT, result);
        auditTrail.record(AuditEntry.of(actor, AuditAction.UPDATED, AuditTargetType.ASSET,
                assetId.value().toString(),
                "Passport snapshot (" + phase + ") for asset " + assetId.value() + ", usage " + usageId.value(),
                attributes));
    }
}
