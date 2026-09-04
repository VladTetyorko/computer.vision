package com.drones.vision.learning.application;

import com.drones.vision.platform.AuditEntry;
import com.drones.vision.platform.AuditTargetType;
import com.drones.vision.learning.domain.model.CvModelRecord;
import com.drones.vision.learning.domain.model.DatasetId;
import com.drones.vision.learning.domain.model.DatasetUpload;
import com.drones.vision.learning.domain.model.JobState;
import com.drones.vision.learning.domain.model.MetricsKind;
import com.drones.vision.learning.domain.model.ModelStatus;
import com.drones.vision.learning.domain.model.SampleImage;
import com.drones.vision.learning.domain.model.SampleStatus;
import com.drones.vision.kernel.StreamId;
import com.drones.vision.learning.domain.model.TrainingJobSpec;
import com.drones.vision.learning.domain.model.TrainingProgress;
import com.drones.vision.learning.domain.model.TrainingRunId;
import com.drones.vision.learning.domain.model.TrainingRunRecord;
import com.drones.vision.learning.domain.model.TrainingSample;
import com.drones.vision.learning.domain.model.TrainingSampleId;
import com.drones.vision.learning.domain.port.CvModelRepositoryPort;
import com.drones.vision.learning.domain.port.TrainingRunRepositoryPort;
import com.drones.vision.kernel.UserId;
import com.drones.vision.platform.AuditTrailPort;
import com.drones.vision.learning.domain.port.TrainingPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
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
import com.drones.vision.platform.AccessDeniedException;
import com.drones.vision.platform.Authority;
import com.drones.vision.platform.Capability;
import com.drones.vision.platform.VisibilityScope;

/**
 * Unit tests for {@link DefaultTrainingJobService}. {@link TrainingPort}/{@link LabelingService}/
 * {@link AuditTrailPort}/{@link TrainingRunRepositoryPort}/{@link CvModelRepositoryPort} are
 * hand-rolled in-memory fakes (this module's dominant test style); the executor is a same-thread
 * {@link DirectExecutorService} so every scripted {@link TrainingProgress} sequence lands
 * deterministically, with no real background thread or sleep in most tests. A dedicated real-thread
 * test at the bottom exercises actual concurrency.
 */
class DefaultTrainingJobServiceTest {

    private FakeTrainingPort trainingPort;
    private FakeLabelingService labelingService;
    private FakeAuditTrailPort auditTrail;
    private FakeTrainingRunRepositoryPort trainingRunRepository;
    private FakeCvModelRepositoryPort cvModelRepository;
    private TrainingRunStores trainingRunStores;
    private MutableClock clock;
    private DefaultTrainingJobService service;

    private final UserId actor = UserId.random();
    private final DatasetId datasetId = DatasetId.random();
    private final TrainingJobSpec spec = new TrainingJobSpec("yolo26n.pt", datasetId.value().toString(), 10);
    // docs/plans/active/CV-SETTINGS-PLAN.md §8 OQ9: starting a training job is now team-scoped, not
    // deployment-global -- any manager may claim the shared training host to train a dataset they
    // can already label; adminScope/managerScope both prove the relaxed gate, pilotScope proves it
    // still refuses a caller with no management authority at all.
    private final Authority adminScope = Authority.full();
    private final Authority managerScope =
            new Authority(VisibilityScope.groups(Set.of()), Set.of(Capability.MANAGE_ORG));
    private final Authority pilotScope = new Authority(VisibilityScope.assignedAssets(Set.of()), Set.of());

    @BeforeEach
    void setUp() {
        trainingPort = new FakeTrainingPort();
        labelingService = new FakeLabelingService();
        auditTrail = new FakeAuditTrailPort();
        trainingRunRepository = new FakeTrainingRunRepositoryPort();
        cvModelRepository = new FakeCvModelRepositoryPort();
        trainingRunStores = new TrainingRunStores(trainingRunRepository, cvModelRepository);
        clock = new MutableClock(Instant.parse("2026-08-01T00:00:00Z"));
        service = new DefaultTrainingJobService(trainingPort, labelingService, auditTrail, trainingRunStores,
                new DirectExecutorService(), clock, DefaultTrainingJobService.MAX_FINISHED_JOBS);
    }

