package com.drones.vision.api.dto;

/**
 * Optional body for {@code POST /api/assets/{id}/seats/{kind}} (docs/plans/active/CREW-CONTROL-PLAN.md
 * &sect;3.6) — mirrors {@link ForceCommandRequest}'s optional-body idiom. The whole body may be
 * absent; {@code force} then defaults to {@code false}.
 *
 * <p>{@code force} is only honoured for a caller whose {@code AssetAuthority#mayForceSeat} answers
 * {@code true} — for anyone else it is silently ignored (falls through to the ordinary take-or-409
 * path), not rejected with its own error.
 *
 * @param force request to evict the current holder, if the caller may
 */
public record TakeSeatRequest(Boolean force) {

    public static final TakeSeatRequest EMPTY = new TakeSeatRequest(null);

    public boolean forceOrDefault() {
        return force != null && force;
    }
}
