package com.drones.vision.flight.application;

import com.drones.vision.warehouse.domain.model.Asset;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.platform.AuditAction;
import com.drones.vision.platform.AuditEntry;
import com.drones.vision.platform.AuditTargetType;
import com.drones.vision.flight.domain.model.CommandResult;
import com.drones.vision.warehouse.domain.model.Device;
import com.drones.vision.flight.domain.model.FlightCapability;
import com.drones.vision.flight.domain.model.ReadinessReport;
import com.drones.vision.kernel.UserId;
import com.drones.vision.platform.AuditTrailPort;
import com.drones.vision.flight.domain.port.FlightCommandPort;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import com.drones.vision.warehouse.application.asset.AssetDetails;
import com.drones.vision.warehouse.application.asset.AssetService;
import com.drones.vision.platform.AccessDeniedException;
import com.drones.vision.platform.VisibilityScope;

/**
 * The one implementation of {@link FlightCommandService}.
 *
 * <h2>Resolving which device to command</h2>
 * Every command resolves {@code assetId} via {@link AssetService#details} ({@link
 * java.util.NoSuchElementException} for an unknown asset — the same 404 every other asset-scoped
 * read/write in this package already produces), then picks the first of the asset's <b>active</b>
 * (not soft-deleted, not deactivated — the same {@link Device#isActive()} filter {@link
 * com.drones.vision.warehouse.application.asset.DefaultAssetService#startStream} already applies when resolving a video device) devices {@link
 * FlightCommandPort#supports} claims. Zero matches is {@link IllegalStateException} ("not
 * commandable" → 409, docs/plans/active/DRONE-INFRA-PLAN.md I-e's frozen wire contract); more than one match
 * silently takes the first — Stage 1/2 fleets pair exactly one flight controller per asset, so this
 * is not yet a real ambiguity to disambiguate.
 *
 * <h2>Why the port's {@code IllegalArgumentException} becomes an {@code IllegalStateException}</h2>
 * The port's command methods throw {@link IllegalArgumentException} for an unsupported device or a
 * firmware with no invocable capability (e.g. Betaflight — <em>not commandable</em>), and {@link
 * IllegalStateException} when the aircraft's own acknowledgement explicitly refuses. Left as-is,
 * {@code vision-api}'s {@code ApiExceptionHandler} would map those to two different codes (400 and
 * 409) — but the frozen wire contract wants exactly one refusal code, 409, for every "not
 * commandable" outcome once a command is actually attempted. Rather than special-casing this in the
 * global handler, {@link #sendAndAudit} catches the port's {@code IllegalArgumentException} and
 * rethrows it as an {@code IllegalStateException} carrying the exact same message — the handler's
 * existing, unmodified {@code IllegalStateException}→409 rule then applies.
 *
 * <h2>Unknown mode is a 400, not a 409</h2>
 * {@link #setMode} validates the requested mode against the vehicle's own {@link
 * FlightCapability#selectableModes()} <em>before</em> the port is called at all, so an unknown mode
 * escapes as a plain {@link IllegalArgumentException} (→ 400) — deliberately <b>not</b> caught by
 * {@link #sendAndAudit}'s try/catch, which only wraps the actual port call. This keeps the two
 * failures cleanly split by <em>where</em> they are thrown: an unknown mode (client error, nothing
 * sent) is a 400 raised during pre-flight validation; a not-commandable vehicle (the port rejecting
 * an attempted command) is a 409. The mode check only fires for a vehicle that actually reports
 * {@link FlightCapability#modeSelectSupported()} — a not-commandable vehicle never reaches it,
 * because {@link #resolveForCommand} already 409s on it (no {@code supports} device) or the port
 * itself does; either way that stays a 409, never a 400.
 *
 * <h2>Audit</h2>
 * One {@link AuditEntry} is written per actual command attempt (i.e. once the port is actually
 * called) — success and refusal alike — plus one for a scope denial. Guards that fail before any
 * command is sent are <em>not</em> attempts and are not audited: resolving no commandable device at
 * all, and an unknown-mode rejection (nothing ever left for any aircraft), both mirror {@link
 * com.drones.vision.warehouse.application.asset.DefaultAssetService#create}'s category-validation guard failing before its own first audit write.
 * {@link AuditAction} (vision-domain, out of this wave's file scope) has no dedicated "commanded"
 * value; {@link AuditAction#UPDATED} is used as the closest existing fit — the free-form {@code
 * summary}/{@code details} carry the actual specifics ({@code command}, {@code result}).
 *
 * <h2>Scope gate (docs/plans/done/U-SCOPE-PLAN.md, U-e slice 2, feature 3)</h2>
 * Each <em>command</em> takes the acting user's {@link VisibilityScope}; when it does not include
 * the resolved asset the command is refused up front with {@link AccessDeniedException} (403),
 * <em>and audited</em> with {@code result=DENIED:out of scope} — an authorization refusal is a
 * security-relevant event worth a trail line. {@link #capabilities}, being a <em>read</em>, instead
 * hides an out-of-scope asset behind a {@link java.util.NoSuchElementException} (404), matching the
 * scoped-read convention rather than the command gate. An {@link VisibilityScope#unbounded()} scope
 * includes every asset, so neither gate fires for ADMIN / auth-off.
 *
 * <h2>Maintenance-grounding gate (docs/plans/active/ASSET-FLOWS-PLAN.md, S1)</h2>
 * {@link #arm} additionally refuses — {@link IllegalStateException} (409), audited {@code
 * REFUSED:maintenance-grounded} — when {@link ReadinessService#evaluate} reports an open
 * flight-blocking {@code MaintenanceRecord}. This mirrors {@link
 * DefaultManualControlService#engage} exactly and runs after the scope gate but before any device
 * is resolved, so a grounded asset's port is never touched. No other command method is gated:
 * disarm, emergency-stop, RTH and mode are energy-reducing/recovery verbs that must stay available
 * on a vehicle that may already be airborne.
 *
 * <h2>Threading</h2>
 * Holds no mutable state — all shared state is reached through the injected collaborators.
 */
