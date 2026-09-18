package com.drones.vision.flight.domain.port;

import com.drones.vision.flight.domain.model.CarrierView;

import java.util.List;

/**
 * Driven port: every carrier currently registered on the station, regardless of which (if any)
 * asset currently claims it (LINK-PAIRING-PLAN.md §3.4/§7 ruling 5) — the station-wide counterpart
 * to {@link VehicleLinkPort}'s per-asset {@code linksFor}. Implemented by {@code drone-link/mavlink}'s
 * {@code MavlinkVehicleLinkPort}, which merges every open {@code MavlinkGateway}'s own registered
 * carriers (one gateway per distinct bind address) into one station-wide list.
 *
 * <p>{@link #carriers()} must never throw for a station with nothing registered yet — it returns an
 * empty list instead, matching CLAUDE.md rule 7's "degrade honestly".
 */
public interface CarrierDirectoryPort {

    /** Every carrier currently registered on the station. Never {@code null} — see the interface javadoc. */
    List<CarrierView> carriers();
}
