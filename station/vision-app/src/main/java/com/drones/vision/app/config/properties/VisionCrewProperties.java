package com.drones.vision.app.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The two-seat model's master switch and seat lifetime ({@code vision.crew.*},
 * docs/plans/active/CREW-CONTROL-PLAN.md &sect;3.6) — read by {@code SeatWiringConfiguration} to
 * build the {@code SeatService}/{@code SeatAccessSettings} beans {@code
 * com.drones.vision.api.security.SeatAccess} and {@code SeatController} consume.
 *
 * <p>{@link #enabled()} defaults {@code false} (the opt-in guardrail, &sect;3.8): with it off, {@code
 * SeatAccess} is a pass-through and the default-config test suites observe no change at all.
 *
 * @param enabled the master switch; default {@code false}
 * @param seatTtlMs how long a seat survives without a renewal, in milliseconds; must be positive;
 *                  default {@value #DEFAULT_SEAT_TTL_MS}
 */
@ConfigurationProperties(prefix = "vision.crew")
public record VisionCrewProperties(@DefaultValue("false") boolean enabled,
                                    @DefaultValue(VisionCrewProperties.DEFAULT_SEAT_TTL_MS) long seatTtlMs) {

    static final String DEFAULT_SEAT_TTL_MS = "15000";

    public VisionCrewProperties {
        if (seatTtlMs <= 0) {
            throw new IllegalArgumentException("vision.crew.seat-ttl-ms must be positive: " + seatTtlMs);
        }
    }
}
