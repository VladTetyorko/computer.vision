package com.drones.vision.domain.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TrainingProgressTest {

    @Test
    void rejectsBlankJobId() {
        assertThrows(IllegalArgumentException.class,
                () -> new TrainingProgress("", 1, 50, 0.5, 0.9, JobState.RUNNING, ""));
        assertThrows(IllegalArgumentException.class,
                () -> new TrainingProgress(null, 1, 50, 0.5, 0.9, JobState.RUNNING, ""));
        assertThrows(IllegalArgumentException.class,
                () -> new TrainingProgress("   ", 1, 50, 0.5, 0.9, JobState.RUNNING, ""));
    }

    @Test
    void rejectsNegativeEpoch() {
        assertThrows(IllegalArgumentException.class,
                () -> new TrainingProgress("job-1", -1, 50, 0.5, 0.9, JobState.RUNNING, ""));
    }

    @Test
    void rejectsNegativeTotalEpochs() {
        assertThrows(IllegalArgumentException.class,
                () -> new TrainingProgress("job-1", 1, -1, 0.5, 0.9, JobState.RUNNING, ""));
    }

    @Test
    void rejectsNullState() {
        assertThrows(IllegalArgumentException.class,
                () -> new TrainingProgress("job-1", 1, 50, 0.5, 0.9, null, ""));
    }

    @Test
    void rejectsNullMessage() {
        assertThrows(IllegalArgumentException.class,
                () -> new TrainingProgress("job-1", 1, 50, 0.5, 0.9, JobState.RUNNING, null));
    }

    @Test
    void allowsZeroEpochOnATerminalMessageWithNoEpochOfItsOwn() {
        TrainingProgress progress =
                new TrainingProgress("job-1", 0, 0, 0.0, 0.0, JobState.FAILED, "dataset not found");

        assertEquals(0, progress.epoch());
        assertEquals(JobState.FAILED, progress.state());
        assertEquals("dataset not found", progress.message());
    }

    @Test
    void allowsEmptyMessageOnARunningProgress() {
        TrainingProgress progress =
                new TrainingProgress("job-1", 3, 50, 0.42, 0.71, JobState.RUNNING, "");

        assertEquals("", progress.message());
    }

    @Test
    void acceptsAWellFormedTerminalSuccessMessage() {
        TrainingProgress progress =
                new TrainingProgress("job-1", 50, 50, 0.02, 0.95, JobState.SUCCEEDED, "yolo26n-ft-v3");

        assertEquals("job-1", progress.jobId());
        assertEquals(50, progress.epoch());
        assertEquals(50, progress.totalEpochs());
        assertEquals(0.02, progress.loss());
        assertEquals(0.95, progress.map50());
        assertEquals(JobState.SUCCEEDED, progress.state());
        assertEquals("yolo26n-ft-v3", progress.message());
    }
}
