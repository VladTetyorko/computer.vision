package com.drones.vision.app.config.properties;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Plain unit tests (no Spring context) for {@link VisionMavlinkProperties}'s compact-constructor
 * validation — mirrors {@link VisionDiscoveryPropertiesTest}'s own no-context style. Covers the
 * FLEET-RADIO-PLAN.md D7 fields ({@code dropRateWarnPercent}/{@code dropRateAlarmPercent}/{@code
 * linkFailureGrace}) plus MAVLINK-COMMANDS-PLAN.md P4's {@code commandRetries} validation and the
 * {@code ackTimeout}/{@code commandRetries} default values; the remaining pre-existing fields already
 * have adequate coverage via {@code TelemetryWiringOnboardingTest}.
 */
class VisionMavlinkPropertiesTest {

    @Test
    void negativeCommandRetriesIsRejected() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new VisionMavlinkProperties("0.0.0.0", Duration.ofSeconds(30), 32, Duration.ofSeconds(5),
                        Duration.ofMillis(700), -1, 5.0, 20.0, Duration.ofSeconds(2), null, null));
        assertEquals("commandRetries must be >= 0: -1", ex.getMessage());
    }

    @Test
    void zeroCommandRetriesIsAccepted() {
        assertDoesNotThrow(() -> new VisionMavlinkProperties("0.0.0.0", Duration.ofSeconds(30), 32,
                Duration.ofSeconds(5), Duration.ofMillis(700), 0, 5.0, 20.0, Duration.ofSeconds(2), null, null));
    }

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

    /**
     * MAVLINK-COMMANDS-PLAN.md P4: bound from an <em>empty</em> source, so what this test sees is
     * exactly what a deployment with no {@code vision.mavlink.ack-timeout}/{@code command-retries}
     * block gets -- the {@code @DefaultValue} annotations, not a hand-written {@code new} call.
     * Pins the fix for the production gap P1 left (drone-link/mavlink's MODULE.md Gotchas): before
     * this wave, {@code ackTimeout} defaulted to 2s here while {@code MavlinkSettings.defaults()} had
     * already re-scoped its own default to 700ms, and {@code commandRetries} did not exist on this
     * record at all.
     */
    @Test
    void ackTimeoutAndCommandRetriesDefaultsMatchMavlinkSettings() {
        VisionMavlinkProperties bound = new Binder(new MapConfigurationPropertySource(Map.of()))
                .bindOrCreate("vision.mavlink", VisionMavlinkProperties.class);

        assertEquals(Duration.ofMillis(700), bound.ackTimeout(),
                "must match MavlinkSettings.DEFAULT_ACK_TIMEOUT_MILLIS (700ms), not the old 2s literal");
        assertEquals(2, bound.commandRetries(),
                "must match MavlinkSettings.DEFAULT_COMMAND_RETRIES");
        assertEquals(2, Integer.parseInt(VisionMavlinkProperties.DEFAULT_COMMAND_RETRIES));
    }

    private static VisionMavlinkProperties properties(double warnPercent, double alarmPercent, Duration failureGrace) {
        return new VisionMavlinkProperties("0.0.0.0", Duration.ofSeconds(30), 32, Duration.ofSeconds(5),
                Duration.ofMillis(700), 2, warnPercent, alarmPercent, failureGrace, null, null);
    }
}