    // -- gate (relaxed to canManageOrg -- CV-SETTINGS-PLAN.md §8 OQ9) --------

    @Test
    void startDeniedForAPilotScopeAuditsTheDenialAndNeverCallsThePort() {
        AccessDeniedException ex = assertThrows(AccessDeniedException.class,
                () -> service.start(spec, actor, pilotScope));
        assertTrue(ex.getMessage().toLowerCase().contains("not permitted"));

        assertNull(trainingPort.lastSpec, "the port must never have been called");
        assertNull(labelingService.lastUploadDatasetId, "the dataset pre-check must never have run");

        AuditEntry entry = onlyEntry();
        assertEquals(AuditTargetType.MODEL, entry.targetType());
        assertEquals("DENIED:out of scope", entry.details().get("result"));
        assertEquals("yolo26n.pt", entry.details().get("baseModel"));
        assertEquals(datasetId.value().toString(), entry.details().get("datasetId"));

        // the denied job id (the audit's own target id) was never actually registered
        assertTrue(service.job(entry.targetId()).isEmpty());
        assertTrue(service.jobs().isEmpty());
        assertTrue(trainingRunRepository.saveLog.isEmpty(), "no run may be persisted for a denied start");
    }

    @Test
    void startSucceedsForAManagerScopeSinceTheGateIsRelaxedToCanManageOrg() {
        String jobId = service.start(spec, actor, managerScope);

        assertFalse(jobId.isBlank());
        assertEquals(spec, trainingPort.lastSpec);
        assertEquals("STARTED", onlyEntry().details().get("result"));
    }

    // -- synchronous dataset pre-check ---------------------------------------

    @Test
    void startThrowsNoSuchElementForAnUnknownDataset() {
        labelingService.datasetKnown = false;

        assertThrows(NoSuchElementException.class, () -> service.start(spec, actor, adminScope));

        assertTrue(service.jobs().isEmpty(), "no job may be registered when the pre-check fails");
        assertNull(trainingPort.lastSpec, "training must never be submitted when the pre-check fails");
    }

    @Test
    void startThrowsAccessDeniedWhenTheDatasetIsOutOfScope() {
        labelingService.datasetInScope = false;

        assertThrows(AccessDeniedException.class, () -> service.start(spec, actor, adminScope));

        assertTrue(service.jobs().isEmpty());
        assertNull(trainingPort.lastSpec);
    }

    @Test
    void startThrowsIllegalArgumentWhenTheDatasetHasNoLabeledSamples() {
        labelingService.labeledCount = 0;

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.start(spec, actor, adminScope));
        assertTrue(ex.getMessage().contains("has no LABELED samples to train on"));

