package com.drones.vision.perception.application.pipeline;

import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.Telemetry;
import com.drones.vision.perception.domain.port.DetectionLiveUpdatePort;

import java.util.Objects;
import java.util.Optional;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Every {@link StreamPipeline} collaborator beyond its eight mandatory constructor arguments
 * (stream/device identity, config, source, and the four core ports), bundled per
 * docs/plans/active/ARCHITECTURE-AUDIT-2026-08-26.md Finding R1 — {@code StreamPipeline} previously
 * grew one N-1-arg convenience constructor per wave (nine of them, a 16-arg package-private master)
 * purely so pre-existing call sites kept compiling; this record replaces every one of those
 * overloads with a single canonical constructor plus this collaborators type.
 *
 * <p>{@link #eventEngine()}, {@link #assetId()}, {@link #liveUpdatePublisherPort()}, {@link
 * #telemetrySupplier()} and {@link #pullDetection()} are genuinely optional — the old code's own
 * {@code null} meant "feature off" for each — so they are {@link Optional} here rather than
 * nullable positional arguments; {@link StreamPipeline}'s constructor unwraps each via {@code
 * orElse(null)} into its existing private nullable field, so internal behavior is unchanged.
 * {@link #nanoTimeSource()}/{@link #latencyNanoSource()} keep their existing {@code
 * System::nanoTime} default and {@link #settings()} keeps its existing {@link
 * StreamPipelineSettings#defaults()} default, all as plain non-null fields.
 *
 * @param eventEngine             empty means no debounced {@code DetectionEvent} tracking runs for
 *                                this pipeline (docs/plans/done/MVP2-PLAN.md §E, E-a)
 * @param assetId                 the owning asset of the device streaming, resolved once by {@code
 *                                DefaultStreamService} at stream start; empty when the device
 *                                belongs to no asset, in which case nothing is ever announced
 * @param liveUpdatePublisherPort empty means no live-update announcements for this pipeline
 *                                (docs/plans/done/REALTIME-PLAN.md §4)
 * @param telemetrySupplier       empty means {@link StreamPipeline#cameraAttitude()} can never
 *                                resolve for this pipeline; when present, called at most once per
 *                                submitted detection and must be cheap
 * @param nanoTimeSource          the frame-cadence/detection-outage backoff clock; never {@code null}
 * @param settings                frame-cadence, detection-outage and tracking tuning; never {@code null}
 * @param latencyNanoSource       the wall-clock latency-measurement clock, deliberately separate
 *                                from {@code nanoTimeSource}; never {@code null}
 * @param pullDetection           empty means push-mode detection: {@link StreamPipeline} samples
 *                                frames and calls {@code detectionPort} directly, unchanged
 *                                (docs/plans/done/MEDIA-SOT-PLAN.md wave M5, D5/D6)
 */
public record StreamPipelineCollaborators(Optional<DetectionEventEngine> eventEngine, Optional<AssetId> assetId,
                                           Optional<DetectionLiveUpdatePort> liveUpdatePublisherPort,
                                           Optional<Supplier<Telemetry>> telemetrySupplier,
                                           LongSupplier nanoTimeSource, StreamPipelineSettings settings,
                                           LongSupplier latencyNanoSource,
                                           Optional<PullDetectionBinding> pullDetection) {

    public StreamPipelineCollaborators {
        Objects.requireNonNull(eventEngine, "eventEngine must not be null");
        Objects.requireNonNull(assetId, "assetId must not be null");
        Objects.requireNonNull(liveUpdatePublisherPort, "liveUpdatePublisherPort must not be null");
        Objects.requireNonNull(telemetrySupplier, "telemetrySupplier must not be null");
        Objects.requireNonNull(nanoTimeSource, "nanoTimeSource must not be null");
        Objects.requireNonNull(settings, "settings must not be null");
        Objects.requireNonNull(latencyNanoSource, "latencyNanoSource must not be null");
        Objects.requireNonNull(pullDetection, "pullDetection must not be null");
    }

    /**
     * Reproduces the pre-R1 shortest (8-argument) constructor's behavior exactly: every optional
     * collaborator absent, both clocks default to {@code System::nanoTime}, default {@link
     * StreamPipelineSettings}.
     */
    public static StreamPipelineCollaborators defaults() {
        return new StreamPipelineCollaborators(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                System::nanoTime, StreamPipelineSettings.defaults(), System::nanoTime, Optional.empty());
    }
}