public final class DefaultFlightCommandService implements FlightCommandService {

    private static final String COMMAND_RTL = "RTL";
    private static final String COMMAND_ARM = "ARM";
    private static final String COMMAND_DISARM = "DISARM";
    private static final String COMMAND_EMERGENCY_STOP = "EMERGENCY_STOP";
    private static final String COMMAND_AUX_PREFIX = "AUX:";
    private static final String COMMAND_MODE_PREFIX = "MODE:";
    private static final String ATTR_ASSET_ID = "assetId";
    private static final String ATTR_COMMAND = "command";
    private static final String ATTR_RESULT = "result";
    private static final String REFUSED_PREFIX = "REFUSED:";
    private static final String DENIED_OUT_OF_SCOPE = "DENIED:out of scope";
    private static final String RESULT_REFUSED_MAINTENANCE = "REFUSED:maintenance-grounded";

    private final AssetService assetService;
    private final FlightCommandPort flightCommandPort;
    private final AuditTrailPort auditTrail;
    private final ReadinessService readinessService;

    public DefaultFlightCommandService(AssetService assetService, FlightCommandPort flightCommandPort,
                                        AuditTrailPort auditTrail, ReadinessService readinessService) {
        this.assetService = Objects.requireNonNull(assetService, "assetService must not be null");
        this.flightCommandPort = Objects.requireNonNull(flightCommandPort, "flightCommandPort must not be null");
        this.auditTrail = Objects.requireNonNull(auditTrail, "auditTrail must not be null");
        this.readinessService = Objects.requireNonNull(readinessService, "readinessService must not be null");
    }

    @Override
    public CommandResult returnToHome(AssetId assetId, UserId actor, VisibilityScope scope) {
        requireCommandArgs(assetId, actor, scope);
        Device device = resolveForCommand(assetId, actor, scope, COMMAND_RTL);
        return sendAndAudit(assetId, actor, COMMAND_RTL, device, flightCommandPort::returnToHome);
    }

