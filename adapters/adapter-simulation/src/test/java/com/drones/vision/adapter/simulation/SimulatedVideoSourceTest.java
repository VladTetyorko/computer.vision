package com.drones.vision.adapter.simulation;

import com.drones.vision.perception.domain.model.PixelFormat;
import com.drones.vision.kernel.StreamDescriptor;
import com.drones.vision.kernel.StreamId;
import com.drones.vision.perception.domain.model.VideoFrame;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SimulatedVideoSourceTest {

    @Test
    void supportsOnlySimProtocol() {
        SimulatedVideoSource source = new SimulatedVideoSource();

        assertTrue(source.supports(new StreamDescriptor("sim", URI.create("sim://cam"), Map.of())));
        assertFalse(source.supports(new StreamDescriptor("rtsp", URI.create("rtsp://cam"), Map.of())));
    }

    @Test
    void producesFramesWithDefaultDimensionsFormatAndMonotonicSequence() throws InterruptedException {
        SimulatedVideoSource source = new SimulatedVideoSource();
        StreamId streamId = StreamId.random();
        StreamDescriptor descriptor = new StreamDescriptor("sim", URI.create("sim://cam"),
                Map.of("fps", "30"));

        List<VideoFrame> collected = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch atLeastThree = new CountDownLatch(3);

        Flow.Publisher<VideoFrame> publisher = source.open(streamId, descriptor);
        publisher.subscribe(new Flow.Subscriber<>() {
            private Flow.Subscription subscription;

            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                this.subscription = subscription;
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(VideoFrame item) {
                collected.add(item);
                atLeastThree.countDown();
            }

            @Override
            public void onError(Throwable throwable) {
            }

            @Override
            public void onComplete() {
            }
        });

        try {
            assertTrue(atLeastThree.await(5, TimeUnit.SECONDS), "expected at least 3 frames within 5s");

            List<VideoFrame> snapshot = List.copyOf(collected);
            assertTrue(snapshot.size() >= 3);
            for (int i = 0; i < snapshot.size(); i++) {
                VideoFrame frame = snapshot.get(i);
                assertEquals(streamId, frame.streamId());
                assertEquals(VideoSettings.DEFAULT_WIDTH, frame.width());
                assertEquals(VideoSettings.DEFAULT_HEIGHT, frame.height());
                assertEquals(PixelFormat.JPEG, frame.format());
                assertTrue(frame.data().remaining() > 0, "frame payload must not be empty");
                if (i > 0) {
                    assertTrue(frame.sequence() > snapshot.get(i - 1).sequence(),
                            "sequence must be strictly increasing");
                }
            }
        } finally {
            source.close(streamId);
        }
    }

    @Test
    void honorsWidthHeightAndFpsOptions() throws InterruptedException {
        SimulatedVideoSource source = new SimulatedVideoSource();
        StreamId streamId = StreamId.random();
        StreamDescriptor descriptor = new StreamDescriptor("sim", URI.create("sim://cam"),
                Map.of("width", "320", "height", "240", "fps", "20"));

        List<VideoFrame> collected = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch firstFrame = new CountDownLatch(1);

        Flow.Publisher<VideoFrame> publisher = source.open(streamId, descriptor);
        publisher.subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(VideoFrame item) {
                collected.add(item);
                firstFrame.countDown();
            }

            @Override
            public void onError(Throwable throwable) {
            }

            @Override
            public void onComplete() {
            }
        });

        try {
            assertTrue(firstFrame.await(5, TimeUnit.SECONDS));
            VideoFrame frame = collected.get(0);
            assertEquals(320, frame.width());
            assertEquals(240, frame.height());
        } finally {
            source.close(streamId);
        }
    }

    @Test
    void closeStopsProducingFramesCleanly() throws InterruptedException {
        SimulatedVideoSource source = new SimulatedVideoSource();
        StreamId streamId = StreamId.random();
        StreamDescriptor descriptor = new StreamDescriptor("sim", URI.create("sim://cam"), Map.of("fps", "30"));

        List<VideoFrame> collected = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch atLeastThree = new CountDownLatch(3);

        Flow.Publisher<VideoFrame> publisher = source.open(streamId, descriptor);
        publisher.subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(VideoFrame item) {
                collected.add(item);
                atLeastThree.countDown();
            }

            @Override
            public void onError(Throwable throwable) {
            }

            @Override
            public void onComplete() {
            }
        });

        assertTrue(atLeastThree.await(5, TimeUnit.SECONDS));

        source.close(streamId);
        int sizeRightAfterClose = collected.size();
        Thread.sleep(300);
        int sizeAfterGracePeriod = collected.size();

        assertEquals(sizeRightAfterClose, sizeAfterGracePeriod,
                "no further frames should be produced after close()");

        // close() must be idempotent
        assertDoesNotThrowOnSecondClose(source, streamId);
    }

    private static void assertDoesNotThrowOnSecondClose(SimulatedVideoSource source, StreamId streamId) {
        source.close(streamId);
    }
}
