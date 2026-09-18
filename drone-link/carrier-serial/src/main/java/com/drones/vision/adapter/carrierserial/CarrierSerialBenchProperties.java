package com.drones.vision.adapter.carrierserial;

import com.drones.mavlink.transport.SerialRole;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * {@code vision.carrier.serial.bench.patterns} — the config list this wave's own scope brief calls
 * for: which matched ports are a {@link SerialRole#BENCH} cable rather than a {@link
 * SerialRole#GROUND_RADIO}. Deliberately a <b>separate</b> {@code @ConfigurationProperties} class
 * rather than a field on {@link CarrierSerialProperties} — that record's shape is frozen verbatim
 * by LINK-PAIRING-PLAN.md §3.2, and a role-decision list is a policy concern orthogonal to "which
 * ports are carried at all" (allow/deny).
 *
 * <p>🔒 Matches §3.2's frozen decision: role is <b>declared</b> by pattern, never guessed from USB
 * vendor/product id — a cheap USB-serial bridge chip (CH340, CP2102, FTDI…) is shared by ground
 * radios and bench dongles alike, so vendor/product id carries no signal about which one a given
 * port actually is.
 *
 * @param patterns glob patterns (see {@link SerialPortEnumerator}) matched against a candidate
 *                 port's path/name/description; empty means "nothing is a bench cable", i.e. every
 *                 carried port defaults to {@link SerialRole#GROUND_RADIO}
 */
@ConfigurationProperties(prefix = "vision.carrier.serial.bench")
public record CarrierSerialBenchProperties(List<String> patterns) {

    public CarrierSerialBenchProperties {
        patterns = patterns == null ? List.of() : List.copyOf(patterns);
    }

    /** No bench patterns configured — for tests that need an instance without a Spring context. */
    public static CarrierSerialBenchProperties defaults() {
        return new CarrierSerialBenchProperties(List.of());
    }
}
