package com.drones.vision.application.geofence;

import com.drones.vision.domain.model.AssetId;
import com.drones.vision.domain.model.DeviceId;
import com.drones.vision.domain.model.Event;
import com.drones.vision.domain.model.EventType;
import com.drones.vision.domain.model.GeoPosition;
import com.drones.vision.domain.model.GeofenceZone;
import com.drones.vision.domain.model.Telemetry;
import com.drones.vision.domain.model.ZoneId;
import com.drones.vision.domain.model.ZoneKind;
import com.drones.vision.domain.port.out.EventPublisherPort;
import com.drones.vision.domain.port.out.GeofenceRepositoryPort;
import com.drones.vision.domain.port.out.LiveUpdatePublisherPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GeofenceMonitorTest {

    private static final DeviceId DEVICE_ID = DeviceId.random();

    private GeofenceRepositoryPort geofenceRepository;
    private EventPublisherPort eventPublisher;
    private LiveUpdatePublisherPort liveUpdatePublisherPort;

    @BeforeEach
    void setUp() {
        geofenceRepository = mock(GeofenceRepositoryPort.class);
        eventPublisher = mock(EventPublisherPort.class);
        liveUpdatePublisherPort = mock(LiveUpdatePublisherPort.class);
    }

    private GeofenceMonitor monitor() {
        return new GeofenceMonitor(geofenceRepository, eventPublisher, liveUpdatePublisherPort);
    }

    /** A 10x10 square: lat/lon in [10,20]. */
    private static List<GeoPosition> square() {
        return List.of(
                new GeoPosition(10, 10, null),
                new GeoPosition(10, 20, null),
                new GeoPosition(20, 20, null),
                new GeoPosition(20, 10, null));
    }

    private static GeofenceZone zone(ZoneKind kind, boolean enabled, Double maxAltitudeMeters) {
        return new GeofenceZone(ZoneId.random(), "test zone", kind, square(), maxAltitudeMeters, enabled);
    }

    private static GeofenceZone zone(ZoneKind kind) {
        return zone(kind, true, null);
    }

    private static Telemetry telemetry(Double lat, Double lon, Double altitude) {
        return new Telemetry(DEVICE_ID, Instant.now(), lat, lon, altitude, null, null, Map.of());
    }

    // --- KEEP_OUT ------------------------------------------------------------

    @Test
    void keepOutBreachEntersOnceWhenPositionMovesInside() {
        GeofenceZone outZone = zone(ZoneKind.KEEP_OUT);
        when(geofenceRepository.findAll()).thenReturn(List.of(outZone));
        GeofenceMonitor monitor = monitor();
        AssetId assetId = AssetId.random();

        monitor.evaluate(assetId, telemetry(15.0, 15.0, null)); // inside -> enter

        ArgumentCaptor<Event> captor = ArgumentCaptor.forClass(Event.class);
        verify(eventPublisher, times(1)).publish(captor.capture());
        verify(liveUpdatePublisherPort, times(1)).publishEvent(any());
        Event event = captor.getValue();
        assertEquals(EventType.GEOFENCE_BREACH, event.type());
        assertEquals(assetId.value().toString(), event.attributes().get("assetId"));
        assertEquals(outZone.id().value().toString(), event.attributes().get("zoneId"));
        assertEquals(outZone.name(), event.attributes().get("zoneName"));
        assertEquals("KEEP_OUT", event.attributes().get("kind"));
        assertEquals("enter", event.attributes().get("direction"));
    }

    @Test
    void keepOutNoRepeatEventWhileStayingInside() {
        GeofenceZone outZone = zone(ZoneKind.KEEP_OUT);
        when(geofenceRepository.findAll()).thenReturn(List.of(outZone));
        GeofenceMonitor monitor = monitor();
        AssetId assetId = AssetId.random();

        monitor.evaluate(assetId, telemetry(15.0, 15.0, null));
        monitor.evaluate(assetId, telemetry(15.1, 15.1, null));
        monitor.evaluate(assetId, telemetry(15.2, 15.2, null));

        verify(eventPublisher, times(1)).publish(any());
    }

    @Test
    void keepOutExitPublishesExitEvent() {
        GeofenceZone outZone = zone(ZoneKind.KEEP_OUT);
        when(geofenceRepository.findAll()).thenReturn(List.of(outZone));
        GeofenceMonitor monitor = monitor();
        AssetId assetId = AssetId.random();

        monitor.evaluate(assetId, telemetry(15.0, 15.0, null)); // enter
        monitor.evaluate(assetId, telemetry(5.0, 5.0, null)); // exit

        ArgumentCaptor<Event> captor = ArgumentCaptor.forClass(Event.class);
        verify(eventPublisher, times(2)).publish(captor.capture());
        assertEquals("enter", captor.getAllValues().get(0).attributes().get("direction"));
        assertEquals("exit", captor.getAllValues().get(1).attributes().get("direction"));
    }

    @Test
    void keepOutNeverBreachesWhileOutside() {
        GeofenceZone outZone = zone(ZoneKind.KEEP_OUT);
        when(geofenceRepository.findAll()).thenReturn(List.of(outZone));
        GeofenceMonitor monitor = monitor();

        monitor.evaluate(AssetId.random(), telemetry(5.0, 5.0, null));

        verify(eventPublisher, never()).publish(any());
        verify(liveUpdatePublisherPort, never()).publishEvent(any());
    }

    // --- KEEP_IN ---------------------------------------------------------------

    @Test
    void keepInBreachWhenOutsideTheOnlyEnabledKeepInZone() {
        GeofenceZone inZone = zone(ZoneKind.KEEP_IN);
        when(geofenceRepository.findAll()).thenReturn(List.of(inZone));
        GeofenceMonitor monitor = monitor();
        AssetId assetId = AssetId.random();

        monitor.evaluate(assetId, telemetry(5.0, 5.0, null)); // outside the keep-in zone -> breach

        ArgumentCaptor<Event> captor = ArgumentCaptor.forClass(Event.class);
        verify(eventPublisher, times(1)).publish(captor.capture());
        assertEquals("KEEP_IN", captor.getValue().attributes().get("kind"));
        assertEquals("enter", captor.getValue().attributes().get("direction"));
    }

    @Test
    void keepInNoBreachWhileInside() {
        GeofenceZone inZone = zone(ZoneKind.KEEP_IN);
        when(geofenceRepository.findAll()).thenReturn(List.of(inZone));
        GeofenceMonitor monitor = monitor();

        monitor.evaluate(AssetId.random(), telemetry(15.0, 15.0, null));

        verify(eventPublisher, never()).publish(any());
    }

    @Test
    void keepInBreachExitsWhenReturningInside() {
        GeofenceZone inZone = zone(ZoneKind.KEEP_IN);
        when(geofenceRepository.findAll()).thenReturn(List.of(inZone));
        GeofenceMonitor monitor = monitor();
        AssetId assetId = AssetId.random();

        monitor.evaluate(assetId, telemetry(5.0, 5.0, null)); // outside -> breach enters
        monitor.evaluate(assetId, telemetry(15.0, 15.0, null)); // back inside -> breach exits

        ArgumentCaptor<Event> captor = ArgumentCaptor.forClass(Event.class);
        verify(eventPublisher, times(2)).publish(captor.capture());
        assertEquals("enter", captor.getAllValues().get(0).attributes().get("direction"));
        assertEquals("exit", captor.getAllValues().get(1).attributes().get("direction"));
    }

    @Test
    void noKeepInBreachWhenNoKeepInZonesAreConfiguredAtAll() {
        GeofenceZone outZone = zone(ZoneKind.KEEP_OUT); // only a KEEP_OUT zone exists
        when(geofenceRepository.findAll()).thenReturn(List.of(outZone));
        GeofenceMonitor monitor = monitor();

        // Well outside the KEEP_OUT zone too, so this would only ever fire a KEEP_IN breach if the
        // "no enabled KEEP_IN zone exists" guard were missing.
        monitor.evaluate(AssetId.random(), telemetry(5.0, 5.0, null));

        verify(eventPublisher, never()).publish(any());
    }

    @Test
    void keepInAltitudeCeilingBreachWhileHorizontallyInsideTheZone() {
        GeofenceZone inZone = zone(ZoneKind.KEEP_IN, true, 50.0);
        when(geofenceRepository.findAll()).thenReturn(List.of(inZone));
        GeofenceMonitor monitor = monitor();
        AssetId assetId = AssetId.random();

        monitor.evaluate(assetId, telemetry(15.0, 15.0, 100.0)); // inside horizontally, over the ceiling

        ArgumentCaptor<Event> captor = ArgumentCaptor.forClass(Event.class);
        verify(eventPublisher, times(1)).publish(captor.capture());
        assertEquals("enter", captor.getValue().attributes().get("direction"));
    }

    @Test
    void keepInNoAltitudeBreachWhenUnderTheCeiling() {
        GeofenceZone inZone = zone(ZoneKind.KEEP_IN, true, 50.0);
        when(geofenceRepository.findAll()).thenReturn(List.of(inZone));
        GeofenceMonitor monitor = monitor();

        monitor.evaluate(AssetId.random(), telemetry(15.0, 15.0, 10.0));

        verify(eventPublisher, never()).publish(any());
    }

    @Test
    void keepInNoAltitudeBreachWhenAltitudeIsUnknown() {
        GeofenceZone inZone = zone(ZoneKind.KEEP_IN, true, 50.0);
        when(geofenceRepository.findAll()).thenReturn(List.of(inZone));
        GeofenceMonitor monitor = monitor();

        monitor.evaluate(AssetId.random(), telemetry(15.0, 15.0, null));

        verify(eventPublisher, never()).publish(any());
    }

    // --- Ignored samples / zones -------------------------------------------------

    @Test
    void samplesWithoutLatitudeOrLongitudeAreIgnoredEntirely() {
        GeofenceZone outZone = zone(ZoneKind.KEEP_OUT);
        when(geofenceRepository.findAll()).thenReturn(List.of(outZone));
        GeofenceMonitor monitor = monitor();
        AssetId assetId = AssetId.random();

        monitor.evaluate(assetId, telemetry(null, null, null));
        monitor.evaluate(assetId, telemetry(15.0, null, null));
        monitor.evaluate(assetId, telemetry(null, 15.0, null));

        verify(eventPublisher, never()).publish(any());
        verify(geofenceRepository, never()).findAll(); // never even loads the cache: bails out first
    }

    @Test
    void disabledZonesAreNeverEvaluated() {
        GeofenceZone disabledOutZone = zone(ZoneKind.KEEP_OUT, false, null);
        when(geofenceRepository.findAll()).thenReturn(List.of(disabledOutZone));
        GeofenceMonitor monitor = monitor();

        monitor.evaluate(AssetId.random(), telemetry(15.0, 15.0, null)); // would breach if enabled

        verify(eventPublisher, never()).publish(any());
    }

    // --- Cache refresh -----------------------------------------------------------

    @Test
    void lazilySelfPopulatesTheCacheOnFirstEvaluateWithoutAnExplicitRefresh() {
        GeofenceZone outZone = zone(ZoneKind.KEEP_OUT);
        when(geofenceRepository.findAll()).thenReturn(List.of(outZone));
        GeofenceMonitor monitor = monitor();

        monitor.evaluate(AssetId.random(), telemetry(15.0, 15.0, null));

        verify(geofenceRepository, times(1)).findAll();
        verify(eventPublisher, times(1)).publish(any());
    }

    @Test
    void refreshReloadsTheCacheFromTheRepository() {
        when(geofenceRepository.findAll()).thenReturn(List.of()); // no zones yet
        GeofenceMonitor monitor = monitor();
        AssetId assetId = AssetId.random();
        monitor.evaluate(assetId, telemetry(15.0, 15.0, null));
        verify(eventPublisher, never()).publish(any());

        GeofenceZone outZone = zone(ZoneKind.KEEP_OUT);
        when(geofenceRepository.findAll()).thenReturn(List.of(outZone)); // a zone was just created
        monitor.refresh();

        monitor.evaluate(assetId, telemetry(15.0, 15.0, null));
        verify(eventPublisher, times(1)).publish(any());
    }

    // --- Nullable collaborators ----------------------------------------------------

    @Test
    void evaluateNeverThrowsWhenBothPublishersAreAbsent() {
        GeofenceZone outZone = zone(ZoneKind.KEEP_OUT);
        when(geofenceRepository.findAll()).thenReturn(List.of(outZone));
        GeofenceMonitor monitor = new GeofenceMonitor(geofenceRepository, null, null);

        monitor.evaluate(AssetId.random(), telemetry(15.0, 15.0, null));
    }

    @Test
    void constructorRejectsNullRepository() {
        assertThrows(NullPointerException.class, () -> new GeofenceMonitor(null, eventPublisher, liveUpdatePublisherPort));
    }

    @Test
    void evaluateRejectsNullAssetIdAndSample() {
        GeofenceMonitor monitor = monitor();

        assertThrows(NullPointerException.class, () -> monitor.evaluate(null, telemetry(15.0, 15.0, null)));
        assertThrows(NullPointerException.class, () -> monitor.evaluate(AssetId.random(), null));
    }
}
