package com.drones.vision.flight.domain.model;

import com.drones.vision.kernel.FlightState;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FlightPhaseRuleTest {

    private static final Duration SILENCE = Duration.ofSeconds(10);
    private static final Duration ABANDON = Duration.ofSeconds(120);
    private final FlightPhaseRule rule = new FlightPhaseRule(SILENCE, ABANDON);

    private static FlightState armed(Boolean armed) {
        return new FlightState(null, null, armed, null, null, null, null, null, List.of());
    }

    @Test
    void constructorRejectsNonPositiveWindows() {
        assertThrows(IllegalArgumentException.class, () -> new FlightPhaseRule(Duration.ZERO, ABANDON));
        assertThrows(IllegalArgumentException.class, () -> new FlightPhaseRule(SILENCE, Duration.ZERO));
    }

    @Test
    void constructorRejectsAnAbandonWindowShorterThanSilence() {
        assertThrows(IllegalArgumentException.class,
                () -> new FlightPhaseRule(Duration.ofSeconds(30), Duration.ofSeconds(10)));
    }

    // -- PREFLIGHT ------------------------------------------------------------------

    @Test
    void preflightPromotesToInFlightOnceArmedIsTrue() {
        assertEquals(FlightPhase.IN_FLIGHT,
                rule.nextPhase(FlightPhase.PREFLIGHT, armed(true), Duration.ZERO, 1));
    }

    @Test
    void preflightNeverPromotesWhileArmedIsUnknown() {
        assertEquals(FlightPhase.PREFLIGHT,
                rule.nextPhase(FlightPhase.PREFLIGHT, armed(null), Duration.ZERO, 1));
        assertEquals(FlightPhase.PREFLIGHT,
                rule.nextPhase(FlightPhase.PREFLIGHT, null, Duration.ZERO, 1));
    }

    @Test
    void preflightNeverPromotesWhileArmedIsFalse() {
        assertEquals(FlightPhase.PREFLIGHT,
                rule.nextPhase(FlightPhase.PREFLIGHT, armed(false), Duration.ZERO, 1));
    }

    @Test
    void preflightClosesOnSilenceWithNoStreamsAndNeverArmed() {
        assertEquals(FlightPhase.CLOSED,
                rule.nextPhase(FlightPhase.PREFLIGHT, armed(null), SILENCE, 0));
    }

    @Test
    void preflightDoesNotCloseWhileAStreamIsStillOpenEvenIfTelemetryIsSilent() {
        assertEquals(FlightPhase.PREFLIGHT,
                rule.nextPhase(FlightPhase.PREFLIGHT, armed(null), SILENCE, 1));
    }

    @Test
    void preflightDoesNotCloseOnSilenceAloneWithoutStreamCountAlsoZero() {
        assertEquals(FlightPhase.PREFLIGHT,
                rule.nextPhase(FlightPhase.PREFLIGHT, armed(null), Duration.ofSeconds(1), 0));
    }

    // -- IN_FLIGHT --------------------------------------------------------------------

    @Test
    void inFlightDemotesToPostflightOnceArmedIsFalse() {
        assertEquals(FlightPhase.POSTFLIGHT,
                rule.nextPhase(FlightPhase.IN_FLIGHT, armed(false), Duration.ZERO, 0));
    }

    @Test
    void inFlightStaysInFlightWhileArmedUnknownAndTelemetryStillArriving() {
        assertEquals(FlightPhase.IN_FLIGHT,
                rule.nextPhase(FlightPhase.IN_FLIGHT, armed(null), Duration.ZERO, 1));
    }

    @Test
    void inFlightGoesLinkLostOnSilenceRegardlessOfStreamCount() {
        assertEquals(FlightPhase.LINK_LOST,
                rule.nextPhase(FlightPhase.IN_FLIGHT, armed(true), SILENCE, 0));
    }

    @Test
    void inFlightNeverGoesClosedDirectlyFromASample() {
        // A telemetry-only flight has streamCount == 0 throughout and must not be mistaken for
        // "nothing happening" the way PREFLIGHT/POSTFLIGHT's own closing rule reads it.
        assertEquals(FlightPhase.IN_FLIGHT,
                rule.nextPhase(FlightPhase.IN_FLIGHT, armed(true), Duration.ZERO, 0));
    }

    // -- LINK_LOST --------------------------------------------------------------------

    @Test
    void linkLostReturnsToInFlightWhenReHeardStillArmed() {
        assertEquals(FlightPhase.IN_FLIGHT,
                rule.nextPhase(FlightPhase.LINK_LOST, armed(true), Duration.ZERO, 1));
    }

    @Test
    void linkLostGoesPostflightWhenReHeardDisarmed() {
        assertEquals(FlightPhase.POSTFLIGHT,
                rule.nextPhase(FlightPhase.LINK_LOST, armed(false), Duration.ZERO, 1));
    }

    @Test
    void linkLostStaysLinkLostWhenReHeardButArmingStillUnknown() {
        assertEquals(FlightPhase.LINK_LOST,
                rule.nextPhase(FlightPhase.LINK_LOST, armed(null), Duration.ZERO, 1));
    }

    @Test
    void linkLostStaysLinkLostWhileWithinTheAbandonWindow() {
        assertEquals(FlightPhase.LINK_LOST,
                rule.nextPhase(FlightPhase.LINK_LOST, armed(true), Duration.ofSeconds(60), 0));
    }

    @Test
    void linkLostBecomesAbandonedPastTheAbandonWindow() {
        assertEquals(FlightPhase.ABANDONED,
                rule.nextPhase(FlightPhase.LINK_LOST, armed(true), ABANDON, 0));
    }

    // -- POSTFLIGHT -------------------------------------------------------------------

    @Test
    void postflightReArmsToInFlightForASecondTakeoff() {
        assertEquals(FlightPhase.IN_FLIGHT,
                rule.nextPhase(FlightPhase.POSTFLIGHT, armed(true), Duration.ZERO, 0));
    }

    @Test
    void postflightClosesOnSilenceWithNoStreams() {
        assertEquals(FlightPhase.CLOSED,
                rule.nextPhase(FlightPhase.POSTFLIGHT, armed(false), SILENCE, 0));
    }

    @Test
    void postflightDoesNotCloseWhileAStreamIsStillOpen() {
        assertEquals(FlightPhase.POSTFLIGHT,
                rule.nextPhase(FlightPhase.POSTFLIGHT, armed(false), SILENCE, 1));
    }

    // -- terminal phases ---------------------------------------------------------------

    @Test
    void abandonedAndClosedAreTerminalUnderNextPhase() {
        assertEquals(FlightPhase.ABANDONED,
                rule.nextPhase(FlightPhase.ABANDONED, armed(true), Duration.ZERO, 1));
        assertEquals(FlightPhase.CLOSED,
                rule.nextPhase(FlightPhase.CLOSED, armed(true), Duration.ZERO, 1));
    }

    // -- onSessionClosed ----------------------------------------------------------------

    @Test
    void sessionClosedWhileInFlightOrLinkLostBecomesAbandoned() {
        assertEquals(FlightPhase.ABANDONED, rule.onSessionClosed(FlightPhase.IN_FLIGHT));
        assertEquals(FlightPhase.ABANDONED, rule.onSessionClosed(FlightPhase.LINK_LOST));
    }

    @Test
    void sessionClosedFromEveryOtherPhaseBecomesClosed() {
        assertEquals(FlightPhase.CLOSED, rule.onSessionClosed(FlightPhase.PREFLIGHT));
        assertEquals(FlightPhase.CLOSED, rule.onSessionClosed(FlightPhase.POSTFLIGHT));
        assertEquals(FlightPhase.CLOSED, rule.onSessionClosed(FlightPhase.ABANDONED));
        assertEquals(FlightPhase.CLOSED, rule.onSessionClosed(FlightPhase.CLOSED));
    }

    @Test
    void nextPhaseRejectsNullCurrentPhaseOrNegativeLinkAge() {
        assertThrows(IllegalArgumentException.class,
                () -> rule.nextPhase(null, armed(true), Duration.ZERO, 0));
        assertThrows(IllegalArgumentException.class,
                () -> rule.nextPhase(FlightPhase.PREFLIGHT, armed(true), Duration.ofSeconds(-1), 0));
    }
}
