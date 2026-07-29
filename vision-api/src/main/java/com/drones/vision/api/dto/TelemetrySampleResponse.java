package com.drones.vision.api.dto;

import com.drones.vision.domain.model.Telemetry;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.Map;

/**
 * Response body element for {@code GET /api/usages/{usageId}/telemetry}, the replay timeline, and
 * the {@code telemetry:<assetId>} SSE topic — one DTO serves all three (docs/FC-INTEGRATIONS-PLAN.md
 * F-b), no per-transport variant.
 *
 * <p>All fields except {@code deviceId} and {@code at} are omitted from the JSON entirely (rather
 * than serialized as {@code null}) when the underlying {@link Telemetry} sample did not carry that
 * reading — mirrors {@code Telemetry}'s own per-field nullability (not every device reports every
 * field). {@code deviceId} is never absent: {@link Telemetry#deviceId()} is non-{@code null}-
 * validated in its own compact constructor, so every sample carries one (docs/CYCLES-PLAN.md §11,
 * CD-a — added so samples from different telemetry devices on the same asset/usage become
 * distinguishable, e.g. for grouping by source in a future multi-telemetry UI). {@code extra} is
 * omitted when empty rather than serialized as {@code {}}.
 *
 * @param deviceId       the telemetry device this sample came from, as a canonical UUID string;
 *                       always present
 * @param at             sample timestamp
 * @param latitude       degrees, absent if not reported
 * @param longitude      degrees, absent if not reported
 * @param altitudeMeters meters, absent if not reported
 * @param headingDegrees degrees, absent if not reported
 * @param batteryPercent percent, absent if not reported
 * @param flightState    flight-controller-reported state, absent if the sample carries none (see
 *                       {@link FlightStateResponse})
 * @param extra          protocol-specific readings not modeled as their own field (e.g. {@code
 *                       groundspeedMps}, {@code batteryVoltage}), absent when empty
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TelemetrySampleResponse(String deviceId, Instant at, Double latitude, Double longitude,
                                       Double altitudeMeters, Double headingDegrees, Double batteryPercent,
                                       FlightStateResponse flightState, Map<String, Double> extra) {

    /**
     * Maps a domain {@link Telemetry} sample to its wire representation.
     *
     * @param telemetry the sample to map
     * @return the response body element for {@code telemetry}
     */
    public static TelemetrySampleResponse from(Telemetry telemetry) {
        return new TelemetrySampleResponse(telemetry.deviceId().value().toString(), telemetry.at(),
                telemetry.latitude(), telemetry.longitude(), telemetry.altitudeMeters(),
                telemetry.headingDegrees(), telemetry.batteryPercent(),
                FlightStateResponse.from(telemetry.flightState()),
                telemetry.extra().isEmpty() ? null : telemetry.extra());
    }
}
