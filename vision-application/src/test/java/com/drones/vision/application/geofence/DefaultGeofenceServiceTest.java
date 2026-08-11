package com.drones.vision.application.geofence;

import com.drones.vision.kernel.GeoPosition;
import com.drones.vision.flight.domain.model.GeofenceZone;
import com.drones.vision.flight.domain.model.ZoneId;
import com.drones.vision.flight.domain.model.ZoneKind;
import com.drones.vision.flight.domain.port.GeofenceRepositoryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultGeofenceServiceTest {

    private GeofenceRepositoryPort geofenceRepository;
    private GeofenceMonitor geofenceMonitor;
    private GeofenceService service;

    @BeforeEach
    void setUp() {
        geofenceRepository = mock(GeofenceRepositoryPort.class);
        geofenceMonitor = mock(GeofenceMonitor.class);
        service = new DefaultGeofenceService(geofenceRepository, geofenceMonitor);
        when(geofenceRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private static List<GeoPosition> square() {
        return List.of(
                new GeoPosition(10, 10, null),
                new GeoPosition(10, 20, null),
                new GeoPosition(20, 20, null),
                new GeoPosition(20, 10, null));
    }

    private static GeofenceZone zone(String name) {
        return new GeofenceZone(ZoneId.random(), name, ZoneKind.KEEP_OUT, square(), null, true);
    }

    private static GeofenceZoneSpec spec(String name) {
        return new GeofenceZoneSpec(name, ZoneKind.KEEP_OUT, square(), null, true);
    }

    @Test
    void zonesListsSortedByNameCaseInsensitive() {
        GeofenceZone charlie = zone("Charlie");
        GeofenceZone alpha = zone("alpha");
        GeofenceZone bravo = zone("Bravo");
        when(geofenceRepository.findAll()).thenReturn(List.of(charlie, alpha, bravo));

        assertEquals(List.of(alpha, bravo, charlie), service.zones());
    }

    @Test
    void findDelegatesToRepository() {
        GeofenceZone existing = zone("alpha");
        when(geofenceRepository.findById(existing.id())).thenReturn(Optional.of(existing));

        assertEquals(Optional.of(existing), service.find(existing.id()));
    }

    @Test
    void findIsEmptyForAnUnknownId() {
        ZoneId unknown = ZoneId.random();
        when(geofenceRepository.findById(unknown)).thenReturn(Optional.empty());

        assertEquals(Optional.empty(), service.find(unknown));
    }

    @Test
    void createSavesANewZoneAndRefreshesTheMonitor() {
        GeofenceZoneSpec spec = spec("no-fly");

        GeofenceZone created = service.create(spec);

        assertEquals("no-fly", created.name());
        assertEquals(ZoneKind.KEEP_OUT, created.kind());
        assertEquals(spec.polygon(), created.polygon());
        assertTrue(created.enabled());
        verify(geofenceRepository).save(any());
        verify(geofenceMonitor, times(1)).refresh();
    }

    @Test
    void updateReplacesAnExistingZoneAndRefreshesTheMonitor() {
        GeofenceZone existing = zone("old-name");
        when(geofenceRepository.findById(existing.id())).thenReturn(Optional.of(existing));
        GeofenceZoneSpec replacement = new GeofenceZoneSpec("new-name", ZoneKind.KEEP_IN, square(), 100.0, false);

        GeofenceZone updated = service.update(existing.id(), replacement);

        assertEquals(existing.id(), updated.id(), "identity must be preserved across an update");
        assertEquals("new-name", updated.name());
        assertEquals(ZoneKind.KEEP_IN, updated.kind());
        assertEquals(100.0, updated.maxAltitudeMeters());
        assertFalse(updated.enabled());
        ArgumentCaptor<GeofenceZone> captor = ArgumentCaptor.forClass(GeofenceZone.class);
        verify(geofenceRepository).save(captor.capture());
        assertEquals(existing.id(), captor.getValue().id());
        verify(geofenceMonitor, times(1)).refresh();
    }

    @Test
    void updateThrowsForAnUnknownId() {
        ZoneId unknown = ZoneId.random();
        when(geofenceRepository.findById(unknown)).thenReturn(Optional.empty());

        assertThrows(NoSuchElementException.class, () -> service.update(unknown, spec("zone")));
        verify(geofenceRepository, never()).save(any());
        verify(geofenceMonitor, never()).refresh();
    }

    @Test
    void deleteRemovesAnExistingZoneAndRefreshesTheMonitor() {
        GeofenceZone existing = zone("to-delete");
        when(geofenceRepository.findById(existing.id())).thenReturn(Optional.of(existing));

        service.delete(existing.id());

        verify(geofenceRepository).deleteById(existing.id());
        verify(geofenceMonitor, times(1)).refresh();
    }

    @Test
    void deleteThrowsForAnUnknownId() {
        ZoneId unknown = ZoneId.random();
        when(geofenceRepository.findById(unknown)).thenReturn(Optional.empty());

        assertThrows(NoSuchElementException.class, () -> service.delete(unknown));
        verify(geofenceRepository, never()).deleteById(any());
        verify(geofenceMonitor, never()).refresh();
    }

    @Test
    void constructorRejectsNullCollaborators() {
        assertThrows(NullPointerException.class, () -> new DefaultGeofenceService(null, geofenceMonitor));
        assertThrows(NullPointerException.class, () -> new DefaultGeofenceService(geofenceRepository, null));
    }
}
