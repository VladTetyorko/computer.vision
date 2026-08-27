package com.drones.vision.app.config.wiring;

import com.drones.vision.adapter.mavlink.MavlinkSettings;
import com.drones.vision.app.config.properties.VisionMavlinkProperties;
import com.drones.vision.app.config.properties.VisionOnboardingProperties;
import com.drones.vision.app.config.properties.VisionRcProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves {@code vision.onboarding.*} actually reaches {@link MavlinkSettings.Onboarding} — the half
 * of wave O8 that its own adapter-scoped wave could not wire (docs/plans/active/DRONE-ONBOARDING-PLAN.md).
 * A flag nothing reads looks identical, from the yaml, to a flag that works.
 */
class TelemetryWiringOnboardingTest {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(7);

    @Test
    void theOnConnectFlagIsOffUnlessTheOperatorTurnsItOn() {
        MavlinkSettings settings = toSettings(false);

        assertFalse(settings.onboarding().requestMessagesOnConnect(),
                "default must stay off -- this is the one thing that transmits without being asked");
    }

    @Test
    void turningTheOnConnectFlagOnReachesTheAdapterThatActsOnIt() {
        MavlinkSettings settings = toSettings(true);

        assertTrue(settings.onboarding().requestMessagesOnConnect());
        assertFalse(settings.onboarding().onConnectMessageRequests().isEmpty(),
                "a flag that arrives with nothing to request would be on in name only");
    }

    @Test
    void theProbeRequestTimeoutIsTheOneAnActualProbeUses() {
        MavlinkSettings settings = toSettings(false);

        assertEquals(REQUEST_TIMEOUT, settings.onboarding().capabilityTimeout());
        assertEquals(REQUEST_TIMEOUT, settings.onboarding().parameterTimeout());
    }

    @Test
    void theVerifiedProbeParameterListIsNotSilentlyReplacedByWiring() {
        MavlinkSettings settings = toSettings(true);

        assertEquals(MavlinkSettings.Onboarding.defaults().probeParameters(),
                settings.onboarding().probeParameters(),
                "every name was verified against live firmware; wiring must not substitute its own");
    }

    /** FLEET-RADIO-PLAN F12: the javadoc promised this was overridable; nothing read the property. */
    @Test
    void anOperatorSuppliedProbeListReplacesTheDefaultOne() {
        List<String> mine = List.of("MAV_SYSID", "FRAME_CLASS");

        MavlinkSettings settings = toSettings(false, mine);

        assertEquals(mine, settings.onboarding().probeParameters());
    }

    @Test
    void anEmptyProbePropertyMeansKeepTheDefaultsNotProbeNothing() {
        MavlinkSettings settings = toSettings(false, List.of());

        assertEquals(MavlinkSettings.Onboarding.defaults().probeParameters(),
                settings.onboarding().probeParameters());
    }

    /**
     * FLEET-RADIO-PLAN F0: a renamed parameter appears here in its current spelling only. Listing the
     * obsolete one too would give every probe of every vehicle a guaranteed timeout, since a firmware
     * carries exactly one of the two -- MavlinkVehicleConfigurator asks the other only on a miss.
     */
    @Test
    void theDefaultProbeListCarriesOneSpellingOfTheSystemIdAndItIsTheCurrentOne() {
        List<String> probed = MavlinkSettings.Onboarding.defaults().probeParameters();

        assertTrue(probed.contains("MAV_SYSID"), "4.7+ firmware answers only this one");
        assertFalse(probed.contains("SYSID_THISMAV"), "the fallback pass exists so this need not be probed");
    }

    /** No ArduRover exposes FENCE_ALT_MAX; probing for it buys one guaranteed timeout per rover. */
    @Test
    void theDefaultProbeListDoesNotAskARoverACopterOnlyQuestion() {
        assertFalse(MavlinkSettings.Onboarding.defaults().probeParameters().contains("FENCE_ALT_MAX"));
    }

    private static MavlinkSettings toSettings(boolean requestMessagesOnConnect) {
        return toSettings(requestMessagesOnConnect, List.of());
    }

    private static MavlinkSettings toSettings(boolean requestMessagesOnConnect, List<String> probeParameters) {
        return TelemetryWiring.toMavlinkSettings(
                new VisionMavlinkProperties("0.0.0.0", Duration.ofSeconds(30), 32, Duration.ofSeconds(5),
                        Duration.ofSeconds(2), null, null),
                new VisionRcProperties(300L, 33, 5, 50, 3),
                new VisionOnboardingProperties(
                        new VisionOnboardingProperties.Probe(false, Duration.ofSeconds(10), REQUEST_TIMEOUT,
                                probeParameters),
                        new VisionOnboardingProperties.Remediate(
                                new VisionOnboardingProperties.Remediate.MessageInterval(requestMessagesOnConnect)),
                        new VisionOnboardingProperties.Passport(false)));
    }
}
