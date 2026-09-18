package com.drones.vision.adapter.carrierserial;

import com.drones.mavlink.transport.SerialRole;

import com.fazecast.jSerialComm.SerialPort;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Pure port-matching/role/baud-rate policy over jSerialComm's {@link SerialPort#getCommPorts()} —
 * split out from {@link SerialCarrierConfiguration}'s scheduled poll loop specifically so this
 * class's logic is unit-testable against hand-built {@link PortInfo} values, with zero real
 * hardware and no Spring context (LINK-PAIRING-PLAN.md §4 L1's {@code SerialPortEnumeratorTest}).
 *
 * <h2>Matching</h2>
 * {@code allow}/{@code deny} ({@link CarrierSerialProperties}) and the separate bench pattern list
 * ({@link CarrierSerialBenchProperties}) are simple shell-style globs — only {@code *} is special
 * (zero or more characters); every other character, {@code ?} included, is literal — tested
 * case-insensitively against {@link PortInfo#systemPortPath()}, {@link
 * PortInfo#descriptivePortName()} <b>and</b> {@link PortInfo#systemPortName()}: a pattern matching
 * any one of the three counts as a match, so an operator can key off whichever is stable/legible
 * for their hardware (a USB path, a vendor description string, or a bare {@code ttyUSB0}-style
 * name). An empty {@code allow} list means "everything not denied", not "nothing".
 */
public final class SerialPortEnumerator {

    private final CarrierSerialProperties properties;
    private final CarrierSerialBenchProperties benchProperties;

    public SerialPortEnumerator(CarrierSerialProperties properties, CarrierSerialBenchProperties benchProperties) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.benchProperties = Objects.requireNonNull(benchProperties, "benchProperties must not be null");
    }

    /**
     * A jSerialComm-reported port, captured as a plain value so every method below is pure and
     * testable without touching the native library.
     */
    public record PortInfo(String systemPortName, String systemPortPath, String descriptivePortName) {

        public PortInfo {
            Objects.requireNonNull(systemPortName, "systemPortName must not be null");
            systemPortPath = systemPortPath == null ? "" : systemPortPath;
            descriptivePortName = descriptivePortName == null ? "" : descriptivePortName;
        }

        /**
         * The identifier fed to {@code SerialLink.open} and used as this port's registry/override
         * key: its device path when jSerialComm reports one (e.g. {@code /dev/ttyUSB0} on Linux),
         * falling back to the bare system name ({@code ttyUSB0}) when it does not.
         */
        public String portKey() {
            return systemPortPath.isBlank() ? systemPortName : systemPortPath;
        }
    }

    /**
     * The real production source: every port jSerialComm currently reports, snapshotted into plain
     * values. Safe to call with zero serial ports attached — returns an empty list, never throws
     * (confirmed: {@code SerialPort.getCommPorts()} is a native-but-safe no-op on a host with no
     * matching hardware, not an "external prerequisite" the way Docker/socat are).
     */
    public List<PortInfo> currentPorts() {
        return Arrays.stream(SerialPort.getCommPorts())
                .map(port -> new PortInfo(port.getSystemPortName(), port.getSystemPortPath(), port.getDescriptivePortName()))
                .toList();
    }

    /** Whether {@code port} should be carried at all: not denied, and allowed (or no allow list configured). */
    public boolean matches(PortInfo port) {
        Objects.requireNonNull(port, "port must not be null");
        if (anyPatternMatches(properties.deny(), port)) {
            return false;
        }
        return properties.allow().isEmpty() || anyPatternMatches(properties.allow(), port);
    }

    /**
     * {@link SerialRole#BENCH} if a {@code vision.carrier.serial.bench.patterns} entry matches
     * {@code port}, {@link SerialRole#GROUND_RADIO} otherwise — LINK-PAIRING-PLAN.md §3.2's frozen
     * "declared, not guessed from vendor id" decision.
     */
    public SerialRole roleFor(PortInfo port) {
        Objects.requireNonNull(port, "port must not be null");
        return anyPatternMatches(benchProperties.patterns(), port) ? SerialRole.BENCH : SerialRole.GROUND_RADIO;
    }

    /** {@code baudOverrides.get(port.portKey())}, falling back to {@code defaultBaudRate}. */
    public int baudRateFor(PortInfo port) {
        Objects.requireNonNull(port, "port must not be null");
        Integer override = properties.baudOverrides().get(port.portKey());
        return override != null ? override : properties.defaultBaudRate();
    }

    private static boolean anyPatternMatches(List<String> patterns, PortInfo port) {
        for (String pattern : patterns) {
            Pattern regex = globToRegex(pattern);
            if (regex.matcher(port.systemPortPath()).matches()
                    || regex.matcher(port.descriptivePortName()).matches()
                    || regex.matcher(port.systemPortName()).matches()) {
                return true;
            }
        }
        return false;
    }

    private static Pattern globToRegex(String glob) {
        StringBuilder regex = new StringBuilder();
        for (char c : glob.toCharArray()) {
            switch (c) {
                case '*' -> regex.append(".*");
                case '.', '(', ')', '+', '|', '^', '$', '@', '%', '[', ']', '{', '}', '\\' -> regex.append('\\').append(c);
                default -> regex.append(c);
            }
        }
        return Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE);
    }
}
