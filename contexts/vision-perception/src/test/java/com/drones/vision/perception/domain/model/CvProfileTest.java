package com.drones.vision.perception.domain.model;

import com.drones.vision.kernel.GroupId;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CvProfile}: a profile is a patch (docs/plans/active/CV-ORCHESTRATION-PLAN.md &sect;4.7/&sect;8
 * decision E22, wave W7) — every knob nullable ("inherit from the tier below"), and {@link
 * CvProfile#foldOnto(PipelineConfig)} the per-knob fold that replaced {@code toPipelineConfig}.
 */
class CvProfileTest {

    private static final Instant CREATED_AT = Instant.parse("2026-08-30T00:00:00Z");
    private static final Instant UPDATED_AT = Instant.parse("2026-08-30T01:00:00Z");

    private static CvProfile validProfile() {
        return new CvProfile(CvProfileId.random(), "mast-cams", "Fixed masts, low rate", false, GroupId.random(),
                new ModelRef("yolo26n.pt", "latest"), 0.35, 2, List.of(), List.of("tree"), true,
                null, EventRuleConfig.defaults(), null, CREATED_AT, UPDATED_AT);
    }

    private static CvProfile builtInProfile() {
        return new CvProfile(CvProfileId.random(), "people-vehicles", "General people & vehicles preset", true,
                null, new ModelRef("yolo26n.pt", "latest"), 0.4, 10, List.of(), List.of(), true,
                null, EventRuleConfig.defaults(), null, CREATED_AT, UPDATED_AT);
    }

    /** A profile whose every knob is {@code null} — the pure "inherit everything" patch. */
    private static CvProfile fullyInheritingProfile(GroupId groupId) {
        return new CvProfile(CvProfileId.random(), "mast-cams", "", false, groupId, null, null, null, null, null,
                null, null, null, null, CREATED_AT, UPDATED_AT);
    }

    @Test
    void acceptsAValidNonBuiltInProfile() {
        CvProfile profile = validProfile();

        assertEquals("mast-cams", profile.name());
        assertFalse(profile.builtIn());
        assertNotNull(profile.groupId());
    }

    @Test
    void acceptsAValidBuiltInProfileWithNullGroupId() {
        CvProfile profile = builtInProfile();

        assertTrue(profile.builtIn());
        assertNull(profile.groupId());
    }

    @Test
    void acceptsAProfileWithEveryKnobNull() {
        CvProfile profile = fullyInheritingProfile(GroupId.random());

        assertNull(profile.model());
        assertNull(profile.confidenceThreshold());
        assertNull(profile.inferenceFps());
        assertNull(profile.labelFilter());
        assertNull(profile.labelDenyFilter());
        assertNull(profile.detectionEnabled());
        assertNull(profile.tracking());
        assertNull(profile.eventRule());
        assertNull(profile.intent());
    }

    @Test
    void rejectsNullId() {
        assertThrows(IllegalArgumentException.class, () -> new CvProfile(null, "name", "", false, GroupId.random(),
                new ModelRef("yolo26n.pt", "latest"), 0.4, 10, List.of(), List.of(), true,
                null, EventRuleConfig.defaults(), null, CREATED_AT, UPDATED_AT));
    }

    @Test
    void rejectsBlankName() {
        assertThrows(IllegalArgumentException.class,
                () -> new CvProfile(CvProfileId.random(), null, "", false, GroupId.random(),
                        new ModelRef("yolo26n.pt", "latest"), 0.4, 10, List.of(), List.of(), true,
                        null, EventRuleConfig.defaults(), null, CREATED_AT, UPDATED_AT));
        assertThrows(IllegalArgumentException.class,
                () -> new CvProfile(CvProfileId.random(), "   ", "", false, GroupId.random(),
                        new ModelRef("yolo26n.pt", "latest"), 0.4, 10, List.of(), List.of(), true,
                        null, EventRuleConfig.defaults(), null, CREATED_AT, UPDATED_AT));
    }

    @Test
    void rejectsNullDescription() {
        assertThrows(IllegalArgumentException.class,
                () -> new CvProfile(CvProfileId.random(), "name", null, false, GroupId.random(),
                        new ModelRef("yolo26n.pt", "latest"), 0.4, 10, List.of(), List.of(), true,
                        null, EventRuleConfig.defaults(), null, CREATED_AT, UPDATED_AT));
    }

    @Test
    void allowsBlankButNonNullDescription() {
        CvProfile profile = new CvProfile(CvProfileId.random(), "name", "", false, GroupId.random(),
                new ModelRef("yolo26n.pt", "latest"), 0.4, 10, List.of(), List.of(), true,
                null, EventRuleConfig.defaults(), null, CREATED_AT, UPDATED_AT);

        assertEquals("", profile.description());
    }

    @Test
    void rejectsBuiltInWithNonNullGroupId() {
        assertThrows(IllegalArgumentException.class,
                () -> new CvProfile(CvProfileId.random(), "video-only", "", true, GroupId.random(),
                        new ModelRef("yolo26n.pt", "latest"), 0.4, 10, List.of(), List.of(), false,
                        null, EventRuleConfig.defaults(), null, CREATED_AT, UPDATED_AT));
    }

    @Test
    void rejectsNonBuiltInWithNullGroupId() {
        assertThrows(IllegalArgumentException.class,
                () -> new CvProfile(CvProfileId.random(), "mast-cams", "", false, null,
                        new ModelRef("yolo26n.pt", "latest"), 0.4, 10, List.of(), List.of(), true,
                        null, EventRuleConfig.defaults(), null, CREATED_AT, UPDATED_AT));
    }

    @Test
    void acceptsNullModelUnlikeThePreW7WholesaleRecord() {
        CvProfile profile = new CvProfile(CvProfileId.random(), "name", "", false, GroupId.random(), null, 0.4, 10,
                List.of(), List.of(), true, null, EventRuleConfig.defaults(), null, CREATED_AT, UPDATED_AT);

        assertNull(profile.model());
    }

    @Test
    void rejectsOutOfRangeConfidenceThresholdWhenSet() {
        assertThrows(IllegalArgumentException.class,
                () -> new CvProfile(CvProfileId.random(), "name", "", false, GroupId.random(),
                        new ModelRef("yolo26n.pt", "latest"), -0.01, 10, List.of(), List.of(), true,
                        null, EventRuleConfig.defaults(), null, CREATED_AT, UPDATED_AT));
        assertThrows(IllegalArgumentException.class,
                () -> new CvProfile(CvProfileId.random(), "name", "", false, GroupId.random(),
                        new ModelRef("yolo26n.pt", "latest"), 1.01, 10, List.of(), List.of(), true,
                        null, EventRuleConfig.defaults(), null, CREATED_AT, UPDATED_AT));
    }

    @Test
    void rejectsNonPositiveInferenceFpsWhenSet() {
        assertThrows(IllegalArgumentException.class,
                () -> new CvProfile(CvProfileId.random(), "name", "", false, GroupId.random(),
                        new ModelRef("yolo26n.pt", "latest"), 0.4, 0, List.of(), List.of(), true,
                        null, EventRuleConfig.defaults(), null, CREATED_AT, UPDATED_AT));
    }

    @Test
    void rejectsCustomIntentWithNullLabelFilter() {
        assertThrows(IllegalArgumentException.class,
                () -> new CvProfile(CvProfileId.random(), "name", "", false, GroupId.random(),
                        new ModelRef("yolo26n.pt", "latest"), 0.4, 10, null, List.of(), true,
                        null, EventRuleConfig.defaults(), Intent.CUSTOM, CREATED_AT, UPDATED_AT));
    }

    @Test
    void rejectsCustomIntentWithEmptyLabelFilter() {
        assertThrows(IllegalArgumentException.class,
                () -> new CvProfile(CvProfileId.random(), "name", "", false, GroupId.random(),
                        new ModelRef("yolo26n.pt", "latest"), 0.4, 10, List.of(), List.of(), true,
                        null, EventRuleConfig.defaults(), Intent.CUSTOM, CREATED_AT, UPDATED_AT));
    }

    @Test
    void acceptsCustomIntentWithNonEmptyLabelFilter() {
        CvProfile profile = new CvProfile(CvProfileId.random(), "name", "", false, GroupId.random(),
                new ModelRef("yolo26n.pt", "latest"), 0.4, 10, List.of("forklift"), List.of(), true,
                null, EventRuleConfig.defaults(), Intent.CUSTOM, CREATED_AT, UPDATED_AT);

        assertEquals(Intent.CUSTOM, profile.intent());
    }

    @Test
    void acceptsNonCustomIntentWithNullLabelFilter() {
        CvProfile profile = new CvProfile(CvProfileId.random(), "name", "", false, GroupId.random(),
                new ModelRef("yolo26n.pt", "latest"), 0.4, 10, null, List.of(), true,
                null, EventRuleConfig.defaults(), Intent.PEOPLE, CREATED_AT, UPDATED_AT);

        assertEquals(Intent.PEOPLE, profile.intent());
        assertNull(profile.labelFilter());
    }

    @Test
    void rejectsNullCreatedAt() {
        assertThrows(IllegalArgumentException.class,
                () -> new CvProfile(CvProfileId.random(), "name", "", false, GroupId.random(),
                        new ModelRef("yolo26n.pt", "latest"), 0.4, 10, List.of(), List.of(), true,
                        null, EventRuleConfig.defaults(), null, null, UPDATED_AT));
    }

    @Test
    void rejectsNullUpdatedAt() {
        assertThrows(IllegalArgumentException.class,
                () -> new CvProfile(CvProfileId.random(), "name", "", false, GroupId.random(),
                        new ModelRef("yolo26n.pt", "latest"), 0.4, 10, List.of(), List.of(), true,
                        null, EventRuleConfig.defaults(), null, CREATED_AT, null));
    }

    @Test
    void rejectsUpdatedAtBeforeCreatedAt() {
        assertThrows(IllegalArgumentException.class,
                () -> new CvProfile(CvProfileId.random(), "name", "", false, GroupId.random(),
                        new ModelRef("yolo26n.pt", "latest"), 0.4, 10, List.of(), List.of(), true,
                        null, EventRuleConfig.defaults(), null, UPDATED_AT, CREATED_AT));
    }

    @Test
    void labelFilterIsDefensivelyCopiedAndImmutableWhenSet() {
        List<String> labels = new ArrayList<>();
        labels.add("person");

        CvProfile profile = new CvProfile(CvProfileId.random(), "name", "", false, GroupId.random(),
                new ModelRef("yolo26n.pt", "latest"), 0.4, 10, labels, List.of(), true,
                null, EventRuleConfig.defaults(), null, CREATED_AT, UPDATED_AT);

        labels.add("car");

        assertEquals(1, profile.labelFilter().size());
        assertThrows(UnsupportedOperationException.class, () -> profile.labelFilter().add("dog"));
    }

    @Test
    void labelDenyFilterIsDefensivelyCopiedAndImmutableWhenSet() {
        List<String> denied = new ArrayList<>();
        denied.add("tree");

        CvProfile profile = new CvProfile(CvProfileId.random(), "name", "", false, GroupId.random(),
                new ModelRef("yolo26n.pt", "latest"), 0.4, 10, List.of(), denied, true,
                null, EventRuleConfig.defaults(), null, CREATED_AT, UPDATED_AT);

        denied.add("cloud");

        assertEquals(1, profile.labelDenyFilter().size());
        assertThrows(UnsupportedOperationException.class, () -> profile.labelDenyFilter().add("bird"));
    }

    @Test
    void foldOntoTakesEveryFieldFromTheProfileWhenEveryKnobIsSet() {
        ModelRef model = new ModelRef("orion12l.pt", "latest");
        TrackingKnobPatch tracking = new TrackingKnobPatch(TrackingMode.OFF, "", 0, 2000, 15);
        EventRuleConfig eventRule = new EventRuleConfig(Set.of("truck"), 0.6, 4, Duration.ofSeconds(8));
        CvProfile profile = new CvProfile(CvProfileId.random(), "military-vehicles", "", false, GroupId.random(),
                model, 0.45, 5, List.of("truck", "tank"), List.of("bird"), false, tracking, eventRule, null,
                CREATED_AT, UPDATED_AT);
        PipelineConfig below = PipelineConfig.defaults();

        PipelineConfig resolved = profile.foldOnto(below);

        assertEquals(model, resolved.model());
        assertEquals(0.45, resolved.confidenceThreshold());
        assertEquals(5, resolved.inferenceFps());
        assertEquals(Set.of("truck", "tank"), resolved.labelFilter());
        assertEquals(Set.of("bird"), resolved.labelDenyFilter());
        assertFalse(resolved.detectionEnabled());
        assertEquals(tracking.foldOnto(below.tracking()), resolved.tracking());
        assertEquals(eventRule, resolved.eventRule());
        // maxInFlightInferences/trace are never profile fields -- they always come from below.
        assertEquals(below.maxInFlightInferences(), resolved.maxInFlightInferences());
        assertEquals(below.trace(), resolved.trace());
    }

    @Test
    void foldOntoLeavesEveryNullKnobExactlyAsBelowHasIt() {
        CvProfile profile = fullyInheritingProfile(GroupId.random());
        PipelineConfig below = PipelineConfig.defaults();

        PipelineConfig resolved = profile.foldOnto(below);

        assertEquals(below, resolved, "every knob null must fold to a value-identical PipelineConfig");
    }

    @Test
    void foldOntoPatchesOnlyTheKnobsThisProfileSetsLeavingTheRestAsBelowHasThem() {
        CvProfile profile = new CvProfile(CvProfileId.random(), "asset-override", "", false, GroupId.random(),
                null, 0.9, null, null, null, null, null, null, null, CREATED_AT, UPDATED_AT);
        PipelineConfig below = new PipelineConfig(new ModelRef("category-model", "v2"), 0.4, 7, 2, Set.of("person"),
                EventRuleConfig.defaults(), true, TrackingConfig.defaults(), Set.of(), false);

        PipelineConfig resolved = profile.foldOnto(below);

        assertEquals(0.9, resolved.confidenceThreshold(), "the one knob this profile sets wins");
        assertEquals(below.model(), resolved.model(), "every other knob is inherited from below, unreplaced");
        assertEquals(below.inferenceFps(), resolved.inferenceFps());
        assertEquals(below.labelFilter(), resolved.labelFilter());
        assertEquals(below.detectionEnabled(), resolved.detectionEnabled());
        assertEquals(below.tracking(), resolved.tracking());
    }

    @Test
    void foldOntoRejectsNullBelow() {
        CvProfile profile = validProfile();

        assertThrows(IllegalArgumentException.class, () -> profile.foldOnto(null));
    }

    @Test
    void profileMirroringDefaultsFieldsFoldsToByteIdenticalPipelineConfigDefaults() {
        PipelineConfig defaults = PipelineConfig.defaults();
        TrackingConfig defaultTracking = defaults.tracking();
        TrackingKnobPatch tracking = new TrackingKnobPatch(defaultTracking.mode(), defaultTracking.engineId(),
                defaultTracking.capabilityLevel(), defaultTracking.verifyEveryMillis(), defaultTracking.followFps());
        CvProfile profile = new CvProfile(CvProfileId.random(), "video-only", "", true, null,
                defaults.model(), defaults.confidenceThreshold(), defaults.inferenceFps(),
                List.copyOf(defaults.labelFilter()), List.copyOf(defaults.labelDenyFilter()),
                defaults.detectionEnabled(), tracking, defaults.eventRule(), null, CREATED_AT, UPDATED_AT);

        PipelineConfig resolved = profile.foldOnto(defaults);

        assertEquals(defaults, resolved);
    }
}
