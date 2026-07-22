package com.drones.vision.app;

import com.drones.vision.application.AssetService;
import com.drones.vision.application.CategoryService;
import com.drones.vision.application.DeviceService;
import com.drones.vision.domain.model.Ownership;
import com.drones.vision.domain.port.out.AssetRepositoryPort;
import com.drones.vision.domain.port.out.AssetUsageRepositoryPort;
import com.drones.vision.domain.port.out.AuditTrailPort;
import com.drones.vision.domain.port.out.CategoryRepositoryPort;
import com.drones.vision.domain.port.out.TelemetryRepositoryPort;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertNotNull;

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
        assertNotNull(categoryRepositoryPort, "CategoryRepositoryPort bean must be registered");
        assertNotNull(assetRepositoryPort, "AssetRepositoryPort bean must be registered");
        assertNotNull(assetUsageRepositoryPort, "AssetUsageRepositoryPort bean must be registered");
        assertNotNull(telemetryRepositoryPort, "TelemetryRepositoryPort bean must be registered");
        assertNotNull(auditTrailPort, "AuditTrailPort bean must be registered");
        assertNotNull(actingOwnership, "Ownership bean must be registered for CurrentUser to fall back to");
    }
}
