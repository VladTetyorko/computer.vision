package com.drones.vision.perception.application.pipeline;

import com.drones.vision.perception.domain.model.FrameLedger;
import com.drones.vision.perception.domain.model.PipelineConfig;
import com.drones.vision.perception.domain.model.VideoFrame;

import java.util.List;
import java.util.Objects;

/**
 * {@code cv-trace}'s gate and frame ledgers, plus the trace-demand fold that turns the underlying
 * cv-service capture on and off, as one collaborator {@link StreamPipeline} holds (docs/plans/active/
 * CV-ORCHESTRATION-PLAN.md &sect;4.4, wave W2.9 extraction) — {@link StreamPipeline} grew past its
 * peer target this wave carrying exactly this trio inline; nothing here changed behavior, only
 * where the code lives, mirroring how {@link WorldModel}/{@link TrackingStatsWindow}/{@link
 * PipelineLatencyWindow} are already peers rather than fields spread across the pipeline itself.
 *
 * <p>Built directly inside {@link StreamPipeline}'s constructor from its {@link
 * StreamPipelineSettings}, exactly like {@link WorldModel}/{@link TrackingStatsWindow} are — this is
 * {@link StreamPipeline}'s own per-stream bookkeeping, not a substitutable collaborator threaded
 * through {@link StreamPipelineCollaborators}, so it costs that record's own constructor nothing
 * (java-clean-code &sect;3: no new constructor overload, no parameter whose contract is "null means
 * off").
 *
 * <h2>What moved here</h2>
 * <ul>
 *   <li>{@link FrameGateLedger}/{@link FrameLedgerRing} themselves — the two rings {@link
 *       StreamPipeline#gateLedger(int)}/{@link StreamPipeline#frameLedger(int)} read from, via
 *       {@link #gateLedger(int)}/{@link #frameLedger(int)} below (same names, same signatures,
 *       {@link StreamPipeline} simply delegates now).</li>
 *   <li>{@link #recordSkip}/{@link #recordSent}/{@link #recordFrameLedger} — the write sides {@link
 *       StreamPipeline#maybeDetect}/{@link StreamPipeline#submitDetection}/{@link
 *       StreamPipeline#onDetectionResult} call into, building each {@link GateDecision} here rather
 *       than handing this collaborator an already-built one.</li>
 *   <li>{@link #clear()} — both rings' clear, called from {@link
 *       StreamPipeline#clearDetectionDerivedState()} exactly as before (detector-health windows
 *       tied to whether inference itself is running, never to whether anyone is watching — see that
 *       method's own javadoc for why {@link StreamPipeline#clearLiveDerivedState()} leaves these
 *       alone).</li>
 *   <li>{@link #withTraceDemand} — the pure fold {@link StreamPipeline#updateTraceDemand} delegates
 *       to: rebuilding a {@link PipelineConfig} with {@link PipelineConfig#trace()} flipped, or
 *       returning the same instance unchanged when it already matches. Kept beside the ledgers it
 *       governs (a stream only ever attaches a {@link FrameLedger} while {@code trace} is {@code
 *       true}) rather than left inlined on the pipeline itself.</li>
 * </ul>
 *
 * <p>{@link StreamPipeline#config} itself — the field {@link #withTraceDemand}'s result gets
 * assigned back onto — deliberately stays on {@link StreamPipeline}: it is that class's own
 * live-swappable state (every other hot-knob field reads it directly), not something this
 * collaborator should own or mutate on {@link StreamPipeline}'s behalf.
 */
final class PipelineTrace {

    private final FrameGateLedger gateLedger;
    private final FrameLedgerRing frameLedger;

    /**
     * @param settings supplies {@link StreamPipelineSettings#gateLedgerDepth()}/{@link
     *                 StreamPipelineSettings#frameLedgerDepth()} — deployment-configured ring
     *                 depths, never a literal here (no magic numbers)
     */
    PipelineTrace(StreamPipelineSettings settings) {
        Objects.requireNonNull(settings, "settings must not be null");
        this.gateLedger = new FrameGateLedger(settings.gateLedgerDepth());
        this.frameLedger = new FrameLedgerRing(settings.frameLedgerDepth());
    }

    /**
     * Records a {@link GateOutcome#SKIPPED} entry for {@code frame}'s deadline — {@link
     * StreamPipeline#maybeDetect}/{@link StreamPipeline#submitDetection}'s own shorthand for the
     * common case; building the {@link GateDecision} is this ledger's own concern, not the
     * pipeline's, so it lives here rather than on the pipeline.
     */
    void recordSkip(VideoFrame frame, DemandSnapshot demand, GateReason reason) {
        gateLedger.record(new GateDecision(frame.sequence(), frame.capturedAt(), GateOutcome.SKIPPED, reason, demand));
    }

    /** A {@link GateOutcome#SENT}/{@link GateOutcome#PROBE} entry for {@code frame}; see {@link #recordSkip}. */
    void recordSent(VideoFrame frame, DemandSnapshot demand, boolean isProbe) {
        gateLedger.record(new GateDecision(frame.sequence(), frame.capturedAt(),
                isProbe ? GateOutcome.PROBE : GateOutcome.SENT, null, demand));
    }

    /** @see StreamPipeline#onDetectionResult */
    void recordFrameLedger(FrameLedger ledger) {
        frameLedger.record(ledger);
    }

    /** @see StreamPipeline#gateLedger(int) */
    List<GateDecision> gateLedger(int last) {
        return gateLedger.recent(last);
    }

    /** @see StreamPipeline#frameLedger(int) */
    List<FrameLedger> frameLedger(int last) {
        return frameLedger.recent(last);
    }

    /** @see StreamPipeline#clearDetectionDerivedState() */
    void clear() {
        gateLedger.clear();
        frameLedger.clear();
    }

    /**
     * Rebuilds {@code current} with {@link PipelineConfig#trace()} set to {@code wanted}, or returns
     * {@code current} unchanged (same instance) when it already matches — {@link
     * StreamPipeline#updateTraceDemand}'s own fold, moved here verbatim (byte-identical logic and
     * field order) so it sits beside the ledgers it governs.
     *
     * @param current the pipeline's current config
     * @param wanted  whether trace capture is currently demanded
     * @return {@code current} unchanged if {@code current.trace() == wanted}, otherwise a new
     *         {@link PipelineConfig} with every other field carried through unchanged
     */
    static PipelineConfig withTraceDemand(PipelineConfig current, boolean wanted) {
        if (current.trace() == wanted) {
            return current;
        }
        return new PipelineConfig(current.model(), current.confidenceThreshold(), current.inferenceFps(),
                current.maxInFlightInferences(), current.labelFilter(), current.eventRule(),
                current.detectionEnabled(), current.tracking(), current.labelDenyFilter(), wanted);
    }
}
