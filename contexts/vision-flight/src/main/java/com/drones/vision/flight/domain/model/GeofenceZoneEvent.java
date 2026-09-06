package com.drones.vision.flight.domain.model;

/**
 * A live change to a geofence zone — created, updated, or deleted (docs/plans/active/
 * LIVE-POLL-RETIREMENT-PLAN.md &sect;3 D2/&sect;4.1, wave L3) — the payload {@link
 * com.drones.vision.flight.domain.port.GeofenceLiveUpdatePort#publishZoneEvent} carries to a
 * driving adapter. Mirrors {@code vision-map}'s {@code MapEvent} idiom, minus the entity dimension:
 * a geofence zone is the only kind of thing this topic ever describes, so there is no {@code
 * EntityType} to carry alongside {@link #action()}.
 *
 * @param action what happened to {@link #zone()}
 * @param zone   the zone's current (or, for a delete, last-known) state — {@link Action#DELETED}
 *               still carries the zone in full, not merely its id, so a client's own 10s Undo can
 *               re-{@code POST} the exact body it just removed
 */
public record GeofenceZoneEvent(Action action, GeofenceZone zone) {

    public GeofenceZoneEvent {
        if (action == null) {
            throw new IllegalArgumentException("GeofenceZoneEvent action must not be null");
        }
        if (zone == null) {
            throw new IllegalArgumentException("GeofenceZoneEvent zone must not be null");
        }
    }

    /** What happened to the zone a {@link GeofenceZoneEvent} describes. */
    public enum Action {
        CREATED,
        UPDATED,
        DELETED
    }
}
