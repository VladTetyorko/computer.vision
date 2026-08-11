package com.drones.vision.app.config.wiring;

import com.drones.vision.api.dto.CvTrackerResponse;
import com.drones.vision.app.config.properties.VisionApplicationProperties;
import com.drones.vision.app.config.properties.VisionTrackingProperties;
import com.drones.vision.application.pipeline.StreamPipelineSettings;
import com.drones.vision.domain.model.TrackingConfig;
import com.drones.vision.domain.model.TrackingMode;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit coverage for the {@code vision.tracking.*} mapping (docs/TRACKING-PLAN.md &sect;4.F,
 * docs/TRACKING-ORCHESTRATION.md &sect;4.3) — no Spring context: both wiring methods are plain
 * mappings from a properties record, and {@link TrackingWiringContextTest} separately proves the
 * beans actually resolve in the real context.
 *
 * <p>Lives in the wiring package so it can call {@link
 * ApplicationServiceWiring#streamPipelineSettings} (package-private) directly — that binding is the
 * one place {@code stats-window-seconds} reaches {@code TrackingStatsWindow}, and asserting it here
 * is cheaper and more precise than starting a stream in a Spring test to read the number back off
 * {@code GET /api/streams/{id}/tracks}.
 */
class TrackingWiringTest {

    private static VisionTrackingProperties properties(String mode, int followFps, int verifyEveryMillis,
                                                        int statsWindowSeconds, int trackRetentionSeconds) {
        return new VisionTrackingProperties(mode, followFps, verifyEveryMillis, statsWindowSeconds,
                trackRetentionSeconds);
    }

    private static VisionTrackingProperties defaults() {
        return properties("OFF", 15, 2000, 30, 5);
    }

    @Test
    void defaultPropertiesSeedNewStreamsWithTrackingOffExactlyAsBeforeTrackingExisted() {
        TrackingConfig seed = new TrackingWiring().streamStartTrackingDefaults(defaults());

        assertEquals(TrackingConfig.off(), seed, "the shipped default must leave stream starts unchanged");
    }

    @Test
    void deploymentPropertiesSeedTheModeAndTheTwoCadencesTheyOwn() {
        TrackingConfig seed = new TrackingWiring().streamStartTrackingDefaults(properties("associate", 20, 1500,
                30, 5));

        assertEquals(TrackingMode.ASSOCIATE, seed.mode(), "the mode is matched case-insensitively");
        assertEquals(20, seed.followFps());
        assertEquals(1500, seed.verifyEveryMillis());
        assertEquals("", seed.engineId(), "the engine is cv-service's choice, not a Java-side default");
        assertEquals(TrackingConfig.DEFAULT_REDETECT_IOU_PERCENT, seed.redetectIouPercent(),
                "the cv-service-owned knobs keep the domain defaults -- one number, one owner");
        assertEquals(TrackingConfig.DEFAULT_MAX_AGE_FRAMES, seed.maxAgeFrames());
        assertEquals(TrackingConfig.DEFAULT_MIN_HITS, seed.minHits());
        assertNull(seed.lock(), "a seed never carries a lock -- no stream exists yet to lock onto");
    }

    @Test
    void anUnknownDefaultModeFailsAtStartupRatherThanPerRequest() {
        TrackingWiring wiring = new TrackingWiring();
        VisionTrackingProperties bad = properties("CHASE", 15, 2000, 30, 5);

        IllegalArgumentException thrown =
                assertThrows(IllegalArgumentException.class, () -> wiring.streamStartTrackingDefaults(bad));
        assertTrue(thrown.getMessage().contains("ASSOCIATE"), "the message lists the valid modes");
    }

    @Test
    void nonPositiveWindowsAreRejectedByThePropertiesRecordItself() {
        assertThrows(IllegalArgumentException.class, () -> properties("OFF", 15, 2000, 0, 5));
        assertThrows(IllegalArgumentException.class, () -> properties("OFF", 15, 2000, 30, 0));
        assertThrows(IllegalArgumentException.class, () -> properties("OFF", 0, 2000, 30, 5));
        assertThrows(IllegalArgumentException.class, () -> properties("OFF", 15, 0, 30, 5));
    }

    @Test
    void statsWindowAndTrackRetentionBindIntoStreamPipelineSettings() {
        StreamPipelineSettings settings = ApplicationServiceWiring.streamPipelineSettings(
                new VisionApplicationProperties(200L, null, null, null, null, null, null, null),
                properties("OFF", 15, 2000, 45, 9));

        assertEquals(Duration.ofSeconds(45), settings.trackingStatsWindow());
        assertEquals(Duration.ofSeconds(9), settings.trackRetention());
    }

    @Test
    void theDefaultWindowsAreByteIdenticalToTheSettingsRecordsOwnDefaults() {
        StreamPipelineSettings mapped = ApplicationServiceWiring.streamPipelineSettings(
                new VisionApplicationProperties(200L, null, null, null, null, null, null, null), defaults());

        assertEquals(StreamPipelineSettings.defaults().trackingStatsWindow(), mapped.trackingStatsWindow());
        assertEquals(StreamPipelineSettings.defaults().trackRetention(), mapped.trackRetention());
    }

    @Test
    void theTrackerRosterIsTheThreeEnginesCvServiceShipsWithTheirModes() {
        List<CvTrackerResponse> roster = new TrackingWiring().cvTrackerRoster();

        assertEquals(3, roster.size());
        assertEquals("bytetrack", roster.get(0).id());
        assertEquals(List.of("ASSOCIATE"), roster.get(0).modes());
        assertEquals("lk", roster.get(1).id());
        assertEquals(List.of("FOLLOW"), roster.get(1).modes());
        assertEquals("ncc", roster.get(2).id());
        assertEquals(List.of("FOLLOW"), roster.get(2).modes());
        assertTrue(roster.stream().noneMatch(CvTrackerResponse::needsAssets),
                "none of the three shipped engines needs model assets (docs/TRACKING-PLAN.md §5.B)");
    }
}
