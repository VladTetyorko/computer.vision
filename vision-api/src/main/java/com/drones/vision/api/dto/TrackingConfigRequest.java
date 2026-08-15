package com.drones.vision.api.dto;

import com.drones.vision.perception.application.stream.TrackingConfigPatch;
import com.drones.vision.perception.domain.model.TrackingConfig;
import com.drones.vision.perception.domain.model.TrackingMode;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * The {@code tracking} object accepted by {@code PATCH /api/streams/{streamId}/config} and by both
 * start-stream request bodies (docs/plans/done/TRACKING-PLAN.md &sect;4.D's frozen wire contract).
 *
 * <p>Every field is independently optional, and this record maps <b>one-for-one</b> onto the
 * application layer's {@link TrackingConfigPatch}: a JSON field that is absent becomes {@code null},
 * which means "leave this knob unchanged". <b>Nothing is merged here</b> — this edge does not know
 * what a running stream is configured with, and the layer that does folds each field itself:
 *
 * <ul>
 *   <li><b>On a start request</b> ({@link #toStartPatch()}) the patch is folded onto the deployment
 *       seed ({@code vision.tracking.*}) and then the domain's defaults, so a new stream starts on
 *       the deployment's values with whatever the client stated on top. A {@code lock} is
 *       <b>rejected</b> outright: it names a track that cannot exist before the stream produces one
 *       (&sect;4.D).</li>
 *   <li><b>On a PATCH</b> ({@link #toPatch()}) the patch is folded onto the stream's own running
 *       configuration, so an unmentioned knob keeps the value the operator gave it.</li>
 * </ul>
 *
 * <p>{@code lock}'s absence never means "release" — it means "leave whatever lock the stream is
 * holding alone" ({@link TrackingConfigPatch}'s own javadoc). Dropping a lock is the explicit {@code
 * release} form, never an omission.
 *
 * <p>{@code redetectIouPercent} is an {@code int} percent [0,100], not a fraction — mirroring the
 * domain's own {@link TrackingConfig}; the conversion to the proto's {@code float} happens in
 * adapter-cv-grpc, never here. Range/positivity validation is deliberately not duplicated in this
 * record: {@link TrackingConfig}'s own compact constructor rejects an out-of-range value as an
 * {@link IllegalArgumentException} &rarr; 400, the same idiom every other DTO here follows.
 *
 * @param mode               {@code "OFF"}/{@code "ASSOCIATE"}/{@code "FOLLOW"}, matched
 *                           case-insensitively; anything else is a 400
 * @param engineId           tracker engine id from {@code GET /api/cv/trackers}; {@code ""} means
 *                           "the server's default for the mode"
 * @param verifyEveryMillis  {@code FOLLOW} detector re-verify cadence, milliseconds
 * @param followFps          the Java-side sampler's target rate while {@code FOLLOW} is active
 * @param redetectIouPercent   re-anchor threshold, percent [0,100]
 * @param maxAgeFrames         unmatched frames before a track goes {@code LOST}
 * @param minHits              detector hits needed to confirm a new track
 * @param capabilityLevel      the capability-ladder ceiling requested for this stream
 *                             (docs/plans/active/TRACKING-V3-BAND1-CONTEXT.md &sect;2), [0,5]; {@code
 *                             0} = auto-probe; absent leaves the running/seeded ceiling untouched.
 *                             <b>A ceiling, not a demand</b> (invariant B5): cv-service serves {@code
 *                             min(requested, affordable)}, never a refusal, and this field is never
 *                             what the response should be read as — {@link
 *                             FrameTrackingResponse#capability()} is the only source of truth for
 *                             what actually ran
 * @param reupdateMaxGapMillis the longest gap ORU may reconstruct, milliseconds; {@code 0} = server
 *                             default; absent leaves the running/seeded value untouched
 * @param lock                 which object {@code FOLLOW} should hold; absent leaves the running lock
 *                             untouched, and is rejected outright on a start request
 */
public record TrackingConfigRequest(String mode, String engineId, Integer verifyEveryMillis, Integer followFps,
                                     Integer redetectIouPercent, Integer maxAgeFrames, Integer minHits,
                                     Integer capabilityLevel, Integer reupdateMaxGapMillis,
                                     TargetLockRequest lock) {

    /**
     * Maps this request to the application-level tracking patch, field for field.
     *
     * @return what this request states about tracking; absent fields stay {@code null}
     * @throws IllegalArgumentException if {@code mode} is not a known mode, or the lock is not
     *                                  exactly one of its three forms (&rarr; 400)
     */
    public TrackingConfigPatch toPatch() {
        return new TrackingConfigPatch(mode == null ? null : parseMode(mode), engineId, verifyEveryMillis, followFps,
                redetectIouPercent, maxAgeFrames, minHits, capabilityLevel, reupdateMaxGapMillis,
                lock == null ? null : lock.toTargetLock());
    }

    /**
     * {@link #toPatch()} for a stream that does not exist yet: identical, except a {@code lock} is
     * refused rather than silently ignored (docs/plans/done/TRACKING-PLAN.md &sect;4.D — the start bodies carry
     * "the same shape minus {@code lock}").
     *
     * @return what this start request states about tracking
     * @throws IllegalArgumentException if a {@code lock} is present (&rarr; 400), plus every case
     *                                  {@link #toPatch()} throws for
     */
    public TrackingConfigPatch toStartPatch() {
        if (lock != null) {
            throw new IllegalArgumentException(
                    "tracking.lock cannot be set when starting a stream: it names a track that does not exist yet");
        }
        return toPatch();
    }

    /**
     * Case-insensitive {@link TrackingMode} lookup, listing the valid values on failure — the same
     * idiom {@code CapabilityParsing}/{@code SetLifecycleStateRequest} already use.
     */
    private static TrackingMode parseMode(String value) {
        return Arrays.stream(TrackingMode.values())
                .filter(candidate -> candidate.name().equalsIgnoreCase(value.trim()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown tracking mode: " + value + ". Valid values: "
                        + Arrays.stream(TrackingMode.values()).map(Enum::name).collect(Collectors.joining(", "))));
    }
}
