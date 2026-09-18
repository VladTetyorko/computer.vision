package com.drones.vision.api.dto;

import com.drones.vision.flight.domain.model.CarrierView;

/**
 * Response body for one entry of {@code GET /api/carriers} (LINK-PAIRING-PLAN.md §3.4/§7 ruling 5,
 * {@code CarrierSummaryResponse} in {@code station/vision-web}) — station-wide reference data, not
 * per-asset; see {@link com.drones.vision.api.controller.CarriersController}'s own javadoc.
 *
 * @param id         this carrier's stable identity
 * @param carrier    {@code "UDP"} or {@code "SERIAL"} (the enum name)
 * @param serialRole {@code "NONE"}, {@code "GROUND_RADIO"} or {@code "BENCH"} (the enum name)
 * @param label      a short, human-facing name
 * @param priority   election priority (higher wins)
 */
public record CarrierSummaryResponse(String id, String carrier, String serialRole, String label, int priority) {

    /**
     * Maps a domain {@link CarrierView} to its wire representation.
     *
     * @param view the carrier to map
     * @return the response body for {@code view}
     */
    public static CarrierSummaryResponse from(CarrierView view) {
        return new CarrierSummaryResponse(view.id().value(), view.carrier().name(), view.serialRole().name(),
                view.label(), view.priority());
    }
}
