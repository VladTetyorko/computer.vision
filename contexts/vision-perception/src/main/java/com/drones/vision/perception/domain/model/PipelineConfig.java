package com.drones.vision.perception.domain.model;

import java.util.Set;

/**
 * Per-stream configuration for a running pipeline.
 *
 * <p>{@code inferenceFps} governs frame sampling: the application layer
 * samples every Nth frame for inference while the full-FPS video passes
 * through untouched. {@code maxInFlightInferences} bounds concurrent calls
 * to {@code DetectionPort} so that a slow CV service can never stall the
 * video path — the application layer skips sampled frames rather than
 * queuing them once the bound is reached. {@code labelFilter} is
 * defensively copied to an immutable set; an empty set means "all labels".
 *
 * <p>{@code eventRule} (docs/plans/done/MVP2-PLAN.md §E, E-a) governs debounced {@link
 * DetectionEvent} tracking — the rule engine consuming this pipeline's
 * results decides, independently of {@code labelFilter}, when a label's
 * streak of qualifying results opens/closes an event; see {@link
 * EventRuleConfig}.
 *
 * <p>{@code labelDenyFilter} (docs/plans/done/CV-CLEAN-FEED-PLAN.md &sect;2, D-2) is the
 * operator-facing "hide this class" act: a detection whose label matches this set is dropped
 * <b>even when</b> {@code labelFilter} would otherwise keep it. The two sets answer different
 * questions — {@code labelFilter}, when non-empty, is the model-intent allowlist seed ("only
 * these labels ever exist for this stream"); {@code labelDenyFilter} is the everyday "stop
 * showing me trees" act, and writing to it never touches the allowlist, so classes not yet
 * observed keep appearing instead of being silently swept into an enumerated complement (the
 * allowlist-only defect docs/plans/done/CV-UX-RESEARCH.md &sect;4.4 diagnosed). Defensively
 * copied to an immutable set; empty means "deny nothing".
 *
 * <p>{@code detectionEnabled} (docs/plans/done/CV-CONTROL-PLAN.md §1, Wave B) is the
 * per-stream detection on/off switch: {@code false} means the pipeline skips
 * {@code detect()} entirely — no {@code DetectionPort} calls are made, so
 * detection costs zero CPU — while video keeps flowing at full rate,
 * untouched. Re-enabling resumes detection on the next sampled frame. The
 * skip itself is enforced by the application layer's {@code StreamPipeline}
 * (Wave C); this record only carries the flag. Since docs/plans/done/CV-DEMAND-PLAN.md &sect;1
 * (wave D1) this is one of <em>two</em> independent gates {@code StreamPipeline} ANDs together —
 * see {@code DetectionDemandPort} for the other, system-derived half — and {@link
 * #DEFAULT_DETECTION_ENABLED} flipped to {@code false}: a stream is opt-in, not opt-out.
 *
 * <p>{@code tracking} (docs/plans/done/TRACKING-PLAN.md §4.B) is this stream's {@link TrackingConfig} —
 * mode, engine, duty-cycle cadences, and an optional {@link TargetLock}. {@link #defaults()}
 * returns {@link TrackingConfig#defaults()} ({@code ASSOCIATE}): a new stream tracks by default,
 * so every detection carries a stable {@code trackId}. The <em>convenience</em> constructors below
 * deliberately keep defaulting to {@link TrackingConfig#off()} — they exist so call sites written
 * before tracking existed compile <em>and behave</em> unchanged, which is a different question from
 * what a new stream should do.
 *
 * @param model                  model to run
 * @param confidenceThreshold    minimum confidence to keep a detection, range [0,1]
 * @param inferenceFps           target inference sample rate; must be positive
 * @param maxInFlightInferences  max concurrent in-flight {@code DetectionPort} calls; must be positive
 * @param labelFilter            labels to keep; empty means all labels; defensively copied
 * @param eventRule              debounce settings for {@link DetectionEvent} tracking
 * @param detectionEnabled       whether the pipeline runs detection at all; {@code false} skips
 *                               {@code detect()} entirely while video keeps flowing
 * @param tracking               per-stream tracking configuration; never {@code null} —
 *                               {@link TrackingConfig#off()} is how "no tracking" is spelled
 * @param labelDenyFilter        labels to drop even when {@code labelFilter} would keep them;
 *                               empty means deny nothing; defensively copied
 * @param trace                  per-stream demand for the warm trace tier
 *                               (docs/plans/active/CV-ORCHESTRATION-PLAN.md §4.4) — {@code true}
 *                               attaches the frame ledger to the wire response; {@code false} at
 *                               every call site in this wave. Wave W2's {@code TraceDemand} is
 *                               what will actually drive this from an open inspector; nothing in
 *                               this wave wires it to any UI toggle
 */
