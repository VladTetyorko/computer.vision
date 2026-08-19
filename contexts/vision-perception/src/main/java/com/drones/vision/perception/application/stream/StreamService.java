package com.drones.vision.perception.application.stream;

import com.drones.vision.perception.domain.model.Detection;
import com.drones.vision.perception.domain.model.DetectionState;
import com.drones.vision.kernel.DeviceId;
import com.drones.vision.perception.domain.model.PipelineConfig;
import com.drones.vision.perception.domain.model.StopReason;
import com.drones.vision.perception.domain.model.StreamState;
import com.drones.vision.kernel.StreamId;
import com.drones.vision.perception.domain.model.TrackedObject;
import com.drones.vision.perception.domain.model.VideoFrame;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import com.drones.vision.warehouse.application.asset.AssetService;
import com.drones.vision.perception.application.pipeline.StreamPipeline;
import com.drones.vision.perception.application.pipeline.DetectionRate;
import com.drones.vision.perception.application.pipeline.PipelineLatency;
import com.drones.vision.perception.application.pipeline.TrackingStats;

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
     * <p>The new stream's {@link com.drones.vision.perception.domain.model.TrackingConfig} is composed here from
     * all three configuration layers, precedence running strictly left to right: <b>{@code tracking}
     * &gt; the deployment seed ({@code vision.tracking.*}, carried on {@link
     * com.drones.vision.perception.application.pipeline.StreamPipelineSettings#trackingSeed()}) &gt; {@code
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
     *                                           com.drones.vision.perception.domain.model.TrackingConfig}'s own
     *                                           validation
     */
    StreamId start(DeviceId deviceId, PipelineConfig config, TrackingConfigPatch tracking);

    /**
     * Stops a stream and releases its source, on an operator's behalf. A no-op for an unknown or
     * already-stopped id.
     *
     * @param streamId the stream to stop
     */
    void stop(StreamId streamId);

    /**
     * Stops a stream and releases its source, recording <b>why</b> on the emitted
     * {@code STREAM_STOPPED} event (docs/plans/active/STREAM-STATE-PLAN.md &sect;3.2). A no-op for an
     * unknown or already-stopped id.
     *
     * <p>{@link #stop(StreamId)} is this method with {@link StopReason#OPERATOR}. The reason exists
     * because {@link IdleStreamReaper} made stops something the system can decide on its own, and a
     * stream that disappears with the unqualified message "Stream stopped" reads to an operator as a
     * crash to go hunting for.
     *
     * @param streamId the stream to stop
     * @param reason   why it is being stopped; never {@code null}
     */
    void stop(StreamId streamId, StopReason reason);

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
     * com.drones.vision.perception.domain.port.StreamPublisherPort#publish}. Backs the per-stream JPEG
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
     * Whether server-side overlay burn-in is actually active for a running stream
     * (docs/plans/active/MEDIA-SOT-PLAN.md &sect;5.4) — {@code false} exactly when nothing burns detection
     * boxes into the published video (this device's source is proxied, D4 — no {@code
     * VideoSourcePort} was opened at all, so no JVM frame is ever published for the overlay renderer
     * to burn into — or no {@code OverlayPort} is wired, or this stream's own {@code overlayBurnIn} is
     * off), {@code true} otherwise. Whether this stream's <em>detections</em> arrive by push or pull
     * plays no part: the overlay render is driven by the published video frame, not by the detection
     * transport, so a JVM-published, pull-detected stream (Phase 1's V4L2/MJPEG/sim answer) burns
     * boxes exactly like push mode does. Computed once at start, since neither fact it depends on can
     * change over a running stream's life.
     *
     * @param streamId the stream to inspect
     * @return whether burn-in is active, or {@code false} for an unknown/not-running stream — the
     *         same honest "nothing burns boxes here" answer a proxied stream already gives, never an
     *         error
     */
    boolean burnedIn(StreamId streamId);

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
     * A running stream's wall-clock detection latency over the stats window
     * (docs/conclusions/CV-RATE-BUDGET.md &sect;3) — exactly {@link StreamPipeline#pipelineLatency()}.
     *
     * <p>Unlike {@link #trackingStats(StreamId)} this is populated whatever the tracking mode is:
     * latency is a property of the detection path, not of tracking, and the stream most likely to
     * be under investigation for lag is often one with tracking off.
     *
     * @param streamId the stream to inspect
     * @return the figures, or {@link Optional#empty()} if {@code streamId} is unknown or not running
     *         on this instance — empty rather than zeroed, for the same reason as {@link
     *         #trackingStats(StreamId)}
     */
    Optional<PipelineLatency> pipelineLatency(StreamId streamId);

    /**
     * A running stream's sampler accounting over the stats window
     * (docs/plans/active/CV-RATE-CONTROL-PLAN.md &sect;1) — exactly {@link StreamPipeline#detectionRate()}.
     *
     * <p>The companion to {@link #pipelineLatency(StreamId)}: that one reports what a detection
     * cost, this one reports how many were asked for and what became of them, which is what turns
     * "the stream is not running at its configured rate" from an observation into a diagnosis.
     *
     * @param streamId the stream to inspect
     * @return the counters, or {@link Optional#empty()} if {@code streamId} is unknown or not
     *         running on this instance — empty rather than zeroed, for the same reason as {@link
     *         #trackingStats(StreamId)}
     */
    Optional<DetectionRate> detectionRate(StreamId streamId);

    /**
     * Which of the two independent detection gates currently explains a running stream's
     * boxes-or-no-boxes state (docs/plans/active/CV-DEMAND-PLAN.md &sect;3.6) — exactly {@link
     * StreamPipeline#detectionState()}. See {@link DetectionState}'s own javadoc: this reports
     * gating, never health — a stalled detector still reads {@link DetectionState#RUNNING}, which
     * is what {@link #detectionRate(StreamId)}/outage events are for.
     *
     * @param streamId the stream to inspect
     * @return the gating state, or {@link Optional#empty()} if {@code streamId} is unknown or not
     *         running on this instance — empty rather than a guessed state, for the same reason as
     *         {@link #trackingStats(StreamId)}
     */
    Optional<DetectionState> detectionState(StreamId streamId);

    /**
     * Whether a running stream's <b>video</b> is actually flowing right now
     * (docs/plans/active/STREAM-STATE-PLAN.md &sect;2.3) — the fact a client previously had to
     * reconstruct by latching on a poll gap, because {@code GET /api/streams} carried no state.
     *
     * <p>See {@link StreamState}'s own javadoc for why this is a third axis and not a widening of
     * {@link #detectionState(StreamId)}: this reports video flow and never detection, and
     * {@link StreamState#UNOBSERVED} ("this JVM opens no source for a proxied stream, so it cannot
     * judge") is explicitly not a fault.
     *
     * @param streamId the stream to inspect
     * @return the video-flow state, or {@link Optional#empty()} if {@code streamId} is unknown or
     *         not running on this instance — a stopped stream has no state here by design; it is
     *         answered by {@code AssetUsage}'s own {@code endedAt} on the lifecycle axis
     */
    Optional<StreamState> streamState(StreamId streamId);

    /**
     * A running stream's <b>effective</b> configuration — the missing read half of a knob that was
     * write-only over HTTP (docs/plans/active/STREAM-STATE-PLAN.md &sect;2.5).
     *
     * <p>{@link #updateConfig(StreamId, PipelineConfigPatch)} could change {@code detectionEnabled},
     * the model, the label filter and the whole tracking configuration, and nothing could read any of
     * it back. A client that wants to *render* those controls therefore had to keep its own local
     * copy of what it believed it had sent — which is a guess the moment anything else patches the
     * stream, the stream restarts, or a second client connects.
     *
     * @param streamId the stream to inspect
     * @return the configuration the pipeline is running right now, or {@link Optional#empty()} if
     *         {@code streamId} is unknown or not running on this instance
     */
    Optional<PipelineConfig> config(StreamId streamId);

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
