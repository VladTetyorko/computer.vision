package com.drones.vision.api.controller;

import com.drones.vision.api.dto.StartTrainingJobRequest;
import com.drones.vision.api.dto.TrainingJobResponse;
import com.drones.vision.api.dto.TrainingJobsResponse;
import com.drones.vision.api.exception.ApiExceptionHandler;
import com.drones.vision.learning.application.TrainingJobService;
import com.drones.vision.learning.domain.model.DatasetId;
import com.drones.vision.learning.domain.model.TrainingJobSpec;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import com.drones.vision.api.security.CurrentUser;

/**
 * Driving REST adapter for the CV training-job flow (docs/plans/done/CV-TRAINING-PLAN.md §7/§8, Phase 2) —
 * starting a fine-tune run against a dataset and polling its progress, over {@link
 * TrainingJobService}. The last backend piece of the training loop: {@link DatasetController}/
 * {@link LabelingController} build the dataset, {@link ModelRegistryController} promotes the
 * result; this controller is the run in between.
 *
 * <p>Gated by {@code vision.training.enabled} (default {@code false}), same as {@link
 * DatasetController}/{@link LabelingController}/{@link ModelRegistryController} — this whole
 * controller is absent from the context when off, every route 404s like any other unmapped path.
 *
 * <p>{@link #start} nests under {@code /api/datasets/{id}/train} (the dataset being trained on),
 * while {@link #job}/{@link #jobs} live under {@code /api/training/jobs} — a job outlives, and is
 * never re-scoped by, the dataset it started from, exactly the split docs/plans/done/CV-TRAINING-PLAN.md §8
 * pins. {@link #start} parses {@code id} into a {@link DatasetId} at the edge (docs/plans/done/CV-TRAINING-V2-PLAN.md
 * §5) — a malformed id is a synchronous {@code 400}, not a job that fails later — then threads its
 * canonical string form into the started {@link TrainingJobSpec}, whose {@code datasetId} field
 * itself stays a plain string all the way to the gRPC boundary ({@link
 * TrainingJobSpec#datasetId()}'s own javadoc explains why).
 *
 * <p>Error mapping is entirely {@link TrainingJobService#start}'s own exceptions surfacing through
 * {@link ApiExceptionHandler}, plus this controller's own edge parse: {@link
 * com.drones.vision.identity.application.scope.AccessDeniedException} (caller may not manage the organization, or
 * the dataset is outside their scope) → 403; {@link java.util.NoSuchElementException} (unknown
 * dataset) → 404; {@link IllegalArgumentException} (a malformed dataset id; a malformed spec — blank
 * {@code baseModel} or non-positive {@code epochs}, {@link TrainingJobSpec}'s own
 * compact-constructor checks; or a dataset with no {@code LABELED} samples to train on,
 * docs/plans/done/CV-TRAINING-V2-PLAN.md §4's synchronous pre-check) → 400. A training run that fails
 * mid-flight (including a rejected dataset upload, docs/plans/done/CV-TRAINING-V2-PLAN.md §4) is <b>never</b>
 * a thrown exception — it is a polled {@link com.drones.vision.learning.domain.model.JobState#FAILED} {@link
 * TrainingJobResponse#state()}, so {@link #job}/{@link #jobs} never special-case it.
 */
@RestController
@ConditionalOnProperty(prefix = "vision.training", name = "enabled", havingValue = "true")
public class TrainingJobController {

    private final TrainingJobService trainingJobService;
    private final CurrentUser currentUser;

    public TrainingJobController(TrainingJobService trainingJobService, CurrentUser currentUser) {
        this.trainingJobService = Objects.requireNonNull(trainingJobService, "trainingJobService must not be null");
        this.currentUser = Objects.requireNonNull(currentUser, "currentUser must not be null");
    }

    /**
     * Starts a fine-tune job against a dataset — uploading it to the training host over gRPC and
     * kicking off the run, all in {@link TrainingJobService#start}'s off-thread work
     * (docs/plans/done/CV-TRAINING-V2-PLAN.md §4).
     *
     * @param id      the dataset id to train on, as a canonical UUID string, parsed at this edge
     *                (400 on a malformed id) before its canonical string form is threaded into the
     *                started job's {@link TrainingJobSpec#datasetId()}
     * @param request the base model checkpoint and epoch count to run
     * @return the freshly started job's initial state — present immediately, per {@link
     *         TrainingJobService#start}'s own contract
     */
    @PostMapping("/api/datasets/{id}/train")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public TrainingJobResponse start(@PathVariable String id, @RequestBody StartTrainingJobRequest request) {
        DatasetId datasetId = DatasetId.of(id);
        TrainingJobSpec spec = new TrainingJobSpec(request.baseModel(), datasetId.value().toString(), request.epochs());
        String jobId = trainingJobService.start(spec, currentUser.userId(), currentUser.scope());
        return trainingJobService.job(jobId).map(TrainingJobResponse::from)
                .orElseThrow(() -> new IllegalStateException("Training job vanished immediately after start: " + jobId));
    }

    /**
     * Polls one job's current state.
     *
     * @param jobId the id {@link #start} returned
     * @return the job's latest known state
     * @throws NoSuchElementException if {@code jobId} is unknown (404) — never started, or evicted
     *                                under {@link TrainingJobService}'s finished-job retention policy
     */
    @GetMapping("/api/training/jobs/{jobId}")
    public TrainingJobResponse job(@PathVariable String jobId) {
        return trainingJobService.job(jobId).map(TrainingJobResponse::from)
                .orElseThrow(() -> new NoSuchElementException("Unknown training job: " + jobId));
    }

    /**
     * Lists every tracked job.
     *
     * @return every job {@link TrainingJobService#jobs()} still tracks, newest-first
     */
    @GetMapping("/api/training/jobs")
    public TrainingJobsResponse jobs() {
        List<TrainingJobResponse> jobs = trainingJobService.jobs().stream().map(TrainingJobResponse::from).toList();
        return new TrainingJobsResponse(jobs);
    }
}
