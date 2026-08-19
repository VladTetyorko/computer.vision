package com.drones.vision.flight.application;

import com.drones.vision.flight.domain.model.MessageIntervalOutcome;
import com.drones.vision.flight.domain.model.ParameterTier;
import com.drones.vision.flight.domain.model.ParameterWriteOutcome;
import com.drones.vision.flight.domain.port.VehicleConfigPort;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.FlightState;
import com.drones.vision.kernel.Ownership;
import com.drones.vision.kernel.Telemetry;
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
import com.drones.vision.warehouse.domain.model.Device;
import com.drones.vision.warehouse.domain.port.AssetLiveStatePort;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The one implementation of {@link RemediationService}.
 *
 * <h2>Gate ordering</h2>
 * <ol>
 *   <li><b>Tier first, before anything is even resolved</b> ({@link #writeParameter} only): Tier C
 *       and any unclassified name are refused without ever looking up the asset, checking scope, or
 *       touching the audit trail or the port -- section 4a/D9's "not exposed at any authority
 *       level" is read literally here.</li>
 *   <li><b>Resolve the asset</b> via {@link AssetService#details(AssetId)} -- unknown is a plain
 *       404, mirroring {@code DefaultFlightCommandService#resolveForCommand}.</li>
 *   <li><b>Authority</b>: {@link VisibilityScope#canManage(Ownership)} for message-interval and
 *       Tier-A writes; {@link VisibilityScope#canAdminister()} for Tier-B writes (section 6.1). A
 *       denial is audited.</li>
 *   <li><b>Tier-B consent</b> ({@link #writeParameter} only): a Tier-B write without {@code
 *       explicitConsent} is a plain {@link IllegalArgumentException} (400) -- a request-shape
 *       problem, not an authority one, checked only once authority itself is already established
 *       (mirrors {@code DefaultFlightCommandService#setMode}'s own unknown-mode-is-400-not-403
 *       ordering).</li>
 *   <li><b>Disarmed-only</b> (D10): {@code armed == null} is refused exactly like {@code armed ==
 *       true} -- "unknown is never optimistic". Read from {@link
 *       AssetLiveStatePort#latestTelemetry(AssetId)}, the same freshest-sample source {@code
 *       AssetAttention#armed()} already reads. Both a refusal and an aircraft-refused/no-ack outcome
 *       are audited as a refusal, mirroring {@code DefaultFlightCommandService#sendAndAudit}.</li>
 *   <li><b>Device resolution</b>: the first active device {@link VehicleConfigPort#supports}
 *       claims; zero matches is a 409, not an attempt, not audited.</li>
 * </ol>
 */
public final class DefaultRemediationService implements RemediationService {

    private static final String COMMAND_MESSAGE_INTERVAL = "MESSAGE_INTERVAL";
    private static final String COMMAND_PARAM_WRITE_PREFIX = "PARAM_WRITE:";
    private static final String ATTR_ASSET_ID = "assetId";
    private static final String ATTR_COMMAND = "command";
    private static final String ATTR_RESULT = "result";
    private static final String DENIED_OUT_OF_SCOPE = "DENIED:out of scope";
    private static final String REFUSED_ARMED = "REFUSED:aircraft is armed";
    private static final String REFUSED_ARMING_UNKNOWN = "REFUSED:arming state unknown";

    private final AssetService assetService;
    private final AssetLiveStatePort assetLiveStatePort;
    private final VehicleConfigPort vehicleConfigPort;
    private final AuditTrailPort auditTrail;

    public DefaultRemediationService(AssetService assetService, AssetLiveStatePort assetLiveStatePort,
                                      VehicleConfigPort vehicleConfigPort, AuditTrailPort auditTrail) {
        this.assetService = Objects.requireNonNull(assetService, "assetService must not be null");
        this.assetLiveStatePort = Objects.requireNonNull(assetLiveStatePort, "assetLiveStatePort must not be null");
        this.vehicleConfigPort = Objects.requireNonNull(vehicleConfigPort, "vehicleConfigPort must not be null");
        this.auditTrail = Objects.requireNonNull(auditTrail, "auditTrail must not be null");
    }

    @Override
    public MessageIntervalOutcome requestMessageInterval(AssetId assetId, int messageId, Duration interval,
                                                           UserId actor, VisibilityScope scope) {
        Objects.requireNonNull(assetId, "assetId must not be null");
        Objects.requireNonNull(interval, "interval must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(scope, "scope must not be null");

        Asset asset = resolveAsset(assetId);
        requireCanManage(asset, scope, actor, assetId, COMMAND_MESSAGE_INTERVAL);
        requireDisarmed(assetId, actor, COMMAND_MESSAGE_INTERVAL);
        Device device = firstSupportedDevice(assetId);

        MessageIntervalOutcome outcome =
                vehicleConfigPort.requestMessageInterval(linkKeyOf(device), messageId, interval);
        audit(actor, assetId, COMMAND_MESSAGE_INTERVAL, outcome.outcome().name());
        return outcome;
    }

    @Override
    public ParameterWriteOutcome writeParameter(AssetId assetId, String parameterName, double value,
                                                 boolean explicitConsent, UserId actor, VisibilityScope scope) {
        Objects.requireNonNull(assetId, "assetId must not be null");
        Objects.requireNonNull(parameterName, "parameterName must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(scope, "scope must not be null");

        // Tier first: Tier C / unclassified is refused before the asset is even looked up (D9) --
        // "not exposed at any authority level" means no authority check ever gets the chance to run.
        ParameterTier tier = ParameterTier.classify(parameterName).orElse(null);
        if (tier == null || !tier.everWritable()) {
            throw new IllegalArgumentException(
                    "Parameter '" + parameterName + "' is not on the writable allowlist");
        }

        String command = COMMAND_PARAM_WRITE_PREFIX + parameterName;
        Asset asset = resolveAsset(assetId);
        if (tier == ParameterTier.B) {
            requireCanAdminister(asset, scope, actor, assetId, command);
            if (!explicitConsent) {
                throw new IllegalArgumentException(
                        "Tier-B parameter '" + parameterName + "' requires explicit per-item consent");
            }
        } else {
            requireCanManage(asset, scope, actor, assetId, command);
        }
        requireDisarmed(assetId, actor, command);
        Device device = firstSupportedDevice(assetId);

        ParameterWriteOutcome outcome = vehicleConfigPort.writeParam(linkKeyOf(device), parameterName, value);
        audit(actor, assetId, command, outcome.outcome().name());
        return outcome;
    }

    private Asset resolveAsset(AssetId assetId) {
        AssetDetails details = assetService.details(assetId); // NoSuchElementException -> 404
        return details.summary().asset();
    }

    private void requireCanManage(Asset asset, VisibilityScope scope, UserId actor, AssetId assetId, String command) {
        if (!scope.canManage(asset.ownership())) {
            audit(actor, assetId, command, DENIED_OUT_OF_SCOPE);
            throw new AccessDeniedException(
                    "Asset " + assetId.value() + " is outside your management scope; you may not configure it");
        }
    }

    private void requireCanAdminister(Asset asset, VisibilityScope scope, UserId actor, AssetId assetId, String command) {
        if (!scope.canAdminister()) {
            audit(actor, assetId, command, DENIED_OUT_OF_SCOPE);
            throw new AccessDeniedException(
                    "Asset " + assetId.value() + " requires administrator authority for a tier-B parameter");
        }
    }

    /**
     * Disarmed-only (D10): {@code armed == null} (no telemetry at all, or a sample whose {@link
     * FlightState} has never resolved arming) is refused exactly like {@code armed == true}.
     */
    private void requireDisarmed(AssetId assetId, UserId actor, String command) {
        Boolean armed = assetLiveStatePort.latestTelemetry(assetId)
                .map(Telemetry::flightState)
                .map(FlightState::armed)
                .orElse(null);
        if (armed == null) {
            audit(actor, assetId, command, REFUSED_ARMING_UNKNOWN);
            throw new IllegalStateException("Arming state unknown for asset " + assetId.value() + "; refusing to write");
        }
        if (armed) {
            audit(actor, assetId, command, REFUSED_ARMED);
            throw new IllegalStateException("Asset " + assetId.value() + " is armed; refusing to write");
        }
    }

    private Device firstSupportedDevice(AssetId assetId) {
        AssetDetails details = assetService.details(assetId);
        return details.devices().stream()
                .filter(Device::isActive)
                .filter(vehicleConfigPort::supports)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Asset " + assetId.value() + " has no active device this platform can configure"));
    }

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
                assetId.value().toString(), "Remediation '" + command + "' for asset " + assetId.value(), attributes));
    }
}
