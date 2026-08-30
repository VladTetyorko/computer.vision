package com.drones.vision.api.support;

import com.drones.vision.flight.domain.model.VehicleProfile;
import com.drones.vision.flight.domain.port.VehicleProfileRepositoryPort;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.DeviceId;
import com.drones.vision.warehouse.domain.model.Asset;
import com.drones.vision.warehouse.domain.port.AssetUsageRepositoryPort;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Joins two read-only facts onto an asset row that neither {@code AssetSummary} nor {@code
 * AssetService} can own without either crossing the warehouse&rarr;flight dependency rule or
 * widening a canonical record roughly twenty {@code new AssetSummary(...)} call sites across five
 * modules construct (docs/plans/active/WAREHOUSE-UX-PLAN.md &sect;3.3, D5; docs/plans/active/
 * WAREHOUSE-UX-CONTEXT.md W4 handoff):
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

    public AssetRowFacts(VehicleProfileRepositoryPort vehicleProfileRepositoryPort,
                          AssetUsageRepositoryPort assetUsageRepositoryPort) {
        this.vehicleProfileRepositoryPort =
                Objects.requireNonNull(vehicleProfileRepositoryPort, "vehicleProfileRepositoryPort must not be null");
        this.assetUsageRepositoryPort =
                Objects.requireNonNull(assetUsageRepositoryPort, "assetUsageRepositoryPort must not be null");
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
}
