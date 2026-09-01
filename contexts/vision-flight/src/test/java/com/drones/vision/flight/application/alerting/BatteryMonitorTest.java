package com.drones.vision.flight.application.alerting;

import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.DeviceId;
import com.drones.vision.kernel.Telemetry;
import com.drones.vision.platform.Event;
import com.drones.vision.platform.EventLiveUpdatePort;
import com.drones.vision.platform.EventPublisherPort;
import com.drones.vision.platform.EventType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class BatteryMonitorTest {

    private static final DeviceId DEVICE_ID = DeviceId.random();
    private static final BatteryAlertSettings SETTINGS = new BatteryAlertSettings(10.0, 25.0);

    private EventPublisherPort eventPublisher;
    private EventLiveUpdatePort liveUpdatePublisherPort;

    @BeforeEach
    void setUp() {
        eventPublisher = mock(EventPublisherPort.class);
        liveUpdatePublisherPort = mock(EventLiveUpdatePort.class);
    }

    private BatteryMonitor monitor() {
        return new BatteryMonitor(eventPublisher, liveUpdatePublisherPort, SETTINGS);
    }

    private static Telemetry telemetry(Double batteryPercent) {
        return new Telemetry(DEVICE_ID, Instant.now(), null, null, null, null, batteryPercent, Map.of());
    }

    @Test
    void risingEdgeAtCriticalPublishesOneEvent() {
        BatteryMonitor monitor = monitor();
        AssetId assetId = AssetId.random();

        monitor.evaluate(assetId, telemetry(10.0)); // at the critical line -> crosses

        ArgumentCaptor<Event> captor = ArgumentCaptor.forClass(Event.class);
        verify(eventPublisher, times(1)).publish(captor.capture());
        verify(liveUpdatePublisherPort, times(1)).publishEvent(any());
        Event event = captor.getValue();
        assertEquals(EventType.BATTERY_LOW, event.type());
        assertEquals(assetId.value().toString(), event.attributes().get("assetId"));
        assertEquals("10.0", event.attributes().get("batteryPercent"));
    }

    @Test
    void staysSilentWhileAlreadyLatchedAndStillBelowCritical() {
        BatteryMonitor monitor = monitor();
        AssetId assetId = AssetId.random();

        monitor.evaluate(assetId, telemetry(9.0));
        monitor.evaluate(assetId, telemetry(8.0));
        monitor.evaluate(assetId, telemetry(5.0));

        verify(eventPublisher, times(1)).publish(any());
    }

    @Test
    void neverFiresAboveCritical() {
        BatteryMonitor monitor = monitor();

        monitor.evaluate(AssetId.random(), telemetry(50.0));
        monitor.evaluate(AssetId.random(), telemetry(11.0));

        verify(eventPublisher, never()).publish(any());
        verify(liveUpdatePublisherPort, never()).publishEvent(any());
    }

    @Test
    void dippingBelowCriticalThenBouncingUnderWarningNeverReFires() {
        BatteryMonitor monitor = monitor();
        AssetId assetId = AssetId.random();

        monitor.evaluate(assetId, telemetry(9.0)); // crosses critical -> fires, latches
        monitor.evaluate(assetId, telemetry(15.0)); // above critical, still under warning -> not re-armed
        monitor.evaluate(assetId, telemetry(9.5)); // dips again, but never re-armed -> silent

        verify(eventPublisher, times(1)).publish(any());
    }

    @Test
    void reArmsOnlyAtOrAboveWarningThenFiresAgainOnTheNextCriticalCrossing() {
        BatteryMonitor monitor = monitor();
        AssetId assetId = AssetId.random();

        monitor.evaluate(assetId, telemetry(9.0)); // fires, latches
        monitor.evaluate(assetId, telemetry(25.0)); // at warning -> re-arms, silent
        monitor.evaluate(assetId, telemetry(9.0)); // crosses critical again -> fires again

        ArgumentCaptor<Event> captor = ArgumentCaptor.forClass(Event.class);
        verify(eventPublisher, times(2)).publish(captor.capture());
    }

    @Test
    void reArmEventItselfIsSilent() {
        BatteryMonitor monitor = monitor();
        AssetId assetId = AssetId.random();

        monitor.evaluate(assetId, telemetry(9.0)); // fires
        monitor.evaluate(assetId, telemetry(30.0)); // re-arms

        verify(eventPublisher, times(1)).publish(any());
    }

    @Test
    void samplesWithoutBatteryPercentAreIgnoredEntirely() {
        BatteryMonitor monitor = monitor();
        AssetId assetId = AssetId.random();

        monitor.evaluate(assetId, telemetry(null));

        verify(eventPublisher, never()).publish(any());
    }

    @Test
    void unknownBatteryReadingNeverSilentlyReArms() {
        BatteryMonitor monitor = monitor();
        AssetId assetId = AssetId.random();

        monitor.evaluate(assetId, telemetry(9.0)); // fires, latches
        monitor.evaluate(assetId, telemetry(null)); // unknown: ignored, latch untouched
        monitor.evaluate(assetId, telemetry(9.0)); // still latched -> silent

        verify(eventPublisher, times(1)).publish(any());
    }

    @Test
    void differentAssetsAreTrackedIndependently() {
        BatteryMonitor monitor = monitor();
        AssetId first = AssetId.random();
        AssetId second = AssetId.random();

        monitor.evaluate(first, telemetry(9.0));
        monitor.evaluate(second, telemetry(9.0));

        verify(eventPublisher, times(2)).publish(any());
    }

    @Test
    void evaluateNeverThrowsWhenBothPublishersAreAbsent() {
        BatteryMonitor monitor = new BatteryMonitor(null, null, SETTINGS);

        monitor.evaluate(AssetId.random(), telemetry(5.0));
    }

    @Test
    void constructorRejectsNullSettings() {
        assertThrows(NullPointerException.class, () -> new BatteryMonitor(eventPublisher, liveUpdatePublisherPort, null));
    }

    @Test
    void evaluateRejectsNullAssetIdAndSample() {
        BatteryMonitor monitor = monitor();

        assertThrows(NullPointerException.class, () -> monitor.evaluate(null, telemetry(5.0)));
        assertThrows(NullPointerException.class, () -> monitor.evaluate(AssetId.random(), null));
    }
}
