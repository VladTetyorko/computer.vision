package com.drones.vision.api.dto;

/**
 * {@code GET}/{@code POST /api/assets/{id}/seats[/{kind}]}'s frozen response shape
 * (docs/plans/active/CREW-CONTROL-PLAN.md &sect;3.6) — both seats always present, never omitted.
 *
 * @param assetId       the asset these seats belong to, as a canonical UUID string
 * @param ttlMs         seat lifetime without a renewal ({@code vision.crew.seat-ttl-ms}), served so
 *                      the caller can derive its own renewal cadence ({@code ttlMs / 3}) rather than
 *                      hard-coding one
 * @param flight        the FLIGHT seat's current state
 * @param camera        the CAMERA seat's current state
 * @param mayTakeFlight whether the <em>caller</em> may currently take/hold the FLIGHT seat
 * @param mayTakeCamera whether the <em>caller</em> may currently take/hold the CAMERA seat
 * @param mayForceSeat  whether the <em>caller</em> may force either seat away from its holder
 */
public record SeatsResponse(String assetId, long ttlMs, SeatHolderResponse flight, SeatHolderResponse camera,
                             boolean mayTakeFlight, boolean mayTakeCamera, boolean mayForceSeat) {
}