    @Override
    public CommandResult setMode(AssetId assetId, String modeName, UserId actor, VisibilityScope scope) {
        requireCommandArgs(assetId, actor, scope);
        Objects.requireNonNull(modeName, "modeName must not be null");
        String command = COMMAND_MODE_PREFIX + modeName;
        Device device = resolveForCommand(assetId, actor, scope, command);
        // Pre-flight mode validation, outside sendAndAudit's try/catch on purpose: an unknown mode
        // for a mode-capable vehicle is a client error (400), not the 409 a not-commandable vehicle
        // gets. A not-commandable vehicle reports modeSelectSupported=false and never lands here --
        // it has already 409'd in resolveForCommand or will when the port rejects the attempt.
        FlightCapability capability = flightCommandPort.capabilities(device);
        if (capability.modeSelectSupported() && !capability.selectableModes().contains(modeName)) {
            throw new IllegalArgumentException("Unknown flight mode '" + modeName + "' for asset "
                    + assetId.value() + "; selectable modes: " + capability.selectableModes());
        }
        return sendAndAudit(assetId, actor, command, device, d -> flightCommandPort.setMode(d, modeName));
    }

    @Override
    public CommandResult arm(AssetId assetId, boolean force, UserId actor, VisibilityScope scope) {
        requireCommandArgs(assetId, actor, scope);
        AssetDetails details = resolveScopedAsset(assetId, actor, scope, COMMAND_ARM);
        requireNotMaintenanceGrounded(assetId, actor, scope, COMMAND_ARM);
        Device device = commandableDevice(details, assetId);
        return sendAndAudit(assetId, actor, COMMAND_ARM, device, d -> flightCommandPort.arm(d, force));
    }

    @Override
    public CommandResult disarm(AssetId assetId, boolean force, UserId actor, VisibilityScope scope) {
        requireCommandArgs(assetId, actor, scope);
        Device device = resolveForCommand(assetId, actor, scope, COMMAND_DISARM);
        return sendAndAudit(assetId, actor, COMMAND_DISARM, device, d -> flightCommandPort.disarm(d, force));
    }

    @Override
    public CommandResult emergencyStop(AssetId assetId, UserId actor, VisibilityScope scope) {
        requireCommandArgs(assetId, actor, scope);
        Device device = resolveForCommand(assetId, actor, scope, COMMAND_EMERGENCY_STOP);
        return sendAndAudit(assetId, actor, COMMAND_EMERGENCY_STOP, device, flightCommandPort::emergencyStop);
    }

    @Override
    public CommandResult auxFunction(AssetId assetId, int function, int level, UserId actor, VisibilityScope scope) {
        requireCommandArgs(assetId, actor, scope);
        // Range-checked before anything is resolved or sent, so a malformed binding is a 400 and
        // never reaches an aircraft -- the same "guard before an attempt" split setMode uses for an
        // unknown mode name.
        if (level < 0 || level > 2) {
            throw new IllegalArgumentException("Aux function switch level must be within [0,2]: " + level);
        }
        String command = COMMAND_AUX_PREFIX + function + "@" + level;
        Device device = resolveForCommand(assetId, actor, scope, command);
        return sendAndAudit(assetId, actor, command, device, d -> flightCommandPort.auxFunction(d, function, level));
    }

    @Override
    public FlightCapability capabilities(AssetId assetId, VisibilityScope scope) {
        Objects.requireNonNull(assetId, "assetId must not be null");
        Objects.requireNonNull(scope, "scope must not be null");
        // Scoped read: NoSuchElementException for an unknown OR out-of-scope asset (404, hides
        // existence) -- deliberately not the 403 a command gets, per this class's javadoc.
        AssetDetails details = assetService.details(scope, assetId);
        return firstCommandableDevice(details.devices())
                .map(flightCommandPort::capabilities)
                .orElseGet(FlightCapability::notCommandable);
    }

