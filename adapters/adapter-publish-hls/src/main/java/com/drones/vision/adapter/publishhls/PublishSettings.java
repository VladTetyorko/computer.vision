package com.drones.vision.adapter.publishhls;

import java.time.Duration;
import java.util.Objects;

/**
 * Plain, framework-free settings for this module's mediamtx publish path (docs/plans/active/LAYERING-REFACTOR-PLAN.md
 * &sect;1.3/&sect;2.2, wave F3) — the single source for every {@code vision.publish.encoder.*}/{@code
 * .resilience.*}/{@code .cadence.*} tunable {@link MediamtxStreamPublisher}, {@link
 * H264RecorderFactory}, {@link PublishBackoff}, and {@link CadenceEstimator} need. No Spring
 * annotations here: {@code vision-app} owns a {@code VisionPublishProperties} record bound to {@code
 * application.yaml} and maps it to one of these before handing it to {@link
 * MediamtxStreamPublisher}'s constructor — this class must never be constructed from a {@code
 * @ConfigurationProperties} type directly (that would point this module at {@code vision-app},
 * breaking the hexagon).
 *
 * <p>{@code vision.publish.replay.window}/{@code .read-timeout} are deliberately <b>not</b> fields
 * here — {@link MediamtxReplayFrameExtractor} has only two tunables of its own (docs/plans/active/LAYERING-REFACTOR-PLAN.md
 * &sect;1.3 rule 4: modules with &le;2 tunables skip the settings record), so it takes them as plain
 * constructor parameters instead.
 *
 * @param encoder    {@link H264RecorderFactory}'s x264 rate-control/GOP tunables; defaulted as a
 *                   whole when absent
 * @param resilience {@link PublishBackoff}'s reconnect-throttling bounds; defaulted as a whole when absent
 * @param cadence    {@link CadenceEstimator}'s measurement/drift-detection tunables; defaulted as a
 *                   whole when absent
 */
public record PublishSettings(Encoder encoder, Resilience resilience, Cadence cadence) {

    public PublishSettings {
        if (encoder == null) {
            encoder = Encoder.defaults();
        }
        if (resilience == null) {
            resilience = Resilience.defaults();
        }
        if (cadence == null) {
            cadence = Cadence.defaults();
        }
    }

    /** Every field equals the literal each collaborator used to hardcode as a {@code static final} constant. */
    public static PublishSettings defaults() {
        return new PublishSettings(Encoder.defaults(), Resilience.defaults(), Cadence.defaults());
    }

    /**
     * @param crf                x264 constant-quality rate factor; lower is higher quality/bitrate;
     *                           default {@value H264RecorderFactory#X264_CRF}
     * @param maxrateBitsPerSecond VBV cap bounding worst-case bitrate; default 6,000,000 (6 Mbps)
     * @param bufsizeBits        VBV buffer size, conventionally 2x {@code maxrateBitsPerSecond};
     *                           default 12,000,000 (12 Mbit)
     * @param preset             x264 encoder preset; default {@code "veryfast"}
     * @param gopSeconds         keyframe interval in seconds; default {@value H264RecorderFactory#GOP_SECONDS}
     * @param scenecutThreshold  x264 adaptive scene-cut threshold, {@code 0} disables it (keeps GOP
     *                           strictly periodic); default {@value H264RecorderFactory#X264_SCENECUT_THRESHOLD}
     */
    public record Encoder(int crf, long maxrateBitsPerSecond, long bufsizeBits, String preset, int gopSeconds,
                           int scenecutThreshold) {

        public Encoder {
            Objects.requireNonNull(preset, "preset must not be null");
            if (preset.isBlank()) {
                throw new IllegalArgumentException("preset must not be blank");
            }
            if (crf < 0) {
                throw new IllegalArgumentException("crf must not be negative: " + crf);
            }
            if (maxrateBitsPerSecond <= 0) {
                throw new IllegalArgumentException("maxrateBitsPerSecond must be > 0: " + maxrateBitsPerSecond);
            }
            if (bufsizeBits <= 0) {
                throw new IllegalArgumentException("bufsizeBits must be > 0: " + bufsizeBits);
            }
            if (gopSeconds <= 0) {
                throw new IllegalArgumentException("gopSeconds must be > 0: " + gopSeconds);
            }
            if (scenecutThreshold < 0) {
                throw new IllegalArgumentException("scenecutThreshold must not be negative: " + scenecutThreshold);
            }
        }

        public static Encoder defaults() {
            return new Encoder(21, 6_000_000L, 12_000_000L, "veryfast", 1, 0);
        }
    }

