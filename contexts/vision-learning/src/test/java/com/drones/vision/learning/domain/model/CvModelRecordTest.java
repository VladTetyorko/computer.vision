package com.drones.vision.learning.domain.model;

import com.drones.vision.kernel.UserId;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CvModelRecordTest {

    private static CvModelRecord model(List<String> defaultLabelFilter, List<String> classes,
                                        ModelStatus status, UserId promotedBy, Instant promotedAt) {
        return new CvModelRecord("yolo26n.pt", "latest", "People & vehicles", "general", false,
                defaultLabelFilter, ModelTaskType.DETECT, ModelRuntime.PYTORCH, classes, status,
                new ModelMetrics(0.71, MetricsKind.TRAINING), ModelProvenance.none(), promotedBy,
                promotedAt, Instant.now());
    }

    private static CvModelRecord draftModel() {
        return model(List.of(), List.of("person", "car"), ModelStatus.DRAFT, null, null);
    }

    @Test
    void rejectsBlankModelId() {
        assertThrows(IllegalArgumentException.class,
                () -> new CvModelRecord("", "latest", "name", "general", false, List.of(),
                        ModelTaskType.DETECT, ModelRuntime.PYTORCH, List.of(), ModelStatus.DRAFT, null,
                        ModelProvenance.none(), null, null, Instant.now()));
        assertThrows(IllegalArgumentException.class,
                () -> new CvModelRecord(null, "latest", "name", "general", false, List.of(),
                        ModelTaskType.DETECT, ModelRuntime.PYTORCH, List.of(), ModelStatus.DRAFT, null,
                        ModelProvenance.none(), null, null, Instant.now()));
    }

    @Test
    void rejectsBlankVersion() {
        assertThrows(IllegalArgumentException.class,
                () -> new CvModelRecord("yolo26n.pt", "  ", "name", "general", false, List.of(),
                        ModelTaskType.DETECT, ModelRuntime.PYTORCH, List.of(), ModelStatus.DRAFT, null,
                        ModelProvenance.none(), null, null, Instant.now()));
    }

    @Test
    void rejectsBlankDisplayName() {
        assertThrows(IllegalArgumentException.class,
                () -> new CvModelRecord("yolo26n.pt", "latest", "", "general", false, List.of(),
                        ModelTaskType.DETECT, ModelRuntime.PYTORCH, List.of(), ModelStatus.DRAFT, null,
                        ModelProvenance.none(), null, null, Instant.now()));
    }

    @Test
    void rejectsBlankKind() {
        assertThrows(IllegalArgumentException.class,
                () -> new CvModelRecord("yolo26n.pt", "latest", "name", "", false, List.of(),
                        ModelTaskType.DETECT, ModelRuntime.PYTORCH, List.of(), ModelStatus.DRAFT, null,
                        ModelProvenance.none(), null, null, Instant.now()));
    }

    @Test
    void rejectsNullDefaultLabelFilter() {
        assertThrows(IllegalArgumentException.class,
                () -> new CvModelRecord("yolo26n.pt", "latest", "name", "general", false, null,
                        ModelTaskType.DETECT, ModelRuntime.PYTORCH, List.of(), ModelStatus.DRAFT, null,
                        ModelProvenance.none(), null, null, Instant.now()));
    }

    @Test
    void rejectsNullTaskType() {
        assertThrows(IllegalArgumentException.class,
                () -> new CvModelRecord("yolo26n.pt", "latest", "name", "general", false, List.of(), null,
                        ModelRuntime.PYTORCH, List.of(), ModelStatus.DRAFT, null, ModelProvenance.none(),
                        null, null, Instant.now()));
    }

    @Test
    void rejectsNullRuntime() {
        assertThrows(IllegalArgumentException.class,
                () -> new CvModelRecord("yolo26n.pt", "latest", "name", "general", false, List.of(),
                        ModelTaskType.DETECT, null, List.of(), ModelStatus.DRAFT, null,
                        ModelProvenance.none(), null, null, Instant.now()));
    }

    @Test
    void rejectsNullClasses() {
        assertThrows(IllegalArgumentException.class,
                () -> new CvModelRecord("yolo26n.pt", "latest", "name", "general", false, List.of(),
                        ModelTaskType.DETECT, ModelRuntime.PYTORCH, null, ModelStatus.DRAFT, null,
                        ModelProvenance.none(), null, null, Instant.now()));
    }

    @Test
    void rejectsNullStatus() {
        assertThrows(IllegalArgumentException.class,
                () -> new CvModelRecord("yolo26n.pt", "latest", "name", "general", false, List.of(),
                        ModelTaskType.DETECT, ModelRuntime.PYTORCH, List.of(), null, null,
                        ModelProvenance.none(), null, null, Instant.now()));
    }

    @Test
    void allowsNullMetrics() {
        CvModelRecord model = new CvModelRecord("yolo26n.pt", "latest", "name", "general", false, List.of(),
                ModelTaskType.DETECT, ModelRuntime.PYTORCH, List.of(), ModelStatus.DRAFT, null,
                ModelProvenance.none(), null, null, Instant.now());

        assertNull(model.metrics());
    }

    @Test
    void rejectsNullProvenance() {
        assertThrows(IllegalArgumentException.class,
                () -> new CvModelRecord("yolo26n.pt", "latest", "name", "general", false, List.of(),
                        ModelTaskType.DETECT, ModelRuntime.PYTORCH, List.of(), ModelStatus.DRAFT, null, null,
                        null, null, Instant.now()));
    }

    @Test
    void rejectsPromotedByWithoutPromotedAt() {
        assertThrows(IllegalArgumentException.class,
                () -> model(List.of(), List.of(), ModelStatus.LIVE, UserId.random(), null));
    }

    @Test
    void rejectsPromotedAtWithoutPromotedBy() {
        assertThrows(IllegalArgumentException.class,
                () -> model(List.of(), List.of(), ModelStatus.LIVE, null, Instant.now()));
    }

    @Test
    void rejectsNullCreatedAt() {
        assertThrows(IllegalArgumentException.class,
                () -> new CvModelRecord("yolo26n.pt", "latest", "name", "general", false, List.of(),
                        ModelTaskType.DETECT, ModelRuntime.PYTORCH, List.of(), ModelStatus.DRAFT, null,
                        ModelProvenance.none(), null, null, null));
    }

    @Test
    void defaultLabelFilterAndClassesAreDefensivelyCopiedAndOrderPreserving() {
        List<String> defaultLabelFilter = new ArrayList<>(List.of("person"));
        List<String> classes = new ArrayList<>(List.of("person", "car"));

        CvModelRecord model = model(defaultLabelFilter, classes, ModelStatus.DRAFT, null, null);
        defaultLabelFilter.add("mutation");
        classes.add("mutation");

        assertEquals(List.of("person"), model.defaultLabelFilter());
        assertEquals(List.of("person", "car"), model.classes());
        assertThrows(UnsupportedOperationException.class, () -> model.defaultLabelFilter().add("nope"));
        assertThrows(UnsupportedOperationException.class, () -> model.classes().add("nope"));
    }

    @Test
    void promoteRejectsNullPromotedBy() {
        CvModelRecord model = draftModel();

        assertThrows(IllegalArgumentException.class, () -> model.promote(null, Instant.now()));
    }

    @Test
    void promoteRejectsNullPromotedAt() {
        CvModelRecord model = draftModel();

        assertThrows(IllegalArgumentException.class, () -> model.promote(UserId.random(), null));
    }

    @Test
    void promoteReturnsALiveCopyStampedWithActorAndTime() {
        CvModelRecord model = draftModel();
        UserId actor = UserId.random();
        Instant now = Instant.now();

        CvModelRecord promoted = model.promote(actor, now);

        assertEquals(ModelStatus.LIVE, promoted.status());
        assertEquals(actor, promoted.promotedBy());
        assertEquals(now, promoted.promotedAt());
        assertEquals(ModelStatus.DRAFT, model.status(), "the original row must be unchanged");
        assertNull(model.promotedBy(), "the original row must be unchanged");
    }

    @Test
    void retireReturnsARetiredCopyKeepingPromotionHistory() {
        UserId actor = UserId.random();
        Instant promotedAt = Instant.now();
        CvModelRecord live = model(List.of(), List.of(), ModelStatus.LIVE, actor, promotedAt);

        CvModelRecord retired = live.retire();

        assertEquals(ModelStatus.RETIRED, retired.status());
        assertEquals(actor, retired.promotedBy(), "retire keeps promotion history");
        assertEquals(promotedAt, retired.promotedAt(), "retire keeps promotion history");
        assertEquals(ModelStatus.LIVE, live.status(), "the original row must be unchanged");
    }

    @Test
    void retireOfADraftRowWithNoPromotionHistoryStaysNull() {
        CvModelRecord retired = draftModel().retire();

        assertEquals(ModelStatus.RETIRED, retired.status());
        assertNull(retired.promotedBy());
        assertNull(retired.promotedAt());
    }
}
