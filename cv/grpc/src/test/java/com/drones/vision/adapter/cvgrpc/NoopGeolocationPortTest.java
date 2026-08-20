package com.drones.vision.adapter.cvgrpc;

import com.drones.vision.kernel.StreamId;
import com.drones.vision.kernel.VisualFix;
import com.drones.vision.perception.domain.model.GeoSessionConfig;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests {@link NoopGeolocationPort} — the {@code vision.geo.visual.enabled=false} fallback (D9).
 */
class NoopGeolocationPortTest {

    private static final GeoSessionConfig CONFIG = new GeoSessionConfig("", 0f, null);

    @Test
    void openDeliversExactlyOneRefusedFixThenCompletesNormally() throws InterruptedException {
        NoopGeolocationPort port = new NoopGeolocationPort();
        CapturingSubscriber subscriber = new CapturingSubscriber();

        Flow.Publisher<VisualFix> publisher =
                port.open(StreamId.random(), URI.create("rtsp://localhost:8554/x"), CONFIG);
        publisher.subscribe(subscriber);

        assertTrue(subscriber.terminal.await(5, TimeUnit.SECONDS));
        assertEquals(1, subscriber.results.size());
        VisualFix fix = subscriber.results.get(0);
        assertNull(fix.position());
        assertTrue(fix.refusal().contains("disabled"));
        assertEquals(0, subscriber.errors.size(), "must complete normally, never onError -- a refusal is not an error");
    }

    @Test
    void telemetryAndCloseAreNoOpsThatNeverThrow() {
        NoopGeolocationPort port = new NoopGeolocationPort();
        StreamId id = StreamId.random();

        assertDoesNotThrow(() -> port.telemetry(id, null), "telemetry() holds no per-stream state; must never throw");
        assertDoesNotThrow(() -> port.close(id));
    }

    private static final class CapturingSubscriber implements Flow.Subscriber<VisualFix> {
        final List<VisualFix> results = new ArrayList<>();
        final List<Throwable> errors = new ArrayList<>();
        final CountDownLatch terminal = new CountDownLatch(1);

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(VisualFix item) {
            results.add(item);
        }

        @Override
        public void onError(Throwable throwable) {
            errors.add(throwable);
            terminal.countDown();
        }

        @Override
        public void onComplete() {
            terminal.countDown();
        }
    }
}
