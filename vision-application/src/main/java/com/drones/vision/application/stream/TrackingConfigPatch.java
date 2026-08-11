package com.drones.vision.application.stream;

import com.drones.vision.perception.domain.model.TargetLock;
import com.drones.vision.perception.domain.model.TrackingConfig;
import com.drones.vision.perception.domain.model.TrackingMode;

import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * A per-field, partial statement about a stream's {@link TrackingConfig} (docs/plans/done/TRACKING-PLAN.md
 * &sect;4.D): every component is nullable and {@code null} means <b>leave this knob unchanged</b> —
 * the same null-means-unchanged idiom {@link PipelineConfigPatch} uses for the rest of the pipeline
 * config, applied one level down.
 *
 * <p>This type exists because tracking is <b>eight independent knobs, not one value</b>. The UI
 * changes them one at a time ({@code {"tracking":{"engineId":"ncc"}}}, an independent verify-cadence
 * slider, an independent follow-fps slider, {@code {"tracking":{"lock":{"release":true}}}}), so a
 * fold that replaced the whole {@link TrackingConfig} made every patch silently reset the six knobs
 * it did not mention — changing the verify cadence and then the follow fps reverted the first.
 * Folding field-by-field is the fix, and it lives here rather than at the REST edge because the
 * running configuration is the application layer's state: a controller reconstructing it from a read
 * model can only ever guess.
 *
 * <h2>The two folds, and the layering they express</h2>
 * {@link #foldOnto} is used at both ends of a stream's life, which is what makes the configuration
 * precedence of docs/extracts/TRACKING-ORCHESTRATION.md &sect;4.1 — <b>per-stream request &gt; deployment env
 * &gt; code default</b> — literal in code rather than prose:
 *
 * <ul>
 *   <li><b>Starting a stream</b> — {@code request.foldOnto(deploymentSeed.foldOnto(codeDefault))}.
 *       The deployment seed ({@code vision.tracking.*}) is itself one of these patches, stating only
 *       the knobs a deployment actually owns; the rest fall through to {@link TrackingConfig}'s own
 *       literals in {@code vision-domain}, so one number never has two owners.</li>
 *   <li><b>Patching a running stream</b> — {@code request.foldOnto(runningConfig)}. Nothing else is
 *       touched, so a cadence tweak never drops the operator's target, switches tracking off, or
 *       resets the engine.</li>
 * </ul>
 *
 * <h2>The lock is not an ordinary knob</h2>
 * {@code lock == null} means "leave whatever lock the stream is holding alone", never "no lock" —
 * dropping a target is the explicit {@code release} form of {@link TargetLock}, never an omission.
 * A present lock has its {@code lockSeq} <b>discarded and replaced</b> with a freshly allocated
 * value: clients never allocate one, which is what stops a replayed stale lock from resurrecting an
 * abandoned target, and it keeps the UI free of a counter it has no business knowing. Callers
 * building this record should leave it {@code 0}.
 *
 * <h2>Validation</h2>
 * Deliberately none here: an out-of-range cadence is rejected by {@link TrackingConfig}'s own
 * compact constructor when the fold produces one, so the rule lives in exactly one place and
 * surfaces at the REST edge as a 400 either way.
 *
 * @param mode               replacement mode, or {@code null} to keep the current one
 * @param engineId           replacement tracker engine id, or {@code null} to keep the current one;
 *                           {@code ""} is a real value meaning "the server's default for the mode"
 * @param verifyEveryMillis  replacement {@code FOLLOW} re-verify cadence, or {@code null}
 * @param followFps          replacement Java-side sampler rate for {@code FOLLOW}, or {@code null}
 * @param redetectIouPercent replacement re-anchor threshold percent, or {@code null}
 * @param maxAgeFrames       replacement unmatched-frame budget, or {@code null}
 * @param minHits            replacement hits-to-confirm budget, or {@code null}
 * @param lock               a new target lock, or {@code null} to keep the running one; see this
 *                           record's own javadoc for why absence is never a release
 */
public record TrackingConfigPatch(TrackingMode mode, String engineId, Integer verifyEveryMillis, Integer followFps,
                                   Integer redetectIouPercent, Integer maxAgeFrames, Integer minHits,
                                   TargetLock lock) {

    /** A patch that changes nothing — the identity of {@link #foldOnto}. */
    public static final TrackingConfigPatch NOTHING =
            new TrackingConfigPatch(null, null, null, null, null, null, null, null);

    /**
     * Folds this patch's present fields onto {@code current}, leaving every absent one exactly as
     * {@code current} has it.
     *
     * @param current      the configuration an absent field falls back to; never {@code null}
     * @param lockSequence the stream's monotonic lock-sequence allocator, consulted <b>only</b> when
     *                     this patch carries a lock — so a patch without one burns no number and the
     *                     sequence stays readable in a log; never {@code null}
     * @return the folded configuration
     * @throws IllegalArgumentException if a folded value fails {@link TrackingConfig}'s own
     *                                   validation (an out-of-range cadence, an impossible lock form)
     */
    public TrackingConfig foldOnto(TrackingConfig current, LongSupplier lockSequence) {
        Objects.requireNonNull(current, "current must not be null");
        Objects.requireNonNull(lockSequence, "lockSequence must not be null");
        return new TrackingConfig(
                mode == null ? current.mode() : mode,
                engineId == null ? current.engineId() : engineId,
                verifyEveryMillis == null ? current.verifyEveryMillis() : verifyEveryMillis,
                followFps == null ? current.followFps() : followFps,
                redetectIouPercent == null ? current.redetectIouPercent() : redetectIouPercent,
                maxAgeFrames == null ? current.maxAgeFrames() : maxAgeFrames,
                minHits == null ? current.minHits() : minHits,
                lock == null ? current.lock()
                        : new TargetLock(lockSequence.getAsLong(), lock.trackId(), lock.pointX(), lock.pointY(),
                                lock.release()));
    }
}