    private void requireCommandArgs(AssetId assetId, UserId actor, VisibilityScope scope) {
        Objects.requireNonNull(assetId, "assetId must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(scope, "scope must not be null");
    }

    /**
     * Resolves the asset, enforces the command scope gate (auditing a denial), and returns the
     * device to command. Shared by every command method so the gate and device-resolution rules can
     * never drift between them.
     */
    private Device resolveForCommand(AssetId assetId, UserId actor, VisibilityScope scope, String command) {
        return commandableDevice(resolveScopedAsset(assetId, actor, scope, command), assetId);
    }

    /**
     * Resolves the asset and enforces the command scope gate (auditing a denial). Split out from
     * {@link #resolveForCommand} so {@link #arm} can insert the maintenance-grounding gate between
     * the scope check and device resolution, mirroring {@link DefaultManualControlService#engage}'s
     * scope-then-grounding-then-device order — every other command method still goes through {@link
     * #resolveForCommand} unchanged.
     */
    private AssetDetails resolveScopedAsset(AssetId assetId, UserId actor, VisibilityScope scope, String command) {
        AssetDetails details = assetService.details(assetId); // NoSuchElementException -> 404
        Asset asset = details.summary().asset();
        if (!scope.includes(asset.id(), asset.ownership())) {
            // A read would 404 to hide existence, but for a command it is more honest to deny
            // explicitly (403). An unbounded scope never lands here. The denial is audited: an
            // authorization refusal is a security-relevant event, unlike the "no commandable device"
            // config guard below, which is not an attempt.
            audit(actor, assetId, command, DENIED_OUT_OF_SCOPE);
            throw new AccessDeniedException(
                    "Asset " + assetId.value() + " is outside your scope; you may not command it");
        }
        return details;
    }

    private Device commandableDevice(AssetDetails details, AssetId assetId) {
        return firstCommandableDevice(details.devices())
                .orElseThrow(() -> new IllegalStateException("Asset " + assetId.value()
                        + " has no active MAVLink telemetry device to command"));
    }

    /**
     * Refuses a command when the asset carries an open flight-blocking {@code MaintenanceRecord}
     * (custody grounding or an equivalent {@code GROUNDING}/{@code INSPECTION_DUE} record) — the
     * same {@link ReadinessService#evaluate} + {@link DefaultReadinessService#MAINTENANCE_BLOCKER_PREFIX}
     * idiom {@link DefaultManualControlService#engage} already uses. Only {@link #arm} calls this:
     * energy-reducing/recovery verbs (disarm, emergency-stop, RTH, mode) must stay available on an
     * already-moving vehicle (docs/plans/active/ASSET-FLOWS-PLAN.md &sect;2).
     */
    private void requireNotMaintenanceGrounded(AssetId assetId, UserId actor, VisibilityScope scope, String command) {
        ReadinessReport report = readinessService.evaluate(assetId, scope);
        List<String> maintenanceBlockers = report.blockers().stream()
                .filter(blocker -> blocker.startsWith(DefaultReadinessService.MAINTENANCE_BLOCKER_PREFIX))
                .toList();
        if (maintenanceBlockers.isEmpty()) {
            return;
        }
        audit(actor, assetId, command, RESULT_REFUSED_MAINTENANCE);
        throw new IllegalStateException("Asset " + assetId.value()
                + " is grounded for maintenance and may not be armed: "
                + String.join(" ", maintenanceBlockers));
    }

    private Optional<Device> firstCommandableDevice(List<Device> devices) {
        return devices.stream()
                .filter(Device::isActive)
                .filter(flightCommandPort::supports)
                .findFirst();
    }

    /**
     * Calls the port for one command against an already-resolved device and audits the outcome. The
     * port's {@code IllegalArgumentException} (not-commandable) is rethrown as an {@code
     * IllegalStateException} so it stays a 409; its {@code IllegalStateException} (aircraft refused)
     * propagates unchanged. Both are audited as {@code REFUSED:<message>}.
     */
    private CommandResult sendAndAudit(AssetId assetId, UserId actor, String command, Device device,
                                        Function<Device, CommandResult> portCall) {
        try {
            CommandResult result = portCall.apply(device);
            audit(actor, assetId, command, result.name());
            return result;
        } catch (IllegalArgumentException e) {
            // See this class's own javadoc "Why the port's IllegalArgumentException becomes an
            // IllegalStateException" section.
            audit(actor, assetId, command, REFUSED_PREFIX + e.getMessage());
            throw new IllegalStateException(e.getMessage(), e);
        } catch (IllegalStateException e) {
            // The aircraft's own acknowledgement explicitly refused -- already 409-shaped.
            audit(actor, assetId, command, REFUSED_PREFIX + e.getMessage());
            throw e;
        }
    }

    private void audit(UserId actor, AssetId assetId, String command, String result) {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put(ATTR_ASSET_ID, assetId.value().toString());
        attributes.put(ATTR_COMMAND, command);
        attributes.put(ATTR_RESULT, result);
        auditTrail.record(AuditEntry.of(actor, AuditAction.UPDATED, AuditTargetType.ASSET,
                assetId.value().toString(), "Flight command '" + command + "' for asset " + assetId.value(),
                attributes));
    }
}
