package com.drones.vision.learning.domain.model;

import com.drones.vision.kernel.UserId;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TrainingRunRecordTest {

    private static TrainingRunRecord run(JobState state, String outputModelId, Instant finishedAt) {
        return new TrainingRunRecord(TrainingRunId.random(), DatasetId.random(), "yolo26n.pt", 50, state,
                10, 50, 0.42, 0.71, outputModelId, UserId.random(), Instant.now(), finishedAt, "");
    }

    @Test
    void rejectsNullRunId() {
        assertThrows(IllegalArgumentException.class,
                () -> new TrainingRunRecord(null, DatasetId.random(), "yolo26n.pt", 50, JobState.RUNNING, 10,
                        50, 0.42, 0.71, null, UserId.random(), Instant.now(), null, ""));
    }

    @Test
    void rejectsNullDatasetId() {
        assertThrows(IllegalArgumentException.class,
                () -> new TrainingRunRecord(TrainingRunId.random(), null, "yolo26n.pt", 50, JobState.RUNNING,
                        10, 50, 0.42, 0.71, null, UserId.random(), Instant.now(), null, ""));
    }

    @Test
    void rejectsBlankBaseModel() {
        assertThrows(IllegalArgumentException.class,
                () -> new TrainingRunRecord(TrainingRunId.random(), DatasetId.random(), "", 50,
                        JobState.RUNNING, 10, 50, 0.42, 0.71, null, UserId.random(), Instant.now(), null, ""));
        assertThrows(IllegalArgumentException.class,
                () -> new TrainingRunRecord(TrainingRunId.random(), DatasetId.random(), null, 50,
                        JobState.RUNNING, 10, 50, 0.42, 0.71, null, UserId.random(), Instant.now(), null, ""));
    }

    @Test
    void rejectsNonPositiveEpochs() {
        assertThrows(IllegalArgumentException.class,
                () -> new TrainingRunRecord(TrainingRunId.random(), DatasetId.random(), "yolo26n.pt", 0,
                        JobState.RUNNING, 10, 50, 0.42, 0.71, null, UserId.random(), Instant.now(), null, ""));
    }

    @Test
    void rejectsNullState() {
        assertThrows(IllegalArgumentException.class,
                () -> new TrainingRunRecord(TrainingRunId.random(), DatasetId.random(), "yolo26n.pt", 50, null,
                        10, 50, 0.42, 0.71, null, UserId.random(), Instant.now(), null, ""));
    }

    @Test
    void rejectsNegativeEpoch() {
        assertThrows(IllegalArgumentException.class,
                () -> new TrainingRunRecord(TrainingRunId.random(), DatasetId.random(), "yolo26n.pt", 50,
                        JobState.RUNNING, -1, 50, 0.42, 0.71, null, UserId.random(), Instant.now(), null, ""));
    }

    @Test
    void rejectsNegativeTotalEpochs() {
        assertThrows(IllegalArgumentException.class,
                () -> new TrainingRunRecord(TrainingRunId.random(), DatasetId.random(), "yolo26n.pt", 50,
                        JobState.RUNNING, 10, -1, 0.42, 0.71, null, UserId.random(), Instant.now(), null, ""));
    }

    @Test
    void rejectsBlankOutputModelIdWhenPresent() {
        assertThrows(IllegalArgumentException.class, () -> run(JobState.SUCCEEDED, "", Instant.now()));
        assertThrows(IllegalArgumentException.class, () -> run(JobState.SUCCEEDED, "   ", Instant.now()));
    }

    @Test
    void allowsNullOutputModelId() {
        TrainingRunRecord running = run(JobState.RUNNING, null, null);

        assertNull(running.outputModelId());
    }

    @Test
    void rejectsNullStartedBy() {
        assertThrows(IllegalArgumentException.class,
                () -> new TrainingRunRecord(TrainingRunId.random(), DatasetId.random(), "yolo26n.pt", 50,
                        JobState.RUNNING, 10, 50, 0.42, 0.71, null, null, Instant.now(), null, ""));
    }

    @Test
    void rejectsNullStartedAt() {
        assertThrows(IllegalArgumentException.class,
                () -> new TrainingRunRecord(TrainingRunId.random(), DatasetId.random(), "yolo26n.pt", 50,
                        JobState.RUNNING, 10, 50, 0.42, 0.71, null, UserId.random(), null, null, ""));
    }

    @Test
    void allowsNullFinishedAtWhileRunning() {
        TrainingRunRecord running = run(JobState.RUNNING, null, null);

        assertNull(running.finishedAt());
    }

    @Test
    void rejectsNullMessage() {
        assertThrows(IllegalArgumentException.class,
                () -> new TrainingRunRecord(TrainingRunId.random(), DatasetId.random(), "yolo26n.pt", 50,
                        JobState.RUNNING, 10, 50, 0.42, 0.71, null, UserId.random(), Instant.now(), null,
                        null));
    }

    @Test
    void acceptsAWellFormedSucceededRun() {
        Instant finishedAt = Instant.now();
        TrainingRunRecord succeeded = run(JobState.SUCCEEDED, "buildings-50e.pt", finishedAt);

        assertEquals(JobState.SUCCEEDED, succeeded.state());
        assertEquals("buildings-50e.pt", succeeded.outputModelId());
        assertEquals(finishedAt, succeeded.finishedAt());
    }
}
