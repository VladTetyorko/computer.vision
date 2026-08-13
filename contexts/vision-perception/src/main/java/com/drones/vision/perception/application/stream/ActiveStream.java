package com.drones.vision.perception.application.stream;

import com.drones.vision.kernel.DeviceId;
import com.drones.vision.kernel.StreamId;

import java.time.Instant;

/**
 * A stream that is running right now.
 *
 * @param streamId  the stream's identity
 * @param deviceId  the device it is pulling frames from
 * @param startedAt when it started
 * @param burnedIn  whether server-side overlay burn-in is actually active for this stream
 *                  (docs/plans/active/MEDIA-SOT-PLAN.md &sect;5.4) — {@code false} exactly when nothing burns
 *                  detection boxes into the published video (a proxied source — no JVM frame is ever
 *                  published for the overlay renderer to burn into — or no {@code OverlayPort} wired,
 *                  or {@code overlayBurnIn} off for this stream), {@code true} otherwise. The
 *                  detection transport (push or pull) plays no part: a JVM-published, pull-detected
 *                  stream burns boxes exactly like push mode does
 */
public record ActiveStream(StreamId streamId, DeviceId deviceId, Instant startedAt, boolean burnedIn) {

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
    }

    /**
     * The shape before {@link #burnedIn()} was added (docs/plans/active/MEDIA-SOT-PLAN.md &sect;5.4, wave M5),
     * kept as a convenience constructor defaulting it to {@code true} — today's behavior for every
     * pre-existing call site (push-mode streaming, the only kind that existed before this wave). Same
     * "N-1-arg convenience ctor" idiom the domain records use.
     */
    public ActiveStream(StreamId streamId, DeviceId deviceId, Instant startedAt) {
        this(streamId, deviceId, startedAt, true);
    }
}
