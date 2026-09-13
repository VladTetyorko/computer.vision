package com.drones.vision.perception.application.pipeline;

import com.drones.vision.perception.domain.model.PipelineConfig;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The three detection gates and {@code StreamPipeline#maybeDetect}'s frame-classification decision,
 * as one collaborator (docs/plans/active/CV-ORCHESTRATION-PLAN.md &sect;4.9/K3, wave W8.2
 * extraction; the gate vocabulary itself is docs/plans/active/ALWAYS-ON-FLOW-PLAN.md &sect;4 "the
 * gate is three questions, not two") — built directly inside {@link StreamPipeline}'s constructor
 * from its very first {@link PipelineConfig}, exactly like {@link FrameSampler}/{@link
 * OutageSupervisor} are (java-clean-code &sect;3: no new constructor overload on {@link
 * StreamPipeline}).
 *
 * <h2>What stays on {@link StreamPipeline}, and why</h2>
 * <ul>
 *   <li>{@link PipelineConfig} itself — every gate method below takes it as a parameter rather than
 *       storing it, the same reason {@link PipelineTrace}'s javadoc gives: it is {@link
 *       StreamPipeline}'s own live-swappable state, not something a collaborator should hold a
 *       possibly-stale copy of.</li>
 *   <li>The transition <b>side effects</b> — {@code clearDetectionDerivedState()}/{@code
 *       clearLiveDerivedState()} and whatever a future wave adds for "the state changed" — stay on
 *       {@link StreamPipeline}, driven by the {@link Edge} {@link #handleTransition} reports. This
 *       class only ever decides <i>which</i> edge fired.</li>
 *   <li>{@link GateReason#CV_UNAVAILABLE} is <b>not</b> producible by {@link #classify} — it is
 *       decided later, by {@code StreamPipeline#submitDetection} peeking whether the {@link
 *       com.drones.vision.perception.domain.port.DetectionPort#detect} call it just made is already
 *       completed exceptionally (docs/plans/active/CV-ORCHESTRATION-PLAN.md &sect;4.4). Folding that
 *       peek in here would mean this class calling {@code detectionPort.detect} itself, which is a
 *       behavior change this wave does not make; {@link #classify} therefore returns one of the
 *       other six {@link GateReason}s.</li>
 * </ul>
 */
final class DetectionGate {

    /**
     * Detection <b>demand</b> (docs/plans/done/CV-DEMAND-PLAN.md &sect;1, &sect;3.2) — the
     * system-derived "someone is actually consuming the output" gate, independent of {@link
     * PipelineConfig#detectionEnabled()}'s operator-intent gate. Initializes to {@code true} —
     * fail-open, so a pipeline whose {@code DefaultStreamService} was never given a {@code
     * DetectionDemandPort} never has this field written at all, and {@link #classify} gates on
     * {@link PipelineConfig#detectionEnabled()} alone, exactly as before this gate existed.
     */
    private volatile boolean detectionDemand = true;

    /**
     * The per-asset {@code DetectionPolicy.ALWAYS} opt-in (docs/plans/active/ALWAYS-ON-FLOW-PLAN.md
     * wave D1) — a third, independent OR-term widening {@link #detectionGateOpen} beyond {@link
     * #detectionDemand}, deliberately <b>not</b> read by {@link #liveGateOpen}. Initializes to
     * {@code false} — fail <b>closed</b>, the opposite direction from {@link #detectionDemand}'s
     * fail-open {@code true} (see docs/plans/active/ALWAYS-ON-FLOW-PLAN.md &sect;3: nothing bounds
     * concurrent inference across streams, so {@code ALWAYS} must stay strictly opt-in).
     */
    private volatile boolean detectionPolicyAlwaysOn = false;

    /**
     * Guards {@link #gateWasOpen}/{@link #liveGateWasOpen}: {@code StreamPipeline#updateConfig}
     * (operator intent, an HTTP-request thread) and {@link #updateDetectionDemand}/{@link
     * #updateDetectionPolicy} (both {@code DefaultStreamService}'s demand-poll scheduler thread) can
     * each close one or both gates, so the read-compare-write in {@link #handleTransition} needs to
     * be atomic across all three callers.
     */
    private final Object gateLock = new Object();

    /**
     * Last observed value of {@link #detectionGateOpen} (the inference/durable gate), read/written
     * only under {@link #gateLock}. Seeded in the constructor from the pipeline's very first {@link
     * PipelineConfig} so the first genuine open&rarr;closed edge — not construction itself — is what
     * triggers the first clear.
     */
    private boolean gateWasOpen;

    /**
     * Last observed value of {@link #liveGateOpen} (the live-plane gate), read/written only under
     * {@link #gateLock}. Tracked separately from {@link #gateWasOpen} because the two gates can
     * close on different edges — see {@link #handleTransition}.
     */
    private boolean liveGateWasOpen;

    /**
     * @param initialConfig the pipeline's very first {@link PipelineConfig} — seeds {@link
     *                       #gateWasOpen}/{@link #liveGateWasOpen} so construction itself is never
     *                       mistaken for a close
     */
    DetectionGate(PipelineConfig initialConfig) {
        Objects.requireNonNull(initialConfig, "initialConfig must not be null");
        this.gateWasOpen = detectionGateOpen(initialConfig);
        this.liveGateWasOpen = liveGateOpen(initialConfig);
    }

    /** @see StreamPipeline#detectionDemand() */
    boolean detectionDemand() {
        return detectionDemand;
    }

    /** @see StreamPipeline#updateDetectionDemand(boolean) */
    void updateDetectionDemand(boolean demanded) {
        this.detectionDemand = demanded;
    }

    /** @see StreamPipeline#detectionPolicyAlwaysOn() */
    boolean detectionPolicyAlwaysOn() {
        return detectionPolicyAlwaysOn;
    }

    /** @see StreamPipeline#updateDetectionPolicy(boolean) */
    void updateDetectionPolicy(boolean alwaysOn) {
        this.detectionPolicyAlwaysOn = alwaysOn;
    }

    /**
     * The <b>inference</b> gate (docs/plans/active/ALWAYS-ON-FLOW-PLAN.md &sect;4 "the gate is three
     * questions, not two") — {@link PipelineConfig#detectionEnabled()} (the operator's own per-stream
     * choice) <b>and</b> ({@link #detectionDemand} (the system-derived "someone is actually
     * watching" fact) <b>or</b> {@link #detectionPolicyAlwaysOn} (this asset's {@code
     * DetectionPolicy.ALWAYS} opt-in)). A strict superset of the pre-D2 single gate — an asset that
     * has never opted into {@code ALWAYS} sees {@code detectionPolicyAlwaysOn} permanently {@code
     * false}, so this collapses back to exactly {@link #liveGateOpen} and nothing that infers today
     * stops inferring.
     *
     * <p>Persistence, the {@code DETECTION} platform event and {@code DetectionEventEngine} — the
     * <b>durable</b> plane — gate on this same method inside {@code StreamPipeline#onDetectionResult}
     * ("durable follows inference"); the narrower {@link #liveGateOpen} governs only the live read
     * models a viewer's screen reads.
     */
    boolean detectionGateOpen(PipelineConfig config) {
        return config.detectionEnabled() && (detectionDemand || detectionPolicyAlwaysOn);
    }

    /**
     * The <b>live</b> gate (docs/plans/active/ALWAYS-ON-FLOW-PLAN.md &sect;4) — exactly the pre-D2
     * single gate, unchanged: {@link PipelineConfig#detectionEnabled()} <b>and</b> {@link
     * #detectionDemand} alone, deliberately never widened by {@link #detectionPolicyAlwaysOn}. A
     * {@code DetectionPolicy.ALWAYS} asset's whole point is inference that outlives its last viewer,
     * which means this narrower gate is exactly what must still be able to close for it.
     */
    boolean liveGateOpen(PipelineConfig config) {
        return config.detectionEnabled() && detectionDemand;
    }

    /** Which state {@link #handleTransition} found stale, if any — see that method's own javadoc. */
    enum Edge {
        /** Neither gate took a true&rarr;false edge this call. */
        NONE,
        /** {@link #detectionGateOpen} closed — the full clear. */
        INFERENCE_CLOSED,
        /** {@link #liveGateOpen} closed while {@link #detectionGateOpen} stayed open — the live-only clear. */
        LIVE_CLOSED
    }

    /**
     * Detects a true&rarr;false transition of {@link #detectionGateOpen} (inference/durable) and of
     * {@link #liveGateOpen} (live) independently against each gate's own last-observed value ({@link
     * #gateWasOpen}/{@link #liveGateWasOpen}), not "which setter ran" — {@code
     * StreamPipeline#updateConfig} is called for plain confidence/fps/label-filter patches too, and
     * {@link #updateDetectionDemand}/{@link #updateDetectionPolicy} are called on every demand-poll
     * tick regardless of whether anything actually changed, so only a genuine open&rarr;closed
     * <i>edge</i> may report anything — a repeated {@code false} must be a no-op here, not a
     * re-clearing thrash.
     *
     * <p><b>Two edges, checked in this order, deliberately not both:</b> {@link #gateWasOpen}
     * true&rarr;false (inference closes) reports {@link Edge#INFERENCE_CLOSED} — inference itself is
     * stopping, so nothing downstream of it, live <em>or</em> durable, will get a fresher answer
     * until it reopens; this is the only edge an asset that has never opted into {@code ALWAYS} can
     * ever take. Otherwise, {@link #liveGateWasOpen} true&rarr;false (live closes while inference
     * stays open) reports {@link Edge#LIVE_CLOSED} — reachable only for a {@code
     * DetectionPolicy.ALWAYS} asset losing its last viewer. When both gates close on the same call
     * (the ordinary, non-{@code ALWAYS} case), only {@link Edge#INFERENCE_CLOSED} is reported — the
     * caller's full clear already covers everything the live-only clear would.
     *
     * @param config the pipeline's current {@link PipelineConfig}, read once here alongside this
     *               object's own demand/policy fields
     * @return the edge the caller should act on, or {@link Edge#NONE}
     */
    Edge handleTransition(PipelineConfig config) {
        boolean open = detectionGateOpen(config);
        boolean liveOpen = liveGateOpen(config);
        synchronized (gateLock) {
            Edge edge;
            if (gateWasOpen && !open) {
                edge = Edge.INFERENCE_CLOSED;
            } else if (liveGateWasOpen && !liveOpen) {
                edge = Edge.LIVE_CLOSED;
            } else {
                edge = Edge.NONE;
            }
            gateWasOpen = open;
            liveGateWasOpen = liveOpen;
            return edge;
        }
    }

    /**
     * {@code StreamPipeline#maybeDetect}'s three-way classification, unchanged in substance: gated
     * first on pull mode, then on {@link #detectionGateOpen}'s two component checks (each producing
     * its own {@link GateReason} so the trace names which failed), then on {@code
     * outageSupervisor}'s outage/probe state, then on the sample deadline and the in-flight bound.
     *
     * <p><b>Returns {@link Optional#empty()} for exactly one case</b>: an outage backoff deadline
     * that has not yet arrived. The original inline code left this frame entirely unrecorded — no
     * gate ledger entry, no rate-window entry — "so the counter stays comparable with the others:
     * one entry per deadline, not one per frame arriving during a ten-second backoff" (ported
     * comment). Every other branch returns a verdict.
     *
     * <p>{@code inFlightInferences} is read here but never mutated — the caller performs the actual
     * {@code incrementAndGet()} on a returned {@link GateVerdict.Send} with {@code probe == false},
     * synchronously, before this method's caller does anything else; since {@code maybeDetect} only
     * ever runs on one thread (this pipeline's own {@code Flow.Subscriber} signals are serialized),
     * moving the increment past this method's return is not observable.
     *
     * @param config                the pipeline's current {@link PipelineConfig}
     * @param now                   this frame's arrival timestamp (see {@link
     *                              FrameSampler#recordArrival()})
     * @param sampler               asked for {@link FrameSampler#sampleDue}, not owned
     * @param outageSupervisor      asked for {@link OutageSupervisor#outageDecision()}, not owned
     * @param effectiveInferenceFps {@code StreamPipeline#effectiveInferenceFps()}'s current value,
     *                              threaded through to {@code sampler}
     * @param rateController        asked, not owned — threaded through to {@code sampler}
     * @param detectionRate         asked, not owned — threaded through to {@code sampler} for its
     *                              missed-deadline count
     * @param inFlightInferences    read-only here; see above
     * @param pullDetection         {@code null} in push mode; non-{@code null} always yields {@link
     *                              GateReason#PULL_MODE} (the worker owns its own sampling loop)
     * @return the verdict for this frame, or {@link Optional#empty()} for the one silent case above
     */
    Optional<GateVerdict> classify(PipelineConfig config, long now, FrameSampler sampler,
                                    OutageSupervisor outageSupervisor, int effectiveInferenceFps,
                                    DetectionRateController rateController, DetectionRateWindow detectionRate,
                                    AtomicInteger inFlightInferences, PullDetectionBinding pullDetection) {
        boolean demand = detectionDemand;
        boolean alwaysOn = detectionPolicyAlwaysOn;
        DemandSnapshot snapshot = new DemandSnapshot(config.detectionEnabled(), demand, alwaysOn);
        if (pullDetection != null) {
            return Optional.of(new GateVerdict.Skip(GateReason.PULL_MODE, snapshot));
        }
        if (!config.detectionEnabled()) {
            return Optional.of(new GateVerdict.Skip(GateReason.GATE_OFF, snapshot));
        }
        if (!(demand || alwaysOn)) {
            return Optional.of(new GateVerdict.Skip(GateReason.GATE_NO_DEMAND, snapshot));
        }
        return switch (outageSupervisor.outageDecision()) {
            case PROBE ->
                // A probe is a LIVENESS check, not a sample: its cadence is the outage backoff, so it
                // deliberately ignores the sample deadline -- letting the sampler gate it too would make
                // two independent schedules interfere.
                    Optional.of(new GateVerdict.Send(true, snapshot));
            case SKIP ->
                // Consulted through the deadline so the rate-window counter stays comparable with the
                // others -- one entry per deadline, not one per frame arriving during a ten-second
                // backoff; every other frame in the backoff window produces no verdict at all.
                    sampler.sampleDue(now, rateController, effectiveInferenceFps, config.maxInFlightInferences(),
                            detectionRate)
                            ? Optional.of(new GateVerdict.Skip(GateReason.OUTAGE_BACKOFF, snapshot))
                            : Optional.empty();
            case NORMAL -> {
                if (!sampler.sampleDue(now, rateController, effectiveInferenceFps, config.maxInFlightInferences(),
                        detectionRate)) {
                    yield Optional.of(new GateVerdict.Skip(GateReason.DEADLINE_NOT_DUE, snapshot));
                }
                if (inFlightInferences.get() >= config.maxInFlightInferences()) {
                    // bounded in-flight: skip this sample rather than queue it
                    yield Optional.of(new GateVerdict.Skip(GateReason.IN_FLIGHT_FULL, snapshot));
                }
                yield Optional.of(new GateVerdict.Send(false, snapshot));
            }
        };
    }
}
