package com.drones.vision.application.flight;

import com.drones.vision.domain.model.Asset;
import com.drones.vision.domain.model.AssetId;
import com.drones.vision.domain.model.AuditAction;
import com.drones.vision.domain.model.AuditEntry;
import com.drones.vision.domain.model.AuditTargetType;
import com.drones.vision.domain.model.CommandResult;
import com.drones.vision.domain.model.Device;
import com.drones.vision.domain.model.FlightCapability;
import com.drones.vision.domain.model.UserId;
import com.drones.vision.domain.port.out.AuditTrailPort;
import com.drones.vision.domain.port.out.FlightCommandPort;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import com.drones.vision.application.asset.AssetDetails;
import com.drones.vision.application.asset.AssetService;
import com.drones.vision.application.asset.DefaultAssetService;
import com.drones.vision.application.scope.AccessDeniedException;
import com.drones.vision.application.scope.VisibilityScope;

/**
 * The one implementation of {@link FlightCommandService}.
 *
 * <h2>Resolving which device to command</h2>
 * Every command resolves {@code assetId} via {@link AssetService#details} ({@link
 * java.util.NoSuchElementException} for an unknown asset — the same 404 every other asset-scoped
 * read/write in this package already produces), then picks the first of the asset's <b>active</b>
 * (not soft-deleted, not deactivated — the same {@link Device#isActive()} filter {@link
 * DefaultAssetService#startStream} already applies when resolving a video device) devices {@link
 * FlightCommandPort#supports} claims. Zero matches is {@link IllegalStateException} ("not
 * commandable" → 409, docs/DRONE-INFRA-PLAN.md I-e's frozen wire contract); more than one match
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
 * DefaultAssetService#create}'s category-validation guard failing before its own first audit write.
 * {@link AuditAction} (vision-domain, out of this wave's file scope) has no dedicated "commanded"
 * value; {@link AuditAction#UPDATED} is used as the closest existing fit — the free-form {@code
 * summary}/{@code details} carry the actual specifics ({@code command}, {@code result}).
 *
 * <h2>Scope gate (docs/U-SCOPE-PLAN.md, U-e slice 2, feature 3)</h2>
 * Each <em>command</em> takes the acting user's {@link VisibilityScope}; when it does not include
 * the resolved asset the command is refused up front with {@link AccessDeniedException} (403),
 * <em>and audited</em> with {@code result=DENIED:out of scope} — an authorization refusal is a
 * security-relevant event worth a trail line. {@link #capabilities}, being a <em>read</em>, instead
 * hides an out-of-scope asset behind a {@link java.util.NoSuchElementException} (404), matching the
 * scoped-read convention rather than the command gate. An {@link VisibilityScope#unbounded()} scope
 * includes every asset, so neither gate fires for ADMIN / auth-off.
 *
 * <h2>Threading</h2>
 * Holds no mutable state — all shared state is reached through the injected collaborators.
 */
public final class DefaultFlightCommandService implements FlightCommandService {

    private static final String COMMAND_RTL = "RTL";
    private static final String COMMAND_ARM = "ARM";
    private static final String COMMAND_DISARM = "DISARM";
    private static final String COMMAND_MODE_PREFIX = "MODE:";
    private static final String ATTR_ASSET_ID = "assetId";
    private static final String ATTR_COMMAND = "command";
    private static final String ATTR_RESULT = "result";
    private static final String REFUSED_PREFIX = "REFUSED:";
    private static final String DENIED_OUT_OF_SCOPE = "DENIED:out of scope";

    private final AssetService assetService;
    private final FlightCommandPort flightCommandPort;
    private final AuditTrailPort auditTrail;

    public DefaultFlightCommandService(AssetService assetService, FlightCommandPort flightCommandPort,
                                        AuditTrailPort auditTrail) {
        this.assetService = Objects.requireNonNull(assetService, "assetService must not be null");
        this.flightCommandPort = Objects.requireNonNull(flightCommandPort, "flightCommandPort must not be null");
        this.auditTrail = Objects.requireNonNull(auditTrail, "auditTrail must not be null");
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
        Device device = resolveForCommand(assetId, actor, scope, COMMAND_ARM);
        return sendAndAudit(assetId, actor, COMMAND_ARM, device, d -> flightCommandPort.arm(d, force));
    }

    @Override
    public CommandResult disarm(AssetId assetId, boolean force, UserId actor, VisibilityScope scope) {
        requireCommandArgs(assetId, actor, scope);
        Device device = resolveForCommand(assetId, actor, scope, COMMAND_DISARM);
        return sendAndAudit(assetId, actor, COMMAND_DISARM, device, d -> flightCommandPort.disarm(d, force));
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
        AssetDetails details = assetService.details(assetId); // NoSuchElementException -> 404
        Asset asset = details.summary().asset();
        if (!scope.includes(asset)) {
            // A read would 404 to hide existence, but for a command it is more honest to deny
            // explicitly (403). An unbounded scope never lands here. The denial is audited: an
            // authorization refusal is a security-relevant event, unlike the "no commandable device"
            // config guard below, which is not an attempt.
            audit(actor, assetId, command, DENIED_OUT_OF_SCOPE);
            throw new AccessDeniedException(
                    "Asset " + assetId.value() + " is outside your scope; you may not command it");
        }
        return firstCommandableDevice(details.devices())
                .orElseThrow(() -> new IllegalStateException("Asset " + assetId.value()
                        + " has no active MAVLink telemetry device to command"));
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
