package com.drones.vision.map.domain.model;

/**
 * A live change to the map's data — a {@link Mark}, {@link Drawing}, {@link MapLayer}, or {@link
 * ProjectedTrack} created, updated, cleared, or deleted (docs/plans/done/MAP-REWORK-PLAN.md §2.3;
 * {@link EntityType#TRACK} added docs/plans/active/FIXED-CAMERA-GEO-PLAN.md decision D11) — the
 * payload {@code MapLiveUpdatePort#publishMapEvent} carries to a driving adapter.
 *
 * <p>{@link #payload()} is the domain object itself, its concrete type dictated by {@link
 * #entity()} ({@link EntityType#MARK} &rarr; {@link Mark}, {@link EntityType#DRAWING} &rarr;
 * {@link Drawing}, {@link EntityType#LAYER} &rarr; {@link MapLayer}, {@link EntityType#TRACK}
 * &rarr; {@link ProjectedTrack}) — mapping it to a wire DTO is the API layer's job, not this
 * record's. {@link Action#CLEARED} is valid only for {@link EntityType#MARK} (status flipped to
 * {@link MarkStatus#CLEARED}) and {@link EntityType#TRACK} (the track expired from the perception
 * track book, or its owning stream stopped) — a drawing or layer has no "cleared" lifecycle state,
 * only created/updated/deleted.
 *
 * @param entity   which kind of map object changed
 * @param action   what happened to it
 * @param layerId  the layer the object lives (or lived) on
 * @param payload  the domain object's current (or, for a delete/clear, last-known) state; its
 *                 runtime type must match {@code entity}
 */
public record MapEvent(EntityType entity, Action action, LayerId layerId, Object payload) {

    public MapEvent {
        if (entity == null) {
            throw new IllegalArgumentException("MapEvent entity must not be null");
        }
        if (action == null) {
            throw new IllegalArgumentException("MapEvent action must not be null");
        }
        if (layerId == null) {
            throw new IllegalArgumentException("MapEvent layerId must not be null");
        }
        if (payload == null) {
            throw new IllegalArgumentException("MapEvent payload must not be null");
        }
        if (action == Action.CLEARED && entity != EntityType.MARK && entity != EntityType.TRACK) {
            throw new IllegalArgumentException(
                    "MapEvent action CLEARED is only valid for entity MARK or TRACK: " + entity);
        }
        boolean payloadMatchesEntity = switch (entity) {
            case MARK -> payload instanceof Mark;
            case DRAWING -> payload instanceof Drawing;
            case LAYER -> payload instanceof MapLayer;
            case TRACK -> payload instanceof ProjectedTrack;
        };
        if (!payloadMatchesEntity) {
            throw new IllegalArgumentException(
                    "MapEvent payload type must match entity " + entity + ", got: "
                            + payload.getClass().getSimpleName());
        }
    }

    /** Which kind of map object a {@link MapEvent} describes. */
    public enum EntityType {
        /** {@link MapEvent#payload()} is a {@link Mark}. */
        MARK,
        /** {@link MapEvent#payload()} is a {@link Drawing}. */
        DRAWING,
        /** {@link MapEvent#payload()} is a {@link MapLayer}. */
        LAYER,
        /** {@link MapEvent#payload()} is a {@link ProjectedTrack} (docs/plans/active/FIXED-CAMERA-GEO-PLAN.md D11). */
        TRACK
    }

    /** What happened to the entity a {@link MapEvent} describes. */
    public enum Action {
        CREATED,
        UPDATED,
        /** Mark or track only: a mark's status flipped to {@link MarkStatus#CLEARED}, or a track expired/stopped. */
        CLEARED,
        DELETED
    }
}