    /**
     * @param initialBackoff how long to wait before the first reconnect attempt after a publish
     *                       failure; default 500ms
     * @param maxBackoff     cap the doubling backoff never exceeds; default 10s
     */
    public record Resilience(Duration initialBackoff, Duration maxBackoff) {

        public Resilience {
            Objects.requireNonNull(initialBackoff, "initialBackoff must not be null");
            Objects.requireNonNull(maxBackoff, "maxBackoff must not be null");
            if (initialBackoff.isNegative() || initialBackoff.isZero()) {
                throw new IllegalArgumentException("initialBackoff must be positive: " + initialBackoff);
            }
            if (maxBackoff.compareTo(initialBackoff) < 0) {
                throw new IllegalArgumentException(
                        "maxBackoff must be >= initialBackoff: " + maxBackoff + " < " + initialBackoff);
            }
        }

        public static Resilience defaults() {
            return new Resilience(Duration.ofMillis(500), Duration.ofSeconds(10));
        }
    }

    /**
     * @param measurementFrames     frames sampled to measure a stream's source cadence before its
     *                              recorder starts; default 5
     * @param minMeasuredFrameRateFps lower sanity-clamp bound for the measured rate; default 1.0
     * @param maxMeasuredFrameRateFps upper sanity-clamp bound for the measured rate; default 120.0
     * @param driftRatioHigh        post-start drift ratio ceiling ({@code 1/driftRatioHigh} is the
     *                              floor); default 1.5
     * @param driftEwmaAlpha        smoothing factor for the post-start actual-cadence EWMA; default 0.2
     * @param sustainedDriftWindow  how long the drift ratio must stay outside bounds before it is
     *                              logged; default 2s
     * @param defaultFrameRateFps   fallback rate used only when the measured cadence is degenerate;
     *                              default 15.0
     */
    public record Cadence(int measurementFrames, double minMeasuredFrameRateFps, double maxMeasuredFrameRateFps,
                           double driftRatioHigh, double driftEwmaAlpha, Duration sustainedDriftWindow,
                           double defaultFrameRateFps) {

        public Cadence {
            Objects.requireNonNull(sustainedDriftWindow, "sustainedDriftWindow must not be null");
            if (measurementFrames < 2) {
                throw new IllegalArgumentException("measurementFrames must be >= 2: " + measurementFrames);
            }
            if (minMeasuredFrameRateFps <= 0 || maxMeasuredFrameRateFps < minMeasuredFrameRateFps) {
                throw new IllegalArgumentException("minMeasuredFrameRateFps/maxMeasuredFrameRateFps out of order: "
                        + minMeasuredFrameRateFps + "/" + maxMeasuredFrameRateFps);
            }
            if (driftRatioHigh <= 1.0) {
                throw new IllegalArgumentException("driftRatioHigh must be > 1.0: " + driftRatioHigh);
            }
            if (driftEwmaAlpha <= 0 || driftEwmaAlpha > 1) {
                throw new IllegalArgumentException("driftEwmaAlpha must be in (0,1]: " + driftEwmaAlpha);
            }
            if (defaultFrameRateFps <= 0) {
                throw new IllegalArgumentException("defaultFrameRateFps must be > 0: " + defaultFrameRateFps);
            }
        }

        public static Cadence defaults() {
            return new Cadence(5, 1.0, 120.0, 1.5, 0.2, Duration.ofSeconds(2), 15.0);
        }
    }
}
