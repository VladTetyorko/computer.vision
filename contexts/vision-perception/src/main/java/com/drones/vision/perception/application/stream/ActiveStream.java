package com.drones.vision.perception.application.stream;

import com.drones.vision.kernel.DeviceId;
import com.drones.vision.kernel.StreamId;
import com.drones.vision.perception.domain.model.StreamState;

import java.time.Instant;

/**
 * A stream that is running right now.
 *
 * @param streamId  the stream's identity
 * @param deviceId  the device it is pulling frames from
 * @param startedAt when it started
 * @param state     whether this stream's <b>video</b> is actually flowing right now
 *                  (docs/plans/done/STREAM-STATE-PLAN.md &sect;2.3) — non-null. Reports video flow
 *                  only; it says nothing about detection, which {@code detectionEnabled} below and
 *                  {@code DetectionState} answer on their own separate axes
 * @param detectionEnabled the operator's own per-stream detect-on/off intent
 *                  ({@code PipelineConfig#detectionEnabled()}), carried here because this record is
 *                  what {@code GET /api/streams} is built from and that intent had <b>no read
 *                  surface at all</b> before this plan — leaving every client to render its own
 *                  local guess of a value only the server knows
 */
public record ActiveStream(StreamId streamId, DeviceId deviceId, Instant startedAt,
                            StreamState state, boolean detectionEnabled) {

    public ActiveStream {
        if (streamId == null) {
            throw new IllegalArgumentException("ActiveStream streamId must not be null");
        }
        if (deviceId == null) {
            throw new IllegalArgumentException("ActiveStream deviceId must not be null");
        }
        if (startedAt == null) {
            throw new IllegalArgumentException("ActiveStream startedAt must not be null");
        }
        if (state == null) {
            throw new IllegalArgumentException("ActiveStream state must not be null");
        }
    }

    /**
     * The shape before {@link #state()}/{@link #detectionEnabled()} were added
     * (docs/plans/done/STREAM-STATE-PLAN.md &sect;2.3), kept as a convenience constructor.
     *
     * <p>{@code state} defaults to {@link StreamState#UNOBSERVED} and {@code detectionEnabled} to
     * {@code false} — both are the "we were not told" answers, deliberately, not optimistic ones. A
     * caller that omits the state has not established that video is flowing, and {@code UNOBSERVED}
     * is precisely the state that says "cannot judge" without claiming a fault; defaulting to
     * {@link StreamState#LIVE} would manufacture a fact. {@code false} likewise mirrors
     * {@code PipelineConfig.DEFAULT_DETECTION_ENABLED} since docs/plans/done/CV-DEMAND-PLAN.md
     * wave D1.
     */
    public ActiveStream(StreamId streamId, DeviceId deviceId, Instant startedAt) {
        this(streamId, deviceId, startedAt, StreamState.UNOBSERVED, false);
    }
}
