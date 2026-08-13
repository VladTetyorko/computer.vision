package com.drones.vision.perception.domain.port;

import com.drones.vision.kernel.Capability;
import com.drones.vision.warehouse.domain.model.Device;
import com.drones.vision.kernel.DeviceId;
import com.drones.vision.kernel.StreamDescriptor;
import com.drones.vision.kernel.StreamId;
import com.drones.vision.perception.domain.model.VideoFrame;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Pins {@link StreamPublisherPort#proxiesSource(Device)}'s default: an implementation that does
 * not override it (every implementation in the tree today, per docs/plans/active/MEDIA-SOT-PLAN.md
 * &sect;4 D4) must report that it does not proxy the source, so {@code DefaultStreamService} keeps
 * opening a {@code VideoSourcePort} exactly as it does today.
 */
class StreamPublisherPortTest {

    private static final class MinimalStreamPublisher implements StreamPublisherPort {
        @Override
        public void streamStarted(StreamId id, Device device) {
            // no-op; only proxiesSource's default is under test
        }

        @Override
        public void publish(StreamId id, VideoFrame frame) {
            // no-op; only proxiesSource's default is under test
        }

        @Override
        public void streamEnded(StreamId id) {
            // no-op; only proxiesSource's default is under test
        }
    }

    @Test
    void proxiesSourceDefaultsToFalse() {
        StreamPublisherPort publisher = new MinimalStreamPublisher();
        Device device = new Device(DeviceId.random(), "cam-1", Set.of(Capability.VIDEO),
                new StreamDescriptor("rtsp", URI.create("rtsp://cam/stream"), Map.of()));

        assertFalse(publisher.proxiesSource(device));
    }
}
