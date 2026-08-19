package com.drones.vision.perception.application.geo;

import com.drones.vision.kernel.DeviceId;
import com.drones.vision.kernel.StreamId;
import com.drones.vision.kernel.Telemetry;
import com.drones.vision.kernel.VisualFix;
import com.drones.vision.kernel.VisualFixEvidence;
import com.drones.vision.perception.domain.model.GeoSessionConfig;
import com.drones.vision.perception.domain.port.PulledGeolocationPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PulledGeolocationPort} is a hand-rolled in-memory fake (this module's dominant style) --
 * no cross-context service is a dependency here, so nothing is a Mockito mock.
 */
class DefaultGeolocationSessionServiceTest {

    private static final StreamId STREAM_ID = StreamId.random();
    private static final URI SOURCE_URL = URI.create("rtsp://localhost:8554/stream-1");
    private static final GeoSessionConfig CONFIG = new GeoSessionConfig("kyiv-pozniaky", 1.0f, null);

    private FakePulledGeolocationPort port;
    private DefaultGeolocationSessionService service;

    @BeforeEach
    void setUp() {
        port = new FakePulledGeolocationPort();
        service = new DefaultGeolocationSessionService(port);
    }

    @Test
    void startOpensExactlyOneSessionAndReturnsThePortsPublisher() {
        Flow.Publisher<VisualFix> publisher = service.start(STREAM_ID, SOURCE_URL, CONFIG);

        assertEquals(1, port.openCalls.size());
        assertEquals(STREAM_ID, port.openCalls.get(0).streamId());
        assertEquals(SOURCE_URL, port.openCalls.get(0).sourceUrl());
        assertEquals(CONFIG, port.openCalls.get(0).config());
        assertSame(port.publisherFor(STREAM_ID), publisher);
        assertTrue(service.isOpen(STREAM_ID));
    }

    @Test
    void aVisualFixPublishedByThePortReachesTheCallersSubscriber() {
        Flow.Publisher<VisualFix> publisher = service.start(STREAM_ID, SOURCE_URL, CONFIG);
        AtomicReference<VisualFix> received = new AtomicReference<>();
        publisher.subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(1);
            }

            @Override
            public void onNext(VisualFix item) {
                received.set(item);
            }

            @Override
            public void onError(Throwable throwable) {
            }

            @Override
            public void onComplete() {
            }
        });

        VisualFix fix = refusedFix();
        port.emit(STREAM_ID, fix);

        assertSame(fix, received.get());
    }

    @Test
    void startingTwiceForTheSameStreamThrowsAndOpensOnlyOnce() {
        service.start(STREAM_ID, SOURCE_URL, CONFIG);

        assertThrows(IllegalStateException.class, () -> service.start(STREAM_ID, SOURCE_URL, CONFIG));
        assertEquals(1, port.openCalls.size());
    }

    @Test
    void telemetryIsANoOpForAnUnopenedStream() {
        service.telemetry(STREAM_ID, telemetryAt(1.0));

        assertTrue(port.telemetryCalls.isEmpty());
        assertTrue(service.currentTelemetry(STREAM_ID).isEmpty());
    }

    @Test
    void telemetryForwardsEveryCallAndLatestWinsForTheCachedReading() {
        service.start(STREAM_ID, SOURCE_URL, CONFIG);
        Telemetry first = telemetryAt(1.0);
        Telemetry second = telemetryAt(2.0);

        service.telemetry(STREAM_ID, first);
        service.telemetry(STREAM_ID, second);

        assertEquals(List.of(first, second), port.telemetryCalls.stream().map(TelemetryCall::telemetry).toList());
        Optional<Telemetry> current = service.currentTelemetry(STREAM_ID);
        assertTrue(current.isPresent());
        assertEquals(second, current.get(), "currentTelemetry must reflect only the latest sample, not the first");
    }

    @Test
    void stopClosesAndClearsTelemetryAndIsIdempotent() {
        service.start(STREAM_ID, SOURCE_URL, CONFIG);
        service.telemetry(STREAM_ID, telemetryAt(1.0));

        service.stop(STREAM_ID);

        assertEquals(List.of(STREAM_ID), port.closed);
        assertFalse(service.isOpen(STREAM_ID));
        assertTrue(service.currentTelemetry(STREAM_ID).isEmpty());

        service.stop(STREAM_ID); // idempotent -- no second close
        assertEquals(List.of(STREAM_ID), port.closed);
    }

    @Test
    void stopForAnUnopenedStreamIsANoOp() {
        service.stop(STREAM_ID);

        assertTrue(port.closed.isEmpty());
    }

    @Test
    void isOpenReflectsSessionLifecycle() {
        assertFalse(service.isOpen(STREAM_ID));

        service.start(STREAM_ID, SOURCE_URL, CONFIG);
        assertTrue(service.isOpen(STREAM_ID));

        service.stop(STREAM_ID);
        assertFalse(service.isOpen(STREAM_ID));
    }

    private static Telemetry telemetryAt(double latitude) {
        return new Telemetry(DeviceId.random(), Instant.parse("2026-08-19T12:00:00Z"), latitude, 30.0, 100.0, null,
                null, Map.of());
    }

    private static VisualFix refusedFix() {
        VisualFixEvidence evidence = new VisualFixEvidence(2, 5, 1, 0.1, 0.05, 8.0, true, false, 1, 0.0, false, 0.0,
                0, 1.0);
        return new VisualFix(Instant.parse("2026-08-19T12:00:00Z"), null, null, null, null, "kyiv-pozniaky", "",
                "LOW_TEXTURE", evidence, 200, 300);
    }

    private record Call(StreamId streamId, URI sourceUrl, GeoSessionConfig config) {
    }

    private record TelemetryCall(StreamId streamId, Telemetry telemetry) {
    }

    private static final class FakePulledGeolocationPort implements PulledGeolocationPort {
        private final List<Call> openCalls = new ArrayList<>();
        private final List<TelemetryCall> telemetryCalls = new ArrayList<>();
        private final List<StreamId> closed = new ArrayList<>();
        private final Map<StreamId, Flow.Subscriber<? super VisualFix>> subscribers = new HashMap<>();
        private final Map<StreamId, Flow.Publisher<VisualFix>> publishers = new HashMap<>();

        @Override
        public Flow.Publisher<VisualFix> open(StreamId id, URI sourceUrl, GeoSessionConfig config) {
            openCalls.add(new Call(id, sourceUrl, config));
            Flow.Publisher<VisualFix> publisher = subscriber -> {
                subscribers.put(id, subscriber);
                subscriber.onSubscribe(new Flow.Subscription() {
                    @Override
                    public void request(long n) {
                        // test drives delivery directly via emit()
                    }

                    @Override
                    public void cancel() {
                        // no-op
                    }
                });
            };
            publishers.put(id, publisher);
            return publisher;
        }

        @Override
        public void telemetry(StreamId id, Telemetry telemetry) {
            telemetryCalls.add(new TelemetryCall(id, telemetry));
        }

        @Override
        public void close(StreamId id) {
            closed.add(id);
        }

        Flow.Publisher<VisualFix> publisherFor(StreamId id) {
            return publishers.get(id);
        }

        void emit(StreamId id, VisualFix fix) {
            subscribers.get(id).onNext(fix);
        }
    }
}
