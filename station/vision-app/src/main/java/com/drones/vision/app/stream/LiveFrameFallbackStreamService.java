package com.drones.vision.app.stream;

import com.drones.vision.adapter.publishhls.MediamtxLiveFrameGrabber;
import com.drones.vision.perception.application.pipeline.DetectionRate;
import com.drones.vision.perception.application.pipeline.PipelineLatency;
import com.drones.vision.perception.application.pipeline.TrackingStats;
import com.drones.vision.perception.application.stream.ActiveStream;
import com.drones.vision.perception.application.stream.PipelineConfigPatch;
import com.drones.vision.perception.application.stream.StreamService;
import com.drones.vision.perception.application.stream.TrackingConfigPatch;
import com.drones.vision.perception.application.stream.UpdateOutcome;
import com.drones.vision.perception.domain.model.Detection;
import com.drones.vision.kernel.DeviceId;
import com.drones.vision.perception.domain.model.DetectionState;
import com.drones.vision.perception.domain.model.FollowStatus;
import com.drones.vision.perception.domain.model.StopReason;
import com.drones.vision.perception.domain.model.StreamState;
import com.drones.vision.perception.domain.model.PipelineConfig;
import com.drones.vision.kernel.StreamId;
import com.drones.vision.perception.domain.model.TrackedObject;
import com.drones.vision.perception.domain.model.VideoFrame;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * {@link StreamService} decorator that falls back to a live, on-demand grab from mediamtx's own RTSP
 * output when the delegate has no cached frame — the gap docs/plans/done/MEDIA-SOT-PLAN.md wave M6
 * opened and wave M7 closes: in proxy mode ({@code vision.publish.source-proxy.enabled=true}) the JVM
 * opens no {@code VideoSourcePort} at all (D4), so {@code DefaultStreamService#latestFrame}/{@code
 * latestRawFrame} — which back the snapshot endpoint ({@code StreamController}) and training-sample
 * capture ({@code DefaultLabelingService}) respectively — would otherwise sit permanently empty for a
 * proxied stream.
 *
 * <h2>Only wired when it can matter</h2>
 * {@code wiring.ApplicationServiceWiring#streamService} only wraps the plain {@code
 * DefaultStreamService} in this class when {@code vision.publish.source-proxy.enabled=true}: with the
 * default {@code false} (D1), this class is never constructed and the default-config behaviour is
 * byte-identical to before it existed. Gating on the flag (rather than always wrapping) also protects
 * push-mode streams from a spurious grab attempt during the brief window before their first frame
 * arrives — {@link MediamtxLiveFrameGrabber#grab} bounds its own connect/read I/O at 5s each, so an
 * unconditional wrap would trade an instant "no frame yet" for up to 10s of latency on every stream
 * that has genuinely just started.
 *
 * <h2>Why check {@link #isRunning}, not just "delegate returned empty"</h2>
 * An empty {@code Optional} from the delegate also means "unknown or not-running stream" (see {@link
 * StreamService#latestFrame}'s own contract) — grabbing against mediamtx for a stream that was never
 * started, or was already stopped (and so may have no mediamtx path left at all, D3's {@code
 * streamEnded}), would just wait out the grabber's own connect timeout before honestly returning empty
 * anyway. Consulting {@link StreamService#streams()} first keeps that case exactly as fast as it is
 * today, and only pays the grab cost for a stream that is genuinely running but has no cached frame —
 * exactly the proxied-stream case this class exists for.
 *
 * <p>Every other method delegates unchanged. Plain class with no framework dependency — instantiated
 * directly by {@code vision-app}'s wiring configuration.
 */
public final class LiveFrameFallbackStreamService implements StreamService {

    private final StreamService delegate;
    private final MediamtxLiveFrameGrabber liveFrameGrabber;

    public LiveFrameFallbackStreamService(StreamService delegate, MediamtxLiveFrameGrabber liveFrameGrabber) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.liveFrameGrabber = Objects.requireNonNull(liveFrameGrabber, "liveFrameGrabber must not be null");
    }

    @Override
    public StreamId start(DeviceId deviceId, PipelineConfig config) {
        return delegate.start(deviceId, config);
    }

    @Override
    public StreamId start(DeviceId deviceId, PipelineConfig config, TrackingConfigPatch tracking) {
        return delegate.start(deviceId, config, tracking);
    }

    @Override
    public void stop(StreamId streamId) {
        delegate.stop(streamId);
    }

    @Override
    public void stop(StreamId streamId, StopReason reason) {
        delegate.stop(streamId, reason);
    }

    @Override
    public List<ActiveStream> streams() {
        return delegate.streams();
    }

    @Override
    public Set<DeviceId> activeDeviceIds() {
        return delegate.activeDeviceIds();
    }

    @Override
    public Optional<VideoFrame> latestFrame(StreamId streamId) {
        Optional<VideoFrame> cached = delegate.latestFrame(streamId);
        return cached.isPresent() ? cached : liveGrabIfRunning(streamId);
    }

    @Override
    public Optional<VideoFrame> latestRawFrame(StreamId streamId) {
        Optional<VideoFrame> cached = delegate.latestRawFrame(streamId);
        return cached.isPresent() ? cached : liveGrabIfRunning(streamId);
    }

    @Override
    public List<Detection> latestDetections(StreamId streamId) {
        return delegate.latestDetections(streamId);
    }

    @Override
    public List<TrackedObject> tracks(StreamId streamId) {
        return delegate.tracks(streamId);
    }

    @Override
    public Optional<TrackingStats> trackingStats(StreamId streamId) {
        return delegate.trackingStats(streamId);
    }

    @Override
    public Optional<FollowStatus> followStatus(StreamId streamId) {
        return delegate.followStatus(streamId);
    }

    @Override
    public Optional<PipelineLatency> pipelineLatency(StreamId streamId) {
        return delegate.pipelineLatency(streamId);
    }

    @Override
    public Optional<DetectionRate> detectionRate(StreamId streamId) {
        return delegate.detectionRate(streamId);
    }

    @Override
    public Optional<DetectionState> detectionState(StreamId streamId) {
        return delegate.detectionState(streamId);
    }

    @Override
    public Optional<StreamState> streamState(StreamId streamId) {
        return delegate.streamState(streamId);
    }

    @Override
    public Optional<PipelineConfig> config(StreamId streamId) {
        return delegate.config(streamId);
    }

    @Override
    public UpdateOutcome updateConfig(StreamId streamId, PipelineConfigPatch patch) {
        return delegate.updateConfig(streamId, patch);
    }

    private Optional<VideoFrame> liveGrabIfRunning(StreamId streamId) {
        if (!isRunning(streamId)) {
            return Optional.empty();
        }
        return liveFrameGrabber.grab(streamId);
    }

    private boolean isRunning(StreamId streamId) {
        return delegate.streams().stream().anyMatch(active -> active.streamId().equals(streamId));
    }
}
