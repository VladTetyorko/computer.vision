package com.drones.vision.flight.domain.port;

import com.drones.vision.flight.domain.model.FlightPhase;
import com.drones.vision.flight.domain.model.VehicleProfile;
import com.drones.vision.kernel.DeviceId;
import com.drones.vision.kernel.UsageId;

import java.util.Optional;

/**
 * Driven port: append-only storage for {@link VehicleProfile} snapshots, keyed by {@link DeviceId}
 * rather than carried as a field on the profile itself (docs/plans/active/DRONE-ONBOARDING-PLAN.md
 * section 5.2, D5) -- the same "the key lives in the port call, not the payload" idiom {@code
 * TelemetryRepositoryPort#save(UsageId, Telemetry)} already uses.
 *
 * <h2>Contract</h2>
 * A device may accumulate many profiles over time (one per probe/re-probe); nothing here is ever
 * updated or deleted -- the forensic-evidence use case (section 2.4's paired PREFLIGHT/POSTFLIGHT
 * snapshots, O11) depends on every snapshot surviving.
 */
public interface VehicleProfileRepositoryPort {

    /**
     * Appends one snapshot for {@code deviceId}. Never overwrites an earlier snapshot.
     */
    void save(DeviceId deviceId, VehicleProfile profile);

    /**
     * @return the most recently observed profile for {@code deviceId}, or empty if this device has
     *         never been probed
     */
    Optional<VehicleProfile> findLatest(DeviceId deviceId);

    /**
     * Appends one snapshot for {@code deviceId}, tagged as belonging to {@code usageId}'s {@code
     * phase} -- the passport's attachment point (docs/plans/active/DRONE-ONBOARDING-PLAN.md O11).
     * {@code phase} is meaningful only as {@link FlightPhase#PREFLIGHT} or {@link
     * FlightPhase#POSTFLIGHT}; this port stores whatever it is given, and it is the application
     * layer's job to restrict which phases may be captured. Never overwrites an earlier snapshot,
     * same append-only contract as {@link #save(DeviceId, VehicleProfile)}.
     */
    void save(DeviceId deviceId, UsageId usageId, FlightPhase phase, VehicleProfile profile);

    /**
     * @return the most recently observed snapshot tagged with this {@code usageId} and {@code
     *         phase}, or empty if none was ever captured
     */
    Optional<VehicleProfile> findByUsageAndPhase(UsageId usageId, FlightPhase phase);
}
