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

class CvProfileTest {

    private static final Instant CREATED_AT = Instant.parse("2026-08-30T00:00:00Z");
    private static final Instant UPDATED_AT = Instant.parse("2026-08-30T01:00:00Z");

    private static CvProfile validProfile() {
        return new CvProfile(CvProfileId.random(), "mast-cams", "Fixed masts, low rate", false, GroupId.random(),
                new ModelRef("yolo26n.pt", "latest"), 0.35, 2, List.of(), List.of("tree"), true,
                TrackingConfig.defaults(), EventRuleConfig.defaults(), CREATED_AT, UPDATED_AT);
    }

    private static CvProfile builtInProfile() {
        return new CvProfile(CvProfileId.random(), "people-vehicles", "General people & vehicles preset", true,
                null, new ModelRef("yolo26n.pt", "latest"), 0.4, 10, List.of(), List.of(), true,
                TrackingConfig.defaults(), EventRuleConfig.defaults(), CREATED_AT, UPDATED_AT);
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
    void rejectsNullId() {
        assertThrows(IllegalArgumentException.class, () -> new CvProfile(null, "name", "", false, GroupId.random(),
                new ModelRef("yolo26n.pt", "latest"), 0.4, 10, List.of(), List.of(), true,
                TrackingConfig.defaults(), EventRuleConfig.defaults(), CREATED_AT, UPDATED_AT));
    }

    @Test
    void rejectsBlankName() {
        assertThrows(IllegalArgumentException.class,
                () -> new CvProfile(CvProfileId.random(), null, "", false, GroupId.random(),
                        new ModelRef("yolo26n.pt", "latest"), 0.4, 10, List.of(), List.of(), true,
                        TrackingConfig.defaults(), EventRuleConfig.defaults(), CREATED_AT, UPDATED_AT));
        assertThrows(IllegalArgumentException.class,
                () -> new CvProfile(CvProfileId.random(), "   ", "", false, GroupId.random(),
                        new ModelRef("yolo26n.pt", "latest"), 0.4, 10, List.of(), List.of(), true,
                        TrackingConfig.defaults(), EventRuleConfig.defaults(), CREATED_AT, UPDATED_AT));
    }

    @Test
    void rejectsNullDescription() {
        assertThrows(IllegalArgumentException.class,
                () -> new CvProfile(CvProfileId.random(), "name", null, false, GroupId.random(),
                        new ModelRef("yolo26n.pt", "latest"), 0.4, 10, List.of(), List.of(), true,
                        TrackingConfig.defaults(), EventRuleConfig.defaults(), CREATED_AT, UPDATED_AT));
    }

    @Test
    void allowsBlankButNonNullDescription() {
        CvProfile profile = new CvProfile(CvProfileId.random(), "name", "", false, GroupId.random(),
                new ModelRef("yolo26n.pt", "latest"), 0.4, 10, List.of(), List.of(), true,
                TrackingConfig.defaults(), EventRuleConfig.defaults(), CREATED_AT, UPDATED_AT);

        assertEquals("", profile.description());
    }

    @Test
    void rejectsBuiltInWithNonNullGroupId() {
        assertThrows(IllegalArgumentException.class,
                () -> new CvProfile(CvProfileId.random(), "video-only", "", true, GroupId.random(),
                        new ModelRef("yolo26n.pt", "latest"), 0.4, 10, List.of(), List.of(), false,
                        TrackingConfig.defaults(), EventRuleConfig.defaults(), CREATED_AT, UPDATED_AT));
    }

    @Test
    void rejectsNonBuiltInWithNullGroupId() {
        assertThrows(IllegalArgumentException.class,
                () -> new CvProfile(CvProfileId.random(), "mast-cams", "", false, null,
                        new ModelRef("yolo26n.pt", "latest"), 0.4, 10, List.of(), List.of(), true,
                        TrackingConfig.defaults(), EventRuleConfig.defaults(), CREATED_AT, UPDATED_AT));
    }

    @Test
    void rejectsNullModel() {
        assertThrows(IllegalArgumentException.class,
                () -> new CvProfile(CvProfileId.random(), "name", "", false, GroupId.random(), null, 0.4, 10,
                        List.of(), List.of(), true, TrackingConfig.defaults(), EventRuleConfig.defaults(),
                        CREATED_AT, UPDATED_AT));
    }

    @Test
    void rejectsOutOfRangeConfidenceThreshold() {
        assertThrows(IllegalArgumentException.class,
                () -> new CvProfile(CvProfileId.random(), "name", "", false, GroupId.random(),
                        new ModelRef("yolo26n.pt", "latest"), -0.01, 10, List.of(), List.of(), true,
                        TrackingConfig.defaults(), EventRuleConfig.defaults(), CREATED_AT, UPDATED_AT));
        assertThrows(IllegalArgumentException.class,
                () -> new CvProfile(CvProfileId.random(), "name", "", false, GroupId.random(),
                        new ModelRef("yolo26n.pt", "latest"), 1.01, 10, List.of(), List.of(), true,
                        TrackingConfig.defaults(), EventRuleConfig.defaults(), CREATED_AT, UPDATED_AT));
    }

    @Test
    void rejectsNonPositiveInferenceFps() {
        assertThrows(IllegalArgumentException.class,
                () -> new CvProfile(CvProfileId.random(), "name", "", false, GroupId.random(),
                        new ModelRef("yolo26n.pt", "latest"), 0.4, 0, List.of(), List.of(), true,
                        TrackingConfig.defaults(), EventRuleConfig.defaults(), CREATED_AT, UPDATED_AT));
    }

    @Test
    void rejectsNullLabelFilter() {
        assertThrows(IllegalArgumentException.class,
                () -> new CvProfile(CvProfileId.random(), "name", "", false, GroupId.random(),
                        new ModelRef("yolo26n.pt", "latest"), 0.4, 10, null, List.of(), true,
                        TrackingConfig.defaults(), EventRuleConfig.defaults(), CREATED_AT, UPDATED_AT));
    }

    @Test
    void rejectsNullLabelDenyFilter() {
        assertThrows(IllegalArgumentException.class,
                () -> new CvProfile(CvProfileId.random(), "name", "", false, GroupId.random(),
                        new ModelRef("yolo26n.pt", "latest"), 0.4, 10, List.of(), null, true,
                        TrackingConfig.defaults(), EventRuleConfig.defaults(), CREATED_AT, UPDATED_AT));
    }

    @Test
    void rejectsNullTracking() {
        assertThrows(IllegalArgumentException.class,
                () -> new CvProfile(CvProfileId.random(), "name", "", false, GroupId.random(),
                        new ModelRef("yolo26n.pt", "latest"), 0.4, 10, List.of(), List.of(), true, null,
                        EventRuleConfig.defaults(), CREATED_AT, UPDATED_AT));
    }

    @Test
    void rejectsNullEventRule() {
        assertThrows(IllegalArgumentException.class,
                () -> new CvProfile(CvProfileId.random(), "name", "", false, GroupId.random(),
                        new ModelRef("yolo26n.pt", "latest"), 0.4, 10, List.of(), List.of(), true,
                        TrackingConfig.defaults(), null, CREATED_AT, UPDATED_AT));
    }

    @Test
    void rejectsNullCreatedAt() {
        assertThrows(IllegalArgumentException.class,
                () -> new CvProfile(CvProfileId.random(), "name", "", false, GroupId.random(),
                        new ModelRef("yolo26n.pt", "latest"), 0.4, 10, List.of(), List.of(), true,
                        TrackingConfig.defaults(), EventRuleConfig.defaults(), null, UPDATED_AT));
    }

    @Test
    void rejectsNullUpdatedAt() {
        assertThrows(IllegalArgumentException.class,
                () -> new CvProfile(CvProfileId.random(), "name", "", false, GroupId.random(),
                        new ModelRef("yolo26n.pt", "latest"), 0.4, 10, List.of(), List.of(), true,
                        TrackingConfig.defaults(), EventRuleConfig.defaults(), CREATED_AT, null));
    }

    @Test
    void rejectsUpdatedAtBeforeCreatedAt() {
        assertThrows(IllegalArgumentException.class,
                () -> new CvProfile(CvProfileId.random(), "name", "", false, GroupId.random(),
                        new ModelRef("yolo26n.pt", "latest"), 0.4, 10, List.of(), List.of(), true,
                        TrackingConfig.defaults(), EventRuleConfig.defaults(), UPDATED_AT, CREATED_AT));
    }

    @Test
    void labelFilterIsDefensivelyCopiedAndImmutable() {
        List<String> labels = new ArrayList<>();
        labels.add("person");

        CvProfile profile = new CvProfile(CvProfileId.random(), "name", "", false, GroupId.random(),
                new ModelRef("yolo26n.pt", "latest"), 0.4, 10, labels, List.of(), true,
                TrackingConfig.defaults(), EventRuleConfig.defaults(), CREATED_AT, UPDATED_AT);

        labels.add("car");

        assertEquals(1, profile.labelFilter().size());
        assertThrows(UnsupportedOperationException.class, () -> profile.labelFilter().add("dog"));
    }

    @Test
    void labelDenyFilterIsDefensivelyCopiedAndImmutable() {
        List<String> denied = new ArrayList<>();
        denied.add("tree");

        CvProfile profile = new CvProfile(CvProfileId.random(), "name", "", false, GroupId.random(),
                new ModelRef("yolo26n.pt", "latest"), 0.4, 10, List.of(), denied, true,
                TrackingConfig.defaults(), EventRuleConfig.defaults(), CREATED_AT, UPDATED_AT);

        denied.add("cloud");

        assertEquals(1, profile.labelDenyFilter().size());
        assertThrows(UnsupportedOperationException.class, () -> profile.labelDenyFilter().add("bird"));
    }

    @Test
    void toPipelineConfigTakesEveryFieldFromTheProfileExceptMaxInFlightInferences() {
        ModelRef model = new ModelRef("orion12l.pt", "latest");
        TrackingConfig tracking = TrackingConfig.off();
        EventRuleConfig eventRule = new EventRuleConfig(Set.of("truck"), 0.6, 4, Duration.ofSeconds(8));
        CvProfile profile = new CvProfile(CvProfileId.random(), "military-vehicles", "", false, GroupId.random(),
                model, 0.45, 5, List.of("truck", "tank"), List.of("bird"), false, tracking, eventRule,
                CREATED_AT, UPDATED_AT);
        PipelineConfig defaults = PipelineConfig.defaults();

        PipelineConfig resolved = profile.toPipelineConfig(defaults);

        assertEquals(model, resolved.model());
        assertEquals(0.45, resolved.confidenceThreshold());
        assertEquals(5, resolved.inferenceFps());
        assertEquals(Set.of("truck", "tank"), resolved.labelFilter());
        assertEquals(Set.of("bird"), resolved.labelDenyFilter());
        assertFalse(resolved.detectionEnabled());
        assertEquals(tracking, resolved.tracking());
        assertEquals(eventRule, resolved.eventRule());
        // maxInFlightInferences is not a profile field -- it always comes from defaults.
        assertEquals(defaults.maxInFlightInferences(), resolved.maxInFlightInferences());
    }

    @Test
    void toPipelineConfigRejectsNullDefaults() {
        CvProfile profile = validProfile();

        assertThrows(IllegalArgumentException.class, () -> profile.toPipelineConfig(null));
    }

    @Test
    void profileMirroringDefaultsFieldsFoldsToByteIdenticalPipelineConfigDefaults() {
        PipelineConfig defaults = PipelineConfig.defaults();
        CvProfile profile = new CvProfile(CvProfileId.random(), "video-only", "", true, null,
                defaults.model(), defaults.confidenceThreshold(), defaults.inferenceFps(),
                List.copyOf(defaults.labelFilter()), List.copyOf(defaults.labelDenyFilter()),
                defaults.detectionEnabled(), defaults.tracking(), defaults.eventRule(), CREATED_AT, UPDATED_AT);

        PipelineConfig resolved = profile.toPipelineConfig(defaults);

        assertEquals(defaults, resolved);
    }
}
