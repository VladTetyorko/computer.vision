package com.drones.vision.application;

import com.drones.vision.domain.model.AuditEntry;
import com.drones.vision.domain.model.AuditTargetType;
import com.drones.vision.domain.model.JobState;
import com.drones.vision.domain.model.TrainingJobSpec;
import com.drones.vision.domain.model.TrainingProgress;
import com.drones.vision.domain.model.UserId;
import com.drones.vision.domain.port.out.AuditTrailPort;
import com.drones.vision.domain.port.out.TrainingPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link DefaultTrainingJobService}. {@link TrainingPort}/{@link AuditTrailPort}
 * are hand-rolled in-memory fakes (this module's dominant test style); the executor is a
 * same-thread {@link DirectExecutorService} so every scripted {@link TrainingProgress} sequence
 * lands deterministically, with no real background thread or sleep in most tests. A dedicated
 * real-thread test at the bottom exercises actual concurrency.
 */
class DefaultTrainingJobServiceTest {

    private FakeTrainingPort trainingPort;
    private FakeAuditTrailPort auditTrail;
    private MutableClock clock;
    private DefaultTrainingJobService service;

    private final UserId actor = UserId.random();
    private final TrainingJobSpec spec = new TrainingJobSpec("yolo26n.pt", "dataset-1", 10);
    private final VisibilityScope managerScope = VisibilityScope.groups(Set.of());
    private final VisibilityScope pilotScope = VisibilityScope.assignedAssets(Set.of());

    @BeforeEach
    void setUp() {
        trainingPort = new FakeTrainingPort();
        auditTrail = new FakeAuditTrailPort();
        clock = new MutableClock(Instant.parse("2026-08-01T00:00:00Z"));
        service = new DefaultTrainingJobService(trainingPort, auditTrail, new DirectExecutorService(), clock);
    }

    // -- gate ---------------------------------------------------------------

    @Test
    void startDeniedForAPilotScopeAuditsTheDenialAndNeverCallsThePort() {
        AccessDeniedException ex = assertThrows(AccessDeniedException.class,
                () -> service.start(spec, actor, pilotScope));
        assertTrue(ex.getMessage().toLowerCase().contains("not permitted"));

        assertNull(trainingPort.lastSpec, "the port must never have been called");

        AuditEntry entry = onlyEntry();
        assertEquals(AuditTargetType.MODEL, entry.targetType());
        assertEquals("DENIED:out of scope", entry.details().get("result"));
        assertEquals("yolo26n.pt", entry.details().get("baseModel"));
        assertEquals("dataset-1", entry.details().get("datasetId"));

        // the denied job id (the audit's own target id) was never actually registered
        assertTrue(service.job(entry.targetId()).isEmpty());
        assertTrue(service.jobs().isEmpty());
    }

    // -- start / initial state -----------------------------------------------

    @Test
    void startReturnsAJobIdAndTheJobAppearsRunningBeforeAnyProgressArrives() {
        // an empty script models a job that was submitted but has not yet reported any progress
        String jobId = service.start(spec, actor, managerScope);

        assertFalse(jobId.isBlank());
        assertEquals(spec, trainingPort.lastSpec, "the job must actually have been submitted to the port");

        TrainingJobView job = service.job(jobId).orElseThrow();
        assertEquals(jobId, job.jobId());
        assertEquals("yolo26n.pt", job.baseModel());
        assertEquals("dataset-1", job.datasetId());
        assertEquals(10, job.epochs());
        assertEquals(0, job.epoch());
        assertEquals(0, job.totalEpochs());
        assertEquals(JobState.RUNNING, job.state());
        assertEquals("", job.message());
        assertEquals(clock.instant, job.startedAt());

        AuditEntry entry = onlyEntry();
        assertEquals(jobId, entry.targetId());
        assertEquals("STARTED", entry.details().get("result"));
    }

    // -- streamed progress ----------------------------------------------------

    @Test
    void streamedProgressUpdatesTheViewToTheLatestMessage() {
        trainingPort.script = List.of(
                new TrainingProgress("wire-job-xyz", 1, 10, 0.9, 0.10, JobState.RUNNING, ""),
                new TrainingProgress("wire-job-xyz", 2, 10, 0.6, 0.35, JobState.RUNNING, ""));

        String jobId = service.start(spec, actor, managerScope);

        TrainingJobView job = service.job(jobId).orElseThrow();
        assertEquals(2, job.epoch());
        assertEquals(10, job.totalEpochs());
        assertEquals(0.6, job.loss());
        assertEquals(0.35, job.map50());
        assertEquals(JobState.RUNNING, job.state());
        // identity/spec fields are untouched by progress updates
        assertEquals(jobId, job.jobId());
        assertEquals("yolo26n.pt", job.baseModel());
    }

    // -- terminal states --------------------------------------------------------

    @Test
    void terminalSucceededLandsUnderTheLocalJobIdEvenThoughTheWireJobIdDiffers() {
        trainingPort.script = List.of(
                new TrainingProgress("wire-job-completely-different", 1, 10, 0.9, 0.10, JobState.RUNNING, ""),
                new TrainingProgress("wire-job-completely-different", 10, 10, 0.05, 0.91, JobState.SUCCEEDED,
                        "yolo26n-finetuned-v7"));

        String jobId = service.start(spec, actor, managerScope);

        assertNotEquals("wire-job-completely-different", jobId);
        TrainingJobView job = service.job(jobId).orElseThrow();
        assertEquals(JobState.SUCCEEDED, job.state());
        assertEquals(10, job.epoch());
        assertEquals("yolo26n-finetuned-v7", job.message());
        assertTrue(service.jobs().contains(job));
    }

    @Test
    void terminalFailedLands() {
        trainingPort.script = List.of(
                new TrainingProgress("wire-job", 3, 10, 1.2, 0.05, JobState.RUNNING, ""),
                new TrainingProgress("wire-job", 3, 10, 1.2, 0.05, JobState.FAILED, "GPU OOM at epoch 3"));

        String jobId = service.start(spec, actor, managerScope);

        TrainingJobView job = service.job(jobId).orElseThrow();
        assertEquals(JobState.FAILED, job.state());
        assertEquals("GPU OOM at epoch 3", job.message());
    }

    // -- transport failure --------------------------------------------------------

    @Test
    void aTrainingPortExceptionBecomesAFailedJobInsteadOfEscaping() {
        trainingPort.script = List.of(new TrainingProgress("wire-job", 2, 10, 0.8, 0.2, JobState.RUNNING, ""));
        trainingPort.failure = new IllegalStateException("connection reset by peer");

        String jobId = service.start(spec, actor, managerScope); // must not throw

        TrainingJobView job = service.job(jobId).orElseThrow();
        assertEquals(JobState.FAILED, job.state());
        assertEquals("connection reset by peer", job.message());
        // the last progress before the failure is preserved
        assertEquals(2, job.epoch());
    }

    @Test
    void aTrainingPortExceptionWithNoMessageFallsBackToTheExceptionClassName() {
        trainingPort.failure = new IllegalStateException();

        String jobId = service.start(spec, actor, managerScope);

        assertEquals("IllegalStateException", service.job(jobId).orElseThrow().message());
    }

    // -- retention ------------------------------------------------------------

    @Test
    void finishedJobsBeyondTheCapAreEvictedButRunningJobsNeverAre() {
        String runningJobId = service.start(spec, actor, managerScope); // empty script -> stays RUNNING

        for (int i = 0; i < DefaultTrainingJobService.MAX_FINISHED_JOBS + 5; i++) {
            trainingPort.script = List.of(new TrainingProgress("w", 1, 1, 0.0, 1.0, JobState.SUCCEEDED, "m" + i));
            service.start(spec, actor, managerScope);
        }

        assertTrue(service.job(runningJobId).isPresent(), "a still-RUNNING job must never be evicted");
        assertEquals(DefaultTrainingJobService.MAX_FINISHED_JOBS + 1, service.jobs().size());
    }

    // -- concurrent jobs isolate (deterministic, same-thread executor) --------

    @Test
    void concurrentJobsIsolateFromEachOthersState() {
        trainingPort.script = List.of(new TrainingProgress("w", 5, 20, 0.4, 0.5, JobState.RUNNING, ""));
        String jobA = service.start(new TrainingJobSpec("yolo26n.pt", "dataset-A", 20), actor, managerScope);

        trainingPort.script = List.of(new TrainingProgress("w", 8, 30, 0.2, 0.7, JobState.SUCCEEDED, "model-B"));
        String jobB = service.start(new TrainingJobSpec("yolo11n.pt", "dataset-B", 30), actor, managerScope);

        assertNotEquals(jobA, jobB);
        TrainingJobView viewA = service.job(jobA).orElseThrow();
        TrainingJobView viewB = service.job(jobB).orElseThrow();

        assertEquals("dataset-A", viewA.datasetId());
        assertEquals(5, viewA.epoch());
        assertEquals(JobState.RUNNING, viewA.state());

        assertEquals("dataset-B", viewB.datasetId());
        assertEquals(8, viewB.epoch());
        assertEquals(JobState.SUCCEEDED, viewB.state());
        assertEquals("model-B", viewB.message());

        assertEquals(Set.of(jobA, jobB), Set.copyOf(service.jobs().stream().map(TrainingJobView::jobId).toList()));
    }

    @Test
    void concurrentJobsOnRealThreadsIsolateFromEachOther() throws InterruptedException {
        ExecutorService realExecutor = Executors.newCachedThreadPool();
        DefaultTrainingJobService realService =
                new DefaultTrainingJobService(new LatchedTrainingPort(), auditTrail, realExecutor, Instant::now);
        try {
            // A real cached-thread-pool executor returns from execute() without waiting for the
            // task, so these two run genuinely concurrently on background threads.
            String jobA = realService.start(new TrainingJobSpec("yolo26n.pt", "dataset-A", 5), actor, managerScope);
            String jobB = realService.start(new TrainingJobSpec("yolo11n.pt", "dataset-B", 7), actor, managerScope);

            awaitTerminal(realService, jobA);
            awaitTerminal(realService, jobB);

            TrainingJobView viewA = realService.job(jobA).orElseThrow();
            TrainingJobView viewB = realService.job(jobB).orElseThrow();
            assertEquals("dataset-A", viewA.datasetId());
            assertEquals(JobState.SUCCEEDED, viewA.state());
            assertEquals("dataset-B", viewB.datasetId());
            assertEquals(JobState.SUCCEEDED, viewB.state());
            assertNotEquals(jobA, jobB);
        } finally {
            realExecutor.shutdownNow();
        }
    }

    private static void awaitTerminal(TrainingJobService service, String jobId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            JobState state = service.job(jobId).orElseThrow().state();
            if (state == JobState.SUCCEEDED || state == JobState.FAILED) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("job " + jobId + " never reached a terminal state");
    }

    // -- constructor validation -------------------------------------------------

    @Test
    void constructorsRejectNullCollaborators() {
        assertThrows(NullPointerException.class, () -> new DefaultTrainingJobService(null, auditTrail));
        assertThrows(NullPointerException.class, () -> new DefaultTrainingJobService(trainingPort, null));
        assertThrows(NullPointerException.class,
                () -> new DefaultTrainingJobService(null, auditTrail, new DirectExecutorService(), clock));
        assertThrows(NullPointerException.class,
                () -> new DefaultTrainingJobService(trainingPort, null, new DirectExecutorService(), clock));
        assertThrows(NullPointerException.class,
                () -> new DefaultTrainingJobService(trainingPort, auditTrail, null, clock));
        assertThrows(NullPointerException.class,
                () -> new DefaultTrainingJobService(trainingPort, auditTrail, new DirectExecutorService(), null));
    }

    private AuditEntry onlyEntry() {
        assertEquals(1, auditTrail.recorded.size(), "expected exactly one audit entry");
        return auditTrail.recorded.get(0);
    }

    // -- test doubles -----------------------------------------------------------

    /** In-memory {@link TrainingPort}: replays a scripted sequence synchronously, then optionally throws. */
    private static final class FakeTrainingPort implements TrainingPort {
        private List<TrainingProgress> script = List.of();
        private RuntimeException failure;
        private volatile TrainingJobSpec lastSpec;

        @Override
        public void startTraining(TrainingJobSpec spec, Consumer<TrainingProgress> onProgress) {
            lastSpec = spec;
            for (TrainingProgress progress : script) {
                onProgress.accept(progress);
            }
            if (failure != null) {
                throw failure;
            }
        }
    }

    /**
     * A real-thread {@link TrainingPort}: always streams one RUNNING message then a SUCCEEDED
     * terminal one, each spec producing its own deterministic "model" name from its dataset id so
     * the real-thread isolation test can tell the two jobs' results apart.
     */
    private static final class LatchedTrainingPort implements TrainingPort {
        @Override
        public void startTraining(TrainingJobSpec spec, Consumer<TrainingProgress> onProgress) {
            onProgress.accept(new TrainingProgress("wire-" + spec.datasetId(), 1, spec.epochs(), 1.0, 0.1,
                    JobState.RUNNING, ""));
            onProgress.accept(new TrainingProgress("wire-" + spec.datasetId(), spec.epochs(), spec.epochs(), 0.1,
                    0.9, JobState.SUCCEEDED, "model-for-" + spec.datasetId()));
        }
    }

    /** In-memory {@link AuditTrailPort}: {@code record} is the only method this suite exercises. */
    private static final class FakeAuditTrailPort implements AuditTrailPort {
        final List<AuditEntry> recorded = new ArrayList<>();

        @Override
        public AuditEntry record(AuditEntry entry) {
            recorded.add(entry);
            return entry;
        }

        @Override
        public List<AuditEntry> findRecent(int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<AuditEntry> findByTarget(AuditTargetType targetType, String targetId, int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<AuditEntry> findByActor(UserId actorId, int limit) {
            throw new UnsupportedOperationException();
        }
    }

    /** Runs every submitted task synchronously on the calling thread -- no real background thread. */
    private static final class DirectExecutorService extends AbstractExecutorService {
        @Override
        public void execute(Runnable command) {
            command.run();
        }

        @Override
        public void shutdown() {
        }

        @Override
        public List<Runnable> shutdownNow() {
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return false;
        }

        @Override
        public boolean isTerminated() {
            return false;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return true;
        }
    }

    /** A clock a test can read the current fixed instant from -- no real time ever passes. */
    private static final class MutableClock implements java.util.function.Supplier<Instant> {
        private final Instant instant;

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        @Override
        public Instant get() {
            return instant;
        }
    }
}
