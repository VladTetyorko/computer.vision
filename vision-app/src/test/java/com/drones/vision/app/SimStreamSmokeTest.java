package com.drones.vision.app;

import com.drones.vision.warehouse.application.asset.AssetService;
import com.drones.vision.perception.application.stream.AssetStreamService;
import com.drones.vision.warehouse.application.asset.AssetSpec;
import com.drones.vision.warehouse.application.device.DeviceRegistration;
import com.drones.vision.app.devsupport.DevPrincipal;
import com.drones.vision.warehouse.domain.model.Asset;
import com.drones.vision.warehouse.domain.model.AssetUsage;
import com.drones.vision.kernel.Capability;
import com.drones.vision.kernel.CategoryId;
import com.drones.vision.warehouse.domain.model.Device;
import com.drones.vision.perception.domain.model.PipelineConfig;
import com.drones.vision.kernel.StreamDescriptor;
import com.drones.vision.kernel.StreamId;
import com.drones.vision.kernel.Telemetry;
import com.drones.vision.kernel.UsageId;
import com.drones.vision.perception.domain.model.VideoFrame;
import com.drones.vision.warehouse.domain.port.AssetUsageRepositoryPort;
import com.drones.vision.perception.domain.port.StreamPublisherPort;
import com.drones.vision.flight.domain.port.TelemetryRepositoryPort;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end smoke test: loads the full Spring context (production wiring
 * from {@link WiringConfiguration}, with only {@link StreamPublisherPort}
 * swapped for a frame-recording test double), creates an asset with a
 * {@code sim} video device and a {@code sim} telemetry device purely through
 * the asset-first use-case beans, starts its stream, observes both a real
 * video frame from {@code SimulatedVideoSource} <b>and</b> at least two
 * telemetry samples from {@code SimulatedTelemetrySource} accumulating on
 * the asset's open {@code AssetUsage}, then stops the stream and asserts the
 * usage closed with a populated start/last position and sample count — the
 * extended scenario from docs/plans/done/ASSET-MODEL-PLAN.md M4.
 *
 * <p>{@code vision.publish.enabled=false} is set here for determinism even
 * though it wouldn't otherwise matter: {@link RecordingPublisherConfig}'s
 * {@code RecordingStreamPublisher} is {@code @Primary} and always wins the
 * autowiring, and {@code MediamtxStreamPublisher} tolerates a missing
 * mediamtx by design (see docs/plans/done/PHASE1-PLAN.md §0.3) — but this test
 * shouldn't depend on that resilience, nor on mediamtx being reachable at
 * all, to stay green.
 *
 * <p><b>Telemetry cadence:</b> {@code SimulatedTelemetrySource}'s
 * package-private constructor test seam (a faster-than-1Hz interval, see
 * adapter-simulation/MODULE.md) lives in {@code
 * com.drones.vision.adapter.simulation} and isn't reachable from this
 * module's {@code com.drones.vision.app} test package, nor is it the bean
 * {@link WiringConfiguration} wires (production always uses the public
 * no-arg, real 1&nbsp;Hz constructor). This test therefore polls the
 * in-memory telemetry repository at the real cadence with a generous but
 * bounded wait ({@link #TELEMETRY_TIMEOUT}) rather than depending on the
 * test-only fast interval.
 */
@SpringBootTest(properties = "vision.publish.enabled=false")
@Import(SimStreamSmokeTest.RecordingPublisherConfig.class)
class SimStreamSmokeTest {

    /** Bounded wait for >=2 samples at the sim telemetry source's real 1Hz cadence. */
    private static final Duration TELEMETRY_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration TELEMETRY_POLL_INTERVAL = Duration.ofMillis(200);

    @Autowired
    private AssetService assetService;

    @Autowired
    private AssetStreamService assetStreamService;

    @Autowired
    private AssetUsageRepositoryPort assetUsageRepositoryPort;

    @Autowired
    private TelemetryRepositoryPort telemetryRepositoryPort;

    @Autowired
    private RecordingStreamPublisher recordingStreamPublisher;

    @Test
    void createsAssetStartsStreamObservesFramesAndTelemetryThenStopsWithClosedUsage() throws InterruptedException {
        DeviceRegistration videoDevice = new DeviceRegistration(
                "smoke-sim-camera", Set.of(Capability.VIDEO),
                new StreamDescriptor("sim", URI.create("sim://smoke-video"), Map.of("fps", "30")));
        DeviceRegistration telemetryDevice = new DeviceRegistration(
                "smoke-sim-gps", Set.of(Capability.TELEMETRY),
                new StreamDescriptor("sim", URI.create("sim://smoke-telemetry"), Map.of()));

        Asset asset = assetService.create(new AssetSpec("Smoke Test Drone",
                new CategoryId("drone"), Map.of(), List.of(videoDevice, telemetryDevice)),
                DevPrincipal.OWNERSHIP, DevPrincipal.USER_ID);

        StreamId streamId = assetStreamService.startStream(asset.id(), null, PipelineConfig.defaults());
        try {
            boolean receivedFrame = recordingStreamPublisher.awaitFirstFrame(5, TimeUnit.SECONDS);
            assertTrue(receivedFrame, "expected at least one frame to reach StreamPublisherPort within 5s");
            assertFalse(recordingStreamPublisher.frames().isEmpty());
            assertTrue(recordingStreamPublisher.frames().stream().allMatch(f -> f.streamId().equals(streamId)));

            AssetUsage openUsage = assetUsageRepositoryPort.findOpenByAsset(asset.id())
                    .orElseThrow(() -> new AssertionError(
                            "expected an open AssetUsage immediately after starting the asset's stream"));

            List<Telemetry> samples = awaitAtLeastTwoTelemetrySamples(openUsage.id());
            assertTrue(samples.size() >= 2, "expected >=2 telemetry samples within " + TELEMETRY_TIMEOUT
                    + " of the sim telemetry source's 1Hz cadence, got " + samples.size());
        } finally {
            assetService.stopStream(asset.id());
        }

        AssetUsage closedUsage = assetUsageRepositoryPort.findRecentByAsset(asset.id(), 1).stream().findFirst()
                .orElseThrow(() -> new AssertionError("expected a persisted usage after stopping the asset stream"));
        assertNotNull(closedUsage.endedAt(), "usage must be closed after stopping the asset's stream");
        assertNotNull(closedUsage.startPosition(), "usage must have a start position from telemetry sampling");
        assertNotNull(closedUsage.lastPosition(), "usage must have a last position from telemetry sampling");
        assertTrue(closedUsage.sampleCount() >= 2,
                "expected a closed usage sampleCount >= 2, got " + closedUsage.sampleCount());
    }

    private List<Telemetry> awaitAtLeastTwoTelemetrySamples(UsageId usageId) throws InterruptedException {
        Instant deadline = Instant.now().plus(TELEMETRY_TIMEOUT);
        List<Telemetry> samples = List.of();
        while (Instant.now().isBefore(deadline)) {
            samples = telemetryRepositoryPort.findByUsage(usageId, 100);
            if (samples.size() >= 2) {
                return samples;
            }
            Thread.sleep(TELEMETRY_POLL_INTERVAL.toMillis());
        }
        return samples;
    }

    @TestConfiguration
    static class RecordingPublisherConfig {

        @Bean
        @Primary
        RecordingStreamPublisher recordingStreamPublisher() {
            return new RecordingStreamPublisher();
        }
    }

    /** Test-only {@link StreamPublisherPort} that records published frames instead of discarding them. */
    static final class RecordingStreamPublisher implements StreamPublisherPort {

        private final List<VideoFrame> frames = new CopyOnWriteArrayList<>();
        private final CountDownLatch firstFrame = new CountDownLatch(1);

        @Override
        public void streamStarted(StreamId id, Device device) {
            // no-op
        }

        @Override
        public void publish(StreamId id, VideoFrame frame) {
            frames.add(frame);
            firstFrame.countDown();
        }

        @Override
        public void streamEnded(StreamId id) {
            // no-op
        }

        boolean awaitFirstFrame(long timeout, TimeUnit unit) throws InterruptedException {
            return firstFrame.await(timeout, unit);
        }

        List<VideoFrame> frames() {
            return frames;
        }
    }
}