public record PipelineConfig(ModelRef model, double confidenceThreshold, int inferenceFps,
                              int maxInFlightInferences, Set<String> labelFilter,
                              EventRuleConfig eventRule, boolean detectionEnabled,
                              TrackingConfig tracking, Set<String> labelDenyFilter, boolean trace) {

    /**
     * Default for {@link #detectionEnabled()} on every N-1-arg convenience constructor, and what
     * {@link #defaults()} itself starts a new stream at — {@code false}
     * (docs/plans/done/CV-DEMAND-PLAN.md &sect;1, wave D1, which flipped this constant from its
     * original {@code true}). Detection is opt-in per stream now, not opt-out: a deployment running
     * many concurrent streams can leave every one of them video-only, at effectively zero CV cost,
     * until an operator turns detection on for the ones they actually want to look at. A deployment
     * that wants the old always-on behavior back can restore it deployment-wide via {@code
     * vision.cv.detection-default-enabled=true} (wave D2) without touching this constant.
     */
    public static final boolean DEFAULT_DETECTION_ENABLED = false;

    public PipelineConfig {
        if (model == null) {
            throw new IllegalArgumentException("PipelineConfig model must not be null");
        }
        if (Double.isNaN(confidenceThreshold) || confidenceThreshold < 0.0 || confidenceThreshold > 1.0) {
            throw new IllegalArgumentException(
                    "PipelineConfig confidenceThreshold must be within [0,1]: " + confidenceThreshold);
        }
        if (inferenceFps <= 0) {
            throw new IllegalArgumentException("PipelineConfig inferenceFps must be positive: " + inferenceFps);
        }
        if (maxInFlightInferences <= 0) {
            throw new IllegalArgumentException(
                    "PipelineConfig maxInFlightInferences must be positive: " + maxInFlightInferences);
        }
        if (labelFilter == null) {
            throw new IllegalArgumentException("PipelineConfig labelFilter must not be null");
        }
        if (eventRule == null) {
            throw new IllegalArgumentException("PipelineConfig eventRule must not be null");
        }
        if (tracking == null) {
            throw new IllegalArgumentException("PipelineConfig tracking must not be null");
        }
        if (labelDenyFilter == null) {
            throw new IllegalArgumentException("PipelineConfig labelDenyFilter must not be null");
        }
        labelFilter = Set.copyOf(labelFilter);
        labelDenyFilter = Set.copyOf(labelDenyFilter);
    }

    /**
     * Convenience constructor for callers that don't care about {@link #labelDenyFilter()} —
     * defaults it to an empty set ("deny nothing"), the same "N-1-arg convenience ctor" idiom used
     * elsewhere. This was the canonical constructor before docs/plans/done/CV-CLEAN-FEED-PLAN.md
     * &sect;2 added {@link #labelDenyFilter()}; every pre-existing 8-arg call site compiles
     * <em>and behaves</em> unchanged. Also defaults {@link #trace()} to {@code false}
     * (docs/plans/active/CV-ORCHESTRATION-PLAN.md §4.4, wave W1) — this constructor predates
     * tracing entirely, and off is what every pre-existing call site already behaves as.
     */
    public PipelineConfig(ModelRef model, double confidenceThreshold, int inferenceFps, int maxInFlightInferences,
                           Set<String> labelFilter, EventRuleConfig eventRule, boolean detectionEnabled,
                           TrackingConfig tracking) {
        this(model, confidenceThreshold, inferenceFps, maxInFlightInferences, labelFilter, eventRule,
                detectionEnabled, tracking, Set.of(), false);
    }

    /**
     * Convenience constructor for callers that don't care about {@link #tracking()} — defaults it
     * to {@link TrackingConfig#off()}, the same "N-1-arg convenience ctor" idiom used elsewhere.
     * This was the canonical constructor before docs/plans/done/TRACKING-PLAN.md §4.B added {@link
     * #tracking()}; every pre-existing (pre-clean-feed) 7-arg call site compiles <em>and behaves</em>
     * unchanged.
     *
     * <p><strong>{@code off()} here, {@code ASSOCIATE} in {@link #defaults()}, deliberately.</strong>
     * A convenience constructor's contract is "the component you did not mention keeps the value it
     * had before the component existed"; {@link #defaults()}'s contract is "what a new stream
     * should be". Those are different questions, and answering the first one with the second would
     * silently turn tracking on for every caller that merely predates the field. {@link #trace()}
     * defaults to {@code false} for the same "predates the field" reason.
     */
    public PipelineConfig(ModelRef model, double confidenceThreshold, int inferenceFps, int maxInFlightInferences,
                           Set<String> labelFilter, EventRuleConfig eventRule, boolean detectionEnabled) {
        this(model, confidenceThreshold, inferenceFps, maxInFlightInferences, labelFilter, eventRule,
                detectionEnabled, TrackingConfig.off());
    }

    /**
     * Convenience constructor for callers that don't care about {@link #detectionEnabled()} —
     * defaults it to {@link #DEFAULT_DETECTION_ENABLED} (unchanged behavior), the same "N-1-arg
     * convenience ctor" idiom used elsewhere ({@code Asset}'s 6-arg ctor, {@code AssetUsage}'s
     * 7-arg ctor, this record's own ctors above), chaining onto the 7-arg convenience ctor above
     * (so {@link #tracking()} also defaults to {@link TrackingConfig#off()}, and {@link #trace()}
     * to {@code false}). This was the canonical constructor before docs/plans/done/CV-CONTROL-PLAN.md
     * Wave B added {@link #detectionEnabled()}; every pre-existing 6-arg call site compiles unchanged.
     */
    public PipelineConfig(ModelRef model, double confidenceThreshold, int inferenceFps, int maxInFlightInferences,
                           Set<String> labelFilter, EventRuleConfig eventRule) {
        this(model, confidenceThreshold, inferenceFps, maxInFlightInferences, labelFilter, eventRule,
                DEFAULT_DETECTION_ENABLED);
    }

    /**
     * Convenience constructor for callers that don't care about {@link #eventRule()} either —
     * defaults it to {@link EventRuleConfig#defaults()}, chaining onto the 6-arg convenience ctor
     * above (so {@link #trace()} also defaults to {@code false}, transitively).
     */
    public PipelineConfig(ModelRef model, double confidenceThreshold, int inferenceFps, int maxInFlightInferences,
                           Set<String> labelFilter) {
        this(model, confidenceThreshold, inferenceFps, maxInFlightInferences, labelFilter,
                EventRuleConfig.defaults());
    }

    /**
     * Reasonable defaults for a new stream: the {@code "yolo26n.pt"} model
     * (docs/plans/done/CV-CONTROL-PLAN.md §1, Wave B — the real checkpoint id cv-service
     * already falls back to; the previous {@code "yolo"} id matched no actual
     * checkpoint and relied on that silent fallback), a 0.4 confidence
     * threshold, 10 FPS inference sampling, at most 2 in-flight inference
     * calls, no label filtering (all labels kept), no label denying,
     * {@link EventRuleConfig#defaults()}, detection <strong>disabled</strong>, and tracking
     * {@link TrackingConfig#defaults() ASSOCIATE}.
     *
     * <p><strong>Detection defaults to {@code false}</strong> (docs/plans/done/CV-DEMAND-PLAN.md &sect;1,
     * wave D1 — the plan's own flip of {@link #DEFAULT_DETECTION_ENABLED}): a new stream is
     * video-only, at zero CV cost, until an operator deliberately turns detection on for it. This is
     * what makes running many concurrent streams affordable — a stream nobody has switched on never
     * calls {@code detect()} at all, regardless of how many others are also idle-but-running.
     * Turning detection on is a deliberate act, exactly like tracking below: per stream via {@code
     * PATCH /api/streams/{id}/config}, or per deployment via {@code
     * vision.cv.detection-default-enabled=true} (wave D2) to restore the pre-D1 always-on default.
     *
     * <p><strong>Tracking defaults to {@link TrackingConfig#defaults()} ({@code ASSOCIATE}), not
     * {@link TrackingConfig#off()}</strong> — docs/plans/done/TRACKING-PLAN.md §5.G, the one behavior change
     * wave T8 exists for. Every detection on a stream started from these defaults therefore carries
     * a stable {@code trackId} across frames, which is what S2 geolocation trails, click-to-follow
     * and cross-sensor fusion are all blocked on. Turning tracking off is now a deliberate act:
     * per stream via {@code PATCH /api/streams/{id}/config}, or per deployment via {@code
     * vision.tracking.default-mode=OFF} (vision-app) — there is no JVM-wide tracking flag, because
     * {@code vision.cv.enabled} already kills CV wholesale and a per-stream switch is strictly
     * better than a global one.
     *
     * <p><strong>{@link #trace()} defaults to {@code false}</strong>
     * (docs/plans/active/CV-ORCHESTRATION-PLAN.md §4.4, wave W1) — a new stream carries no
     * inspector demand until one actually opens; wave W2's {@code TraceDemand} is what flips this.
     *
     * @return a default {@code PipelineConfig}
     */
    public static PipelineConfig defaults() {
        return new PipelineConfig(new ModelRef("yolo26n.pt", "latest"), 0.4, 10, 2, Set.of(),
                EventRuleConfig.defaults(), DEFAULT_DETECTION_ENABLED, TrackingConfig.defaults(), Set.of(), false);
    }
}
