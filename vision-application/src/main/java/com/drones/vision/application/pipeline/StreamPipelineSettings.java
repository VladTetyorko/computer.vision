package com.drones.vision.application.pipeline;

/**
 * Tunables for one running stream's {@link StreamPipeline} runtime — frame-cadence measurement,
 * detection-outage backoff, video-source reopen backoff (the bounds {@link DefaultStreamService}
 * passes when wrapping a source in a {@link SupervisedPublisher}), and detection-box extrapolation
 * (see {@link DetectionExtrapolator}) — extracted per docs/LAYERING-REFACTOR-PLAN.md &sect;1.3's
 * config-extraction rule: none of these may live as a hardcoded literal inside the classes that use
 * them.
 *
 * <p>Framework-free (no Spring annotation): {@code vision-app} binds a {@code
 * VisionApplicationProperties} record from {@code application.properties} and maps it onto this
 * record's constructor, one field at a time, before handing it to {@link DefaultStreamService}.
 * Every {@link #defaults()} value is byte-identical to the literal it replaces.
 *
 * <p><b>Two distinct backoff pairs are preserved on purpose</b> (not unified): {@link
 * #detectionBackoffInitialNanos()}/{@link #detectionBackoffMaxNanos()} bound how quickly {@link
 * StreamPipeline} retries a probe inference after a detection outage (1s&ndash;10s); {@link
 * #sourceReopenBackoffInitialNanos()}/{@link #sourceReopenBackoffMaxNanos()} bound how quickly
 * {@link DefaultStreamService} retries reopening a failed video source (1s&ndash;30s, {@link
 * SupervisedPublisher}'s own historical default). The two concerns are unrelated failures with
 * unrelated recovery costs, so collapsing them to one pair would be a behavior change, not a
 * cleanup.
 *
 * @param assumedSourceFps               assumed source frame rate used before the measured rate is
 *                                        trusted; must be positive
 * @param measuredFpsEwmaAlpha            smoothing factor for the source frame-rate EWMA, in
 *                                        {@code (0,1]}; lower = smoother/slower to react
 * @param warmupFrames                   frame arrivals observed before the measured rate replaces
 *                                        {@code assumedSourceFps}; must be positive
 * @param minMeasuredFps                 lower sanity-clamp bound for the measured source frame rate
 * @param maxMeasuredFps                 upper sanity-clamp bound for the measured source frame
 *                                        rate; must exceed {@code minMeasuredFps}
 * @param detectionBackoffInitialNanos   backoff before the first retry probe after a detection
 *                                        outage begins; must be positive
 * @param detectionBackoffMaxNanos       cap the exponential detection-outage backoff doubles up to;
 *                                        must be &ge; {@code detectionBackoffInitialNanos}
 * @param sourceReopenBackoffInitialNanos backoff before the first retry after a video source ends;
 *                                        must be positive
 * @param sourceReopenBackoffMaxNanos     cap the exponential source-reopen backoff doubles up to;
 *                                        must be &ge; {@code sourceReopenBackoffInitialNanos}
 * @param extrapolationMaxMillis         how far past the latest completed result's capture time
 *                                        {@link DetectionExtrapolator} extrapolates before the
 *                                        output freezes; must not be negative
 * @param extrapolationMatchGate         max normalized box-center distance for a same-label match
 *                                        between two completed results; must not be negative
 */
public record StreamPipelineSettings(
        int assumedSourceFps,
        double measuredFpsEwmaAlpha,
        int warmupFrames,
        double minMeasuredFps,
        double maxMeasuredFps,
        long detectionBackoffInitialNanos,
        long detectionBackoffMaxNanos,
        long sourceReopenBackoffInitialNanos,
        long sourceReopenBackoffMaxNanos,
        long extrapolationMaxMillis,
        double extrapolationMatchGate) {

    public StreamPipelineSettings {
        if (assumedSourceFps <= 0) {
            throw new IllegalArgumentException("assumedSourceFps must be positive, was " + assumedSourceFps);
        }
        if (measuredFpsEwmaAlpha <= 0 || measuredFpsEwmaAlpha > 1) {
            throw new IllegalArgumentException(
                    "measuredFpsEwmaAlpha must be in (0,1], was " + measuredFpsEwmaAlpha);
        }
        if (warmupFrames <= 0) {
            throw new IllegalArgumentException("warmupFrames must be positive, was " + warmupFrames);
        }
        if (maxMeasuredFps <= minMeasuredFps) {
            throw new IllegalArgumentException("maxMeasuredFps must exceed minMeasuredFps");
        }
        if (detectionBackoffInitialNanos <= 0) {
            throw new IllegalArgumentException("detectionBackoffInitialNanos must be positive");
        }
        if (detectionBackoffMaxNanos < detectionBackoffInitialNanos) {
            throw new IllegalArgumentException("detectionBackoffMaxNanos must be >= detectionBackoffInitialNanos");
        }
        if (sourceReopenBackoffInitialNanos <= 0) {
            throw new IllegalArgumentException("sourceReopenBackoffInitialNanos must be positive");
        }
        if (sourceReopenBackoffMaxNanos < sourceReopenBackoffInitialNanos) {
            throw new IllegalArgumentException(
                    "sourceReopenBackoffMaxNanos must be >= sourceReopenBackoffInitialNanos");
        }
        if (extrapolationMaxMillis < 0) {
            throw new IllegalArgumentException("extrapolationMaxMillis must not be negative");
        }
        if (extrapolationMatchGate < 0) {
            throw new IllegalArgumentException("extrapolationMatchGate must not be negative");
        }
    }

    /** Every value byte-identical to the literal it replaces (docs/LAYERING-REFACTOR-PLAN.md &sect;1.3). */
    public static StreamPipelineSettings defaults() {
        return new StreamPipelineSettings(
                30, 0.2, 5, 1.0, 240.0,
                1_000_000_000L, 10_000_000_000L,
                1_000_000_000L, 30_000_000_000L,
                800L, 0.15);
    }
}
