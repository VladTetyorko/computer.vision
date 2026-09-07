package com.drones.vision.flight.domain.port;

import com.drones.vision.flight.domain.model.GeofenceZoneEvent;

/**
 * Driven port: announce a {@link GeofenceZoneEvent} live, so a driving adapter can push it to
 * connected viewers on the {@code zones} live topic (docs/plans/active/LIVE-POLL-RETIREMENT-PLAN.md
 * &sect;3 D2/&sect;4.1, wave L3).
 *
 * <p>Unlike {@code vision-map}'s {@code MapLiveUpdatePort} (the {@code map} topic), delivery here
 * needs no per-connection scoping: {@code GeofenceController#list} is {@code @OpenByDesign}
 * ("hiding a no-fly zone from any role would itself be the safety hole, not prevent one"), so every
 * zone is visible to every caller and a broadcast is exactly as scoped as the REST read it replaces.
 *
 * <h2>Contract</h2>
 * Must return quickly and must not throw for an ordinary delivery failure — a disconnected viewer,
 * a full connection registry, or the feature being disabled entirely (a no-op implementation) must
 * never surface as an exception on the caller's own hot path.
 *
 * <h2>Threading</h2>
 * Called from {@code DefaultGeofenceService#create}/{@code #update}/{@code #delete}, immediately
 * after the existing {@code GeofenceMonitor#refresh()} call — a rare, operator-driven write, not a
 * hot path, but implementations must still be cheap and effectively fire-and-forget: hand off to a
 * background dispatcher for any real I/O rather than doing it on the calling thread. Safe for
 * concurrent use.
 */
public interface GeofenceLiveUpdatePort {

    /**
     * Announces a {@link GeofenceZoneEvent} — a zone created, updated, or deleted.
     *
     * @param event the zone event that occurred
     */
    void publishZoneEvent(GeofenceZoneEvent event);
}
