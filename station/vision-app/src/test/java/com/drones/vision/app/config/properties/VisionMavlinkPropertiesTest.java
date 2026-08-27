package com.drones.vision.app.config.properties;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Plain unit tests (no Spring context) for {@link VisionMavlinkProperties}'s compact-constructor
 * validation — mirrors {@link VisionDiscoveryPropertiesTest}'s own no-context style. Covers only the
 * FLEET-RADIO-PLAN.md D7 fields ({@code dropRateWarnPercent}/{@code dropRateAlarmPercent}/{@code
 * linkFailureGrace}) this wave added; the pre-existing fields already have adequate coverage via
 * {@code TelemetryWiringOnboardingTest}.
 */
class VisionMavlinkPropertiesTest {

    @Test
    void validThresholdsAreCarriedThrough() {
        VisionMavlinkProperties properties = properties(5.0, 20.0, Duration.ofSeconds(2));

        assertEquals(5.0, properties.dropRateWarnPercent());
        assertEquals(20.0, properties.dropRateAlarmPercent());
        assertEquals(Duration.ofSeconds(2), properties.linkFailureGrace());
    }

    @Test
    void alarmBelowWarnIsRejected() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> properties(20.0, 5.0, Duration.ofSeconds(2)));
        assertEquals("dropRateAlarmPercent must be >= dropRateWarnPercent: 5.0 < 20.0", ex.getMessage());
    }

    @Test
    void negativeWarnPercentIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> properties(-1.0, 20.0, Duration.ofSeconds(2)));
    }

    @Test
    void alarmPercentAboveOneHundredIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> properties(5.0, 101.0, Duration.ofSeconds(2)));
    }

    @Test
    void zeroOrNegativeFailureGraceIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> properties(5.0, 20.0, Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> properties(5.0, 20.0, Duration.ofSeconds(-1)));
    }

    @Test
    void equalWarnAndAlarmIsAccepted() {
        assertDoesNotThrow(() -> properties(10.0, 10.0, Duration.ofSeconds(1)));
    }

    @Test
    void defaultsMatchApplicationYamlsDocumentedValues() {
        // These three DEFAULT_* constants are exactly what application.yaml's commented
        // vision.mavlink.drop-rate-warn-percent/drop-rate-alarm-percent/link-failure-grace lines
        // document -- and byte-identical to MavlinkSettings.LinkStatus.defaults()'s own literals,
        // which TelemetryWiring#toMavlinkSettings maps these onto.
        assertEquals(5.0, Double.parseDouble(VisionMavlinkProperties.DEFAULT_DROP_RATE_WARN_PERCENT));
        assertEquals(20.0, Double.parseDouble(VisionMavlinkProperties.DEFAULT_DROP_RATE_ALARM_PERCENT));
        assertEquals(Duration.ofSeconds(2), Duration.parse("PT" + VisionMavlinkProperties.DEFAULT_LINK_FAILURE_GRACE.toUpperCase()));
    }

    private static VisionMavlinkProperties properties(double warnPercent, double alarmPercent, Duration failureGrace) {
        return new VisionMavlinkProperties("0.0.0.0", Duration.ofSeconds(30), 32, Duration.ofSeconds(5),
                Duration.ofSeconds(2), warnPercent, alarmPercent, failureGrace, null, null);
    }
}
