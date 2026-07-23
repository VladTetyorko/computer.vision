package com.drones.vision.app;

import com.drones.vision.adapter.mjpeg.MjpegFeedTransmitter;
import com.drones.vision.adapter.rtsp.RtspFeedTransmitter;
import com.drones.vision.api.SimulationController;
import com.drones.vision.application.AssetService;
import com.drones.vision.application.CategoryService;
import com.drones.vision.application.DeviceService;
import com.drones.vision.application.FeedTransmitterRegistry;
import com.drones.vision.application.SimulationService;
import com.drones.vision.domain.model.FeedSpec;
import com.drones.vision.domain.model.Ownership;
import com.drones.vision.domain.port.out.AssetRepositoryPort;
import com.drones.vision.domain.port.out.AssetUsageRepositoryPort;
import com.drones.vision.domain.port.out.AuditTrailPort;
import com.drones.vision.domain.port.out.CategoryRepositoryPort;
import com.drones.vision.domain.port.out.FeedTransmitterPort;
import com.drones.vision.domain.port.out.TelemetryRepositoryPort;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Context test asserting {@link WiringConfiguration} registers every asset-model bean that
 * {@code AssetController}/{@code CategoryController} (component-scanned from {@code vision-api})
 * require: the service interfaces and the driven repository ports.
 *
 * <p>This test's real assertion is largely "the context loaded" — a missing bean fails
 * {@code @SpringBootTest} itself. The explicit {@code assertNotNull} calls double as a readable
 * inventory of what the asset model needs wired, mirroring {@link PublishWiringTest}/{@link
 * DiscoveryWiringTest}'s style for other wiring concerns.
 *
 * <p>The inventory shrank when the per-operation {@code *UseCase} interfaces collapsed into one
 * service interface per area: six autowired use cases became {@link AssetService} plus
 * {@link CategoryService}, which is the whole point of that refactor.
 *
 * <p>{@code vision.publish.enabled=false} for determinism, same as {@link
 * SimStreamSmokeTest}/{@link DiscoveryWiringTest} — this test doesn't care about stream egress
 * and shouldn't depend on mediamtx being reachable.
 *
 * <p>Extended for docs/CYCLES-PLAN.md §1c: {@link SimulationService} and {@link
 * SimulationController} (the one-call, zero-hardware simulation entry point) are asserted here
 * too rather than in a new test class, since they are asset-model beans built directly on top of
 * {@link AssetService}. Further extended for docs/CYCLES-PLAN.md §3: a {@link FeedTransmitterPort}
 * (backed by {@link RtspFeedTransmitter}) was asserted to resolve directly — since docs/CYCLES-PLAN.md
 * §5 generalized {@link #simulationService}'s single transmitter dependency into a {@link
 * FeedTransmitterRegistry} (mirroring {@code VideoSourceRegistry}) once a second transmit protocol
 * ({@code mjpeg}, backed by {@link MjpegFeedTransmitter}) exists alongside {@code rtsp}, this now
 * autowires the whole {@code List<FeedTransmitterPort>} and asserts both adapters are present and
 * that the registry it feeds resolves each by protocol.
 */
@SpringBootTest(properties = "vision.publish.enabled=false")
class AssetWiringTest {

    @Autowired
    private AssetService assetService;

    @Autowired
    private CategoryService categoryService;

    @Autowired
    private DeviceService deviceService;

    @Autowired
    private SimulationService simulationService;

    @Autowired
    private SimulationController simulationController;

    @Autowired
    private List<FeedTransmitterPort> feedTransmitterPorts;

    @Autowired
    private FeedTransmitterRegistry feedTransmitterRegistry;

    @Autowired
    private CategoryRepositoryPort categoryRepositoryPort;

    @Autowired
    private AssetRepositoryPort assetRepositoryPort;

    @Autowired
    private AssetUsageRepositoryPort assetUsageRepositoryPort;

    @Autowired
    private TelemetryRepositoryPort telemetryRepositoryPort;

    @Autowired
    private AuditTrailPort auditTrailPort;

    /** The principal control-plane changes are attributed to until authentication lands. */
    @Autowired
    private Ownership actingOwnership;

    @Test
    void everyAssetModelServiceAndRepositoryBeanIsRegistered() {
        assertNotNull(assetService, "AssetService bean must be registered");
        assertNotNull(categoryService, "CategoryService bean must be registered");
        assertNotNull(deviceService, "DeviceService bean must be registered");
        assertNotNull(simulationService, "SimulationService bean must be registered");
        assertNotNull(simulationController, "SimulationController must resolve its constructor dependencies");
        assertNotNull(categoryRepositoryPort, "CategoryRepositoryPort bean must be registered");
        assertNotNull(assetRepositoryPort, "AssetRepositoryPort bean must be registered");
        assertNotNull(assetUsageRepositoryPort, "AssetUsageRepositoryPort bean must be registered");
        assertNotNull(telemetryRepositoryPort, "TelemetryRepositoryPort bean must be registered");
        assertNotNull(auditTrailPort, "AuditTrailPort bean must be registered");
        assertNotNull(actingOwnership, "Ownership bean must be registered for CurrentUser to fall back to");
    }

    @Test
    void bothRtspAndMjpegFeedTransmittersAreWiredAsFeedTransmitterPortBeans() {
        assertTrue(feedTransmitterPorts.stream().anyMatch(RtspFeedTransmitter.class::isInstance),
                "docs/CYCLES-PLAN.md §3's transport=rtsp simulations need a real FeedTransmitterPort");
        assertTrue(feedTransmitterPorts.stream().anyMatch(MjpegFeedTransmitter.class::isInstance),
                "docs/CYCLES-PLAN.md §5's transport=mjpeg simulations need a real FeedTransmitterPort");
    }

    @Test
    void feedTransmitterRegistryResolvesRtspAndMjpegByProtocol() {
        FeedSpec rtspSpec = new FeedSpec("rtsp", URI.create("file:///tmp/clip.mp4"), Map.of());
        FeedSpec mjpegSpec = new FeedSpec("mjpeg", URI.create("file:///tmp/clip.mp4"), Map.of());

        assertInstanceOf(RtspFeedTransmitter.class, feedTransmitterRegistry.transmitterFor(rtspSpec));
        assertInstanceOf(MjpegFeedTransmitter.class, feedTransmitterRegistry.transmitterFor(mjpegSpec));
    }
}
