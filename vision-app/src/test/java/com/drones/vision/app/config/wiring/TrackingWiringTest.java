package com.drones.vision.app.config.wiring;

import com.drones.vision.api.dto.CvTrackerResponse;
import com.drones.vision.app.config.properties.VisionApplicationProperties;
import com.drones.vision.app.config.properties.VisionTrackingProperties;
import com.drones.vision.application.pipeline.StreamPipelineSettings;
import com.drones.vision.application.stream.TrackingConfigPatch;
import com.drones.vision.perception.domain.model.PipelineConfig;
import com.drones.vision.perception.domain.model.TrackingConfig;
import com.drones.vision.perception.domain.model.TrackingMode;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit coverage for the {@code vision.tracking.*} mapping (docs/plans/done/TRACKING-PLAN.md &sect;4.F,
 * docs/extracts/TRACKING-ORCHESTRATION.md &sect;4.3) — no Spring context: both wiring methods are plain
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

    /** The values {@code VisionTrackingProperties}' own {@code @DefaultValue}s bind to. */
    private static VisionTrackingProperties defaults() {
        return properties("ASSOCIATE", 15, 2000, 30, 5);
    }

    @Test
    void defaultPropertiesSeedNewStreamsWithAssociateSoTheDomainFlipReachesEveryStartPath() {
        // Wave T8's flip lives in PipelineConfig.defaults(), but the seed is folded OVER that
        // default at every start and always states a mode -- so a seed still saying OFF would have
        // made the flip inert for every real stream while the domain test proving it stayed green.
        TrackingConfigPatch seed = TrackingWiring.streamStartTrackingSeed(defaults());

        assertEquals(TrackingConfig.defaults(), seed.foldOnto(TrackingConfig.off(), () -> 1L),
                "the shipped default starts streams tracking, even folded over an OFF base");
    }

    @Test
    void aDeploymentCanStillPinTrackingOffAndThatIsTheOnlyWayToGetThePreTrackingBehaviour() {
        TrackingConfigPatch seed = TrackingWiring.streamStartTrackingSeed(properties("OFF", 15, 2000, 30, 5));

        assertEquals(TrackingConfig.off(), seed.foldOnto(PipelineConfig.defaults().tracking(), () -> 1L),
                "an explicit OFF wins over the domain's ASSOCIATE default -- deployment > code default");
    }

    @Test
    void deploymentPropertiesSeedTheModeAndTheTwoCadencesTheyOwnAndNothingElse() {
        TrackingConfigPatch seed = TrackingWiring.streamStartTrackingSeed(properties("associate", 20, 1500, 30, 5));

        assertEquals(TrackingMode.ASSOCIATE, seed.mode(), "the mode is matched case-insensitively");
        assertEquals(20, seed.followFps());
        assertEquals(1500, seed.verifyEveryMillis());
        assertNull(seed.engineId(), "the engine is cv-service's choice, not a Java-side default");
        assertNull(seed.redetectIouPercent(),
                "a knob with no property stays unstated, so the domain's own literal wins -- one number, one owner");
        assertNull(seed.maxAgeFrames());
        assertNull(seed.minHits());
        assertNull(seed.lock(), "a seed never carries a lock -- no stream exists yet to lock onto");
    }

    @Test
    void theSeedFoldsOntoTheDomainDefaultsForEveryKnobItDoesNotState() {
        TrackingConfigPatch seed = TrackingWiring.streamStartTrackingSeed(properties("associate", 20, 1500, 30, 5));

        TrackingConfig folded = seed.foldOnto(TrackingConfig.off(), () -> 1L);

        assertEquals(TrackingMode.ASSOCIATE, folded.mode());
        assertEquals(1500, folded.verifyEveryMillis());
        assertEquals(20, folded.followFps());
        assertEquals("", folded.engineId());
        assertEquals(TrackingConfig.DEFAULT_REDETECT_IOU_PERCENT, folded.redetectIouPercent());
        assertEquals(TrackingConfig.DEFAULT_MAX_AGE_FRAMES, folded.maxAgeFrames());
        assertEquals(TrackingConfig.DEFAULT_MIN_HITS, folded.minHits());
    }

    @Test
    void anUnknownDefaultModeFailsAtStartupRatherThanPerRequest() {
        VisionTrackingProperties bad = properties("CHASE", 15, 2000, 30, 5);

        IllegalArgumentException thrown =
                assertThrows(IllegalArgumentException.class, () -> TrackingWiring.streamStartTrackingSeed(bad));
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
    void theStreamStartSeedBindsIntoStreamPipelineSettingsSoEveryStartPathSeesIt() {
        // The one binding that matters for docs/extracts/TRACKING-ORCHESTRATION.md §4.1: the seed reaches
        // DefaultStreamService, which every start path -- device, asset, simulation, demo fleet --
        // goes through, instead of only the two REST endpoints that used to be injected with it.
        StreamPipelineSettings mapped = ApplicationServiceWiring.streamPipelineSettings(
                new VisionApplicationProperties(200L, null, null, null, null, null, null, null),
                properties("FOLLOW", 20, 1500, 30, 5));

        assertEquals(TrackingMode.FOLLOW, mapped.trackingSeed().mode());
        assertEquals(1500, mapped.trackingSeed().verifyEveryMillis());
        assertEquals(20, mapped.trackingSeed().followFps());
    }

    @Test
    void theDefaultSeedAgreesWithTheDomainDefaultSoAStartThatSaysNothingGetsExactlyIt() {
        StreamPipelineSettings mapped = ApplicationServiceWiring.streamPipelineSettings(
                new VisionApplicationProperties(200L, null, null, null, null, null, null, null), defaults());

        // The two layers must not drift: a start request that states nothing gets the domain default
        // back unchanged, rather than a deployment layer quietly re-deciding it.
        assertEquals(PipelineConfig.defaults().tracking(),
                mapped.trackingSeed().foldOnto(PipelineConfig.defaults().tracking(), () -> 1L));
        assertEquals(TrackingMode.ASSOCIATE, mapped.trackingSeed().mode());
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
                "none of the three shipped engines needs model assets (docs/plans/done/TRACKING-PLAN.md §5.B)");
    }
}
