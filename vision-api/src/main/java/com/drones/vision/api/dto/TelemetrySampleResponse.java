package com.drones.vision.api.dto;

import com.drones.vision.domain.model.Telemetry;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * Response body element for {@code GET /api/usages/{usageId}/telemetry}.
 *
 * <p>All fields except {@code at} are omitted from the JSON entirely
 * (rather than serialized as {@code null}) when the underlying {@link
 * Telemetry} sample did not carry that reading — mirrors {@code Telemetry}'s
 * own per-field nullability (not every device reports every field).
 *
 * @param at              sample timestamp
 * @param latitude        degrees, absent if not reported
 * @param longitude       degrees, absent if not reported
 * @param altitudeMeters  meters, absent if not reported
 * @param headingDegrees  degrees, absent if not reported
 * @param batteryPercent  percent, absent if not reported
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TelemetrySampleResponse(Instant at, Double latitude, Double longitude, Double altitudeMeters,
                                       Double headingDegrees, Double batteryPercent) {

    /**
     * Maps a domain {@link Telemetry} sample to its wire representation.
     *
     * @param telemetry the sample to map
     * @return the response body element for {@code telemetry}
     */
    public static TelemetrySampleResponse from(Telemetry telemetry) {
        return new TelemetrySampleResponse(telemetry.at(), telemetry.latitude(), telemetry.longitude(),
                telemetry.altitudeMeters(), telemetry.headingDegrees(), telemetry.batteryPercent());
    }
}
