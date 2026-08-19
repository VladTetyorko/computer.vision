package com.drones.vision.flight.domain.model;

import com.drones.vision.kernel.AssetId;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the §4.5 numeric semantics directly against {@link DivergenceRule}: sigma * combined-sigma
 * qualification, the N-consecutive-fix latch, the single rising-edge event, and the clear-after
 * timeout. {@link DefaultTrackCorrectionServiceTest} exercises the same rule wired through {@code
 * submit}; this class isolates the state machine's own arithmetic.
 */
class DivergenceRuleTest {

    private static final double SIGMA = 3.0;
    private static final int CONSECUTIVE_FIXES = 4;
    private static final Duration CLEAR_AFTER = Duration.ofSeconds(30);
    private static final double SIGMA_METERS = 14.0; // combined 1-sigma; threshold = 3.0 * 14.0 = 42.0m
    private static final double QUALIFYING_SEPARATION = 50.0; // > 42.0
    private static final double NON_QUALIFYING_SEPARATION = 10.0; // <= 42.0

    private static final AssetId ASSET_ID = AssetId.random();
    private static final Instant T0 = Instant.parse("2026-08-19T12:00:00Z");

    private final DivergenceRule rule = new DivergenceRule(SIGMA, CONSECUTIVE_FIXES, CLEAR_AFTER);

    // -- constructor validation -------------------------------------------------------------

    @Test
    void constructorRejectsNonPositiveSigma() {
        assertThrows(IllegalArgumentException.class, () -> new DivergenceRule(0.0, 4, CLEAR_AFTER));
        assertThrows(IllegalArgumentException.class, () -> new DivergenceRule(-1.0, 4, CLEAR_AFTER));
    }

    @Test
    void constructorRejectsConsecutiveFixesBelowOne() {
        assertThrows(IllegalArgumentException.class, () -> new DivergenceRule(SIGMA, 0, CLEAR_AFTER));
    }

