package com.drones.vision.api.dto;

/**
 * Response body for {@code PATCH /api/streams/{streamId}/config} (docs/CV-CONTROL-PLAN.md §3's
 * frozen wire contract).
 *
 * @param streamId     the updated stream's id, canonical UUID string
 * @param modelReArmed {@code true} only when the patch's {@code model} field was present and
 *                     differed from the stream's running model, so the caller knows a brief
 *                     detection gap occurred while the detector swapped — straight from {@code
 *                     UpdateOutcome#modelReArmed()} (vision-application). Every other knob applies
 *                     hot and never re-arms
 */
public record UpdateStreamConfigResponse(String streamId, boolean modelReArmed) {
}
