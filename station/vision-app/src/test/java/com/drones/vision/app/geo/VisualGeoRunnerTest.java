package com.drones.vision.app.geo;

import com.drones.vision.app.config.properties.VisionGeoVisualProperties;
import com.drones.vision.flight.application.TrackCorrectionService;
import com.drones.vision.flight.domain.port.TrackCorrectionRepositoryPort;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.CategoryId;
import com.drones.vision.kernel.DeviceId;
import com.drones.vision.kernel.GroupId;
import com.drones.vision.kernel.LifecycleState;
import com.drones.vision.kernel.Ownership;
import com.drones.vision.kernel.StreamDescriptor;
import com.drones.vision.kernel.StreamId;
import com.drones.vision.kernel.Telemetry;
import com.drones.vision.kernel.UsageId;
import com.drones.vision.kernel.UsageOrigin;
import com.drones.vision.warehouse.domain.model.UsagePhase;
import com.drones.vision.kernel.UserId;
import com.drones.vision.kernel.VisualFix;
import com.drones.vision.kernel.VisualFixEvidence;
import com.drones.vision.perception.application.geo.GeolocationSessionService;
import com.drones.vision.perception.application.pipeline.UsageTracker;
import com.drones.vision.perception.application.stream.ActiveStream;
import com.drones.vision.perception.application.stream.StreamService;
import com.drones.vision.perception.domain.model.GeoSessionConfig;
import com.drones.vision.perception.domain.model.PixelFormat;
import com.drones.vision.perception.domain.model.VideoFrame;
import com.drones.vision.warehouse.application.asset.AssetDetails;
import com.drones.vision.warehouse.application.asset.AssetService;
import com.drones.vision.warehouse.application.asset.AssetStatus;
import com.drones.vision.warehouse.application.asset.AssetSummary;
import com.drones.vision.warehouse.domain.model.Asset;
import com.drones.vision.warehouse.domain.model.AssetUsage;
import com.drones.vision.warehouse.domain.model.Custody;
import com.drones.vision.warehouse.domain.model.Identity;
import com.drones.vision.warehouse.domain.model.Device;
import com.drones.vision.warehouse.domain.port.AssetUsageRepositoryPort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link VisualGeoRunner}'s session lifecycle (docs/plans/done/VISUAL-GEO-V2-PLAN.md
 * §9.11 defect 1, wave H8) — real background scheduler driven at a fast tick and observed by polling,
 * the same style {@code TrackProjectionRunnerTest} (this runner's own template) already uses.
 *
 * <p>The collaborator that matters here is a hand-written {@link RecordingSessionService} rather than
 * a Mockito stub: the defect is about what happens <em>after</em> a session's publisher terminates,
 * so the test needs to hold the very {@link SubmissionPublisher} the runner subscribed to and kill it
 * mid-flight — exactly what a cv-service bounce does to the real bidi call.
 */
class VisualGeoRunnerTest {

    private static final long FAST_TICK_MILLIS = 20;

    private final List<VisualGeoRunner> runners = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (VisualGeoRunner runner : runners) {
            runner.close();
        }
    }

    @Test
    void aSessionKilledByACvServiceBounceIsReopenedOnALaterTick() throws Exception {
        Fixture fixture = new Fixture();
        VisualGeoRunner runner = fixture.newRunner();
        runner.start();

        awaitTrue(() -> fixture.sessions.starts.get() >= 1, "the runner must open a session for a flying asset");
        SubmissionPublisher<VisualFix> first = fixture.sessions.publishers.get(0);

        // What a cv-service bounce does to the live bidi call: GeolocationSession#failAndDrop closes
        // the publisher exceptionally after dropping itself from the adapter's own session map.
        first.closeExceptionally(new IllegalStateException("cv-service bounced"));

        awaitTrue(() -> fixture.sessions.starts.get() >= 2,
                "a terminated session must be reopened on a later tick -- H7's defect 1 left the dead "
                        + "session in openSessions, so ensureSessionOpen's early return ended geolocation forever");
        assertEquals(1, fixture.sessions.stops.size(),
                "the dead session must be stopped exactly once before reopening, so "
                        + "GeolocationSessionService's own open-session bookkeeping is cleared too");
        assertEquals(fixture.streamId, fixture.sessions.stops.get(0));

        // ... and fixes flow again on the fresh session.
        SubmissionPublisher<VisualFix> second = fixture.sessions.publishers.get(1);
        awaitTrue(() -> second.getNumberOfSubscribers() == 1, "the runner must subscribe to the reopened session");
        second.submit(refusedFix());

        awaitTrue(() -> verified(() -> verify(fixture.trackCorrectionService)
                        .submit(eq(fixture.assetId), eq(fixture.usageId), any(VisualFix.class), any())),
                "a fix arriving on the reopened session must reach TrackCorrectionService#submit");
    }

    @Test
    void aHealthySessionIsOpenedOnceAndNeverReopenedTickAfterTick() throws Exception {
        Fixture fixture = new Fixture();
        VisualGeoRunner runner = fixture.newRunner();
        runner.start();

        awaitTrue(() -> fixture.sessions.starts.get() >= 1, "the runner must open a session for a flying asset");
        SubmissionPublisher<VisualFix> only = fixture.sessions.publishers.get(0);
        Thread.sleep(FAST_TICK_MILLIS * 6);

        assertEquals(1, fixture.sessions.starts.get(),
                "a live session must be opened exactly once -- the terminated-session check must not "
                        + "make every tick tear down and reopen a perfectly healthy session");
        assertTrue(fixture.sessions.stops.isEmpty(), "a live session must never be stopped while still desired");
        assertSame(only, fixture.sessions.publishers.get(0));
    }

    /** Everything one flying asset with one active stream needs, so each test states only its own twist. */
    private final class Fixture {
        private final AssetId assetId = AssetId.random();
        private final DeviceId deviceId = DeviceId.random();
        private final StreamId streamId = StreamId.random();
        private final UsageId usageId = UsageId.random();
        private final RecordingSessionService sessions = new RecordingSessionService();
        private final TrackCorrectionService trackCorrectionService = mock(TrackCorrectionService.class);

        private VisualGeoRunner newRunner() {
            Asset asset = Asset.register(assetId, "drone-1", new CategoryId("drone"),
                    new Ownership(UserId.random(), GroupId.random()), Set.of(deviceId), Map.of(), Identity.NONE,
                    Custody.NONE);
            AssetSummary summary = new AssetSummary(asset, "Drone", AssetStatus.OFFLINE, null, null,
                    asset.inventoryState(), asset.identity(), asset.custody());
            Device device = new Device(deviceId, "cam-1", Set.of(),
                    new StreamDescriptor("sim", URI.create("sim://cam-1"), Map.of()), LifecycleState.ACTIVE);

            AssetService assetService = mock(AssetService.class);
            when(assetService.assets(false)).thenReturn(List.of(summary));
            when(assetService.details(assetId)).thenReturn(new AssetDetails(summary, List.of(device), List.of()));

            AssetUsageRepositoryPort usages = mock(AssetUsageRepositoryPort.class);
            when(usages.findOpenByAsset(assetId)).thenReturn(Optional.of(
                    new AssetUsage(usageId, assetId, Instant.now().minusSeconds(60), null, null, null, 0, null,
                            UsagePhase.PREFLIGHT, UsageOrigin.STREAM)));

            StreamService streamService = mock(StreamService.class);
            when(streamService.streams()).thenReturn(List.of(new ActiveStream(streamId, deviceId, Instant.now())));
            when(streamService.latestRawFrame(streamId)).thenReturn(Optional.of(new VideoFrame(
                    streamId, 0L, Instant.now(), 1920, 1080, PixelFormat.JPEG, ByteBuffer.allocate(0))));

            UsageTracker usageTracker = mock(UsageTracker.class);
            when(usageTracker.latestTelemetry(assetId)).thenReturn(Optional.empty());

            VisualGeoRunner runner = new VisualGeoRunner(assetService, usages, streamService, usageTracker,
                    sessions, trackCorrectionService, mock(TrackCorrectionRepositoryPort.class), properties());
            runners.add(runner);
            return runner;
        }
    }

    /**
     * A {@link GeolocationSessionService} that hands out one live {@link SubmissionPublisher} per
     * {@code start} and records every {@code start}/{@code stop} — and, like the real {@code
     * DefaultGeolocationSessionService}, refuses a second {@code start} for a stream that was never
     * stopped, so a test that "reopens" without closing first fails loudly instead of silently
     * leaking a session.
     */
    private static final class RecordingSessionService implements GeolocationSessionService {
        private final AtomicInteger starts = new AtomicInteger();
        private final List<SubmissionPublisher<VisualFix>> publishers = new CopyOnWriteArrayList<>();
        private final List<StreamId> stops = new CopyOnWriteArrayList<>();
        private final Set<StreamId> open = java.util.concurrent.ConcurrentHashMap.newKeySet();

        @Override
        public Flow.Publisher<VisualFix> start(StreamId streamId, URI sourceUrl, GeoSessionConfig config) {
            if (!open.add(streamId)) {
                throw new IllegalStateException("a geolocation session is already open for stream " + streamId);
            }
            SubmissionPublisher<VisualFix> publisher = new SubmissionPublisher<>();
            publishers.add(publisher);
            starts.incrementAndGet();
            return publisher;
        }

        @Override
        public void telemetry(StreamId streamId, Telemetry telemetry) {
            // no-op: this fixture's UsageTracker never reports a sample
        }

        @Override
        public Optional<Telemetry> currentTelemetry(StreamId streamId) {
            return Optional.empty();
        }

        @Override
        public boolean isOpen(StreamId streamId) {
            return open.contains(streamId);
        }

        @Override
        public void stop(StreamId streamId) {
            if (open.remove(streamId)) {
                stops.add(streamId);
            }
        }
    }

    private static VisionGeoVisualProperties properties() {
        return new VisionGeoVisualProperties(true, URI.create("rtsp://localhost:8554"), "tcp", 1.0f,
                FAST_TICK_MILLIS, java.time.Duration.ofSeconds(2), 0.0,
                new VisionGeoVisualProperties.Region(17, 4000),
                new VisionGeoVisualProperties.Tiles("https://tiles.invalid/{z}/{y}/{x}", "test", 4, 20.0,
                        java.time.Duration.ofSeconds(10), 5, "vision-geo/test", false),
                new VisionGeoVisualProperties.Upload(java.time.Duration.ofMinutes(30), 262144),
                new VisionGeoVisualProperties.Gate(5.0, 120.0, 3, 60.0, java.time.Duration.ofSeconds(10)),
                new VisionGeoVisualProperties.Divergence(3.0, 4, java.time.Duration.ofSeconds(30), 10.0),
                java.time.Duration.ofHours(12), 20000);
    }

    private static VisualFix refusedFix() {
        return new VisualFix(Instant.now(), null, null, null, null, "kyiv-maidan", "",
                "G-b_inlier_ratio",
                new VisualFixEvidence(10, 40, 2, 0.05, 0.0, 0.0, false, false, 0, 0.0, false, 0.0, 0, 1.0),
                0L, 120L);
    }

    private static boolean verified(Runnable verification) {
        try {
            verification.run();
            return true;
        } catch (AssertionError e) {
            return false;
        }
    }

    private static void awaitTrue(BooleanSupplier condition, String message) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(10);
        }
        fail(message);
    }
}