    @Test
    void constructorRejectsNonPositiveClearAfter() {
        assertThrows(IllegalArgumentException.class, () -> new DivergenceRule(SIGMA, 4, Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new DivergenceRule(SIGMA, 4, null));
    }

    @Test
    void evaluateRejectsNullArguments() {
        assertThrows(IllegalArgumentException.class,
                () -> rule.evaluate(null, CorrectionStatus.CONFIRMED, 50.0, 14.0, T0));
        assertThrows(IllegalArgumentException.class,
                () -> rule.evaluate(ASSET_ID, null, 50.0, 14.0, T0));
        assertThrows(IllegalArgumentException.class,
                () -> rule.evaluate(ASSET_ID, CorrectionStatus.CONFIRMED, 50.0, 14.0, null));
    }

    // -- non-participation --------------------------------------------------------------------

    @Test
    void probableNeverArmsEvenWithAQualifyingSeparation() {
        for (int i = 0; i < CONSECUTIVE_FIXES + 2; i++) {
            DivergenceRule.Outcome outcome = rule.evaluate(ASSET_ID, CorrectionStatus.PROBABLE,
                    QUALIFYING_SEPARATION, SIGMA_METERS, T0.plusSeconds(i));
            assertFalse(outcome.divergent());
            assertFalse(outcome.risingEdge());
        }
    }

    @Test
    void noFixNeverArmsEvenWithAQualifyingSeparation() {
        DivergenceRule.Outcome outcome =
                rule.evaluate(ASSET_ID, CorrectionStatus.NO_FIX, QUALIFYING_SEPARATION, SIGMA_METERS, T0);
        assertFalse(outcome.divergent());
    }

    @Test
    void confirmedWithNoSeparationOrSigmaNeitherArmsNorClears() {
        DivergenceRule.Outcome outcome1 =
                rule.evaluate(ASSET_ID, CorrectionStatus.CONFIRMED, null, SIGMA_METERS, T0);
        assertFalse(outcome1.divergent());
        DivergenceRule.Outcome outcome2 =
                rule.evaluate(ASSET_ID, CorrectionStatus.CONFIRMED, QUALIFYING_SEPARATION, null, T0);
        assertFalse(outcome2.divergent());
    }

    // -- qualification boundary ------------------------------------------------------------

    @Test
    void separationExactlyAtTheThresholdDoesNotQualify() {
        // threshold = sigma * sigmaMeters = 3.0 * 14.0 = 42.0 exactly -- ">" is strict.
        latchAlarm(); // consume the alarm state with a real latch first to prove equality never arms it either
        DivergenceRule rule2 = new DivergenceRule(SIGMA, 1, CLEAR_AFTER);
        DivergenceRule.Outcome outcome = rule2.evaluate(AssetId.random(), CorrectionStatus.CONFIRMED, 42.0,
                SIGMA_METERS, T0);
        assertFalse(outcome.divergent());
    }

    // -- arming / latching --------------------------------------------------------------------

    @Test
    void fewerThanConsecutiveFixesQualifyingNeverLatches() {
        for (int i = 0; i < CONSECUTIVE_FIXES - 1; i++) {
            DivergenceRule.Outcome outcome = rule.evaluate(ASSET_ID, CorrectionStatus.CONFIRMED,
                    QUALIFYING_SEPARATION, SIGMA_METERS, T0.plusSeconds(i));
            assertFalse(outcome.divergent(), "fix " + i + " should not have latched yet");
            assertFalse(outcome.risingEdge());
        }
    }

    @Test
    void theNthConsecutiveQualifyingFixLatchesWithARisingEdge() {
        DivergenceRule.Outcome last = null;
        for (int i = 0; i < CONSECUTIVE_FIXES; i++) {
            last = rule.evaluate(ASSET_ID, CorrectionStatus.CONFIRMED, QUALIFYING_SEPARATION, SIGMA_METERS,
                    T0.plusSeconds(i));
        }
        assertTrue(last.divergent());
        assertTrue(last.risingEdge());
        assertEquals(T0.plusSeconds(CONSECUTIVE_FIXES - 1), last.divergentSince());
    }

    @Test
    void reArmingWhileAlreadyLatchedDoesNotRepublish() {
        latchAlarm();
        DivergenceRule.Outcome again =
                rule.evaluate(ASSET_ID, CorrectionStatus.CONFIRMED, QUALIFYING_SEPARATION, SIGMA_METERS,
                        T0.plusSeconds(CONSECUTIVE_FIXES));

        assertTrue(again.divergent());
        assertFalse(again.risingEdge());
    }

    @Test
    void divergentSinceStaysAtTheOriginalLatchTimeAcrossRepeatedQualifyingFixes() {
        DivergenceRule.Outcome first = latchAlarm();
        DivergenceRule.Outcome second =
                rule.evaluate(ASSET_ID, CorrectionStatus.CONFIRMED, QUALIFYING_SEPARATION, SIGMA_METERS,
                        T0.plusSeconds(CONSECUTIVE_FIXES));
        DivergenceRule.Outcome third =
                rule.evaluate(ASSET_ID, CorrectionStatus.CONFIRMED, QUALIFYING_SEPARATION, SIGMA_METERS,
                        T0.plusSeconds(CONSECUTIVE_FIXES + 1));

        assertEquals(first.divergentSince(), second.divergentSince());
        assertEquals(first.divergentSince(), third.divergentSince());
    }

    @Test
    void aNonQualifyingConfirmedFixWhileArmingResetsTheCount() {
        // Two qualifying fixes (count=2 of 4), then a non-qualifying one -- resets to 0.
        rule.evaluate(ASSET_ID, CorrectionStatus.CONFIRMED, QUALIFYING_SEPARATION, SIGMA_METERS, T0);
        rule.evaluate(ASSET_ID, CorrectionStatus.CONFIRMED, QUALIFYING_SEPARATION, SIGMA_METERS, T0.plusSeconds(1));
        DivergenceRule.Outcome reset = rule.evaluate(ASSET_ID, CorrectionStatus.CONFIRMED,
                NON_QUALIFYING_SEPARATION, SIGMA_METERS, T0.plusSeconds(2));
        assertFalse(reset.divergent());

        // Now only 3 more qualifying fixes arrive (would have been enough without the reset, since
        // 2 + 3 = 5 >= 4) -- but the count restarted at 0, so 3 alone is not enough.
        DivergenceRule.Outcome afterThreeMore = null;
        for (int i = 0; i < 3; i++) {
            afterThreeMore = rule.evaluate(ASSET_ID, CorrectionStatus.CONFIRMED, QUALIFYING_SEPARATION,
                    SIGMA_METERS, T0.plusSeconds(3 + i));
        }
        assertFalse(afterThreeMore.divergent());
    }

    // -- clearing -----------------------------------------------------------------------------

    @Test
    void aNonQualifyingConfirmedFixWhileAlarmedStaysLatchedBeforeClearAfterElapses() {
        Instant lastQualifyingAt = latchAlarmAndReturnLastQualifyingInstant();
        DivergenceRule.Outcome stillLatched =
                rule.evaluate(ASSET_ID, CorrectionStatus.CONFIRMED, NON_QUALIFYING_SEPARATION, SIGMA_METERS,
                        lastQualifyingAt.plus(CLEAR_AFTER.minusSeconds(1)));

        assertTrue(stillLatched.divergent());
    }

    @Test
    void clearAfterElapsingWithNoQualifyingFixClearsTheAlarm() {
        Instant lastQualifyingAt = latchAlarmAndReturnLastQualifyingInstant();
        DivergenceRule.Outcome cleared =
                rule.evaluate(ASSET_ID, CorrectionStatus.CONFIRMED, NON_QUALIFYING_SEPARATION, SIGMA_METERS,
                        lastQualifyingAt.plus(CLEAR_AFTER));

        assertFalse(cleared.divergent());
        assertNull(cleared.divergentSince());
        assertFalse(cleared.risingEdge());
    }

    @Test
    void afterClearingAFreshRunOfConsecutiveFixesIsRequiredToReLatch() {
        Instant lastQualifyingAt = latchAlarmAndReturnLastQualifyingInstant();
        rule.evaluate(ASSET_ID, CorrectionStatus.CONFIRMED, NON_QUALIFYING_SEPARATION, SIGMA_METERS,
                lastQualifyingAt.plus(CLEAR_AFTER)); // clears

        // Only CONSECUTIVE_FIXES - 1 qualifying fixes after clearing -- not enough yet.
        DivergenceRule.Outcome last = null;
        for (int i = 0; i < CONSECUTIVE_FIXES - 1; i++) {
            last = rule.evaluate(ASSET_ID, CorrectionStatus.CONFIRMED, QUALIFYING_SEPARATION, SIGMA_METERS,
                    lastQualifyingAt.plus(CLEAR_AFTER).plusSeconds(1 + i));
        }
        assertFalse(last.divergent());
    }

    // -- helpers --------------------------------------------------------------------------------

    private DivergenceRule.Outcome latchAlarm() {
        DivergenceRule.Outcome last = null;
        for (int i = 0; i < CONSECUTIVE_FIXES; i++) {
            last = rule.evaluate(ASSET_ID, CorrectionStatus.CONFIRMED, QUALIFYING_SEPARATION, SIGMA_METERS,
                    T0.plusSeconds(i));
        }
        return last;
    }

    private Instant latchAlarmAndReturnLastQualifyingInstant() {
        latchAlarm();
        return T0.plusSeconds(CONSECUTIVE_FIXES - 1);
    }
}
