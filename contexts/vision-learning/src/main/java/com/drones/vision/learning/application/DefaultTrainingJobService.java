package com.drones.vision.learning.application;

import com.drones.vision.platform.AuditAction;
import com.drones.vision.platform.AuditEntry;
import com.drones.vision.platform.AuditTargetType;
import com.drones.vision.learning.domain.model.CvModelRecord;
import com.drones.vision.learning.domain.model.DatasetId;
import com.drones.vision.learning.domain.model.DatasetUpload;
import com.drones.vision.learning.domain.model.JobState;
import com.drones.vision.learning.domain.model.MetricsKind;
import com.drones.vision.learning.domain.model.ModelMetrics;
import com.drones.vision.learning.domain.model.ModelProvenance;
import com.drones.vision.learning.domain.model.ModelRuntime;
import com.drones.vision.learning.domain.model.ModelStatus;
import com.drones.vision.learning.domain.model.ModelTaskType;
import com.drones.vision.learning.domain.model.SampleStatus;
import com.drones.vision.learning.domain.model.TrainingJobSpec;
import com.drones.vision.learning.domain.model.TrainingProgress;
import com.drones.vision.learning.domain.model.TrainingRunId;
import com.drones.vision.learning.domain.model.TrainingRunRecord;
import com.drones.vision.learning.domain.model.TrainingSample;
import com.drones.vision.kernel.UserId;
import com.drones.vision.platform.AuditTrailPort;
import com.drones.vision.learning.domain.port.TrainingPort;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import com.drones.vision.platform.AccessDeniedException;
import com.drones.vision.platform.VisibilityScope;

