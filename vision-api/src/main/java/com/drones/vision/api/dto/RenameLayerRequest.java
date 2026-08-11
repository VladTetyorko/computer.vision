package com.drones.vision.api.dto;

/**
 * Body of {@code PATCH /api/map/layers/{id}} (docs/plans/done/MAP-REWORK-PLAN.md §4.1) — rename only.
 *
 * <p>Unlike the other {@code PATCH} bodies in this package, this one is not a partial patch with
 * optional fields: the endpoint's whole contract is "rename this layer", so a missing/blank {@code
 * name} is a {@code 400} from {@code MapLayer}'s own validation rather than a no-op.
 *
 * @param name the replacement name
 */
public record RenameLayerRequest(String name) {
}
