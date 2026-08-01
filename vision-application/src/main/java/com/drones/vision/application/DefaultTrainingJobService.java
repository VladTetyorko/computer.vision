package com.drones.vision.application;

import com.drones.vision.domain.model.AuditAction;
import com.drones.vision.domain.model.AuditEntry;
import com.drones.vision.domain.model.AuditTargetType;
import com.drones.vision.domain.model.DatasetId;
import com.drones.vision.domain.model.DatasetUpload;
import com.drones.vision.domain.model.JobState;
import com.drones.vision.domain.model.SampleStatus;
import com.drones.vision.domain.model.TrainingJobSpec;
import com.drones.vision.domain.model.TrainingProgress;
import com.drones.vision.domain.model.TrainingSample;
import com.drones.vision.domain.model.UserId;
import com.drones.vision.domain.port.out.AuditTrailPort;
import com.drones.vision.domain.port.out.TrainingPort;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * The one implementation of {@link TrainingJobService}.
 *
 * <h2>Scope gate</h2>
 * {@link #start} requires {@link VisibilityScope#canManageOrg()}, mirroring {@code
 * DefaultModelRegistryService#promote}'s manager/admin gate exactly.
 *
 * <h2>Synchronous dataset pre-check (docs/CV-TRAINING-V2-PLAN.md §4/§E)</h2>
 * After the scope gate, {@link #start} runs one cheap, bounded {@link
 * LabelingService#samples(DatasetId, com.drones.vision.domain.model.SampleStatus, int, UserId,
 * VisibilityScope) LabelingService#samples} read (limit {@code 1}, filtered to {@code LABELED})
 * before ever registering or submitting the job. This surfaces an unknown dataset ({@link
 * java.util.NoSuchElementException}), an out-of-scope one ({@link AccessDeniedException}), and an
 * empty one ({@link IllegalArgumentException}) as real synchronous failures on the calling thread —
 * none of the three registers a job or touches {@code jobs}/{@code auditTrail}'s {@code STARTED}
 * path; a scoped denial from this read is audited by {@link LabelingService} itself, against a
 * {@code DATASET} target, not by this class. Only after this check passes does the existing
 * scope-denial-or-STARTED audit/registration continue exactly as before.
 *
 * <h2>Locally-generated job id vs. the wire job id</h2>
 * {@link TrainingPort#startTraining} <b>blocks</b> for the lifetime of the job, so this service
 * cannot wait for cv-service to assign its own {@link TrainingProgress#jobId()} before answering
 * the caller — that would defeat the entire point of an async "start a long job" endpoint.
 * Instead, {@link #start} mints a fresh {@link UUID} locally, registers a {@link TrainingJobView}
 * under it, and returns it immediately; the actual run is submitted to {@code executor}. Every
 * {@link #updateJob} call is keyed by this closed-over local id, not by whatever id a particular
 * {@link TrainingProgress} message itself carries — the wire {@code jobId} is read nowhere in this
 * class. The two ids are therefore allowed to differ (and in practice, cv-service assigns its own
 * unrelated one); only the locally-generated id is ever exposed through this service's surface, so
 * a caller never needs to know the wire id exists at all.
 *
 * <h2>Off-thread run: upload, then train (docs/CV-TRAINING-V2-PLAN.md §4)</h2>
 * The task submitted to {@code executor} ({@link #runJob}) first calls {@link
 * LabelingService#uploadForTraining} — composing every {@code LABELED} sample into the frozen §5
 * YOLO content and shipping it to the training host — noting the phase in the job's {@code message}
 * field ({@link #note}) before and after, then calls {@link TrainingPort#startTraining} with a
 * callback that folds every {@link TrainingProgress} onto the tracked {@link TrainingJobView}
 * ({@link #updateJob}) exactly as before. No new {@link JobState} is invented for "uploading" — the
 * job stays {@link JobState#RUNNING} from the moment {@link #start} returns, and {@code
 * epoch}/{@code totalEpochs} stay {@code 0} until the first epoch arrives, same as before this
 * upload phase existed. Either step's failure — an upload rejection or a training transport
 * failure — surfaces identically: a thrown {@link RuntimeException} is caught here and recorded as
 * a terminal {@link JobState#FAILED} job (message = the exception's own message, or its class's
 * simple name if none), ensuring neither failure mode silently leaves a dead background thread and
 * a job stuck at {@code RUNNING} forever. The exception never escapes {@link #runJob} itself, so it
 * is never thrown back through {@code executor} to any caller.
 *
 * <h2>Concurrent jobs</h2>
 * Nothing here rejects a second {@link #start} while another job is still {@code RUNNING}: each
 * job is independent (its own id/dataset/base model/progress), and {@link TrainingPort}'s own
 * contract already requires an implementation to be safe for concurrently-running jobs — refusing
 * concurrency at this layer would only add a limitation nothing in the frozen contract asks for.
 *
 * <h2>Retention</h2>
 * {@code jobs} is a {@link ConcurrentHashMap} keyed by job id; a job's entry is replaced (never
 * mutated in place) on every progress update, so a reader always sees one fully-formed, internally
 * consistent {@link TrainingJobView} — never a torn read across its fields. Finished jobs (terminal
 * {@link JobState#SUCCEEDED}/{@link JobState#FAILED}) are additionally tracked, oldest-first, in a
 * companion {@link ConcurrentLinkedDeque}; once more than {@value #MAX_FINISHED_JOBS} have
 * finished, the oldest is evicted from {@code jobs}. A still-{@code RUNNING} job is never evicted —
 * only completions count toward the cap — so this bounds memory for a long-lived service instance
 * without ever losing an in-progress job a poller might still be watching. This is a best-effort
 * cap, not a hard invariant: two completions racing on the size check can occasionally let the map
 * grow one entry past the bound; harmless, and self-corrects on the very next completion.
 *
 * <h2>Audit</h2>
 * Exactly one {@link AuditEntry} per {@link #start} attempt — a scope denial ({@code
 * DENIED:out of scope}) or a successful submission ({@code STARTED}) — against {@link
 * AuditTargetType#MODEL}, keyed by the generated job id (an opaque target id, per that enum's own
 * javadoc), {@link AuditAction#CREATED} (a new job resource is being created, unlike {@code
 * DefaultModelRegistryService#promote}'s update-shaped {@link AuditAction#UPDATED} over an
 * existing model reference). Progress updates and terminal completion are <b>not</b> individually
 * audited — a poller already observes them through {@link #job(String)}, and auditing every
 * progress tick would flood the trail for no security-relevant benefit.
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

    /** Labeled-sample presence check's fetch bound — one row is enough to prove non-emptiness. */
    private static final int PRESENCE_CHECK_LIMIT = 1;

    private final TrainingPort trainingPort;
    private final LabelingService labelingService;
    private final AuditTrailPort auditTrail;
    private final ExecutorService executor;
    private final Supplier<Instant> clock;

    private final Map<String, TrainingJobView> jobs = new ConcurrentHashMap<>();
    private final ConcurrentLinkedDeque<String> finishedOrder = new ConcurrentLinkedDeque<>();

    /** Production convenience ctor: a cached daemon-thread pool, {@link Instant#now()}. */
    public DefaultTrainingJobService(TrainingPort trainingPort, LabelingService labelingService,
                                      AuditTrailPort auditTrail) {
        this(trainingPort, labelingService, auditTrail, defaultExecutor(), Instant::now);
    }

    /** Test/wiring seam: an explicit executor (e.g. a same-thread one) and clock. */
    DefaultTrainingJobService(TrainingPort trainingPort, LabelingService labelingService, AuditTrailPort auditTrail,
                               ExecutorService executor, Supplier<Instant> clock) {
        this.trainingPort = Objects.requireNonNull(trainingPort, "trainingPort must not be null");
        this.labelingService = Objects.requireNonNull(labelingService, "labelingService must not be null");
        this.auditTrail = Objects.requireNonNull(auditTrail, "auditTrail must not be null");
        this.executor = Objects.requireNonNull(executor, "executor must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
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

        String jobId = UUID.randomUUID().toString();

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

        jobs.put(jobId, new TrainingJobView(jobId, spec.baseModel(), spec.datasetId(), spec.epochs(),
                0, 0, 0.0, 0.0, JobState.RUNNING, "", clock.get()));
        audit(actor, jobId, spec, RESULT_STARTED);

        executor.execute(() -> runJob(jobId, spec, datasetId, actor, scope));
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

    private void runJob(String jobId, TrainingJobSpec spec, DatasetId datasetId, UserId actor,
                         VisibilityScope scope) {
        try {
            note(jobId, "Uploading dataset…");
            DatasetUpload upload = labelingService.uploadForTraining(datasetId, actor, scope);
            note(jobId, "Uploaded " + upload.sampleCount() + " sample(s), " + upload.sizeBytes()
                    + " bytes; starting training…");
            trainingPort.startTraining(spec, progress -> updateJob(jobId, progress));
        } catch (RuntimeException e) {
            recordFailure(jobId, e);
        }
    }

    /** Replaces only the tracked view's {@code message} field — same replace-never-mutate idiom as {@link #updateJob}. */
    private void note(String jobId, String message) {
        jobs.computeIfPresent(jobId, (id, current) -> new TrainingJobView(current.jobId(), current.baseModel(),
                current.datasetId(), current.epochs(), current.epoch(), current.totalEpochs(), current.loss(),
                current.map50(), current.state(), message, current.startedAt()));
    }

    private void updateJob(String jobId, TrainingProgress progress) {
        jobs.computeIfPresent(jobId, (id, current) -> new TrainingJobView(current.jobId(), current.baseModel(),
                current.datasetId(), current.epochs(), progress.epoch(), progress.totalEpochs(), progress.loss(),
                progress.map50(), progress.state(), progress.message(), current.startedAt()));
        if (isTerminal(progress.state())) {
            retire(jobId);
        }
    }

    private void recordFailure(String jobId, RuntimeException e) {
        String message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        jobs.computeIfPresent(jobId, (id, current) -> new TrainingJobView(current.jobId(), current.baseModel(),
                current.datasetId(), current.epochs(), current.epoch(), current.totalEpochs(), current.loss(),
                current.map50(), JobState.FAILED, message, current.startedAt()));
        retire(jobId);
    }

    private static boolean isTerminal(JobState state) {
        return state == JobState.SUCCEEDED || state == JobState.FAILED;
    }

    private void retire(String jobId) {
        finishedOrder.addLast(jobId);
        while (finishedOrder.size() > MAX_FINISHED_JOBS) {
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
}
