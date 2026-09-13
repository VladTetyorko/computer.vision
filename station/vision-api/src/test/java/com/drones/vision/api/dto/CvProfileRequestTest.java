package com.drones.vision.api.dto;

import com.drones.vision.perception.application.profile.CvProfileSpec;
import com.drones.vision.perception.domain.model.Intent;
import com.drones.vision.perception.domain.model.TrackingKnobPatch;
import com.drones.vision.perception.domain.model.TrackingMode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link CvProfileRequest#toSpec()} — a straight, unresolved pass-through onto {@link CvProfileSpec}
 * (docs/plans/active/CV-ORCHESTRATION-PLAN.md &sect;4.7, decision E22, wave W7.3). See {@link
 * CvProfileRequest}'s own javadoc for why this replaces the pre-W7.3 intent-fold/{@code
 * fieldSources()} behavior this class used to pin: {@link #toSpec()} no longer resolves {@link
 * Intent} into {@code model}/{@code labelFilter}/a synthesized {@code eventRule} — it carries every
 * knob through byte-identical to what the request itself said, {@code eventRule} always {@code
 * null} (start-time only, never accepted on this wire shape), and {@link Intent} untouched for
 * {@code CvProfileResolver} to resolve later, at fold time.
 */
class CvProfileRequestTest {

    private static final CvProfileTrackingResponse TRACKING =
            new CvProfileTrackingResponse("OFF", "", 0, 500, 5);

    private static CvProfileRequest request(String model, Double confidenceThreshold, Integer inferenceFps,
                                             List<String> labelFilter, List<String> labelDenyFilter,
                                             Boolean detectionEnabled, CvProfileTrackingResponse tracking,
                                             Intent intent) {
        return new CvProfileRequest("profile-name", "a description", model, confidenceThreshold, inferenceFps,
                labelFilter, labelDenyFilter, detectionEnabled, tracking, intent);
    }

    @Test
    void toSpecPassesEveryFieldThroughUnresolvedByteIdenticalToTheRequest() {
        CvProfileRequest request = request("yolov8n.pt", 0.42, 7, List.of("dog"), List.of("cat"), true, TRACKING,
                Intent.PEOPLE);

        CvProfileSpec spec = request.toSpec();

        assertEquals("profile-name", spec.name());
        assertEquals("a description", spec.description());
        assertEquals("yolov8n.pt", spec.model().id());
        assertEquals(0.42, spec.confidenceThreshold());
        assertEquals(7, spec.inferenceFps());
        assertEquals(List.of("dog"), spec.labelFilter());
        assertEquals(List.of("cat"), spec.labelDenyFilter());
        assertEquals(true, spec.detectionEnabled());
        assertEquals(Intent.PEOPLE, spec.intent());
    }

    @Test
    void blankModelBecomesNullOnTheSpecRatherThanAnInvalidModelRef() {
        CvProfileRequest request = request("", null, null, null, null, null, null, null);

        CvProfileSpec spec = request.toSpec();

        assertNull(spec.model());
    }

    @Test
    void nullModelStaysNullOnTheSpec() {
        CvProfileRequest request = request(null, null, null, null, null, null, null, null);

        CvProfileSpec spec = request.toSpec();

        assertNull(spec.model());
    }

    @Test
    void explicitModelBecomesAModelRefWithTheLatestSentinelVersion() {
        CvProfileRequest request = request("yolo26n.pt", null, null, null, null, null, null, null);

        CvProfileSpec spec = request.toSpec();

        assertEquals("yolo26n.pt", spec.model().id());
        assertEquals("latest", spec.model().version());
    }

    @Test
    void intentTravelsToTheSpecUnresolvedRatherThanSeedingAnyField() {
        CvProfileRequest request = request(null, null, null, null, null, null, null, Intent.VEHICLES);

        CvProfileSpec spec = request.toSpec();

        // toSpec() never consults intent to seed model/labelFilter -- both stay unset even though
        // Intent.VEHICLES has its own fixed model/class-set an intent FOLD (CvProfileResolver, at
        // read time) would seed instead.
        assertEquals(Intent.VEHICLES, spec.intent());
        assertNull(spec.model());
        assertNull(spec.labelFilter());
    }

    @Test
    void emptyLabelFilterIsPassedThroughAsAnExplicitAllLabelsValueNotSeeded() {
        CvProfileRequest request = request(null, null, null, List.of(), null, null, null, null);

        CvProfileSpec spec = request.toSpec();

        assertEquals(List.of(), spec.labelFilter());
    }

    @Test
    void nullLabelFilterStaysNullMeaningInherit() {
        CvProfileRequest request = request(null, null, null, null, null, null, null, null);

        CvProfileSpec spec = request.toSpec();

        assertNull(spec.labelFilter());
    }

    @Test
    void customIntentWithEmptyLabelFilterThrowsFromCvProfileSpecsOwnValidation() {
        CvProfileRequest request = request(null, null, null, List.of(), null, null, null, Intent.CUSTOM);

        // CvProfileSpec's own compact constructor rejects CUSTOM with an empty labelFilter --
        // toSpec() performs no intent resolution of its own, so this is CvProfileSpec's validation
        // surfacing, not a duplicate of it.
        assertThrows(IllegalArgumentException.class, request::toSpec);
    }

    @Test
    void customIntentWithNonEmptyLabelFilterSucceedsAndPassesThroughVerbatim() {
        List<String> callerClasses = List.of("forklift", "pallet-jack");
        CvProfileRequest request = request(null, null, null, callerClasses, null, null, null, Intent.CUSTOM);

        CvProfileSpec spec = request.toSpec();

        assertEquals(callerClasses, spec.labelFilter());
        assertEquals(Intent.CUSTOM, spec.intent());
    }

    @Test
    void nullTrackingStaysNullOnTheSpecMeaningInheritTheWholeGroup() {
        CvProfileRequest request = request(null, null, null, null, null, null, null, null);

        CvProfileSpec spec = request.toSpec();

        assertNull(spec.tracking());
    }

    @Test
    void explicitTrackingBecomesATrackingKnobPatchWithAllFiveKnobsSet() {
        CvProfileRequest request = request(null, null, null, null, null, null, TRACKING, null);

        CvProfileSpec spec = request.toSpec();

        TrackingKnobPatch patch = spec.tracking();
        assertEquals(TrackingMode.OFF, patch.mode());
        assertEquals("", patch.engineId());
        assertEquals(0, patch.capabilityLevel());
        assertEquals(500, patch.verifyEveryMillis());
        assertEquals(5, patch.followFps());
    }

    @Test
    void eventRuleIsAlwaysNullOnTheSpecSinceThisWireShapeNeverAcceptsIt() {
        CvProfileRequest request = request("yolov8n.pt", null, null, null, null, null, null, Intent.PEOPLE);

        CvProfileSpec spec = request.toSpec();

        assertNull(spec.eventRule());
    }
}
