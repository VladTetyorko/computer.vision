package com.drones.vision.api.dto;

import com.drones.vision.domain.model.TrackingConfig;
import com.drones.vision.domain.model.TrackingMode;

import java.util.Arrays;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * The {@code tracking} object accepted by {@code PATCH /api/streams/{streamId}/config} and by both
 * start-stream request bodies (docs/TRACKING-PLAN.md &sect;4.D's frozen wire contract).
 *
 * <p>Every field is independently optional and <b>merges onto a base</b> ({@link
 * #toTrackingConfig(TrackingConfig)}): an absent field keeps the base's value. Which base is used
 * depends on the call:
 *
 * <ul>
 *   <li><b>On a start request</b> — the deployment seed ({@code vision.tracking.*}, wired in
 *       vision-app), so a new stream starts on the deployment's defaults with whatever the client
 *       stated on top. {@link #toStartTrackingConfig(TrackingConfig)} additionally <b>rejects</b> a
 *       {@code lock}: a lock names a track that cannot exist before the stream produces one
 *       (&sect;4.D).</li>
 *   <li><b>On a PATCH</b> — the tracking state the server can actually read back for the running
 *       stream (its mode and the engine actually serving it, from {@code
 *       StreamService#trackingStats}); see {@code StreamController#updateConfig} for the one
 *       knob-class this cannot preserve and why.</li>
 * </ul>
 *
 * <p>{@code lock} is the one field whose absence never means "keep the base's" — it maps to {@code
 * null}, which the application layer reads as "leave whatever lock the stream is holding alone"
 * ({@code PipelineConfigPatch#tracking()}'s own javadoc). Dropping a lock is the explicit {@code
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
 * @param redetectIouPercent re-anchor threshold, percent [0,100]
 * @param maxAgeFrames       unmatched frames before a track goes {@code LOST}
 * @param minHits            detector hits needed to confirm a new track
 * @param lock               which object {@code FOLLOW} should hold; absent leaves the running lock
 *                           untouched, and is rejected outright on a start request
 */
public record TrackingConfigRequest(String mode, String engineId, Integer verifyEveryMillis, Integer followFps,
                                     Integer redetectIouPercent, Integer maxAgeFrames, Integer minHits,
                                     TargetLockRequest lock) {

    /**
     * Merges this request's present fields onto {@code base}.
     *
     * @param base the configuration an absent field falls back to; never {@code null}
     * @return the requested tracking configuration
     * @throws IllegalArgumentException if {@code mode} is not a known mode, the lock is not exactly
     *                                  one of its three forms, or a merged value fails {@link
     *                                  TrackingConfig}'s own validation (&rarr; 400)
     */
    public TrackingConfig toTrackingConfig(TrackingConfig base) {
        Objects.requireNonNull(base, "base must not be null");
        return new TrackingConfig(
                mode == null ? base.mode() : parseMode(mode),
                engineId == null ? base.engineId() : engineId,
                verifyEveryMillis == null ? base.verifyEveryMillis() : verifyEveryMillis,
                followFps == null ? base.followFps() : followFps,
                redetectIouPercent == null ? base.redetectIouPercent() : redetectIouPercent,
                maxAgeFrames == null ? base.maxAgeFrames() : maxAgeFrames,
                minHits == null ? base.minHits() : minHits,
                lock == null ? null : lock.toTargetLock());
    }

    /**
     * {@link #toTrackingConfig(TrackingConfig)} for a stream that does not exist yet: identical,
     * except a {@code lock} is refused rather than silently ignored (docs/TRACKING-PLAN.md
     * &sect;4.D — the start bodies carry "the same shape minus {@code lock}").
     *
     * @param base the deployment seed an absent field falls back to; never {@code null}
     * @return the new stream's tracking configuration
     * @throws IllegalArgumentException if a {@code lock} is present (&rarr; 400), plus every case
     *                                  {@link #toTrackingConfig(TrackingConfig)} throws for
     */
    public TrackingConfig toStartTrackingConfig(TrackingConfig base) {
        if (lock != null) {
            throw new IllegalArgumentException(
                    "tracking.lock cannot be set when starting a stream: it names a track that does not exist yet");
        }
        return toTrackingConfig(base);
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
