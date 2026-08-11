package com.drones.vision.app.devsupport;

import com.drones.vision.domain.model.StreamId;
import com.drones.vision.domain.model.VideoFrame;
import com.drones.vision.domain.port.out.ReplayFrameExtractionPort;

import java.time.Instant;
import java.util.Optional;

/**
 * No-op {@link ReplayFrameExtractionPort}: always reports honest absence, never attempts a fetch.
 * The {@code vision.publish.enabled=false} fallback (docs/plans/done/CV-TRAINING-V2-PLAN.md §7) — same
 * "no mediamtx configured, so there is nothing to pull a recorded frame from" posture {@link
 * NoopStreamPublisher} and {@code MediamtxStreamPublisher#playbackUrl}'s own unconfigured-base case
 * already take, applied to the replay-capture read side.
 */
public final class NoopReplayFrameExtractor implements ReplayFrameExtractionPort {

    @Override
    public Optional<VideoFrame> frameAt(StreamId streamId, Instant at) {
        return Optional.empty();
    }
}