/**
 * The one implementation of {@link TrainingJobService}.
 *
 * <h2>Scope gate (docs/plans/active/CV-SETTINGS-PLAN.md §8 OQ9)</h2>
 * {@link #start} requires {@link VisibilityScope#canManageOrg()} — relaxed from {@code
 * canAdminister()} (a security-gate change, accepted by the user per docs/plans/active/CV-SETTINGS-CONTEXT.md's
 * "Decisions taken"): claiming the shared training host is still a privileged act, but training a
 * dataset a manager can already label is team-scoped, not deployment-global — promoting the result
 * to production stays {@code canAdminister()} on {@link ModelRegistryService#promote}. {@link
 * #runs}/{@link #run} are gated on {@code canManageOrg()} too — see {@link TrainingJobService}'s own
 * javadoc, "Scope".
 *
 * <h2>Synchronous dataset pre-check (docs/plans/done/CV-TRAINING-V2-PLAN.md §4/§E)</h2>
 * After the scope gate, {@link #start} runs one cheap, bounded {@link
 * LabelingService#samples(DatasetId, com.drones.vision.learning.domain.model.SampleStatus, int, UserId,
 * VisibilityScope) LabelingService#samples} read (limit {@code 1}, filtered to {@code LABELED})
 * before ever registering or submitting the job. This surfaces an unknown dataset ({@link
 * java.util.NoSuchElementException}), an out-of-scope one ({@link AccessDeniedException}), and an
 * empty one ({@link IllegalArgumentException}) as real synchronous failures on the calling thread —
 * none of the three registers a job or touches {@code jobs}/{@code auditTrail}'s {@code STARTED}
 * path; a scoped denial from this read is audited by {@link LabelingService} itself, against a
 * {@code DATASET} target, not by this class. Only after this check passes does the existing
 * scope-denial-or-STARTED audit/registration continue exactly as before.
 *
 * <h2>Locally-generated job id doubles as the persisted run id</h2>
 * {@link TrainingPort#startTraining} <b>blocks</b> for the lifetime of the job, so this service
 * cannot wait for cv-service to assign its own {@link TrainingProgress#jobId()} before answering
 * the caller. {@link #start} mints one fresh {@link UUID}, uses its string form as the in-memory
 * {@link TrainingJobView#jobId()} (unchanged from before this run-persistence wave) and the same
 * UUID, wrapped, as the {@link TrainingRunId} the persisted {@link TrainingRunRecord} is keyed by —
 * so a caller can look up the same job through either {@link #job(String)} (the live poll) or
 * {@link #run(TrainingRunId, UserId, VisibilityScope)} (the durable record) without tracking two
 * unrelated ids. The wire {@code jobId} a particular {@link TrainingProgress} message carries is
 * still read nowhere in this class — see the "Off-thread run" section below.
 *
 * <h2>Off-thread run: upload, then train, then persist (docs/plans/done/CV-TRAINING-V2-PLAN.md §4;
 * run/candidate persistence per docs/plans/active/CV-SETTINGS-PLAN.md §3.3, fixing H7)</h2>
 * The task submitted to {@code executor} ({@link #runJob}) first calls {@link
 * LabelingService#uploadForTraining} — composing every {@code LABELED} sample into the frozen §5
 * YOLO content and shipping it to the training host — noting the phase in the job's {@code message}
 * field ({@link #note}) before and after, then calls {@link TrainingPort#startTraining} with a
 * callback that folds every {@link TrainingProgress} onto the tracked {@link TrainingJobView}
 * ({@link #updateJob}) exactly as before this wave, <b>and</b> onto a matching {@link
 * TrainingRunRecord} save through {@link TrainingRunStores#trainingRuns()} — the durable
 * counterpart {@code TrainingJobView} never had. A terminal {@link JobState#SUCCEEDED} message
 * additionally registers a {@link ModelStatus#CANDIDATE} {@link CvModelRecord} through {@link
 * TrainingRunStores#models()} (docs/plans/active/CV-SETTINGS-PLAN.md §8 OQ6 — <b>never</b> {@code
 * LIVE}; promotion stays {@link ModelRegistryService#promote}'s own human, {@code
 * canAdminister()}-gated act) — but only when the terminal message actually names a produced model
 * id ({@link TrainingProgress#message()} non-blank); a blank one is treated as "nothing to
 * register", not a crash. Either upload or training failing is recorded as a terminal {@link
 * JobState#FAILED} job (and a matching {@code FAILED} run row) rather than escaping, exactly as
 * before this wave.
 *
 * <h2>Concurrent jobs</h2>
 * Nothing here rejects a second {@link #start} while another job is still {@code RUNNING}: each
 * job is independent (its own id/dataset/base model/progress), and {@link TrainingPort}'s own
 * contract already requires an implementation to be safe for concurrently-running jobs.
 *
 * <h2>Retention</h2>
 * {@code jobs} is a {@link ConcurrentHashMap} keyed by job id; a job's entry is replaced (never
 * mutated in place) on every progress update. Finished jobs (terminal {@link JobState#SUCCEEDED}/
 * {@link JobState#FAILED}) are additionally tracked, oldest-first, in a companion {@link
 * ConcurrentLinkedDeque}; once more than {@value #MAX_FINISHED_JOBS} default (or the configured
 * {@code maxFinishedJobs}) have finished, the oldest is evicted from {@code jobs} — but never from
 * the persisted {@link TrainingRunRecord} store, which {@link #runs}/{@link #run} read from
 * directly and which has no such cap (docs/plans/active/CV-SETTINGS-PLAN.md §3.3's whole point:
 * the durable record outlives the in-memory poll's bound).
 *
 * <h2>Audit</h2>
 * Exactly one {@link AuditEntry} per {@link #start} attempt — a scope denial ({@code
 * DENIED:out of scope}) or a successful submission ({@code STARTED}) — against {@link
 * AuditTargetType#MODEL}, keyed by the generated job id, {@link AuditAction#CREATED}. Progress
 * updates and terminal completion are <b>not</b> individually audited — a poller already observes
 * them through {@link #job(String)}/{@link #run}. {@link #runs}/{@link #run} audit only a scope
 * denial, never a successful read (a read must never audit its own success, matching every other
 * read in this module).
 *
 * <h2>Threading</h2>
 * Holds no state beyond the two thread-safe collections above and the injected collaborators.
 */
public final class DefaultTrainingJobService implements TrainingJobService {

    /** Finished jobs kept before the oldest is evicted — see the class javadoc's Retention section. */
    static final int MAX_FINISHED_JOBS = 50;

