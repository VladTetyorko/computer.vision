package com.drones.vision.app.devsupport;

import com.drones.vision.warehouse.domain.model.Device;
import com.drones.vision.kernel.StreamId;
import com.drones.vision.perception.domain.model.VideoFrame;
import com.drones.vision.perception.domain.port.StreamPublisherPort;

/**
 * No-op {@link StreamPublisherPort}: does not fan frames out anywhere;
 * dev/Phase-0 fallback so pipelines can run end to end without a viewer.
 *
 * <p>Replaced by {@code adapter-publish-hls} (HLS/LL-HLS egress), planned
 * for Phase 1.
 */
public final class NoopStreamPublisher implements StreamPublisherPort {

    @Override
    public void streamStarted(StreamId id, Device device) {
        // no-op
    }

    @Override
    public void publish(StreamId id, VideoFrame frame) {
        // no-op
    }

    @Override
    public void streamEnded(StreamId id) {
        // no-op
    }
}