        assertTrue(service.jobs().isEmpty());
        assertNull(trainingPort.lastSpec);
    }

    // -- start / initial state -----------------------------------------------

    @Test
    void startReturnsAJobIdAndTheJobAppearsRunningBeforeAnyProgressArrives() {
        // an empty script models a job that was submitted but has not yet reported any training progress
        String jobId = service.start(spec, actor, adminScope);

        assertFalse(jobId.isBlank());
        assertEquals(spec, trainingPort.lastSpec, "the job must actually have been submitted to the port");
        assertEquals(datasetId, labelingService.lastUploadDatasetId, "the dataset must have been uploaded first");

        TrainingJobView job = service.job(jobId).orElseThrow();
        assertEquals(jobId, job.jobId());
        assertEquals("yolo26n.pt", job.baseModel());
        assertEquals(datasetId.value().toString(), job.datasetId());
        assertEquals(10, job.epochs());
        assertEquals(0, job.epoch());
        assertEquals(0, job.totalEpochs());
        assertEquals(JobState.RUNNING, job.state());
        // the empty training script leaves the last upload-phase note as the final in-memory message
        assertEquals("Uploaded 3 sample(s), 12345 bytes; starting training…", job.message());
        assertEquals(clock.instant, job.startedAt());

        AuditEntry entry = onlyEntry();
        assertEquals(jobId, entry.targetId());
        assertEquals("STARTED", entry.details().get("result"));
    }

    // -- run persistence (docs/plans/active/CV-SETTINGS-PLAN.md §3.3, fixing H7) ---------------

    @Test
    void startPersistsATrainingRunRecordBeforeAnyProgressArrives() {
        // empty script -> runJob's upload succeeds but training never calls onProgress, so
        // persistRun is invoked exactly once: from start() itself.
        String jobId = service.start(spec, actor, adminScope);

        assertEquals(1, trainingRunRepository.saveLog.size());
        TrainingRunRecord run = trainingRunRepository.findById(TrainingRunId.of(jobId)).orElseThrow();
        assertEquals(datasetId, run.datasetId());
        assertEquals("yolo26n.pt", run.baseModel());
        assertEquals(10, run.epochs());
        assertEquals(JobState.RUNNING, run.state());
        assertEquals(0, run.epoch());
        assertEquals(0, run.totalEpochs());
        assertNull(run.outputModelId());
        assertEquals(actor, run.startedBy());
        assertEquals(clock.instant, run.startedAt());
        assertNull(run.finishedAt(), "still running -- never terminal");
        // the "Uploading dataset..."/"Uploaded N samples..." notes only ever touch the in-memory
        // TrainingJobView (via note()), never the persisted run -- persistRun is not called again
        // until a progress message (or the run's own terminal failure) arrives.
        assertEquals("", run.message());
    }

    @Test
    void progressUpdatesArePersistedOntoTheSameRunRecord() {
        trainingPort.script = List.of(
                new TrainingProgress("wire-job-xyz", 1, 10, 0.9, 0.10, JobState.RUNNING, ""),
                new TrainingProgress("wire-job-xyz", 2, 10, 0.6, 0.35, JobState.RUNNING, ""));

        String jobId = service.start(spec, actor, adminScope);

        TrainingRunRecord run = trainingRunRepository.findById(TrainingRunId.of(jobId)).orElseThrow();
        assertEquals(2, run.epoch());
        assertEquals(10, run.totalEpochs());
        assertEquals(0.6, run.loss());
        assertEquals(0.35, run.map50());
        assertEquals(JobState.RUNNING, run.state());
        assertNull(run.finishedAt());
        // one save from start(), one per progress message
        assertEquals(3, trainingRunRepository.saveLog.size());
    }

    @Test
    void aSucceededRunIsPersistedTerminalWithTheProducedModelId() {
        trainingPort.script = List.of(
                new TrainingProgress("wire-job", 10, 10, 0.05, 0.91, JobState.SUCCEEDED, "yolo26n-finetuned-v7"));

        String jobId = service.start(spec, actor, adminScope);

        TrainingRunRecord run = trainingRunRepository.findById(TrainingRunId.of(jobId)).orElseThrow();
        assertEquals(JobState.SUCCEEDED, run.state());
        assertEquals("yolo26n-finetuned-v7", run.outputModelId());
        assertEquals(clock.instant, run.finishedAt());
    }

    @Test
    void aFailedRunIsPersistedTerminalWithNoOutputModelId() {
        trainingPort.script = List.of(
                new TrainingProgress("wire-job", 3, 10, 1.2, 0.05, JobState.RUNNING, ""),
                new TrainingProgress("wire-job", 3, 10, 1.2, 0.05, JobState.FAILED, "GPU OOM at epoch 3"));

        String jobId = service.start(spec, actor, adminScope);

        TrainingRunRecord run = trainingRunRepository.findById(TrainingRunId.of(jobId)).orElseThrow();
        assertEquals(JobState.FAILED, run.state());
        assertNull(run.outputModelId());
        assertEquals(clock.instant, run.finishedAt());
        assertTrue(cvModelRepository.saved.isEmpty(), "a FAILED run must never register a candidate model");
    }

    @Test
    void aTransportFailureIsPersistedAsAFailedRun() {
        trainingPort.failure = new IllegalStateException("connection reset by peer");

        String jobId = service.start(spec, actor, adminScope);

        TrainingRunRecord run = trainingRunRepository.findById(TrainingRunId.of(jobId)).orElseThrow();
        assertEquals(JobState.FAILED, run.state());
        assertNull(run.outputModelId());
        assertEquals(clock.instant, run.finishedAt());
    }

    // -- SUCCEEDED => CANDIDATE model registration (docs/plans/active/CV-SETTINGS-PLAN.md §8 OQ6) --

    @Test
    void aSucceededRunRegistersACandidateModelWithProvenanceAndTrainingMetrics() {
        trainingPort.script = List.of(
                new TrainingProgress("wire-job", 10, 10, 0.05, 0.91, JobState.SUCCEEDED, "yolo26n-finetuned-v7"));

        String jobId = service.start(spec, actor, adminScope);

        assertEquals(1, cvModelRepository.saved.size());
        CvModelRecord candidate = cvModelRepository.saved.get(0);
        assertEquals("yolo26n-finetuned-v7", candidate.modelId());
        assertEquals(ModelStatus.CANDIDATE, candidate.status(), "never LIVE -- promotion is a separate human act");
        assertNull(candidate.promotedBy());
        assertNull(candidate.promotedAt());

        assertEquals(MetricsKind.TRAINING, candidate.metrics().kind());
        double actualMap50 = candidate.metrics().map50();
        assertEquals(0.91, actualMap50);

        assertEquals(datasetId, candidate.provenance().datasetId());
        assertEquals(TrainingRunId.of(jobId), candidate.provenance().trainingRunId());
        assertEquals("yolo26n.pt", candidate.provenance().baseModel());
        assertEquals(10, candidate.provenance().epochs());
        assertEquals(clock.instant, candidate.provenance().trainedAt());
    }

    @Test
    void aSucceededRunWithABlankTerminalMessageRegistersNoCandidateModel() {
        trainingPort.script = List.of(
                new TrainingProgress("wire-job", 10, 10, 0.05, 0.91, JobState.SUCCEEDED, ""));

        service.start(spec, actor, adminScope);

        assertTrue(cvModelRepository.saved.isEmpty(), "a blank terminal message names no produced model");
    }

    // -- streamed progress (in-memory poll) ------------------------------------

    @Test
    void streamedProgressUpdatesTheViewToTheLatestMessage() {
        trainingPort.script = List.of(
                new TrainingProgress("wire-job-xyz", 1, 10, 0.9, 0.10, JobState.RUNNING, ""),
                new TrainingProgress("wire-job-xyz", 2, 10, 0.6, 0.35, JobState.RUNNING, ""));

        String jobId = service.start(spec, actor, adminScope);

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

        String jobId = service.start(spec, actor, adminScope);

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

        String jobId = service.start(spec, actor, adminScope);

        TrainingJobView job = service.job(jobId).orElseThrow();
        assertEquals(JobState.FAILED, job.state());
        assertEquals("GPU OOM at epoch 3", job.message());
    }

    // -- transport failure --------------------------------------------------------

    @Test
    void aTrainingPortExceptionBecomesAFailedJobInsteadOfEscaping() {
        trainingPort.script = List.of(new TrainingProgress("wire-job", 2, 10, 0.8, 0.2, JobState.RUNNING, ""));
        trainingPort.failure = new IllegalStateException("connection reset by peer");

        String jobId = service.start(spec, actor, adminScope); // must not throw

        TrainingJobView job = service.job(jobId).orElseThrow();
        assertEquals(JobState.FAILED, job.state());
        assertEquals("connection reset by peer", job.message());
        // the last progress before the failure is preserved
        assertEquals(2, job.epoch());
    }

    @Test
    void aTrainingPortExceptionWithNoMessageFallsBackToTheExceptionClassName() {
        trainingPort.failure = new IllegalStateException();

        String jobId = service.start(spec, actor, adminScope);

        assertEquals("IllegalStateException", service.job(jobId).orElseThrow().message());
    }

    // -- upload failure -------------------------------------------------------

    @Test
    void anUploadFailureInsideRunJobResultsInAFailedJob() {
        labelingService.uploadFailure = new IllegalStateException("cv-service rejected the dataset upload: bad zip");

        String jobId = service.start(spec, actor, adminScope); // must not throw -- the pre-check already passed

        TrainingJobView job = service.job(jobId).orElseThrow();
        assertEquals(JobState.FAILED, job.state());
        assertEquals("cv-service rejected the dataset upload: bad zip", job.message());
        assertNull(trainingPort.lastSpec, "training must never start when the upload itself failed");
    }

    // -- retention (in-memory poll only -- the persisted run store has no cap) ------------------

    @Test
    void finishedJobsBeyondTheCapAreEvictedButRunningJobsNeverAre() {
        String runningJobId = service.start(spec, actor, adminScope); // empty script -> stays RUNNING

        for (int i = 0; i < DefaultTrainingJobService.MAX_FINISHED_JOBS + 5; i++) {
            trainingPort.script = List.of(new TrainingProgress("w", 1, 1, 0.0, 1.0, JobState.SUCCEEDED, "m" + i));
            service.start(spec, actor, adminScope);
        }

        assertTrue(service.job(runningJobId).isPresent(), "a still-RUNNING job must never be evicted");
        assertEquals(DefaultTrainingJobService.MAX_FINISHED_JOBS + 1, service.jobs().size());
        // the persisted run store keeps every run -- no eviction, unlike the in-memory poll above
        assertEquals(DefaultTrainingJobService.MAX_FINISHED_JOBS + 6, trainingRunRepository.rows.size());
    }

    // -- runs()/run() (docs/plans/active/CV-SETTINGS-PLAN.md §3.3; gated canManageOrg) ----------

    @Test
    void runsDeniedForAPilotScopeAuditsTheDenialAndNeverReadsTheStore() {
        AccessDeniedException ex = assertThrows(AccessDeniedException.class,
                () -> service.runs(10, actor, pilotScope));
        assertTrue(ex.getMessage().toLowerCase().contains("not permitted"));
        assertEquals("DENIED:out of scope", onlyEntry().details().get("result"));
    }

    @Test
    void runsRejectsANonPositiveLimit() {
        assertThrows(IllegalArgumentException.class, () -> service.runs(0, actor, adminScope));
        assertThrows(IllegalArgumentException.class, () -> service.runs(-1, actor, adminScope));
    }

    @Test
    void runsSucceedsForAManagerScopeAndListsNewestFirst() {
        String olderJobId = service.start(spec, actor, adminScope);
        clock.instant = Instant.parse("2026-08-01T01:00:00Z");
        String newerJobId = service.start(spec, actor, adminScope);

        List<TrainingRunRecord> runs = service.runs(10, actor, managerScope);

        assertEquals(2, runs.size());
        assertEquals(newerJobId, runs.get(0).runId().value().toString());
        assertEquals(olderJobId, runs.get(1).runId().value().toString());
    }

    @Test
    void runDeniedForAPilotScopeAuditsTheDenial() {
        TrainingRunId runId = TrainingRunId.random();

        assertThrows(AccessDeniedException.class, () -> service.run(runId, actor, pilotScope));
        assertEquals("DENIED:out of scope", onlyEntry().details().get("result"));
    }

    @Test
    void runThrowsNoSuchElementForAnUnknownRunEvenWithASufficientScope() {
        TrainingRunId runId = TrainingRunId.random();

        assertThrows(NoSuchElementException.class, () -> service.run(runId, actor, adminScope));
    }

    @Test
    void runSucceedsForAManagerScope() {
        String jobId = service.start(spec, actor, adminScope);

        TrainingRunRecord run = service.run(TrainingRunId.of(jobId), actor, managerScope);

        assertEquals(jobId, run.runId().value().toString());
    }

    // -- concurrent jobs isolate (deterministic, same-thread executor) --------

    @Test
    void concurrentJobsIsolateFromEachOthersState() {
        DatasetId datasetA = DatasetId.random();
        DatasetId datasetB = DatasetId.random();

        trainingPort.script = List.of(new TrainingProgress("w", 5, 20, 0.4, 0.5, JobState.RUNNING, ""));
        String jobA = service.start(new TrainingJobSpec("yolo26n.pt", datasetA.value().toString(), 20), actor,
                adminScope);

        trainingPort.script = List.of(new TrainingProgress("w", 8, 30, 0.2, 0.7, JobState.SUCCEEDED, "model-B"));
        String jobB = service.start(new TrainingJobSpec("yolo11n.pt", datasetB.value().toString(), 30), actor,
                adminScope);

        assertNotEquals(jobA, jobB);
        TrainingJobView viewA = service.job(jobA).orElseThrow();
        TrainingJobView viewB = service.job(jobB).orElseThrow();

        assertEquals(datasetA.value().toString(), viewA.datasetId());
        assertEquals(5, viewA.epoch());
        assertEquals(JobState.RUNNING, viewA.state());

        assertEquals(datasetB.value().toString(), viewB.datasetId());
        assertEquals(8, viewB.epoch());
        assertEquals(JobState.SUCCEEDED, viewB.state());
        assertEquals("model-B", viewB.message());

        assertEquals(Set.of(jobA, jobB), Set.copyOf(service.jobs().stream().map(TrainingJobView::jobId).toList()));
    }

    @Test
    void concurrentJobsOnRealThreadsIsolateFromEachOther() throws InterruptedException {
        DatasetId datasetA = DatasetId.random();
        DatasetId datasetB = DatasetId.random();
        ExecutorService realExecutor = Executors.newCachedThreadPool();
        DefaultTrainingJobService realService = new DefaultTrainingJobService(new LatchedTrainingPort(),
                labelingService, auditTrail, trainingRunStores, realExecutor, Instant::now,
                DefaultTrainingJobService.MAX_FINISHED_JOBS);
        try {
            // A real cached-thread-pool executor returns from execute() without waiting for the
            // task, so these two run genuinely concurrently on background threads.
            String jobA = realService.start(new TrainingJobSpec("yolo26n.pt", datasetA.value().toString(), 5),
                    actor, adminScope);
            String jobB = realService.start(new TrainingJobSpec("yolo11n.pt", datasetB.value().toString(), 7),
                    actor, adminScope);

            awaitTerminal(realService, jobA);
            awaitTerminal(realService, jobB);

            TrainingJobView viewA = realService.job(jobA).orElseThrow();
            TrainingJobView viewB = realService.job(jobB).orElseThrow();
            assertEquals(datasetA.value().toString(), viewA.datasetId());
            assertEquals(JobState.SUCCEEDED, viewA.state());
            assertEquals(datasetB.value().toString(), viewB.datasetId());
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
        assertThrows(NullPointerException.class, () -> new DefaultTrainingJobService(null, labelingService,
                auditTrail, trainingRunStores, DefaultTrainingJobService.MAX_FINISHED_JOBS));
        assertThrows(NullPointerException.class, () -> new DefaultTrainingJobService(trainingPort, null,
                auditTrail, trainingRunStores, DefaultTrainingJobService.MAX_FINISHED_JOBS));
        assertThrows(NullPointerException.class, () -> new DefaultTrainingJobService(trainingPort, labelingService,
                null, trainingRunStores, DefaultTrainingJobService.MAX_FINISHED_JOBS));
        assertThrows(NullPointerException.class, () -> new DefaultTrainingJobService(trainingPort, labelingService,
                auditTrail, null, DefaultTrainingJobService.MAX_FINISHED_JOBS));

        assertThrows(NullPointerException.class, () -> new DefaultTrainingJobService(null, labelingService,
                auditTrail, trainingRunStores, new DirectExecutorService(), clock,
                DefaultTrainingJobService.MAX_FINISHED_JOBS));
        assertThrows(NullPointerException.class, () -> new DefaultTrainingJobService(trainingPort, null,
                auditTrail, trainingRunStores, new DirectExecutorService(), clock,
                DefaultTrainingJobService.MAX_FINISHED_JOBS));
        assertThrows(NullPointerException.class, () -> new DefaultTrainingJobService(trainingPort, labelingService,
                null, trainingRunStores, new DirectExecutorService(), clock,
                DefaultTrainingJobService.MAX_FINISHED_JOBS));
        assertThrows(NullPointerException.class, () -> new DefaultTrainingJobService(trainingPort, labelingService,
                auditTrail, null, new DirectExecutorService(), clock, DefaultTrainingJobService.MAX_FINISHED_JOBS));
        assertThrows(NullPointerException.class, () -> new DefaultTrainingJobService(trainingPort, labelingService,
                auditTrail, trainingRunStores, null, clock, DefaultTrainingJobService.MAX_FINISHED_JOBS));
        assertThrows(NullPointerException.class, () -> new DefaultTrainingJobService(trainingPort, labelingService,
                auditTrail, trainingRunStores, new DirectExecutorService(), null,
                DefaultTrainingJobService.MAX_FINISHED_JOBS));
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

    /**
     * In-memory {@link LabelingService}: only {@link #samples} (the synchronous pre-check) and
     * {@link #uploadForTraining} (the off-thread upload phase) are exercised by this suite; every
     * other method throws since {@link DefaultTrainingJobService} never calls it.
     */
    private static final class FakeLabelingService implements LabelingService {
        private boolean datasetKnown = true;
        private boolean datasetInScope = true;
        private int labeledCount = 1;
        private RuntimeException uploadFailure;
        private volatile DatasetId lastUploadDatasetId;

        @Override
        public TrainingSample capture(CaptureSpec spec, UserId actor, VisibilityScope scope) {
            throw new UnsupportedOperationException("not exercised by this suite");
        }

        @Override
        public TrainingSample captureFromReplay(ReplayCaptureSpec spec, UserId actor, VisibilityScope scope) {
            throw new UnsupportedOperationException("not exercised by this suite");
        }

        @Override
        public List<TrainingSample> samples(DatasetId id, SampleStatus statusOrNull, int limit, UserId actor,
                                             VisibilityScope scope) {
            if (!datasetKnown) {
                throw new NoSuchElementException("Unknown dataset: " + id.value());
            }
            if (!datasetInScope) {
                throw new AccessDeniedException("Dataset " + id.value() + " is outside your scope");
            }
            return labeledCount > 0 ? List.of(stubSample(id)) : List.of();
        }

        @Override
        public SampleImage image(TrainingSampleId id, UserId actor, VisibilityScope scope) {
            throw new UnsupportedOperationException("not exercised by this suite");
        }

        @Override
        public TrainingSample label(TrainingSampleId id, LabelSpec spec, UserId actor, VisibilityScope scope) {
            throw new UnsupportedOperationException("not exercised by this suite");
        }

        @Override
        public DatasetUpload uploadForTraining(DatasetId id, UserId actor, VisibilityScope scope) {
            lastUploadDatasetId = id;
            if (uploadFailure != null) {
                throw uploadFailure;
            }
            return new DatasetUpload(id, Instant.parse("2026-08-01T00:05:00Z"), 3, 12_345);
        }

        private static TrainingSample stubSample(DatasetId datasetId) {
            return new TrainingSample(TrainingSampleId.random(), datasetId, StreamId.random(), null,
                    Instant.parse("2026-08-01T00:00:00Z"), 640, 480, List.of(), SampleStatus.LABELED, null, null);
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

    /** In-memory {@link TrainingRunRepositoryPort}, keyed by run id; {@link #saveLog} keeps every save in order. */
    private static final class FakeTrainingRunRepositoryPort implements TrainingRunRepositoryPort {
        private final Map<TrainingRunId, TrainingRunRecord> rows = new LinkedHashMap<>();
        private final List<TrainingRunRecord> saveLog = new ArrayList<>();

        @Override
        public Optional<TrainingRunRecord> findById(TrainingRunId id) {
            return Optional.ofNullable(rows.get(id));
        }

        @Override
        public List<TrainingRunRecord> findAll(int limit) {
            return rows.values().stream()
                    .sorted(Comparator.comparing(TrainingRunRecord::startedAt).reversed())
                    .limit(limit)
                    .toList();
        }

        @Override
        public TrainingRunRecord save(TrainingRunRecord run) {
            rows.put(run.runId(), run);
            saveLog.add(run);
            return run;
        }
    }

    /** In-memory {@link CvModelRepositoryPort}; this suite only ever exercises {@link #save}. */
    private static final class FakeCvModelRepositoryPort implements CvModelRepositoryPort {
        private final List<CvModelRecord> saved = new ArrayList<>();

        @Override
        public Optional<CvModelRecord> findByIdAndVersion(String modelId, String version) {
            return saved.stream()
                    .filter(r -> r.modelId().equals(modelId) && r.version().equals(version))
                    .findFirst();
        }

        @Override
        public List<CvModelRecord> findAll() {
            return List.copyOf(saved);
        }

        @Override
        public Optional<CvModelRecord> findLive() {
            return saved.stream().filter(r -> r.status() == ModelStatus.LIVE).findFirst();
        }

        @Override
        public CvModelRecord save(CvModelRecord model) {
            saved.removeIf(r -> r.modelId().equals(model.modelId()) && r.version().equals(model.version()));
            saved.add(model);
            return model;
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

    /** A clock a test can read/advance the current fixed instant on -- no real time ever passes. */
    private static final class MutableClock implements java.util.function.Supplier<Instant> {
        private Instant instant;

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        @Override
        public Instant get() {
            return instant;
        }
    }
}