    private static final String THREAD_NAME = "training-job";
    private static final String ATTR_JOB_ID = "jobId";
    private static final String ATTR_BASE_MODEL = "baseModel";
    private static final String ATTR_DATASET_ID = "datasetId";
    private static final String ATTR_RESULT = "result";
    private static final String DENIED_OUT_OF_SCOPE = "DENIED:out of scope";
    private static final String RESULT_STARTED = "STARTED";
    private static final String RUNS_TARGET_ID = "training-runs";

    /** Labeled-sample presence check's fetch bound — one row is enough to prove non-emptiness. */
    private static final int PRESENCE_CHECK_LIMIT = 1;

    /**
     * Version/kind stamped on a run-produced {@link CvModelRecord} — no version axis exists on the
     * training wire (a run's output is one uniquely-named checkpoint, mirroring the worker's own
     * {@code "latest"} sentinel convention for a version-less reference — see
     * docs/plans/active/CV-SETTINGS-CONTEXT.md's W4-domain handoff, deviation 6).
     */
    static final String TRAINED_MODEL_VERSION = "latest";
    static final String TRAINED_MODEL_KIND = "fine-tuned";

    private final TrainingPort trainingPort;
    private final LabelingService labelingService;
    private final AuditTrailPort auditTrail;
    private final TrainingRunStores trainingRunStores;
    private final ExecutorService executor;
    private final Supplier<Instant> clock;

    private final int maxFinishedJobs;

    private final Map<String, TrainingJobView> jobs = new ConcurrentHashMap<>();
    private final ConcurrentLinkedDeque<String> finishedOrder = new ConcurrentLinkedDeque<>();

    /** Production ctor: a cached daemon-thread pool, {@link Instant#now()}. */
    public DefaultTrainingJobService(TrainingPort trainingPort, LabelingService labelingService,
                                      AuditTrailPort auditTrail, TrainingRunStores trainingRunStores,
                                      int maxFinishedJobs) {
        this(trainingPort, labelingService, auditTrail, trainingRunStores, defaultExecutor(), Instant::now,
                maxFinishedJobs);
    }

