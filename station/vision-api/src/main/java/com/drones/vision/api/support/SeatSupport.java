package com.drones.vision.api.support;

import com.drones.vision.flight.domain.model.SeatKind;
import com.drones.vision.identity.application.AuthService;
import com.drones.vision.identity.domain.model.User;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.DeviceId;
import com.drones.vision.kernel.Ownership;
import com.drones.vision.kernel.StreamId;
import com.drones.vision.kernel.UserId;
import com.drones.vision.perception.application.stream.ActiveStream;
import com.drones.vision.perception.application.stream.StreamService;
import com.drones.vision.platform.AuditAction;
import com.drones.vision.platform.AuditEntry;
import com.drones.vision.platform.AuditTargetType;
import com.drones.vision.platform.AuditTrailPort;
import com.drones.vision.warehouse.application.asset.AssetService;
import com.drones.vision.warehouse.domain.model.Asset;
import com.drones.vision.warehouse.domain.port.AssetRepositoryPort;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Bundles {@link com.drones.vision.api.security.SeatAccess}'s smaller, cross-context lookups behind
 * one collaborator — device/stream&rarr;asset resolution, asset ownership, a display-name lookup,
 * and the {@code FORCE}/{@code DENIED:SEAT_HELD} audit writes docs/plans/active/CREW-CONTROL-PLAN.md
 * &sect;3.6 assigns to the enforcement layer rather than {@code DefaultSeatService} itself — see
 * that class's own javadoc "Audit" section: its frozen {@code forceRelease(AssetId, SeatKind)}
 * signature carries no actor to attribute a {@code FORCE} entry to, and {@code take}/{@code release}/
 * {@code preempt} know nothing about a refused conflict being worth its own audit line.
 *
 * <p>Bundled rather than five more {@code SeatAccess} constructor parameters
 * (.claude/skills/java-clean-code/SKILL.md &sect;3) — none of these one-method reads is worth its own
 * collaborator, and none belongs inside {@code SeatAccess} itself, which is the seat *policy*
 * (&sect;3.2's five rules), not asset/stream lookup plumbing.
 *
 * <p>Deliberately does not reuse {@code StreamAccess}'s own private device/stream&rarr;asset helpers
 * (outside this wave's file scope) — the resolution here is the same small read, duplicated rather
 * than exposed from a class this wave may not edit.
 */
@Component
public final class SeatSupport {

    private static final String ATTR_ASSET_ID = "assetId";
    private static final String ATTR_COMMAND = "command";
    private static final String ATTR_RESULT = "result";
    private static final String COMMAND_SEAT = "SEAT";

    private final StreamService streamService;
    private final AssetRepositoryPort assetRepositoryPort;
    private final AssetService assetService;
    private final AuthService authService;
    private final AuditTrailPort auditTrailPort;

    public SeatSupport(StreamService streamService, AssetRepositoryPort assetRepositoryPort,
                        AssetService assetService, AuthService authService, AuditTrailPort auditTrailPort) {
        this.streamService = Objects.requireNonNull(streamService, "streamService must not be null");
        this.assetRepositoryPort =
                Objects.requireNonNull(assetRepositoryPort, "assetRepositoryPort must not be null");
        this.assetService = Objects.requireNonNull(assetService, "assetService must not be null");
        this.authService = Objects.requireNonNull(authService, "authService must not be null");
        this.auditTrailPort = Objects.requireNonNull(auditTrailPort, "auditTrailPort must not be null");
    }

    /**
     * The asset a device belongs to, if any — {@code AssetStreamController}/{@code StreamController}
     * only ever have a {@link DeviceId}/{@link StreamId} in hand at the point the camera seat must be
     * resolved.
     *
     * @param deviceId the device to resolve
     * @return the owning asset's id, or empty if the device belongs to no asset
     */
    public Optional<AssetId> assetIdOf(DeviceId deviceId) {
        return assetRepositoryPort.findByDeviceId(deviceId).map(Asset::id);
    }

    /**
     * As {@link #assetIdOf(DeviceId)}, resolved through the stream's currently-running device (see
     * {@link StreamService#streams()}). Empty for an id that does not currently name a running
     * stream — the same forgiving no-op {@code StreamAccess#requireVisible(StreamId)} documents,
     * left to the handler's own downstream 404/no-op.
     *
     * @param streamId the stream to resolve
     * @return the owning asset's id, or empty if unresolvable
     */
    public Optional<AssetId> assetIdOf(StreamId streamId) {
        return streamService.streams().stream()
                .filter(s -> s.streamId().equals(streamId))
                .map(ActiveStream::deviceId)
                .findFirst()
                .flatMap(this::assetIdOf);
    }

    /**
     * @param assetId the asset to resolve
     * @return the asset's ownership
     * @throws java.util.NoSuchElementException if {@code assetId} is unknown
     */
    public Ownership ownershipOf(AssetId assetId) {
        return assetService.details(assetId).summary().asset().ownership();
    }

    /**
     * {@link AuthService#find(UserId)}'s display name (CREW-CONTROL-PLAN.md &sect;3.6 IC-3/A10),
     * falling back to the raw id when the user has since been removed.
     *
     * @param userId the user to resolve
     * @return the display name, or {@code userId}'s own string form if not found
     */
    public String displayNameOrId(UserId userId) {
        return authService.find(userId).map(User::displayName).orElseGet(() -> userId.value().toString());
    }

    /**
     * Writes {@code FORCE:<KIND>} (&sect;3.2 rule 4, or the equivalent {@code DELETE
     * .../seats/{kind}} eviction) — the enforcement layer's own responsibility, per
     * {@code DefaultSeatService}'s own "Audit" javadoc.
     *
     * @param actor   the manager forcing the seat
     * @param assetId the asset whose seat was forced
     * @param kind    which seat
     */
    public void auditForce(UserId actor, AssetId assetId, SeatKind kind) {
        audit(actor, assetId, "FORCE:" + kind.name());
    }

    /**
     * Writes {@code DENIED:SEAT_HELD:<kind>} — a refused take, whether from a guarded verb's implicit
     * auto-take or an explicit {@code POST .../seats/{kind}}.
     *
     * @param actor   the caller who was refused
     * @param assetId the asset whose seat was contended
     * @param kind    which seat
     */
    public void auditDenied(UserId actor, AssetId assetId, SeatKind kind) {
        audit(actor, assetId, "DENIED:SEAT_HELD:" + kind.name());
    }

    private void audit(UserId actor, AssetId assetId, String result) {
        Map<String, String> attributes = Map.of(
                ATTR_ASSET_ID, assetId.value().toString(),
                ATTR_COMMAND, COMMAND_SEAT,
                ATTR_RESULT, result);
        auditTrailPort.record(AuditEntry.of(actor, AuditAction.UPDATED, AuditTargetType.ASSET,
                assetId.value().toString(), "Seat " + result + " for asset " + assetId.value(), attributes));
    }
}
