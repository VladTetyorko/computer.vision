package com.drones.vision.api.dto;

import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.GeoPosition;
import com.drones.vision.map.domain.model.LayerId;
import com.drones.vision.map.domain.model.MapEvent;
import com.drones.vision.map.domain.model.ProjectedTrack;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit tests for {@link MapEventPayload#from(MapEvent)}'s {@code TRACK} case
 * (docs/plans/done/FIXED-CAMERA-GEO-PLAN.md §5/D11) — {@code track} rides the same {@code map}
 * topic as {@code mark}/{@code drawing}/{@code layer}, so this pins the new entity/action strings and
 * the {@code created}/{@code updated} vs {@code cleared} shape split without needing a live SSE
 * connection ({@link com.drones.vision.api.controller.LiveMapScopingTest} covers the transport/scoping
 * half end to end).
 */
class MapEventPayloadTest {

    private static ProjectedTrack track(AssetId assetId, LayerId layerId) {
        return new ProjectedTrack(assetId, 7L, "car", layerId, new GeoPosition(50.45, 30.52, null), 120.0, 15.0,
                Instant.parse("2026-08-17T10:00:00Z"));
    }

    @Test
    void createdMapsEntityAndActionToLowercaseStrings() {
        AssetId assetId = AssetId.random();
        LayerId layerId = LayerId.random();
        MapEvent event = new MapEvent(MapEvent.EntityType.TRACK, MapEvent.Action.CREATED, layerId,
                track(assetId, layerId));

        MapEventPayload payload = MapEventPayload.from(event);

        assertEquals("track", payload.entity());
        assertEquals("created", payload.action());
        assertEquals(layerId.value().toString(), payload.layerId());
    }

    @Test
    void updatedCarriesTheFullLiveTrackShapeWithNoTrail() {
        AssetId assetId = AssetId.random();
        LayerId layerId = LayerId.random();
        ProjectedTrack track = track(assetId, layerId);
        MapEvent event = new MapEvent(MapEvent.EntityType.TRACK, MapEvent.Action.UPDATED, layerId, track);

        ProjectedTrackResponse response = MapEventPayload.from(event).track();

        assertEquals(assetId.value().toString(), response.assetId());
        assertEquals(7L, response.trackId());
        assertEquals("car", response.label());
        assertEquals(layerId.value().toString(), response.layerId());
        assertEquals(50.45, response.latitude());
        assertEquals(30.52, response.longitude());
        assertEquals(120.0, response.rangeMeters());
        assertEquals(15.0, response.errorRadiusMeters());
        assertEquals(Instant.parse("2026-08-17T10:00:00Z"), response.updatedAt());
        assertNull(response.trail(), "the live channel is not the trail's source -- only GET /api/map/tracks is");
    }

    @Test
    void clearedStripsEveryFieldButAssetIdAndTrackId() {
        AssetId assetId = AssetId.random();
        LayerId layerId = LayerId.random();
        MapEvent event = new MapEvent(MapEvent.EntityType.TRACK, MapEvent.Action.CLEARED, layerId,
                track(assetId, layerId));

        MapEventPayload payload = MapEventPayload.from(event);
        ProjectedTrackResponse response = payload.track();

        assertEquals("cleared", payload.action());
        assertEquals(assetId.value().toString(), response.assetId());
        assertEquals(7L, response.trackId());
        assertNull(response.label(), "a cleared track's last-known state is not part of the wire contract");
        assertNull(response.layerId());
        assertNull(response.latitude());
        assertNull(response.longitude());
        assertNull(response.rangeMeters());
        assertNull(response.errorRadiusMeters());
        assertNull(response.updatedAt());
        assertNull(response.trail());
    }

    @Test
    void onlyTheTrackFieldIsPopulatedNeverMarkDrawingOrLayer() {
        AssetId assetId = AssetId.random();
        LayerId layerId = LayerId.random();
        MapEvent event = new MapEvent(MapEvent.EntityType.TRACK, MapEvent.Action.CREATED, layerId,
                track(assetId, layerId));

        MapEventPayload payload = MapEventPayload.from(event);

        assertNull(payload.mark());
        assertNull(payload.drawing());
        assertNull(payload.layer());
    }
}
