package com.drones.vision.app;

import com.drones.vision.app.devsupport.DevPrincipal;
import com.drones.vision.application.asset.AssetService;
import com.drones.vision.application.asset.AssetSpec;
import com.drones.vision.application.device.DeviceRegistration;
import com.drones.vision.warehouse.domain.model.Asset;
import com.drones.vision.kernel.Capability;
import com.drones.vision.kernel.CategoryId;
import com.drones.vision.perception.domain.model.PipelineConfig;
import com.drones.vision.kernel.StreamDescriptor;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Resilience smoke test for docs/plans/done/MVP1-PLAN.md §C7 bullet 4's done criterion "killing the [CV]
 * service mid-stream leaves video/telemetry running": with {@code vision.cv.enabled=true} but
 * {@code vision.cv.endpoint} pointed at a port nothing is listening on, a {@code sim}-device
 * stream must still flow video to {@link com.drones.vision.perception.domain.port.StreamPublisherPort}
 * and must not crash the pipeline or the application context — proving a real, wired {@code
 * GrpcDetectionPort} degrades exactly like {@code NoopDetectionPort} once {@code
 * StreamPipeline}'s detection-outage/backoff policy (vision-application, docs/plans/done/MVP1-PLAN.md §C7
 * bullet 3) takes over.
 *
 * <p>The unreachable port is obtained by briefly binding a {@link ServerSocket} to an ephemeral
 * port and immediately closing it, so nothing listens there for the rest of the test — the same
 * "reserve, then let go" technique used to hand a genuinely free port to a {@link
 * DynamicPropertySource} before the Spring context loads.
 *
 * <p>Reuses {@link FileSimulationSmokeTest.RecordingPublisherConfig}/{@link
 * FileSimulationSmokeTest.RecordingStreamPublisher}, same as {@link CvDetectionE2ETest}.
 */
@SpringBootTest(properties = "vision.publish.enabled=false")
@Import(FileSimulationSmokeTest.RecordingPublisherConfig.class)
class CvDetectionResilienceSmokeTest {

    private static int unboundPort;

    @BeforeAll
    static void reserveUnboundPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            unboundPort = socket.getLocalPort();
        }
    }

    @DynamicPropertySource
    static void cvProperties(DynamicPropertyRegistry registry) {
        registry.add("vision.cv.enabled", () -> "true");
        registry.add("vision.cv.endpoint", () -> "localhost:" + unboundPort);
    }

    @Autowired
    private AssetService assetService;

    @Autowired
    private FileSimulationSmokeTest.RecordingStreamPublisher recordingStreamPublisher;

    @Test
    void streamKeepsFlowingWhenTheConfiguredCvEndpointIsUnreachable() throws InterruptedException {
        DeviceRegistration videoDevice = new DeviceRegistration(
                "cv-resilience-sim-camera", Set.of(Capability.VIDEO),
                new StreamDescriptor("sim", URI.create("sim://cv-resilience-video"), Map.of()));

        Asset asset = assetService.create(new AssetSpec("CV Resilience Test Drone",
                        new CategoryId("drone"), Map.of(), List.of(videoDevice)),
                DevPrincipal.OWNERSHIP, DevPrincipal.USER_ID);

        assetService.startStream(asset.id(), null, PipelineConfig.defaults());
        try {
            boolean receivedFrame = recordingStreamPublisher.awaitFirstFrame(10, TimeUnit.SECONDS);
            assertTrue(receivedFrame,
                    "expected video to keep flowing even though the configured CV endpoint is "
                            + "unreachable -- StreamPipeline's outage/backoff policy must degrade a "
                            + "real GrpcDetectionPort's connection failures exactly like NoopDetectionPort");
            assertFalse(recordingStreamPublisher.frames().isEmpty());
        } finally {
            assetService.stopStream(asset.id());
        }
    }
}
