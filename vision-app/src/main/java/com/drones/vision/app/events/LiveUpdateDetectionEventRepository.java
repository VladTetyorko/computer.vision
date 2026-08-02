package com.drones.vision.app.events;

import com.drones.vision.domain.model.DetectionEvent;
import com.drones.vision.domain.model.StreamId;
import com.drones.vision.domain.port.out.DetectionEventRepositoryPort;
import com.drones.vision.domain.port.out.LiveUpdatePublisherPort;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * {@link DetectionEventRepositoryPort} decorator that additionally announces a live {@code
 * detection-events} update (docs/REALTIME-PLAN.md §4, extended for the events UI) for every
 * upserted event.
 *
 * <p>{@code DetectionEventEngine} (vision-application) already calls {@link #save} at exactly the
 * three moments a live viewer cares about — an event opening, advancing while already open, or
 * closing — so decorating this port is the one uniform seam for "a detection event changed",
 * mirroring {@link LiveUpdateAuditTrail}'s own reasoning for asset/device changes: no new
 * constructor dependency on {@code DetectionEventEngine}/{@code DefaultStreamService} needed.
 *
 * <p>Only wired ({@link WiringConfiguration#detectionEventRepositoryPort}) when {@code
 * vision.live.enabled} is {@code true}; with it disabled, the plain delegate is used directly and
 * this class is never constructed.
 */
public final class LiveUpdateDetectionEventRepository implements DetectionEventRepositoryPort {

    private final DetectionEventRepositoryPort delegate;
    private final LiveUpdatePublisherPort liveUpdatePublisherPort;

    public LiveUpdateDetectionEventRepository(DetectionEventRepositoryPort delegate,
                                              LiveUpdatePublisherPort liveUpdatePublisherPort) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.liveUpdatePublisherPort =
                Objects.requireNonNull(liveUpdatePublisherPort, "liveUpdatePublisherPort must not be null");
    }

    @Override
    public DetectionEvent save(DetectionEvent event) {
        DetectionEvent saved = delegate.save(event);
        liveUpdatePublisherPort.publishDetectionEvent(saved);
        return saved;
    }

    @Override
    public List<DetectionEvent> findRecent(Instant sinceInclusive, int limit) {
        return delegate.findRecent(sinceInclusive, limit);
    }

    @Override
    public List<DetectionEvent> findByStream(StreamId streamId, int limit) {
        return delegate.findByStream(streamId, limit);
    }
}
