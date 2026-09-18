package com.drones.vision.adapter.carrierserial;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * {@code vision.carrier.serial.*} — LINK-PAIRING-PLAN.md §3.2's frozen contract; field names, order
 * and prefix are verbatim, do not add a field here (a role-decision config list belongs in a
 * separate properties class — see {@link CarrierSerialBenchProperties} — precisely so this shape
 * never has to change again).
 *
 * @param enabled         master switch; {@code false} by default because serial hardware access
 *                        requires a device passthrough most hosts running this compose file don't
 *                        have (see {@code docker-compose.yml}'s {@code serial} profile) — "off
 *                        unless a deployment opts in", not "off because it doesn't work"
 * @param pollInterval    how often {@link SerialCarrierConfiguration} re-samples {@link
 *                        SerialPortEnumerator#currentPorts()} for hotplug appear/disappear
 * @param defaultBaudRate baud rate for a matched port with no entry in {@code baudOverrides}
 * @param allow           glob patterns (see {@link SerialPortEnumerator}); empty means "every port
 *                        not denied", not "no ports"
 * @param deny            glob patterns checked before {@code allow}; a denied port is never opened
 *                        even if some entry in {@code allow} would also have matched it
 * @param baudOverrides   per-port baud rate keyed by {@link SerialPortEnumerator.PortInfo#portKey()}
 *                        (the device path, e.g. {@code /dev/ttyUSB0} on Linux, falling back to the
 *                        bare system name when jSerialComm reports no path)
 */
@ConfigurationProperties(prefix = "vision.carrier.serial")
public record CarrierSerialProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("2s") Duration pollInterval,
        @DefaultValue(CarrierSerialProperties.DEFAULT_BAUD_RATE) int defaultBaudRate,
        List<String> allow,
        List<String> deny,
        Map<String, Integer> baudOverrides) {

    /** ArduPilot/PX4's own common SiK-radio telemetry default — see {@code MODULE.md} Gotchas. */
    static final String DEFAULT_BAUD_RATE = "57600";
    private static final int DEFAULT_BAUD_RATE_INT = 57_600;

    public CarrierSerialProperties {
        Objects.requireNonNull(pollInterval, "pollInterval must not be null");
        if (pollInterval.isZero() || pollInterval.isNegative()) {
            throw new IllegalArgumentException("pollInterval must be positive, got " + pollInterval);
        }
        if (defaultBaudRate <= 0) {
            throw new IllegalArgumentException("defaultBaudRate must be positive, got " + defaultBaudRate);
        }
        allow = allow == null ? List.of() : List.copyOf(allow);
        deny = deny == null ? List.of() : List.copyOf(deny);
        baudOverrides = baudOverrides == null ? Map.of() : Map.copyOf(baudOverrides);
    }

    /** Disabled, 2s polling, {@value #DEFAULT_BAUD_RATE} baud, no filters — for tests that need an instance without a Spring context. */
    public static CarrierSerialProperties defaults() {
        return new CarrierSerialProperties(false, Duration.ofSeconds(2), DEFAULT_BAUD_RATE_INT, List.of(), List.of(), Map.of());
    }
}
