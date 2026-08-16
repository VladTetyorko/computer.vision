package com.drones.vision.api.live;

import com.drones.vision.perception.application.stream.StreamService;
import com.drones.vision.perception.domain.port.DetectionEventRepositoryPort;
import com.drones.vision.perception.domain.port.StreamPublisherPort;
import com.drones.vision.platform.Health;
import com.drones.vision.platform.SubsystemStatus;
import com.drones.vision.warehouse.application.asset.AssetService;
import com.drones.vision.warehouse.application.device.DeviceService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@code live-updates}'s {@link com.drones.vision.platform.SubsystemStatusPort}
 * (docs/plans/active/SYSTEM-STATUS-PLAN.md §4.2) — a minimal {@link LiveUpdateRegistry} (same test
 * double shape as {@link LiveUpdateRegistryTest}, sharing its {@link ImmediateScheduledExecutorService})
 * drives the two branches: healthy with nothing ever dropped, and degraded once a capacity-1
 * "latest only" buffer (e.g. {@code fleetBuffer}) has replaced an unread entry at least once.
 */
class LiveUpdateStatusProviderTest {

    private final AssetService assetService = mock(AssetService.class);
    private final DeviceService deviceService = mock(DeviceService.class);
    private final StreamService streamService = mock(StreamService.class);
    private final StreamPublisherPort streamPublisherPort = mock(StreamPublisherPort.class);
    private final DetectionEventRepositoryPort detectionEventRepositoryPort = mock(DetectionEventRepositoryPort.class);

    private LiveUpdateRegistry registry() {
        when(assetService.assets()).thenReturn(List.of());
        when(deviceService.devices()).thenReturn(List.of());
        when(streamService.streams()).thenReturn(List.of());
        return new LiveUpdateRegistry(provider(assetService), provider(deviceService), provider(streamService),
                streamPublisherPort, provider(detectionEventRepositoryPort), new ImmediateScheduledExecutorService());
    }

    /** {@link ObjectProvider#getObject()} is a {@code default} method, not abstract -- a minimal override stands in for a mock. */
    private static <T> ObjectProvider<T> provider(T value) {
        return new ObjectProvider<>() {
            @Override
            public T getObject() {
                return value;
            }
        };
    }

    @Test
    void reportsOkWithTheOpenConnectionCountWhenNothingHasEverBeenDropped() {
        LiveUpdateRegistry registry = registry();
        LiveUpdateStatusProvider provider = new LiveUpdateStatusProvider(registry);

        SubsystemStatus status = provider.status();

        assertEquals("live-updates", status.id());
        assertEquals(Health.OK, status.health());
        assertEquals("0 live connections open", status.detail());
        assertNull(status.hint());
    }

    @Test
    void reportsDegradedOnceACapacityOneBufferHasReplacedAnUnreadEntry() {
        LiveUpdateRegistry registry = registry();
        // fleetBuffer is capacity-1/collapse-to-latest (LiveUpdateRegistry.java): a second publish
        // before the first is ever read replaces it, flipping everDropped -- exactly the "a slow
        // consumer missed an update" case this provider must surface.
        registry.publishFleetChanged();
        registry.publishFleetChanged();
        LiveUpdateStatusProvider provider = new LiveUpdateStatusProvider(registry);

        SubsystemStatus status = provider.status();

        assertEquals(Health.DEGRADED, status.health());
        assertTrue(status.detail().contains("missed at least one update"), status.detail());
        assertNotNull(status.hint());
    }
}
