package com.drones.vision.api.support;

import com.drones.vision.flight.domain.model.VehicleProfile;
import com.drones.vision.flight.domain.port.VehicleProfileRepositoryPort;
import com.drones.vision.identity.application.AuthService;
import com.drones.vision.identity.domain.model.User;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.DeviceId;
import com.drones.vision.kernel.UserId;
import com.drones.vision.warehouse.domain.model.Asset;
import com.drones.vision.warehouse.domain.model.Custody;
import com.drones.vision.warehouse.domain.port.AssetUsageRepositoryPort;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Joins the read-only facts onto an asset row that neither {@code AssetSummary} nor {@code
 * AssetService} can own without either crossing the warehouse&rarr;flight / warehouse&rarr;identity
 * dependency rule or widening a canonical record roughly twenty {@code new AssetSummary(...)} call
 * sites across five modules construct (docs/plans/active/WAREHOUSE-UX-PLAN.md &sect;3.3, D5;
 * docs/plans/active/WAREHOUSE-UX-CONTEXT.md W4 handoff):
 *
 * <ul>
 *   <li>{@link #firmwareOf(Asset)} &mdash; firmware stays flight-owned (D5): "Warehouse must not
 *       read {@code vehicle_profiles}." The join happens here instead, in vision-api, the same
 *       trick {@code AssetAttention} uses for battery ({@code DefaultFleetSummaryService}) and
 *       {@code ReadinessController} uses for the readiness row. Mirrors {@code
 *       DefaultReadinessService#latestProfileOf} exactly: tries every device on the asset in order,
 *       returns the first snapshot found, no TELEMETRY-capability filtering (no existing precedent
 *       in this codebase does either).</li>
 *   <li>{@link #totalFlightSecondsByAsset()} &mdash; one aggregate query over {@code asset_usages},
 *       computed once per list render rather than once per asset. {@link AssetUsageRepositoryPort}
 *       is warehouse's own port, so this half is not a cross-context read at all &mdash; it is kept
 *       off {@code AssetSummary} purely to avoid widening that record's constructor for a field only
 *       the HTTP row response needs.</li>
 *   <li>{@link #custodianNameOf(Custody)} &mdash; the custodian's display name
 *       (docs/plans/active/INVENTORY-REWORK-PLAN.md D3). Warehouse's {@code Custody} holds a bare
 *       {@link UserId} and must not learn to read identity, so the name is joined here, exactly as
 *       firmware is. It is a <em>label lookup by id</em> on a person the row already names &mdash;
 *       {@link AuthService#find(UserId)}, the same unscoped by-id read {@code SeatSupport} already
 *       uses for this purpose &mdash; not a listing, so it widens nothing about who a caller may
 *       enumerate. That distinction is the whole point: {@code GET /api/users} answers an empty list
 *       for a pilot's {@code ASSIGNED_ASSETS} scope, which is why the client-side join it replaces
 *       rendered raw UUIDs (docs/plans/active/INVENTORY-REWORK-CONTEXT.md &sect;3, defect C).</li>
 * </ul>
 *
 * <p>Bundled into one collaborator, rather than as two separate constructor parameters, so {@link
 * com.drones.vision.api.controller.AssetController}'s constructor stays at
 * .claude/skills/java-clean-code/SKILL.md &sect;3's five-parameter ceiling &mdash; see that class's
 * own javadoc for the collaborator it already had to split off for the identical reason.
 *
 * <h2>Threading</h2>
 * Holds no mutable state; every call reads fresh from the injected ports.
 */
public final class AssetRowFacts {

    private final VehicleProfileRepositoryPort vehicleProfileRepositoryPort;
    private final AssetUsageRepositoryPort assetUsageRepositoryPort;
    private final AuthService authService;

    public AssetRowFacts(VehicleProfileRepositoryPort vehicleProfileRepositoryPort,
                          AssetUsageRepositoryPort assetUsageRepositoryPort, AuthService authService) {
        this.vehicleProfileRepositoryPort =
                Objects.requireNonNull(vehicleProfileRepositoryPort, "vehicleProfileRepositoryPort must not be null");
        this.assetUsageRepositoryPort =
                Objects.requireNonNull(assetUsageRepositoryPort, "assetUsageRepositoryPort must not be null");
        this.authService = Objects.requireNonNull(authService, "authService must not be null");
    }

    /**
     * Resolves {@code asset}'s most recently observed firmware by trying every device on the asset,
     * in order, and returning the first snapshot found.
     *
     * @param asset the asset to resolve firmware for
     * @return the most recent {@link VehicleProfile} found across the asset's devices, or empty if
     *         none of them was ever probed
     */
    public Optional<VehicleProfile> firmwareOf(Asset asset) {
        Objects.requireNonNull(asset, "asset must not be null");
        for (DeviceId deviceId : asset.devices()) {
            Optional<VehicleProfile> found = vehicleProfileRepositoryPort.findLatest(deviceId);
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }

    /**
     * @return cumulative flight seconds per asset, fleet-wide, in one query (see {@link
     *         AssetUsageRepositoryPort#totalFlightSecondsByAsset()} for the exact semantics); an
     *         asset absent from the map has never flown, so a caller should default a missing key to
     *         {@code 0L}
     */
    public Map<AssetId, Long> totalFlightSecondsByAsset() {
        return assetUsageRepositoryPort.totalFlightSecondsByAsset();
    }

    /**
     * Resolves the display name of whoever currently holds {@code custody}.
     *
     * <p>Answers {@code null} — not the raw id — for an in-stock asset or a custodian whose user
     * record no longer resolves, so {@code CustodyResponse} omits the field rather than showing a
     * UUID dressed up as a person's name; the id itself is already on the row for a client that
     * needs something to display.
     *
     * @param custody the custody to resolve a name for; never {@code null}
     * @return the custodian's display name, or {@code null} if nobody holds the asset or the user is
     *         unknown
     */
    public String custodianNameOf(Custody custody) {
        Objects.requireNonNull(custody, "custody must not be null");
        UserId custodianId = custody.custodianId();
        if (custodianId == null) {
            return null;
        }
        return authService.find(custodianId).map(User::displayName).orElse(null);
    }
}
