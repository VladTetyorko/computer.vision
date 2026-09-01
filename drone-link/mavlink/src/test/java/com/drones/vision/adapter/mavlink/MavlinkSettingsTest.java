package com.drones.vision.adapter.mavlink;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MavlinkSettings.Onboarding}'s wave O8 additions (docs/plans/active/DRONE-ONBOARDING-PLAN.md):
 * the {@code requestMessagesOnConnect} flag and the {@code onConnectMessageRequests} message set.
 * Nothing about the pre-existing probe/parameter fields is re-tested here — this class is new for
 * this wave, and the pre-existing behaviour is exercised end-to-end by {@code
 * MavlinkVehicleConfiguratorTest}/{@code MavlinkSitlOnboardingIntegrationTest} already.
 */
class MavlinkSettingsTest {

    @Test
    void defaultsHaveTheFlagOffAndARealMessageSetReady() {
        MavlinkSettings.Onboarding onboarding = MavlinkSettings.Onboarding.defaults();

        assertFalse(onboarding.requestMessagesOnConnect(),
                "wave O8's guardrail: on-connect remediation must default off");
        assertFalse(onboarding.onConnectMessageRequests().isEmpty(),
                "the message set must be populated even while the flag is off, so flipping the flag "
                        + "alone later does something sensible");
        assertTrue(onboarding.onConnectMessageRequests().stream()
                        .allMatch(request -> request.messageId() >= 0 && !request.interval().isNegative()),
                "every default request must itself be constructible under MessageRequest's own rules");
    }

    @Test
    void theBackCompatFiveArgConstructorDefaultsTheFlagOffAndFillsInAMessageSet() {
        MavlinkSettings.Onboarding onboarding = new MavlinkSettings.Onboarding(
                List.of("MAV_SYSID"), Duration.ofSeconds(1), 1, Duration.ofSeconds(1), 1);

        assertFalse(onboarding.requestMessagesOnConnect(),
                "a caller built before this wave must land in the same off state the flag defaults to");
        assertFalse(onboarding.onConnectMessageRequests().isEmpty());
    }

    @Test
    void aNullMessageRequestListIsRejected() {
        assertThrows(NullPointerException.class, () -> new MavlinkSettings.Onboarding(
                List.of(), Duration.ofSeconds(1), 1, Duration.ofSeconds(1), 1, true, null));
    }

    @Test
    void anEmptyMessageRequestListIsALegalNoOpConfiguration() {
        MavlinkSettings.Onboarding onboarding = new MavlinkSettings.Onboarding(
                List.of(), Duration.ofSeconds(1), 1, Duration.ofSeconds(1), 1, true, List.of());

        assertTrue(onboarding.requestMessagesOnConnect());
        assertTrue(onboarding.onConnectMessageRequests().isEmpty());
    }

    @Test
    void aMessageRequestWithANegativeIntervalIsRejected() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new MavlinkSettings.Onboarding.MessageRequest(74, Duration.ofMillis(-1)));
        assertTrue(ex.getMessage().contains("negative"), ex.getMessage());
    }

    @Test
    void aMessageRequestWithANegativeMessageIdIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new MavlinkSettings.Onboarding.MessageRequest(-1, Duration.ofMillis(200)));
    }

    @Test
    void aMessageRequestWithAZeroIntervalIsLegal() {
        // Duration.ZERO means "resume the default/recommended rate" (MessageIntervalService.DEFAULT_RATE),
        // not "never" -- so it must not be rejected the way a negative interval is.
        MavlinkSettings.Onboarding.MessageRequest request =
                new MavlinkSettings.Onboarding.MessageRequest(74, Duration.ZERO);
        assertEquals(Duration.ZERO, request.interval());
    }

    @Test
    void withOnboardingCanTurnTheFlagOnWithAnExplicitMessageSet() {
        MavlinkSettings.Onboarding.MessageRequest vfrHud =
                new MavlinkSettings.Onboarding.MessageRequest(74, Duration.ofMillis(200));
        MavlinkSettings settings = MavlinkSettings.defaults().withOnboarding(
                new MavlinkSettings.Onboarding(List.of(), Duration.ofSeconds(1), 1, Duration.ofSeconds(1), 1,
                        true, List.of(vfrHud)));

        assertTrue(settings.onboarding().requestMessagesOnConnect());
        assertEquals(List.of(vfrHud), settings.onboarding().onConnectMessageRequests());
    }

    // ---- MAVLINK-COMMANDS-PLAN P1 / D2a: ackTimeout re-scoped to per-attempt, commandRetries added ----

    @Test
    void defaultsCarryTheD2aRetryPolicy() {
        MavlinkSettings settings = MavlinkSettings.defaults();

        assertEquals(Duration.ofMillis(700), settings.ackTimeout(),
                "D2a: ackTimeout defaults to the per-attempt wait, 700ms");
        assertEquals(2, settings.commandRetries(), "D2a: commandRetries defaults to 2 (3 attempts total)");
    }

    @Test
    void commandRetriesRejectsANegativeValue() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> new MavlinkSettings(
                "0.0.0.0", Duration.ofSeconds(30), 32, Duration.ofSeconds(5), Duration.ofMillis(700), -1,
                MavlinkSettings.Scan.defaults(), MavlinkSettings.Transmit.defaults(), MavlinkSettings.Rc.defaults(),
                MavlinkSettings.Inventory.defaults(), MavlinkSettings.Onboarding.defaults(),
                MavlinkSettings.LinkStatus.defaults()));
        assertTrue(ex.getMessage().contains("commandRetries"), ex.getMessage());
    }

    @Test
    void bothBackCompatConstructorsDefaultCommandRetriesToTheD2aDefault() {
        MavlinkSettings viaEightArg = new MavlinkSettings("0.0.0.0", Duration.ofSeconds(30), 32,
                Duration.ofSeconds(5), Duration.ofMillis(700), MavlinkSettings.Scan.defaults(),
                MavlinkSettings.Transmit.defaults(), MavlinkSettings.Rc.defaults());
        MavlinkSettings viaNineArg = new MavlinkSettings("0.0.0.0", Duration.ofSeconds(30), 32,
                Duration.ofSeconds(5), Duration.ofMillis(700), MavlinkSettings.Scan.defaults(),
                MavlinkSettings.Transmit.defaults(), MavlinkSettings.Rc.defaults(), MavlinkSettings.Inventory.defaults());

        assertEquals(2, viaEightArg.commandRetries(),
                "the pre-P1 8-arg overload (still used by vision-app's TelemetryWiring) must pick up "
                        + "the new retry default automatically");
        assertEquals(2, viaNineArg.commandRetries());
    }

    @Test
    void withAckTimeoutAndWithCommandRetriesReplaceOnlyThatOneField() {
        MavlinkSettings settings = MavlinkSettings.defaults();

        MavlinkSettings shorterTimeout = settings.withAckTimeout(Duration.ofMillis(50));
        assertEquals(Duration.ofMillis(50), shorterTimeout.ackTimeout());
        assertEquals(settings.commandRetries(), shorterTimeout.commandRetries());
        assertEquals(settings.bindHost(), shorterTimeout.bindHost());

        MavlinkSettings noRetries = settings.withCommandRetries(0);
        assertEquals(0, noRetries.commandRetries());
        assertEquals(settings.ackTimeout(), noRetries.ackTimeout());
    }
}
