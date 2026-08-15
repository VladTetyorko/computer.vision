package com.drones.vision.api.dto;

import com.drones.vision.kernel.FlightState;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Wire representation of a {@link FlightState} (docs/plans/done/FC-INTEGRATIONS-PLAN.md F-b) — the
 * flight-controller-reported facts embedded in {@link TelemetrySampleResponse#flightState()},
 * mirrors the frozen wire contract field-for-field: {@code firmware, mode, armed, failsafe,
 * gpsFixType, satellites, hdop, rssiPercent, armingBlockers}. The frontend was already built
 * against this exact shape — do not rename any field.
 *
 * <p>Every field except {@code armingBlockers} is omitted from the JSON entirely (rather than
 * serialized as {@code null}) when the underlying {@link FlightState} did not carry that reading —
 * mirrors {@link FlightState}'s own per-field nullability (a decoder merges this incrementally as
 * different MAVLink messages arrive, so "unknown" must stay distinguishable from "known
 * false/zero"). {@code armingBlockers} is omitted when empty rather than serialized as {@code []}.
 *
 * @param firmware       {@code "ardupilot"}/{@code "generic"}/{@code "px4"}, absent if unknown
 * @param mode           human mode name (e.g. {@code "RTL"}, {@code "Loiter"}), absent if unknown
 * @param armed          absent if unknown
 * @param failsafe       absent if unknown
 * @param gpsFixType     {@code GPS_FIX_TYPE} ordinal 0..8, absent if unknown
 * @param satellites     visible satellite count, absent if unknown
 * @param hdop           horizontal dilution of precision, absent if unknown
 * @param rssiPercent    0..100, absent if unknown
 * @param armingBlockers currently-known reasons the aircraft refuses to arm, absent when empty
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FlightStateResponse(String firmware, String mode, Boolean armed, Boolean failsafe,
                                   Integer gpsFixType, Integer satellites, Double hdop, Integer rssiPercent,
                                   List<String> armingBlockers) {

    /**
     * Maps a domain {@link FlightState} to its wire representation.
     *
     * @param flightState the flight state to map, or {@code null}
     * @return the response body for {@code flightState}, or {@code null} if {@code flightState} is
     * {@code null}
     */
    public static FlightStateResponse from(FlightState flightState) {
        if (flightState == null) {
            return null;
        }
        return new FlightStateResponse(flightState.firmware(), flightState.mode(), flightState.armed(),
                flightState.failsafe(), flightState.gpsFixType(), flightState.satellites(), flightState.hdop(),
                flightState.rssiPercent(),
                flightState.armingBlockers().isEmpty() ? null : flightState.armingBlockers());
    }
}
