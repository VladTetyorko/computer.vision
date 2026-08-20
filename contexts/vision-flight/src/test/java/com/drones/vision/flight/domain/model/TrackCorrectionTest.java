package com.drones.vision.flight.domain.model;

import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.GeoPosition;
import com.drones.vision.kernel.UsageId;
import com.drones.vision.kernel.VisualFixEvidence;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TrackCorrectionTest {

    private static final AssetId ASSET_ID = AssetId.random();
    private static final UsageId USAGE_ID = UsageId.random();
    private static final Instant FRAME_AT = Instant.parse("2026-08-19T12:00:00Z");
    private static final Instant COMPUTED_AT = Instant.parse("2026-08-19T12:00:00.5Z");
    private static final GeoPosition POSITION = new GeoPosition(50.45, 30.52, null);
    private static final VisualFixEvidence EVIDENCE =
            new VisualFixEvidence(10, 50, 20, 0.4, 0.3, 2.0, true, true, 5, 50.0, true, 10.0, 5, 1.0);

    private static TrackCorrection confirmed(boolean divergent, Instant divergentSince) {
        return new TrackCorrection(ASSET_ID, USAGE_ID, FRAME_AT, COMPUTED_AT, CorrectionStatus.CONFIRMED,
                CorrectionSource.VISUAL_HEAVY, POSITION, 214.6, 18.4, 96.2, POSITION, 16.2, 21.0, divergent,
                divergentSince, "kyiv-pozniaky", "17/76687/44230", "", EVIDENCE);
    }

    private static TrackCorrection noFix(String refusal) {
        return new TrackCorrection(ASSET_ID, USAGE_ID, FRAME_AT, COMPUTED_AT, CorrectionStatus.NO_FIX,
                CorrectionSource.VISUAL_HEAVY, null, null, null, null, null, null, null, false, null,
                "kyiv-pozniaky", "", refusal, EVIDENCE);
    }

    @Test
    void storesEveryFieldOnAConfirmedCorrection() {
        TrackCorrection correction = confirmed(false, null);

        assertEquals(ASSET_ID, correction.assetId());
        assertEquals(USAGE_ID, correction.usageId());
        assertEquals(CorrectionStatus.CONFIRMED, correction.status());
        assertEquals(CorrectionSource.VISUAL_HEAVY, correction.source());
        assertEquals(POSITION, correction.position());
        assertEquals("", correction.refusal());
    }

    @Test
    void storesANoFixCorrectionWithNullPositionAndNonEmptyRefusal() {
        TrackCorrection correction = noFix("radius 4.0m below floor 5.0m (not believed)");

        assertEquals(CorrectionStatus.NO_FIX, correction.status());
        assertEquals(null, correction.position());
        assertEquals("radius 4.0m below floor 5.0m (not believed)", correction.refusal());
    }

    @Test
    void storesADivergentCorrectionWithItsLatchTime() {
        Instant since = Instant.parse("2026-08-19T11:59:00Z");
        TrackCorrection correction = confirmed(true, since);

        assertEquals(true, correction.divergent());
        assertEquals(since, correction.divergentSince());
    }

    @Test
    void rejectsPositionNonNullWhenStatusIsNoFix() {
        assertThrows(IllegalArgumentException.class,
                () -> new TrackCorrection(ASSET_ID, USAGE_ID, FRAME_AT, COMPUTED_AT, CorrectionStatus.NO_FIX,
                        CorrectionSource.VISUAL_HEAVY, POSITION, null, 18.4, null, null, null, null, false, null,
                        "", "", "some refusal", EVIDENCE));
    }

    @Test
    void rejectsNullPositionWhenStatusIsNotNoFix() {
        assertThrows(IllegalArgumentException.class,
                () -> new TrackCorrection(ASSET_ID, USAGE_ID, FRAME_AT, COMPUTED_AT, CorrectionStatus.CONFIRMED,
                        CorrectionSource.VISUAL_HEAVY, null, null, null, null, null, null, null, false, null, "",
                        "", "", EVIDENCE));
    }

    @Test
    void rejectsANonEmptyRefusalAlongsideAPosition() {
        assertThrows(IllegalArgumentException.class,
                () -> new TrackCorrection(ASSET_ID, USAGE_ID, FRAME_AT, COMPUTED_AT, CorrectionStatus.PROBABLE,
                        CorrectionSource.VISUAL_HEAVY, POSITION, null, 18.4, null, null, null, null, false, null,
                        "", "", "should be empty", EVIDENCE));
    }

    @Test
    void rejectsAPositionCarryingAnAltitude() {
        GeoPosition withAltitude = new GeoPosition(50.45, 30.52, 100.0);
        assertThrows(IllegalArgumentException.class,
                () -> new TrackCorrection(ASSET_ID, USAGE_ID, FRAME_AT, COMPUTED_AT, CorrectionStatus.PROBABLE,
                        CorrectionSource.VISUAL_HEAVY, withAltitude, null, 18.4, null, null, null, null, false,
                        null, "", "", "", EVIDENCE));
    }

    @Test
    void rejectsDivergentTrueWithNullDivergentSince() {
        assertThrows(IllegalArgumentException.class, () -> confirmed(true, null));
    }

    @Test
    void rejectsDivergentFalseWithNonNullDivergentSince() {
        assertThrows(IllegalArgumentException.class,
                () -> confirmed(false, Instant.parse("2026-08-19T11:59:00Z")));
    }

    @Test
    void rejectsNegativeRadiusMeters() {
        assertThrows(IllegalArgumentException.class,
                () -> new TrackCorrection(ASSET_ID, USAGE_ID, FRAME_AT, COMPUTED_AT, CorrectionStatus.PROBABLE,
                        CorrectionSource.VISUAL_HEAVY, POSITION, null, -1.0, null, null, null, null, false, null,
                        "", "", "", EVIDENCE));
    }

    @Test
    void rejectsNegativeSeparationOrSigmaMeters() {
        assertThrows(IllegalArgumentException.class,
                () -> new TrackCorrection(ASSET_ID, USAGE_ID, FRAME_AT, COMPUTED_AT, CorrectionStatus.PROBABLE,
                        CorrectionSource.VISUAL_HEAVY, POSITION, null, 18.4, null, POSITION, -1.0, 20.0, false,
                        null, "", "", "", EVIDENCE));
        assertThrows(IllegalArgumentException.class,
                () -> new TrackCorrection(ASSET_ID, USAGE_ID, FRAME_AT, COMPUTED_AT, CorrectionStatus.PROBABLE,
                        CorrectionSource.VISUAL_HEAVY, POSITION, null, 18.4, null, POSITION, 20.0, -1.0, false,
                        null, "", "", "", EVIDENCE));
    }

    @Test
    void rejectsNullAssetIdUsageIdFrameAtComputedAtStatusSourceEvidence() {
        assertThrows(IllegalArgumentException.class,
                () -> new TrackCorrection(null, USAGE_ID, FRAME_AT, COMPUTED_AT, CorrectionStatus.NO_FIX,
                        CorrectionSource.VISUAL_HEAVY, null, null, null, null, null, null, null, false, null, "",
                        "", "refused", EVIDENCE));
        assertThrows(IllegalArgumentException.class,
                () -> new TrackCorrection(ASSET_ID, null, FRAME_AT, COMPUTED_AT, CorrectionStatus.NO_FIX,
                        CorrectionSource.VISUAL_HEAVY, null, null, null, null, null, null, null, false, null, "",
                        "", "refused", EVIDENCE));
        assertThrows(IllegalArgumentException.class,
                () -> new TrackCorrection(ASSET_ID, USAGE_ID, null, COMPUTED_AT, CorrectionStatus.NO_FIX,
                        CorrectionSource.VISUAL_HEAVY, null, null, null, null, null, null, null, false, null, "",
                        "", "refused", EVIDENCE));
        assertThrows(IllegalArgumentException.class,
                () -> new TrackCorrection(ASSET_ID, USAGE_ID, FRAME_AT, null, CorrectionStatus.NO_FIX,
                        CorrectionSource.VISUAL_HEAVY, null, null, null, null, null, null, null, false, null, "",
                        "", "refused", EVIDENCE));
        assertThrows(IllegalArgumentException.class,
                () -> new TrackCorrection(ASSET_ID, USAGE_ID, FRAME_AT, COMPUTED_AT, null,
                        CorrectionSource.VISUAL_HEAVY, null, null, null, null, null, null, null, false, null, "",
                        "", "refused", EVIDENCE));
        assertThrows(IllegalArgumentException.class,
                () -> new TrackCorrection(ASSET_ID, USAGE_ID, FRAME_AT, COMPUTED_AT, CorrectionStatus.NO_FIX, null,
                        null, null, null, null, null, null, null, false, null, "", "", "refused", EVIDENCE));
        assertThrows(IllegalArgumentException.class,
                () -> new TrackCorrection(ASSET_ID, USAGE_ID, FRAME_AT, COMPUTED_AT, CorrectionStatus.NO_FIX,
                        CorrectionSource.VISUAL_HEAVY, null, null, null, null, null, null, null, false, null, "",
                        "", "refused", null));
    }
}
