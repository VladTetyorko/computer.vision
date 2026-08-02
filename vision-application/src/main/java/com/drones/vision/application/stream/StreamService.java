package com.drones.vision.application.stream;

import com.drones.vision.domain.model.Detection;
import com.drones.vision.domain.model.DeviceId;
import com.drones.vision.domain.model.PipelineConfig;
import com.drones.vision.domain.model.StreamId;
import com.drones.vision.domain.model.VideoFrame;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import com.drones.vision.application.asset.AssetService;
import com.drones.vision.application.pipeline.StreamPipeline;

/**
 * The lifecycle of live streams: start one for a device, stop it, list what is running.
 *
 * <p>One interface, one implementation ({@link DefaultStreamService}). Asset-level streaming
 * lives in {@link AssetService}, which resolves the device and then calls in here.
 *
 * <h2>Threading</h2>
 * Implementations must be safe for concurrent use — one pipeline per stream, started and stopped
 * from control-plane calls while others run.
 */
public interface StreamService {

    /**
     * Opens a stream for a device and starts its pipeline.
     *
     * @param deviceId the device to pull frames from
     * @param config   pipeline settings for this stream
     * @return the new stream's id
     * @throws java.util.NoSuchElementException if no device has that id
     * @throws IllegalStateException            if the device is not in service, or already streaming
     */
    StreamId start(DeviceId deviceId, PipelineConfig config);

    /**
     * Stops a stream and releases its source. A no-op for an unknown or already-stopped id.
     *
     * @param streamId the stream to stop
     */
    void stop(StreamId streamId);

    /**
     * Lists the streams running in this instance.
     *
     * @return an immutable snapshot
     */
    List<ActiveStream> streams();

    /**
     * The devices that currently have an active stream.
     *
     * @return an immutable snapshot, for deriving asset status
     */
    Set<DeviceId> activeDeviceIds();

    /**
     * The most recently published frame on a running stream (docs/MVP3-PLAN.md C-a) — post-overlay
     * burn-in when one was drawn, exactly the instance the pipeline last handed to {@link
     * com.drones.vision.domain.port.out.StreamPublisherPort#publish}. Backs the per-stream JPEG
     * snapshot endpoint.
     *
     * @param streamId the stream to inspect
     * @return the frame, or {@link Optional#empty()} if {@code streamId} is unknown/not running on
     *         this instance, or it is but hasn't published a frame yet
     */
    Optional<VideoFrame> latestFrame(StreamId streamId);

    /**
     * The most recently arrived frame on a running stream, before overlay burn-in and at full
     * resolution (docs/CV-TRAINING-PLAN.md &sect;2/&sect;D) — exactly {@link
     * StreamPipeline#latestRawFrame()}. Backs training-sample capture, which wants clean pixels to
     * label, never {@link #latestFrame}'s possibly-annotated one.
     *
     * @param streamId the stream to inspect
     * @return the frame, or {@link Optional#empty()} if {@code streamId} is unknown/not running on
     *         this instance, or it is but hasn't received a frame yet
     */
    Optional<VideoFrame> latestRawFrame(StreamId streamId);

    /**
     * The most recently completed detection result's detections on a running stream — exactly {@link
     * StreamPipeline#latestDetections()} (docs/CV-TRAINING-PLAN.md &sect;2), surfaced here so a
     * caller outside the pipeline (e.g. training-sample capture) never needs to reach into pipeline
     * internals.
     *
     * @param streamId the stream to inspect
     * @return the raw, un-extrapolated detections, or an empty list if {@code streamId} is
     *         unknown/not running on this instance, or no inference has completed yet
     */
    List<Detection> latestDetections(StreamId streamId);

    /**
     * Live-updates a running stream's detection config (docs/CV-CONTROL-PLAN.md &sect;5) — a
     * partial patch folded onto the stream's current {@link PipelineConfig}. Confidence threshold,
     * inference fps, label filter and detection on/off apply instantly with no video interruption;
     * a changed model id briefly re-arms detection instead (see {@link
     * UpdateOutcome#modelReArmed()} and {@code StreamPipeline#updateConfig}'s own javadoc for what
     * that means concretely).
     *
     * @param streamId the running stream to update
     * @param patch    the knobs to change; a {@code null} field on {@code patch} keeps that knob at
     *                 its current value
     * @return whether the patch changed the running model
     * @throws NoSuchElementException  if {@code streamId} is unknown or not running on this instance
     * @throws IllegalArgumentException if the merged config fails {@link PipelineConfig}'s own
     *                                   validation (e.g. confidence outside [0,1])
     */
    UpdateOutcome updateConfig(StreamId streamId, PipelineConfigPatch patch);
}
