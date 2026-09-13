package com.drones.vision.perception.application.pipeline;

import org.junit.jupiter.api.Test;

import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link OutageSupervisor} alone (docs/plans/active/CV-ORCHESTRATION-PLAN.md &sect;4.9/K3, wave
 * W8.1 extraction) — a fake clock, no {@link StreamPipeline}. Every scenario here reproduces a
 * behavior {@code StreamPipeline}'s own (pre-W8) inline outage fields/methods had, ported
 * unchanged.
 *
 * <p><b>{@code GateReason#CV_UNAVAILABLE} is deliberately out of scope here.</b> That
 * classification is {@code StreamPipeline#submitDetection}'s own peek at whether {@code
 * detectionPort.detect} returned an already-failed {@link java.util.concurrent.CompletionStage}
 * (docs/plans/active/CV-ORCHESTRATION-PLAN.md &sect;4.4) — a wire-tier distinction {@link
 * OutageSupervisor} has no vocabulary for and does not need: whether a failure was synchronous
 * (CV_UNAVAILABLE) or asynchronous, {@code submitDetection}'s completion callback reaches this
 * class through exactly the same {@link #recordFailure(boolean)} call with the same {@code
 * isProbe} argument. {@link #firstFailureEntersOutage()} below therefore already exercises
 * every code path a CV_UNAVAILABLE-classified failure takes through this class.
 */
class OutageSupervisorTest {

    private static StreamPipelineSettings settingsWith(long backoffInitialNanos, long backoffMaxNanos) {
        StreamPipelineSettings d = StreamPipelineSettings.defaults();
        return new StreamPipelineSettings(d.assumedSourceFps(), d.measuredFpsEwmaAlpha(), d.warmupFrames(),
                d.minMeasuredFps(), d.maxMeasuredFps(), backoffInitialNanos, backoffMaxNanos,
                d.sourceReopenBackoffInitialNanos(), d.sourceReopenBackoffMaxNanos(), d.trackingStatsWindow(),
                d.trackRetention(), d.trackingSeed(), d.cameraHfovDegrees(), d.adaptiveRate(),
                d.detectionDemandPollInterval(), d.detectionDemandGrace(), d.videoStaleAfter(), d.renderTier(),
                d.gateLedgerDepth(), d.frameLedgerDepth());
    }

    private static OutageSupervisor supervisor(SettableClock clock, long backoffInitialNanos, long backoffMaxNanos) {
        return new OutageSupervisor(settingsWith(backoffInitialNanos, backoffMaxNanos), clock);
    }

    @Test
    void outageDecisionIsNormalWhenNothingHasFailedYet() {
        OutageSupervisor supervisor = supervisor(new SettableClock(0L), 100_000_000L, 400_000_000L);

        assertEquals(OutageSupervisor.OutageDecision.NORMAL, supervisor.outageDecision());
    }

    @Test
    void firstFailureEntersOutage() {
        OutageSupervisor supervisor = supervisor(new SettableClock(0L), 100_000_000L, 400_000_000L);

        boolean enteringOutage = supervisor.recordFailure(false);

        assertTrue(enteringOutage, "the first failure, whether from a probe or a normal sample, enters the outage");
        assertEquals(OutageSupervisor.OutageDecision.SKIP, supervisor.outageDecision(),
                "the backoff has not elapsed yet -- skip, not probe");
    }

    @Test
    void subsequentFailuresWhileAlreadyInOutageDoNotReportEnteringAgain() {
        SettableClock clock = new SettableClock(0L);
        OutageSupervisor supervisor = supervisor(clock, 100_000_000L, 400_000_000L);
        supervisor.recordFailure(false);

        boolean enteringOutage = supervisor.recordFailure(false);

        assertFalse(enteringOutage, "a stray failure from a call already in flight is counted, not a new edge");
    }

    @Test
    void aNonProbeFailureDuringAnOutageDoesNotPerturbTheBackoff() {
        SettableClock clock = new SettableClock(0L);
        OutageSupervisor supervisor = supervisor(clock, 100_000_000L, 400_000_000L);
        supervisor.recordFailure(false); // enters outage, nextProbeAtNanos = 100ms

        supervisor.recordFailure(false); // stray non-probe failure while already in outage

        clock.advance(100_000_000L); // exactly the ORIGINAL (undoubled) backoff
        assertEquals(OutageSupervisor.OutageDecision.PROBE, supervisor.outageDecision(),
                "the original 100ms backoff still governs -- a non-probe failure never doubled it");
    }

    @Test
    void onlyAFailedProbeDoublesTheBackoff() {
        SettableClock clock = new SettableClock(0L);
        OutageSupervisor supervisor = supervisor(clock, 100_000_000L, 400_000_000L);
        supervisor.recordFailure(false); // enters outage, backoff = 100ms

        clock.advance(100_000_000L);
        assertEquals(OutageSupervisor.OutageDecision.PROBE, supervisor.outageDecision());
        supervisor.recordFailure(true); // the probe itself fails -- backoff doubles to 200ms
        supervisor.clearProbeInFlight(); // the completion callback always releases the slot, success or failure

        clock.advance(199_999_999L); // one ns short of the doubled backoff
        assertEquals(OutageSupervisor.OutageDecision.SKIP, supervisor.outageDecision());
        clock.advance(1L);
        assertEquals(OutageSupervisor.OutageDecision.PROBE, supervisor.outageDecision(),
                "the doubled 200ms backoff has now fully elapsed");
    }

    @Test
    void backoffIsCappedAtTheConfiguredMaximum() {
        SettableClock clock = new SettableClock(0L);
        OutageSupervisor supervisor = supervisor(clock, 100_000_000L, 250_000_000L);
        supervisor.recordFailure(false); // backoff = 100ms

        clock.advance(100_000_000L);
        supervisor.outageDecision(); // claims the probe slot
        supervisor.recordFailure(true); // 100ms -> 200ms (still under the 250ms cap)
        supervisor.clearProbeInFlight();

        clock.advance(200_000_000L);
        supervisor.outageDecision();
        supervisor.recordFailure(true); // 200ms -> would be 400ms, capped at 250ms
        supervisor.clearProbeInFlight();

        clock.advance(249_999_999L);
        assertEquals(OutageSupervisor.OutageDecision.SKIP, supervisor.outageDecision(),
                "one ns short of the CAPPED 250ms backoff -- not the uncapped 400ms");
        clock.advance(1L);
        assertEquals(OutageSupervisor.OutageDecision.PROBE, supervisor.outageDecision());
    }

    @Test
    void exactlyOneProbeIsAdmittedAtATime() {
        SettableClock clock = new SettableClock(0L);
        OutageSupervisor supervisor = supervisor(clock, 100_000_000L, 400_000_000L);
        supervisor.recordFailure(false);
        clock.advance(100_000_000L);

        assertEquals(OutageSupervisor.OutageDecision.PROBE, supervisor.outageDecision(), "first caller claims it");
        assertEquals(OutageSupervisor.OutageDecision.SKIP, supervisor.outageDecision(),
                "a second concurrent caller is skipped even though the deadline has passed");

        supervisor.clearProbeInFlight();

        assertEquals(OutageSupervisor.OutageDecision.PROBE, supervisor.outageDecision(),
                "the slot is free again once the in-flight probe completes");
    }

    @Test
    void recordSuccessResetsTheBackoffAndReportsTheRecoveryEdgeWithItsFailureCount() {
        SettableClock clock = new SettableClock(0L);
        OutageSupervisor supervisor = supervisor(clock, 100_000_000L, 400_000_000L);
        supervisor.recordFailure(false); // entering edge
        supervisor.recordFailure(false); // counted, stray
        clock.advance(100_000_000L);
        supervisor.outageDecision(); // claims the probe
        supervisor.recordFailure(true); // failed probe -- backoff doubles, count increments
        supervisor.clearProbeInFlight();

        OutageSupervisor.Recovery recovery = supervisor.recordSuccess();

        assertTrue(recovery.recovered());
        assertEquals(2L, recovery.failuresDuringOutage(), "two failures counted after the entering one");
        assertEquals(OutageSupervisor.OutageDecision.NORMAL, supervisor.outageDecision(), "outage cleared");
    }

    @Test
    void recordSuccessWhenNoOutageWasInProgressReportsNoRecovery() {
        OutageSupervisor supervisor = supervisor(new SettableClock(0L), 100_000_000L, 400_000_000L);

        OutageSupervisor.Recovery recovery = supervisor.recordSuccess();

        assertFalse(recovery.recovered());
    }

    @Test
    void backoffResetsToInitialOnRecoverySoALaterIndependentOutageStartsFresh() {
        SettableClock clock = new SettableClock(0L);
        OutageSupervisor supervisor = supervisor(clock, 100_000_000L, 400_000_000L);
        supervisor.recordFailure(false);
        clock.advance(100_000_000L);
        supervisor.outageDecision();
        supervisor.recordFailure(true); // backoff now 200ms
        supervisor.clearProbeInFlight();
        supervisor.recordSuccess(); // recovers, backoff reset to 100ms

        supervisor.recordFailure(false); // a later, independent outage

        clock.advance(99_999_999L);
        assertEquals(OutageSupervisor.OutageDecision.SKIP, supervisor.outageDecision());
        clock.advance(1L);
        assertEquals(OutageSupervisor.OutageDecision.PROBE, supervisor.outageDecision(),
                "the fresh outage uses the INITIAL 100ms backoff, not the previous outage's doubled 200ms");
    }

    /** @see StreamPipelineGateLedgerTest.SettableClock -- duplicated here per this file's own header javadoc. */
    private static final class SettableClock implements LongSupplier {
        private long now;

        SettableClock(long initial) {
            this.now = initial;
        }

        @Override
        public long getAsLong() {
            return now;
        }

        void advance(long nanos) {
            now += nanos;
        }
    }
}
