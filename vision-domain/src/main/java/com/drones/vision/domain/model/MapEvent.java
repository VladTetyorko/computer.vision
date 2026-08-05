package com.drones.vision.domain.model;

/**
 * A live change to the map's data — a {@link Mark}, {@link Drawing}, or {@link MapLayer} created,
 * updated, cleared, or deleted (docs/MAP-REWORK-PLAN.md §2.3) — the payload {@code
 * LiveUpdatePublisherPort#publishMapEvent} carries to a driving adapter.
 *
 * <p>{@link #payload()} is the domain object itself, its concrete type dictated by {@link
 * #entity()} ({@link EntityType#MARK} &rarr; {@link Mark}, {@link EntityType#DRAWING} &rarr;
 * {@link Drawing}, {@link EntityType#LAYER} &rarr; {@link MapLayer}) — mapping it to a wire DTO is
 * the API layer's job, not this record's. {@link Action#CLEARED} is only ever raised for {@link
 * EntityType#MARK} (a drawing or layer has no "cleared" lifecycle state, only created/updated/
 * deleted).
 *
 * @param entity   which kind of map object changed
 * @param action   what happened to it
 * @param layerId  the layer the object lives (or lived) on
 * @param payload  the domain object's current (or, for a delete, last-known) state; its runtime
 *                 type must match {@code entity}
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
        if (action == Action.CLEARED && entity != EntityType.MARK) {
            throw new IllegalArgumentException("MapEvent action CLEARED is only valid for entity MARK: " + entity);
        }
        boolean payloadMatchesEntity = switch (entity) {
            case MARK -> payload instanceof Mark;
            case DRAWING -> payload instanceof Drawing;
            case LAYER -> payload instanceof MapLayer;
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
        LAYER
    }

    /** What happened to the entity a {@link MapEvent} describes. */
    public enum Action {
        CREATED,
        UPDATED,
        /** Mark-only: status flipped to {@link MarkStatus#CLEARED}. */
        CLEARED,
        DELETED
    }
}
