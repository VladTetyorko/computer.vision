package com.drones.vision.application.stream;

import com.drones.vision.domain.model.Detection;
import com.drones.vision.domain.model.DeviceId;
import com.drones.vision.domain.model.PipelineConfig;
import com.drones.vision.domain.model.StreamId;
import com.drones.vision.domain.model.TrackedObject;
import com.drones.vision.domain.model.VideoFrame;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import com.drones.vision.application.pipeline.StreamPipeline;
import com.drones.vision.application.pipeline.TrackingStats;

/**
 * The lifecycle of live streams: start one for a device, stop it, list what is running.
 *
 * <p>One interface, one implementation ({@link DefaultStreamService}). Asset-level streaming
 * lives in {@link com.drones.vision.application.asset.AssetService}, which resolves the device and then calls in here.
 *
 * <h2>Threading</h2>
 * Implementations must be safe for concurrent use — one pipeline per stream, started and stopped
 * from control-plane calls while others run.
 */
public interface StreamService {

    /**
     * Opens a stream for a device and starts its pipeline, with the caller stating nothing about
     * tracking — exactly {@code start(deviceId, config, TrackingConfigPatch.NOTHING)}.
     *
     * @param deviceId the device to pull frames from
     * @param config   pipeline settings for this stream
     * @return the new stream's id
     * @throws java.util.NoSuchElementException if no device has that id
     * @throws IllegalStateException            if the device is not in service, or already streaming
     */
    StreamId start(DeviceId deviceId, PipelineConfig config);

    /**
     * Opens a stream for a device and starts its pipeline, stating the caller's tracking wishes
     * separately from the rest of the configuration (docs/extracts/TRACKING-ORCHESTRATION.md &sect;4.1).
     *
     * <p>The new stream's {@link com.drones.vision.domain.model.TrackingConfig} is composed here from
     * all three configuration layers, precedence running strictly left to right: <b>{@code tracking}
     * &gt; the deployment seed ({@code vision.tracking.*}, carried on {@link
     * com.drones.vision.application.pipeline.StreamPipelineSettings#trackingSeed()}) &gt; {@code
     * config}'s own {@link PipelineConfig#tracking()}</b>, which is the domain's code default. Seeding
     * in the implementation rather than at each caller is deliberate: the device, asset, simulation
     * and demo-fleet start paths all converge here, so the deployment default cannot come out
     * depending on which button an operator pressed.
     *
     * @param deviceId the device to pull frames from
     * @param config   pipeline settings for this stream; its tracking component is the bottom layer
     *                 of the fold above
     * @param tracking what this request states about tracking, per field; {@link
     *                 TrackingConfigPatch#NOTHING} states nothing. Never {@code null}
     * @return the new stream's id
     * @throws java.util.NoSuchElementException if no device has that id
     * @throws IllegalStateException            if the device is not in service, or already streaming
     * @throws IllegalArgumentException         if the composed tracking configuration fails {@link
     *                                           com.drones.vision.domain.model.TrackingConfig}'s own
     *                                           validation
     */
    StreamId start(DeviceId deviceId, PipelineConfig config, TrackingConfigPatch tracking);

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
     * The most recently published frame on a running stream (docs/plans/done/MVP3-PLAN.md C-a) — post-overlay
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
     * resolution (docs/plans/done/CV-TRAINING-PLAN.md &sect;2/&sect;D) — exactly {@link
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
     * StreamPipeline#latestDetections()} (docs/plans/done/CV-TRAINING-PLAN.md &sect;2), surfaced here so a
     * caller outside the pipeline (e.g. training-sample capture) never needs to reach into pipeline
     * internals.
     *
     * @param streamId the stream to inspect
     * @return the raw, un-extrapolated detections, or an empty list if {@code streamId} is
     *         unknown/not running on this instance, or no inference has completed yet
     */
    List<Detection> latestDetections(StreamId streamId);

    /**
     * The tracks a running stream currently holds (docs/plans/done/TRACKING-PLAN.md &sect;4.E) — exactly
     * {@link StreamPipeline#tracks()}, surfaced here for the same reason {@link #latestDetections}
     * is: a caller outside the pipeline never reaches into pipeline internals.
     *
     * <p><b>Never errors.</b> An unknown or stopped stream reads as an empty list, the same
     * forgiving idiom {@link #latestDetections} already uses — "no tracks" is the honest answer for
     * a stream that is not running, and a 404 would make the polling UI handle two cases where one
     * suffices.
     *
     * @param streamId the stream to inspect
     * @return the booked tracks, ordered by {@code trackId} ascending, or an empty list
     */
    List<TrackedObject> tracks(StreamId streamId);

    /**
     * A running stream's tracking-flow counters over the stats window (docs/plans/done/TRACKING-PLAN.md
     * &sect;4.E) — exactly {@link StreamPipeline#trackingStats()}.
     *
     * @param streamId the stream to inspect
     * @return the counters, or {@link Optional#empty()} if {@code streamId} is unknown or not
     *         running on this instance — deliberately empty rather than a zeroed {@link
     *         TrackingStats}, because a stream that is not running has no window length or mode to
     *         report honestly; what the API renders for that case is its own decision
     */
    Optional<TrackingStats> trackingStats(StreamId streamId);

    /**
     * Live-updates a running stream's detection config (docs/plans/done/CV-CONTROL-PLAN.md &sect;5,
     * docs/plans/done/TRACKING-PLAN.md &sect;4.D) — a partial patch folded onto the stream's current {@link
     * PipelineConfig}. Confidence threshold, inference fps, label filter, detection on/off and the
     * whole tracking configuration apply instantly with no video interruption; a changed model id
     * briefly re-arms detection instead (see {@link UpdateOutcome#modelReArmed()} and {@code
     * StreamPipeline#updateConfig}'s own javadoc for what that means concretely).
     *
     * <p>A {@code tracking} patch carrying a lock has its {@code lockSeq} allocated <b>here</b>,
     * from the stream's own monotonic counter — clients never send one, which is what stops a
     * replayed stale lock from re-acquiring an abandoned target.
     *
     * @param streamId the running stream to update
     * @param patch    the knobs to change; a {@code null} field on {@code patch} keeps that knob at
     *                 its current value
     * @return whether the patch changed the running model and/or the tracking configuration
     * @throws NoSuchElementException  if {@code streamId} is unknown or not running on this instance
     * @throws IllegalArgumentException if the merged config fails {@link PipelineConfig}'s own
     *                                   validation (e.g. confidence outside [0,1])
     */
    UpdateOutcome updateConfig(StreamId streamId, PipelineConfigPatch patch);
}
