package com.drones.vision.application;

import com.drones.vision.domain.model.Asset;
import com.drones.vision.domain.model.AssetId;
import com.drones.vision.domain.model.AuditAction;
import com.drones.vision.domain.model.AuditEntry;
import com.drones.vision.domain.model.AuditTargetType;
import com.drones.vision.domain.model.CommandResult;
import com.drones.vision.domain.model.Device;
import com.drones.vision.domain.model.UserId;
import com.drones.vision.domain.port.out.AuditTrailPort;
import com.drones.vision.domain.port.out.FlightCommandPort;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The one implementation of {@link FlightCommandService}.
 *
 * <h2>Resolving which device to command</h2>
 * {@link #returnToHome} resolves {@code assetId} via {@link AssetService#details} ({@link
 * java.util.NoSuchElementException} for an unknown asset — the same 404 every other asset-scoped
 * read/write in this package already produces), then picks the first of the asset's <b>active</b>
 * (not soft-deleted, not deactivated — the same {@link Device#isActive()} filter {@link
 * DefaultAssetService#startStream} already applies when resolving a video device) devices {@link
 * FlightCommandPort#supports} claims. Zero matches is {@link IllegalStateException} ("not
 * commandable" → 409, docs/DRONE-INFRA-PLAN.md I-e Stage 1's frozen wire contract); more than one
 * match silently takes the first — Stage 1 fleets pair exactly one flight controller per asset, so
 * this is not yet a real ambiguity to disambiguate.
 *
 * <h2>Why the port's {@code IllegalArgumentException} becomes an {@code IllegalStateException}</h2>
 * {@link FlightCommandPort#returnToHome(Device)} throws {@link IllegalArgumentException} for an
 * unsupported device or a firmware with no invocable return-to-home capability, and {@link
 * IllegalStateException} when the aircraft's own acknowledgement explicitly refuses. Left as-is,
 * {@code vision-api}'s {@code ApiExceptionHandler} would map those to two different codes (400 and
 * 409) — but the frozen wire contract wants exactly one refusal code, 409, for every "not
 * commandable" outcome. Rather than special-casing this one endpoint in the global handler (which
 * maps {@code IllegalArgumentException} to 400 for every other endpoint in this codebase), this
 * method catches the port's {@code IllegalArgumentException} and rethrows it as an {@code
 * IllegalStateException} carrying the exact same message — the handler's existing, unmodified
 * {@code IllegalStateException}→409 rule then applies without touching its semantics for anyone
 * else.
 *
 * <h2>Audit</h2>
 * One {@link AuditEntry} is written per actual command attempt (i.e. once {@link
 * FlightCommandPort#returnToHome(Device)} is actually called) — success and refusal alike, so
 * commanding an aircraft always leaves a trail either way. Resolving no commandable device at all
 * is not itself an attempt (nothing was ever sent to any aircraft) and is not audited, the same way
 * {@link DefaultAssetService#create}'s category-validation guard fails before its own first audit
 * write. {@link AuditAction} (vision-domain, out of this wave's file scope) has no dedicated
 * "commanded" value; {@link AuditAction#UPDATED} is used as the closest existing fit — the
 * free-form {@code summary}/{@code details} carry the actual specifics ({@code command}, {@code
 * result}).
 *
 * <h2>Scope gate (docs/U-SCOPE-PLAN.md, U-e slice 2, feature 3)</h2>
 * {@link #returnToHome} takes the acting user's {@link VisibilityScope}. When the scope does not
 * include the resolved asset the command is refused up front with {@link AccessDeniedException}
 * (403), <em>and audited</em> with {@code result=DENIED:out of scope} — an authorization refusal is
 * a security-relevant event worth a trail line, unlike the "no commandable device" config guard,
 * which is not an attempt and is not audited. An {@link VisibilityScope#unbounded()} scope includes
 * every asset, so the gate never fires for ADMIN / auth-off — behavior is unchanged from before.
 *
 * <h2>Threading</h2>
 * Holds no mutable state — all shared state is reached through the injected collaborators.
 */
public final class DefaultFlightCommandService implements FlightCommandService {

    private static final String COMMAND_RTL = "RTL";
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
        Objects.requireNonNull(assetId, "assetId must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(scope, "scope must not be null");

        AssetDetails details = assetService.details(assetId); // NoSuchElementException -> 404
        Asset asset = details.summary().asset();
        if (!scope.includes(asset)) {
            // The user may see the asset does not exist for them (a read would 404), but for a
            // command it is more honest to deny explicitly (403). An unbounded scope never lands
            // here. The denial is audited: an authorization refusal is a security-relevant event,
            // unlike the "no commandable device" config guard below, which is not an attempt.
            audit(actor, assetId, DENIED_OUT_OF_SCOPE);
            throw new AccessDeniedException(
                    "Asset " + assetId.value() + " is outside your scope; you may not command it");
        }
        Device device = resolveCommandableDevice(assetId, details.devices());

        try {
            CommandResult result = flightCommandPort.returnToHome(device);
            audit(actor, assetId, result.name());
            return result;
        } catch (IllegalArgumentException e) {
            // See this class's own javadoc "Why the port's IllegalArgumentException becomes an
            // IllegalStateException" section.
            audit(actor, assetId, REFUSED_PREFIX + e.getMessage());
            throw new IllegalStateException(e.getMessage(), e);
        } catch (IllegalStateException e) {
            // The aircraft's own acknowledgement explicitly refused -- already 409-shaped.
            audit(actor, assetId, REFUSED_PREFIX + e.getMessage());
            throw e;
        }
    }

    private Device resolveCommandableDevice(AssetId assetId, List<Device> devices) {
        return devices.stream()
                .filter(Device::isActive)
                .filter(flightCommandPort::supports)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Asset " + assetId.value()
                        + " has no active MAVLink telemetry device to command"));
    }

    private void audit(UserId actor, AssetId assetId, String result) {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put(ATTR_ASSET_ID, assetId.value().toString());
        attributes.put(ATTR_COMMAND, COMMAND_RTL);
        attributes.put(ATTR_RESULT, result);
        auditTrail.record(AuditEntry.of(actor, AuditAction.UPDATED, AuditTargetType.ASSET,
                assetId.value().toString(), "Return-to-home commanded for asset " + assetId.value(), attributes));
    }
}
