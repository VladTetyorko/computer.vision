package com.drones.vision.api.live;

import com.drones.vision.kernel.AssetId;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Pure unit tests for {@link LiveTopic} parsing (docs/plans/done/REALTIME-PLAN.md §4, item 2) — no Spring.
 */
class LiveTopicTest {

    @Test
    void parsesFleetAndEventAsTheSharedConstants() {
        assertEquals(LiveTopic.FLEET, LiveTopic.parse("fleet"));
        assertEquals(LiveTopic.EVENT, LiveTopic.parse("event"));
    }

    @Test
    void parsesZonesAndSystemAsTheSharedConstants() {
        assertEquals(LiveTopic.ZONES, LiveTopic.parse("zones"));
        assertEquals(LiveTopic.SYSTEM, LiveTopic.parse("system"));
        assertEquals("zones", LiveTopic.ZONES.wire());
        assertEquals("system", LiveTopic.SYSTEM.wire());
    }

    @Test
    void parsesATelemetryTopicWithItsAssetId() {
        AssetId assetId = AssetId.random();

        LiveTopic topic = LiveTopic.parse("telemetry:" + assetId.value());

        assertEquals(LiveTopic.telemetry(assetId), topic);
    }

    @Test
    void parsesADetectionsTopicWithItsAssetId() {
        AssetId assetId = AssetId.random();

        LiveTopic topic = LiveTopic.parse("detections:" + assetId.value());

        assertEquals(LiveTopic.detections(assetId), topic);
    }

    @Test
    void wireRoundTripsForEveryKind() {
        AssetId assetId = AssetId.random();

        assertEquals("fleet", LiveTopic.FLEET.wire());
        assertEquals("event", LiveTopic.EVENT.wire());
        assertEquals("telemetry:" + assetId.value(), LiveTopic.telemetry(assetId).wire());
        assertEquals("detections:" + assetId.value(), LiveTopic.detections(assetId).wire());
    }

    @Test
    void rejectsBlankInput() {
        assertThrows(IllegalArgumentException.class, () -> LiveTopic.parse(null));
        assertThrows(IllegalArgumentException.class, () -> LiveTopic.parse("  "));
    }

    @Test
    void rejectsAnUnknownKind() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> LiveTopic.parse("bogus"));
        assertEquals(true, ex.getMessage().contains("bogus"));
    }

    @Test
    void rejectsATelemetryOrDetectionsTopicMissingItsAssetId() {
        assertThrows(IllegalArgumentException.class, () -> LiveTopic.parse("telemetry"));
        assertThrows(IllegalArgumentException.class, () -> LiveTopic.parse("telemetry:"));
        assertThrows(IllegalArgumentException.class, () -> LiveTopic.parse("detections"));
    }

    @Test
    void rejectsAMalformedAssetId() {
        assertThrows(IllegalArgumentException.class, () -> LiveTopic.parse("telemetry:not-a-uuid"));
    }

    @Test
    void parseTopicsParamHandlesAbsentBlankAndCommaSeparatedInput() {
        assertEquals(Set.of(), LiveTopic.parseTopicsParam(null));
        assertEquals(Set.of(), LiveTopic.parseTopicsParam(""));
        assertEquals(Set.of(), LiveTopic.parseTopicsParam("   "));

        AssetId a = AssetId.random();
        AssetId b = AssetId.random();
        Set<LiveTopic> parsed = LiveTopic.parseTopicsParam("telemetry:" + a.value() + ",detections:" + b.value());

        assertEquals(Set.of(LiveTopic.telemetry(a), LiveTopic.detections(b)), parsed);
    }

    @Test
    void parseTopicsParamIgnoresBlankEntriesBetweenCommas() {
        AssetId a = AssetId.random();

        Set<LiveTopic> parsed = LiveTopic.parseTopicsParam("telemetry:" + a.value() + ",,  ,fleet");

        assertEquals(Set.of(LiveTopic.telemetry(a), LiveTopic.FLEET), parsed);
    }
}
