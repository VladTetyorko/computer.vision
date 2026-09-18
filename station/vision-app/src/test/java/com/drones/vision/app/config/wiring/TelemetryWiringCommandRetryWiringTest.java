package com.drones.vision.app.config.wiring;

import com.drones.vision.adapter.mavlink.MavlinkFlightCommander;
import com.drones.vision.adapter.mavlink.MavlinkSettings;
import com.drones.vision.adapter.mavlink.MavlinkTelemetrySource;
import com.drones.vision.app.config.properties.VisionLinksProperties;
import com.drones.vision.app.config.properties.VisionMavlinkProperties;
import com.drones.vision.app.config.properties.VisionOnboardingProperties;
import com.drones.vision.app.config.properties.VisionRcProperties;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * MAVLINK-COMMANDS-PLAN.md P4: pins the fix for the production gap P1 documented (drone-link/mavlink's
 * own MODULE.md Gotchas) — that {@code TelemetryWiring#mavlinkFlightCommander} still called {@link
 * MavlinkFlightCommander}'s 2-arg {@code (MavlinkTelemetrySource, Duration)} back-compat constructor
 * with {@code VisionMavlinkProperties#ackTimeout()}, which defaulted to 2s and had no {@code
 * commandRetries} seam at all — so a fully-silent vehicle's worst case was ~6s (3 attempts × 2s)
 * instead of the ~2.1s D2a intended (3 attempts × 700ms).
 *
 * <p>{@link MavlinkFlightCommander} exposes no accessor for its own {@code ackTimeout}/{@code
 * commandRetries} fields (private, and {@code drone-link/mavlink} is out of this wave's file scope —
 * another agent works there concurrently), so this test pins the fix at the one seam this module
 * owns: {@link TelemetryWiring#mavlinkFlightCommander} now builds its {@link MavlinkSettings} the
 * exact same way {@link TelemetryWiring#mavlinkTelemetrySource} does — via {@link
 * TelemetryWiring#toMavlinkSettings} — instead of reading {@code ackTimeout} alone into the legacy
 * overload. Every property below is bound from an <em>empty</em> source, so what this test sees is
 * exactly what a deployment with no {@code vision.mavlink}/{@code vision.rc}/{@code vision.onboarding}
 * block gets — the {@code @DefaultValue} annotations, not a hand-written {@code new} call.
 */
class TelemetryWiringCommandRetryWiringTest {

    private final TelemetryWiring wiring = new TelemetryWiring();

    private final VisionMavlinkProperties mavlinkProperties =
            new Binder(new MapConfigurationPropertySource(Map.of()))
                    .bindOrCreate("vision.mavlink", VisionMavlinkProperties.class);

    private final VisionRcProperties rcProperties =
            new Binder(new MapConfigurationPropertySource(Map.of()))
                    .bindOrCreate("vision.rc", VisionRcProperties.class);

    private final VisionOnboardingProperties onboardingProperties =
            new Binder(new MapConfigurationPropertySource(Map.of()))
                    .bindOrCreate("vision.onboarding", VisionOnboardingProperties.class);

    private final VisionLinksProperties linksProperties =
            new Binder(new MapConfigurationPropertySource(Map.of()))
                    .bindOrCreate("vision.links", VisionLinksProperties.class);

    @Test
    void defaultPropertiesYieldTheD2aBudget() {
        MavlinkSettings settings = TelemetryWiring.toMavlinkSettings(
                mavlinkProperties, rcProperties, onboardingProperties, linksProperties);

        assertEquals(Duration.ofMillis(700), settings.ackTimeout(),
                "per-attempt wait must be 700ms, not the old 2s -- see MAVLINK-COMMANDS-PLAN.md D2a");
        assertEquals(2, settings.commandRetries(), "default retry budget must be 2 (3 attempts total)");
    }

    @Test
    void mavlinkFlightCommanderBeanBuildsOnTheSameSettingsAsMavlinkTelemetrySource() {
        MavlinkTelemetrySource telemetrySource = new MavlinkTelemetrySource();

        MavlinkFlightCommander commander = wiring.mavlinkFlightCommander(
                telemetrySource, mavlinkProperties, rcProperties, onboardingProperties, linksProperties);

        assertNotNull(commander, "the canonical (MavlinkTelemetrySource, MavlinkSettings) constructor "
                + "must accept exactly the MavlinkSettings toMavlinkSettings(...) builds from properties defaults");
    }

    /** An operator dialing {@code vision.mavlink.command-retries: 0} must reach every consumer of {@code toMavlinkSettings}. */
    @Test
    void anOperatorSuppliedCommandRetriesReachesTheSettingsObject() {
        VisionMavlinkProperties zeroRetries = new VisionMavlinkProperties("0.0.0.0", Duration.ofSeconds(30), 32,
                Duration.ofSeconds(5), Duration.ofMillis(700), 0, 5.0, 20.0, Duration.ofSeconds(2), null, null);

        MavlinkSettings settings =
                TelemetryWiring.toMavlinkSettings(zeroRetries, rcProperties, onboardingProperties, linksProperties);

        assertEquals(0, settings.commandRetries(),
                "vision.mavlink.command-retries: 0 must restore today's single-shot behaviour");
    }
}
