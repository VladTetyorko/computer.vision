package com.drones.vision.api.dto;

import com.drones.vision.perception.application.profile.CvProfileSpec;
import com.drones.vision.perception.application.profile.ProfileSource;
import com.drones.vision.perception.domain.model.EventRuleConfig;
import com.drones.vision.perception.domain.model.Intent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link CvProfileRequest#toSpec()}'s intent fold and {@link CvProfileRequest#fieldSources()}'s
 * per-knob provenance (docs/plans/active/CV-ORCHESTRATION-PLAN.md &sect;4.7, waves W2.6/W2.8) — see
 * both methods' own javadoc for the exact rules pinned here: a blank {@code model}/empty {@code
 * labelFilter} defer to {@link Intent}'s resolved policy, an explicit value always wins, and {@code
 * fieldSources()} reports {@link ProfileSource#INTENT} under precisely the same condition that made
 * {@link #toSpec()} use the intent's value.
 */
class CvProfileRequestTest {

    private static final CvProfileTrackingResponse OFF_TRACKING =
            new CvProfileTrackingResponse("OFF", "", 0, 500, 5);

    private static CvProfileRequest request(String model, List<String> labelFilter, Intent intent) {
        return new CvProfileRequest("profile-name", "a description", model, 0.42, 7, labelFilter, List.of(), true,
                OFF_TRACKING, intent);
    }

    @Test
    void nullIntentLeavesEveryFieldExactlyAsSentByteIdenticalToBeforeThisWave() {
        CvProfileRequest request = request("yolov8n.pt", List.of("dog"), null);

        CvProfileSpec spec = request.toSpec();

        assertEquals("yolov8n.pt", spec.model().id());
        assertEquals(List.of("dog"), spec.labelFilter());
        assertEquals(EventRuleConfig.defaults(), spec.eventRule());
    }

    @Test
    void blankModelWithIntentSeedsModelFromIntentPolicy() {
        CvProfileRequest request = request("", List.of(), Intent.PEOPLE);

        CvProfileSpec spec = request.toSpec();

        assertEquals("yolo26n.pt", spec.model().id());
        assertEquals(Set.of("person"), Set.copyOf(spec.labelFilter()));
    }

    @Test
    void nonBlankModelWinsOverIntentButLabelFilterIsStillSeededWhenEmpty() {
        CvProfileRequest request = request("expert-pinned-model.pt", List.of(), Intent.PEOPLE);

        CvProfileSpec spec = request.toSpec();

        // "Expert tier can still pin a model" (CvProfileRequest's own javadoc) -- an explicit,
        // non-blank model always wins over the intent's own choice, independently of whether the
        // OTHER seedable field (labelFilter) still defers to the intent.
        assertEquals("expert-pinned-model.pt", spec.model().id());
        assertEquals(Set.of("person"), Set.copyOf(spec.labelFilter()));
    }

    @Test
    void nonEmptyLabelFilterWinsOverIntentEvenWhenModelIsBlank() {
        CvProfileRequest request = request("", List.of("bicycle"), Intent.VEHICLES);

        CvProfileSpec spec = request.toSpec();

        assertEquals("yolo26n.pt", spec.model().id());
        // A non-empty labelFilter beats VEHICLES' own {car, truck, bus, motorcycle} seed.
        assertEquals(List.of("bicycle"), spec.labelFilter());
    }

    @Test
    void customIntentWithItsOwnLabelFilterIsReturnedUnchanged() {
        List<String> callerClasses = List.of("forklift", "pallet-jack");
        CvProfileRequest request = request("", callerClasses, Intent.CUSTOM);

        CvProfileSpec spec = request.toSpec();

        // CUSTOM carries no fixed class set of its own -- a non-empty labelFilter both satisfies
        // IntentPolicyResolver's own validation for CUSTOM and wins outright, coming back as the
        // exact same List (order and duplicates untouched), not a re-derived Set.
        assertEquals(callerClasses, spec.labelFilter());
        assertEquals("yoloe-26s-seg-pf.pt", spec.model().id());
    }

    @Test
    void customIntentWithBlankModelAndEmptyLabelFilterThrowsRatherThanInventingASeed() {
        CvProfileRequest request = request("", List.of(), Intent.CUSTOM);

        // IntentPolicyResolver.resolve(CUSTOM, ...) requires a non-empty customClasses list --
        // toSpec() always resolves the intent up front (it needs the model/eventRule even when
        // labelFilter itself would not need seeding), so an empty labelFilter surfaces this as a
        // 400 at request-validation time rather than silently defaulting to "every label."
        assertThrows(IllegalArgumentException.class, request::toSpec);
    }

    @Test
    void eventRuleConfidenceIsSynthesizedFromTheResolvedIntentPolicyWhenIntentIsPresent() {
        CvProfileRequest request = request("", List.of(), Intent.PEOPLE);

        CvProfileSpec spec = request.toSpec();

        // Every Intent's own IntentPolicy.reportThreshold() happens to be 0.5, same numeric value
        // as EventRuleConfig.defaults() -- this assertion pins that the value is reached via
        // IntentPolicyResolver's fold (resolved.reportThreshold()), not coincidentally equal to
        // defaults() being used untouched; labels/consecutiveToOpen/absenceToClose remain
        // defaults()'s own, since toSpec() only ever substitutes the confidence component.
        assertEquals(EventRuleConfig.defaults().labels(), spec.eventRule().labels());
        assertEquals(0.5, spec.eventRule().confidenceThreshold());
        assertEquals(EventRuleConfig.defaults().consecutiveToOpen(), spec.eventRule().consecutiveToOpen());
        assertEquals(EventRuleConfig.defaults().absenceToClose(), spec.eventRule().absenceToClose());
    }

    @Test
    void fieldSourcesIsNullNullWhenIntentIsNull() {
        CvProfileRequest request = request("yolov8n.pt", List.of("dog"), null);

        CvProfileRequest.Sources sources = request.fieldSources();

        assertNull(sources.model());
        assertNull(sources.labelFilter());
    }

    @Test
    void fieldSourcesReportsIntentForBothKnobsWhenBothWereBlankOrEmpty() {
        CvProfileRequest request = request("", List.of(), Intent.PEOPLE);

        CvProfileRequest.Sources sources = request.fieldSources();

        assertEquals(ProfileSource.INTENT, sources.model());
        assertEquals(ProfileSource.INTENT, sources.labelFilter());
    }

    @Test
    void fieldSourcesReportsOnlyLabelFilterAsIntentWhenModelWasExplicit() {
        CvProfileRequest request = request("expert-pinned-model.pt", List.of(), Intent.PEOPLE);

        CvProfileRequest.Sources sources = request.fieldSources();

        // Matches toSpec()'s own field-by-field logic exactly: a field reports INTENT under
        // precisely the condition that made toSpec() use the intent's value for it.
        assertNull(sources.model());
        assertEquals(ProfileSource.INTENT, sources.labelFilter());
    }

    @Test
    void fieldSourcesReportsOnlyModelAsIntentWhenLabelFilterWasExplicit() {
        CvProfileRequest request = request("", List.of("bicycle"), Intent.VEHICLES);

        CvProfileRequest.Sources sources = request.fieldSources();

        assertEquals(ProfileSource.INTENT, sources.model());
        assertNull(sources.labelFilter());
    }

    @Test
    void fieldSourcesIsNullNullWhenIntentIsPresentButBothFieldsWereAlreadyExplicit() {
        CvProfileRequest request = request("expert-pinned-model.pt", List.of("bicycle"), Intent.VEHICLES);

        CvProfileRequest.Sources sources = request.fieldSources();

        assertNull(sources.model());
        assertNull(sources.labelFilter());
    }
}
