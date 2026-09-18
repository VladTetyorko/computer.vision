package com.drones.vision.adapter.carrierserial;

import com.drones.mavlink.transport.SerialRole;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SerialPortEnumerator}'s pure matching/role/baud-rate policy — hand-built {@link
 * SerialPortEnumerator.PortInfo} values, no real hardware needed for the policy logic itself
 * (LINK-PAIRING-PLAN.md §4 L1). {@link #currentPortsNeverThrowsEvenWithNothingAttached()} is the
 * one test that touches the real jSerialComm native library — not gated by {@code assumeTrue}
 * because the bundled native lib, unlike Docker/socat, is always present once the jar resolves;
 * it is exercised here purely as "returns a list, doesn't throw with zero devices attached", never
 * asserting on which ports (if any) a given CI host happens to expose.
 */
class SerialPortEnumeratorTest {

    private static final SerialPortEnumerator.PortInfo USB_RADIO =
            new SerialPortEnumerator.PortInfo("ttyUSB0", "/dev/ttyUSB0", "FTDI USB Serial Device");
    private static final SerialPortEnumerator.PortInfo ACM_BENCH =
            new SerialPortEnumerator.PortInfo("ttyACM0", "/dev/ttyACM0", "Arduino Uno (bench)");

    @Test
    void withNoAllowOrDenyEveryPortMatches() {
        SerialPortEnumerator enumerator = enumerator(CarrierSerialProperties.defaults(), CarrierSerialBenchProperties.defaults());
        assertTrue(enumerator.matches(USB_RADIO));
        assertTrue(enumerator.matches(ACM_BENCH));
    }

    @Test
    void aDenyPatternExcludesAMatchingPortEvenWithoutAnAllowList() {
        CarrierSerialProperties properties = properties(List.of(), List.of("*ttyUSB*"), Map.of());
        SerialPortEnumerator enumerator = enumerator(properties, CarrierSerialBenchProperties.defaults());

        assertFalse(enumerator.matches(USB_RADIO));
        assertTrue(enumerator.matches(ACM_BENCH), "deny must not affect a port it does not match");
    }

    @Test
    void denyTakesPrecedenceOverAnOverlappingAllow() {
        CarrierSerialProperties properties = properties(List.of("*ttyUSB*"), List.of("*ttyUSB*"), Map.of());
        SerialPortEnumerator enumerator = enumerator(properties, CarrierSerialBenchProperties.defaults());

        assertFalse(enumerator.matches(USB_RADIO), "a port matched by both allow and deny must be denied");
    }

    @Test
    void aNonEmptyAllowListExcludesEverythingNotMatched() {
        CarrierSerialProperties properties = properties(List.of("*ttyACM*"), List.of(), Map.of());
        SerialPortEnumerator enumerator = enumerator(properties, CarrierSerialBenchProperties.defaults());

        assertTrue(enumerator.matches(ACM_BENCH));
        assertFalse(enumerator.matches(USB_RADIO), "a non-empty allow list means only what matches it is carried");
    }

    @Test
    void allowMatchesAgainstDescriptivePortNameToo() {
        CarrierSerialProperties properties = properties(List.of("*Arduino*"), List.of(), Map.of());
        SerialPortEnumerator enumerator = enumerator(properties, CarrierSerialBenchProperties.defaults());

        assertTrue(enumerator.matches(ACM_BENCH), "a glob must be able to key off the human-readable description, not only the device path");
    }

    @Test
    void patternMatchingIsCaseInsensitive() {
        CarrierSerialProperties properties = properties(List.of("*ARDUINO*"), List.of(), Map.of());
        SerialPortEnumerator enumerator = enumerator(properties, CarrierSerialBenchProperties.defaults());

        assertTrue(enumerator.matches(ACM_BENCH));
    }

    @Test
    void roleIsGroundRadioByDefaultAndBenchOnlyWhenAPatternMatches() {
        CarrierSerialProperties properties = CarrierSerialProperties.defaults();
        SerialPortEnumerator noBenchPatterns = enumerator(properties, CarrierSerialBenchProperties.defaults());
        assertEquals(SerialRole.GROUND_RADIO, noBenchPatterns.roleFor(ACM_BENCH),
                "role must never be guessed from vendor/product id -- with no bench pattern configured, everything carried is a ground radio");

        SerialPortEnumerator withBenchPattern = enumerator(properties, new CarrierSerialBenchProperties(List.of("*bench*")));
        assertEquals(SerialRole.BENCH, withBenchPattern.roleFor(ACM_BENCH));
        assertEquals(SerialRole.GROUND_RADIO, withBenchPattern.roleFor(USB_RADIO));
    }

    @Test
    void baudRateFallsBackToTheDefaultWhenNoOverrideMatchesThePortKey() {
        CarrierSerialProperties properties = properties(List.of(), List.of(), Map.of());
        SerialPortEnumerator enumerator = enumerator(properties, CarrierSerialBenchProperties.defaults());

        assertEquals(properties.defaultBaudRate(), enumerator.baudRateFor(USB_RADIO));
    }

    @Test
    void baudOverrideIsKeyedByThePortsPortKeyNotItsBareSystemName() {
        CarrierSerialProperties properties = properties(List.of(), List.of(), Map.of("/dev/ttyUSB0", 115_200));
        SerialPortEnumerator enumerator = enumerator(properties, CarrierSerialBenchProperties.defaults());

        assertEquals(115_200, enumerator.baudRateFor(USB_RADIO));
        assertEquals(properties.defaultBaudRate(), enumerator.baudRateFor(ACM_BENCH), "an override for one port must not leak onto another");
    }

    @Test
    void portKeyPrefersTheDevicePathAndFallsBackToTheBareSystemName() {
        assertEquals("/dev/ttyUSB0", USB_RADIO.portKey());

        SerialPortEnumerator.PortInfo noPath = new SerialPortEnumerator.PortInfo("COM3", "", "Some USB-Serial adapter");
        assertEquals("COM3", noPath.portKey());
    }

    @Test
    void currentPortsNeverThrowsEvenWithNothingAttached() {
        SerialPortEnumerator enumerator = enumerator(CarrierSerialProperties.defaults(), CarrierSerialBenchProperties.defaults());
        assertNotNull(assertDoesNotThrow(enumerator::currentPorts));
    }

    private static SerialPortEnumerator enumerator(CarrierSerialProperties properties, CarrierSerialBenchProperties bench) {
        return new SerialPortEnumerator(properties, bench);
    }

    private static CarrierSerialProperties properties(List<String> allow, List<String> deny, Map<String, Integer> baudOverrides) {
        return new CarrierSerialProperties(true, Duration.ofSeconds(2), 57_600, allow, deny, baudOverrides);
    }
}