    /** Test seam: same as the production constructor, with an explicit executor (e.g. a same-thread one) and clock. */
    DefaultTrainingJobService(TrainingPort trainingPort, LabelingService labelingService, AuditTrailPort auditTrail,
                               TrainingRunStores trainingRunStores, ExecutorService executor,
                               Supplier<Instant> clock, int maxFinishedJobs) {
        this.trainingPort = Objects.requireNonNull(trainingPort, "trainingPort must not be null");
        this.labelingService = Objects.requireNonNull(labelingService, "labelingService must not be null");
        this.auditTrail = Objects.requireNonNull(auditTrail, "auditTrail must not be null");
        this.trainingRunStores = Objects.requireNonNull(trainingRunStores, "trainingRunStores must not be null");
        this.executor = Objects.requireNonNull(executor, "executor must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.maxFinishedJobs = maxFinishedJobs;
    }

    private static ExecutorService defaultExecutor() {
        return Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, THREAD_NAME);
            thread.setDaemon(true);
            return thread;
        });
    }

    @Override
    public String start(TrainingJobSpec spec, UserId actor, VisibilityScope scope) {
        Objects.requireNonNull(spec, "spec must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(scope, "scope must not be null");

        UUID uuid = UUID.randomUUID();
        String jobId = uuid.toString();
        TrainingRunId runId = new TrainingRunId(uuid);

        if (!scope.canManageOrg()) {
            audit(actor, jobId, spec, DENIED_OUT_OF_SCOPE);
            throw new AccessDeniedException("Not permitted to start training jobs");
        }

        DatasetId datasetId = DatasetId.of(spec.datasetId());
        List<TrainingSample> labeledPreview =
                labelingService.samples(datasetId, SampleStatus.LABELED, PRESENCE_CHECK_LIMIT, actor, scope);
        if (labeledPreview.isEmpty()) {
            throw new IllegalArgumentException(
                    "Dataset " + spec.datasetId() + " has no LABELED samples to train on");
        }

        Instant startedAt = clock.get();
        jobs.put(jobId, new TrainingJobView(jobId, spec.baseModel(), spec.datasetId(), spec.epochs(),
                0, 0, 0.0, 0.0, JobState.RUNNING, "", startedAt));
        trainingRunStores.trainingRuns().save(new TrainingRunRecord(runId, datasetId, spec.baseModel(),
                spec.epochs(), JobState.RUNNING, 0, 0, 0.0, 0.0, null, actor, startedAt, null, ""));
        audit(actor, jobId, spec, RESULT_STARTED);

        executor.execute(() -> runJob(jobId, runId, spec, datasetId, actor, startedAt, scope));
        return jobId;
    }

    @Override
    public List<TrainingJobView> jobs() {
        return jobs.values().stream()
                .sorted(Comparator.comparing(TrainingJobView::startedAt).reversed())
                .toList();
    }

    @Override
    public Optional<TrainingJobView> job(String jobId) {
        return Optional.ofNullable(jobs.get(jobId));
    }

    @Override
    public List<TrainingRunRecord> runs(int limit, UserId actor, VisibilityScope scope) {
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(scope, "scope must not be null");
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive: " + limit);
        }
        if (!scope.canManageOrg()) {
            auditRunsDenied(actor, RUNS_TARGET_ID);
            throw new AccessDeniedException("Not permitted to view training runs");
        }
        return trainingRunStores.trainingRuns().findAll(limit);
    }

    @Override
    public TrainingRunRecord run(TrainingRunId runId, UserId actor, VisibilityScope scope) {
        Objects.requireNonNull(runId, "runId must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(scope, "scope must not be null");
        if (!scope.canManageOrg()) {
            auditRunsDenied(actor, runId.value().toString());
            throw new AccessDeniedException("Not permitted to view training runs");
        }
        return trainingRunStores.trainingRuns().findById(runId)
                .orElseThrow(() -> new NoSuchElementException("Unknown training run: " + runId.value()));
    }

    private void runJob(String jobId, TrainingRunId runId, TrainingJobSpec spec, DatasetId datasetId, UserId actor,
                         Instant startedAt, VisibilityScope scope) {
        try {
            note(jobId, "Uploading dataset…");
            DatasetUpload upload = labelingService.uploadForTraining(datasetId, actor, scope);
            note(jobId, "Uploaded " + upload.sampleCount() + " sample(s), " + upload.sizeBytes()
                    + " bytes; starting training…");
            trainingPort.startTraining(spec,
                    progress -> updateJob(jobId, runId, spec, datasetId, actor, startedAt, progress));
        } catch (RuntimeException e) {
            recordFailure(jobId, runId, spec, datasetId, actor, startedAt, e);
        }
    }

    /** Replaces only the tracked view's {@code message} field — same replace-never-mutate idiom as {@link #updateJob}. */
    private void note(String jobId, String message) {
        jobs.computeIfPresent(jobId, (id, current) -> new TrainingJobView(current.jobId(), current.baseModel(),
                current.datasetId(), current.epochs(), current.epoch(), current.totalEpochs(), current.loss(),
                current.map50(), current.state(), message, current.startedAt()));
    }

    private void updateJob(String jobId, TrainingRunId runId, TrainingJobSpec spec, DatasetId datasetId,
                            UserId actor, Instant startedAt, TrainingProgress progress) {
        jobs.computeIfPresent(jobId, (id, current) -> new TrainingJobView(current.jobId(), current.baseModel(),
                current.datasetId(), current.epochs(), progress.epoch(), progress.totalEpochs(), progress.loss(),
                progress.map50(), progress.state(), progress.message(), current.startedAt()));

        boolean terminal = isTerminal(progress.state());
        Instant now = clock.get();
        String outputModelId = outputModelIdOf(progress);
        persistRun(jobId, runId, spec, datasetId, actor, startedAt, terminal ? now : null, outputModelId);

        if (progress.state() == JobState.SUCCEEDED && outputModelId != null) {
            registerCandidateModel(runId, datasetId, spec, outputModelId, progress.map50(), now);
        }
        if (terminal) {
            retire(jobId);
        }
    }

    private void recordFailure(String jobId, TrainingRunId runId, TrainingJobSpec spec, DatasetId datasetId,
                                UserId actor, Instant startedAt, RuntimeException e) {
        String message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        jobs.computeIfPresent(jobId, (id, current) -> new TrainingJobView(current.jobId(), current.baseModel(),
                current.datasetId(), current.epochs(), current.epoch(), current.totalEpochs(), current.loss(),
                current.map50(), JobState.FAILED, message, current.startedAt()));
        persistRun(jobId, runId, spec, datasetId, actor, startedAt, clock.get(), null);
        retire(jobId);
    }

    /** Mirrors the in-memory {@link TrainingJobView} onto a matching {@link TrainingRunRecord} save. */
    private void persistRun(String jobId, TrainingRunId runId, TrainingJobSpec spec, DatasetId datasetId,
                             UserId actor, Instant startedAt, Instant finishedAtOrNull, String outputModelId) {
        TrainingJobView view = jobs.get(jobId);
        if (view == null) {
            return;
        }
        trainingRunStores.trainingRuns().save(new TrainingRunRecord(runId, datasetId, spec.baseModel(),
                spec.epochs(), view.state(), view.epoch(), view.totalEpochs(), view.loss(), view.map50(),
                outputModelId, actor, startedAt, finishedAtOrNull, view.message()));
    }

    /** The produced model id on a {@code SUCCEEDED} terminal message, or {@code null} if blank/absent/not terminal. */
    private static String outputModelIdOf(TrainingProgress progress) {
        if (progress.state() != JobState.SUCCEEDED) {
            return null;
        }
        String message = progress.message();
        return (message == null || message.isBlank()) ? null : message;
    }

    /**
     * Registers the run's output as a {@link ModelStatus#CANDIDATE} {@link CvModelRecord}, never
     * {@link ModelStatus#LIVE} (docs/plans/active/CV-SETTINGS-PLAN.md §8 OQ6). {@code taskType}/
     * {@code runtime}/{@code classes}/{@code defaultLabelFilter} fall back to the same honest
     * "we don't know more than the wire told us" defaults {@link CvModelView#synthesize} uses for a
     * worker-only model — this run's own wire ({@link TrainingProgress}) carries no task type,
     * runtime, or class roster either.
     */
    private void registerCandidateModel(TrainingRunId runId, DatasetId datasetId, TrainingJobSpec spec,
                                         String outputModelId, double map50, Instant trainedAt) {
        ModelMetrics metrics = new ModelMetrics(map50, MetricsKind.TRAINING);
        ModelProvenance provenance =
                new ModelProvenance(datasetId, runId, spec.baseModel(), spec.epochs(), trainedAt);
        CvModelRecord candidate = new CvModelRecord(outputModelId, TRAINED_MODEL_VERSION, outputModelId,
                TRAINED_MODEL_KIND, false, List.of(), ModelTaskType.DETECT, ModelRuntime.PYTORCH, List.of(),
                ModelStatus.CANDIDATE, metrics, provenance, null, null, trainedAt);
        trainingRunStores.models().save(candidate);
    }

    private static boolean isTerminal(JobState state) {
        return state == JobState.SUCCEEDED || state == JobState.FAILED;
    }

    private void retire(String jobId) {
        finishedOrder.addLast(jobId);
        while (finishedOrder.size() > maxFinishedJobs) {
            String oldest = finishedOrder.pollFirst();
            if (oldest != null) {
                jobs.remove(oldest);
            }
        }
    }

    private void audit(UserId actor, String jobId, TrainingJobSpec spec, String result) {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put(ATTR_JOB_ID, jobId);
        attributes.put(ATTR_BASE_MODEL, spec.baseModel());
        attributes.put(ATTR_DATASET_ID, spec.datasetId());
        attributes.put(ATTR_RESULT, result);
        auditTrail.record(AuditEntry.of(actor, AuditAction.CREATED, AuditTargetType.MODEL, jobId,
                "Training job " + jobId + " " + result, attributes));
    }

    private void auditRunsDenied(UserId actor, String targetId) {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put(ATTR_RESULT, DENIED_OUT_OF_SCOPE);
        auditTrail.record(AuditEntry.of(actor, AuditAction.UPDATED, AuditTargetType.MODEL, targetId,
                "Denied reading training runs: out of scope", attributes));
    }
}
