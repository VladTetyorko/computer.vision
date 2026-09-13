package com.drones.vision.perception.application.pipeline;

import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * The detection-outage/probe-backoff state machine (docs/plans/active/CV-ORCHESTRATION-PLAN.md
 * &sect;4.9/K3, wave W8.1 extraction) — one collaborator {@link StreamPipeline} holds, built
 * directly inside its constructor from {@link StreamPipelineSettings} and its own {@link
 * LongSupplier} clock, exactly like {@link FrameSampler} is (java-clean-code &sect;3: no new
 * constructor overload on {@link StreamPipeline}).
 *
 * <p><b>This class owns the state transitions only; {@link StreamPipeline} keeps the logging and
 * the {@code PIPELINE_ERROR} event publish</b> — {@link #recordFailure} and {@link #recordSuccess}
 * report which edge (entering an outage, or recovering from one) just occurred, and the caller
 * decides what to log/publish for it. Splitting it this way keeps this class ignorant of {@code
 * EventPublisherPort}/{@code System.Logger}, the same "collaborator does not know who is listening"
 * shape every other peer in this package follows.
 *
 * <h2>The backoff/probe discipline (ported unchanged from {@code StreamPipeline})</h2>
 * <ul>
 *   <li>The first failure (from any in-flight call, probe or not) enters the outage — the only one
 *       the caller should raise an event for; every later failure while already in outage is
 *       counted silently, and only a failed <b>probe</b> doubles the backoff (capped at {@link
 *       StreamPipelineSettings#detectionBackoffMaxNanos()}) and reschedules the next probe — a
 *       stray failure from a call that was already in flight when the outage began must not perturb
 *       a backoff a probe may have already advanced.</li>
 *   <li>Exactly one probe is in flight at a time — {@link #outageDecision()}'s claim of {@code
 *       probeInFlight} and its deadline check happen together inside one {@code synchronized}
 *       block, which is what prevents a second concurrent probe from starting (or the very first
 *       backoff window from being skipped by a racing thread that observes {@code inOutage}
 *       freshly flipped {@code true} before its paired backoff fields are visible).</li>
 *   <li>A successful detection, whether or not it followed an outage, always resets the backoff
 *       back to its initial interval ({@link #recordSuccess()}).</li>
 * </ul>
 *
 * <p><b>Genuinely different from a closed detection gate</b> (see {@code
 * StreamPipeline#handleDetectionGateTransition}): during an outage the system is still trying and
 * simply has no fresher answer <i>yet</i>, so this class's own state is deliberately left untouched
 * by a gate closing — only {@link #recordFailure}/{@link #recordSuccess}/{@link #outageDecision()}
 * ever mutate it.
 */
final class OutageSupervisor {

    /** Outcome of consulting outage state for a newly-sampled frame, see {@link #outageDecision()}. */
    enum OutageDecision {
        /** No outage in progress: fall through to the normal in-flight-bounded path. */
        NORMAL,
        /** An outage is in progress but the backoff hasn't elapsed, or a probe is already outstanding. */
        SKIP,
        /** The backoff deadline has passed and no probe is outstanding: send exactly this one frame as a probe. */
        PROBE
    }

    /**
     * The result of {@link #recordSuccess()} — whether this success followed an active outage, and
     * (only then) how many attempts failed during it, for the caller's own recovery log line.
     */
    record Recovery(boolean recovered, long failuresDuringOutage) {
    }

    private final LongSupplier nanoTimeSource;
    private final long detectionBackoffInitialNanos;
    private final long detectionBackoffMaxNanos;

    // Genuinely touched from multiple threads without serialization: the frame-arrival thread
    // (consulting outageDecision()) races with detect() completion callbacks (which may land on an
    // arbitrary executor thread). All reads/writes go through the synchronized blocks below rather
    // than volatile/Atomic fields, because entering an outage, doubling the backoff, and reading
    // the backoff deadline must be observed as a single consistent unit.
    private final Object outageLock = new Object();
    private boolean inOutage = false;
    private boolean probeInFlight = false;
    private long backoffNanos;
    private long nextProbeAtNanos = 0L;
    private long outageFailureCount = 0L;

    /**
     * @param settings       supplies {@link StreamPipelineSettings#detectionBackoffInitialNanos()}/
     *                       {@link StreamPipelineSettings#detectionBackoffMaxNanos()} — deployment
     *                       tunables, never a literal here
     * @param nanoTimeSource the pipeline's own cadence clock ({@link StreamPipeline#nanoTimeSource})
     */
    OutageSupervisor(StreamPipelineSettings settings, LongSupplier nanoTimeSource) {
        Objects.requireNonNull(settings, "settings must not be null");
        this.nanoTimeSource = Objects.requireNonNull(nanoTimeSource, "nanoTimeSource must not be null");
        this.detectionBackoffInitialNanos = settings.detectionBackoffInitialNanos();
        this.detectionBackoffMaxNanos = settings.detectionBackoffMaxNanos();
        this.backoffNanos = this.detectionBackoffInitialNanos;
    }

    /**
     * Consults and, where it decides {@link OutageDecision#PROBE}, mutates outage state under
     * {@link #outageLock} in a single atomic step — checking {@code nextProbeAtNanos} and claiming
     * {@code probeInFlight} together, rather than as two separate lock-free reads, is what prevents
     * a second concurrent probe from starting.
     */
    OutageDecision outageDecision() {
        synchronized (outageLock) {
            if (!inOutage) {
                return OutageDecision.NORMAL;
            }
            if (probeInFlight || nanoTimeSource.getAsLong() < nextProbeAtNanos) {
                return OutageDecision.SKIP;
            }
            probeInFlight = true;
            return OutageDecision.PROBE;
        }
    }

    /** Releases the single-probe-in-flight claim {@link #outageDecision()}'s {@code PROBE} branch took. */
    void clearProbeInFlight() {
        synchronized (outageLock) {
            probeInFlight = false;
        }
    }

    /**
     * Records a failed inference's effect on outage state. The first failure (from any in-flight
     * call, probe or not) enters the outage; every later failure while already in outage is counted
     * silently, and only a failed probe doubles the backoff and reschedules the next probe.
     *
     * @param isProbe whether the failed call was an outage-recovery probe
     * @return {@code true} exactly when this failure is the edge that just entered a new outage —
     *         the only edge the caller should raise a {@code PIPELINE_ERROR} event for
     */
    boolean recordFailure(boolean isProbe) {
        synchronized (outageLock) {
            boolean enteringOutage = !inOutage;
            if (enteringOutage) {
                inOutage = true;
                backoffNanos = detectionBackoffInitialNanos;
                outageFailureCount = 0;
                nextProbeAtNanos = nanoTimeSource.getAsLong() + backoffNanos;
            } else {
                outageFailureCount++;
                if (isProbe) {
                    backoffNanos = Math.min(backoffNanos * 2, detectionBackoffMaxNanos);
                    nextProbeAtNanos = nanoTimeSource.getAsLong() + backoffNanos;
                }
            }
            return enteringOutage;
        }
    }

    /**
     * Records a successful inference's effect on outage state (normal sample or outage-ending probe
     * alike) — always resets the backoff to its initial interval, and reports whether this success
     * followed an active outage so the caller can log a recovery line (no second event is emitted;
     * {@code PIPELINE_ERROR} is reserved for {@link #recordFailure}'s outage-entering edge).
     */
    Recovery recordSuccess() {
        synchronized (outageLock) {
            boolean recovered = inOutage;
            long failuresDuringOutage = outageFailureCount;
            inOutage = false;
            backoffNanos = detectionBackoffInitialNanos;
            return new Recovery(recovered, failuresDuringOutage);
        }
    }
}
