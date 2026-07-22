package com.drones.vision.adapter.rtsp;

import com.drones.vision.domain.model.StreamDescriptor;
import com.drones.vision.domain.model.StreamId;
import com.drones.vision.domain.model.VideoFrame;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Documents how to manually exercise {@link RtspVideoSource} against a real
 * RTSP server. Not run in CI -- there is no live camera/RTSP server there --
 * hence {@code @Disabled}.
 *
 * <h2>Manual run</h2>
 * <ol>
 *   <li>Start a local RTSP server, e.g. mediamtx:
 *       {@code docker run --rm -it -p 8554:8554 bluenviron/mediamtx}</li>
 *   <li>Publish a test stream into it with ffmpeg, looping a sample file:
 *       {@code ffmpeg -re -stream_loop -1 -i sample.mp4 -c copy -f rtsp rtsp://localhost:8554/mystream}</li>
 *   <li>Remove (or comment out) the {@code @Disabled} annotation below and run:
 *       {@code ./mvnw -pl adapters/adapter-rtsp test -Dtest=RtspVideoSourceLiveManualTest}</li>
 * </ol>
 */
class RtspVideoSourceLiveManualTest {

    @Disabled("needs a live RTSP server at rtsp://localhost:8554/mystream -- see class javadoc for setup")
    @Test
    void connectsToALocalRtspServerAndReceivesFrames() throws InterruptedException {
        RtspVideoSource source = new RtspVideoSource();
        StreamId streamId = StreamId.random();
        StreamDescriptor descriptor = new StreamDescriptor("rtsp",
                URI.create("rtsp://localhost:8554/mystream"),
                Map.of("rtsp_transport", "tcp"));

        CountDownLatch firstFrame = new CountDownLatch(1);
        Flow.Publisher<VideoFrame> publisher = source.open(streamId, descriptor);
        publisher.subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(VideoFrame item) {
                System.out.println("received frame seq=" + item.sequence() + " "
                        + item.width() + "x" + item.height() + " format=" + item.format());
                firstFrame.countDown();
            }

            @Override
            public void onError(Throwable throwable) {
                throwable.printStackTrace();
            }

            @Override
            public void onComplete() {
            }
        });

        try {
            assertTrue(firstFrame.await(15, TimeUnit.SECONDS), "expected at least one frame from the live stream");
        } finally {
            source.close(streamId);
        }
    }
}
