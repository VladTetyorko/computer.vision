package com.drones.vision.warehouse.application.directory;

import com.drones.vision.warehouse.domain.model.Asset;
import com.drones.vision.kernel.DeviceId;
import com.drones.vision.warehouse.domain.model.Device;
import com.drones.vision.warehouse.application.asset.AssetService;
import com.drones.vision.warehouse.application.device.DeviceService;

import java.util.Optional;

/**
 * Read-only device/asset identity lookups for a collaborator that must not depend on {@link
 * AssetService}/{@link DeviceService} (docs/plans/active/ARCHITECTURE-AUDIT-2026-08-26.md D1, wave
 * R3) — namely vision-perception's {@code UsageTracker}, the one call site this exists for today.
 *
 * <h2>Why this exists instead of reusing {@code AssetService}/{@code DeviceService}</h2>
 * The R3 brief's literal suggestion was to route these lookups through the published {@code
 * AssetService}/{@code DeviceService} rather than a repository port. Both of those, however, are
 * wired with an {@code AssetLiveStatePort} collaborator (to report an asset's live streaming
 * state), and its only implementation ({@code StreamBackedAssetLiveState}, vision-perception)
 * depends on {@code UsageTracker} itself. Wiring {@code UsageTracker -> AssetService ->
 * AssetLiveStatePort -> UsageTracker} is a bean cycle Spring's constructor-only injection (no
 * framework types are allowed in {@code contexts/**} to break it with, e.g. {@code
 * ObjectProvider}) cannot resolve. This service is the narrower seam that avoids the cycle: it is
 * backed only by the two raw repository ports below, with no {@code AssetLiveStatePort} in its own
 * dependency graph, so nothing that depends on it can ever complete a cycle back through {@code
 * UsageTracker}. It still removes exactly what D1 asked removed — {@code UsageTracker} importing
 * {@code AssetRepositoryPort}/{@code DeviceRepositoryPort} directly — without reusing the two
 * services that happen to have a wider blast radius than this caller needs.
 *
 * <p>Deliberately narrow: two device-identity reads, nothing else. A caller that needs the fuller
 * {@code AssetService}/{@code DeviceService} surface (writes, scope-checked reads, live state)
 * should keep depending on those, not this.
 */
public interface AssetDirectoryService {

    /**
     * Finds the asset that wraps the given device, if any — used to resolve "which asset does this
     * device's stream/telemetry belong to" without exposing {@code AssetRepositoryPort} itself.
     *
     * @param deviceId the device id
     * @return the owning asset, or {@link Optional#empty()} if the device is unassigned/unknown
     */
    Optional<Asset> findByDevice(DeviceId deviceId);

    /**
     * Finds a device by id — used to inspect a device's capabilities (e.g. {@code
     * Capability#TELEMETRY}) when deciding whether to subscribe a telemetry source for it.
     *
     * @param deviceId the device id
     * @return the device, or {@link Optional#empty()} if none exists
     */
    Optional<Device> findDevice(DeviceId deviceId);
}
