package com.drones.vision.learning.application;

import com.drones.vision.kernel.UserId;
import com.drones.vision.learning.domain.model.TrainingJobSpec;
import com.drones.vision.learning.domain.model.TrainingRunId;
import com.drones.vision.learning.domain.model.TrainingRunRecord;
import com.drones.vision.learning.domain.port.TrainingPort;
import com.drones.vision.platform.Authority;

import java.util.List;
import java.util.Optional;

/**
 * Starts a CV model fine-tune job and holds its pollable state (docs/plans/done/CV-TRAINING-PLAN.md §6/§7,
 * Phase 2; run persistence per docs/plans/active/CV-SETTINGS-PLAN.md §3.3, fixing H7 — "training
 * metrics evaporate"). One interface, one implementation ({@link DefaultTrainingJobService}) — {@link
 * TrainingPort} itself is the substitutable boundary (a GPU-training-host gRPC client, or a
 * refusing offline default); this service adds the scope gate/audit, the off-thread run, the
 * locally-generated job id, the in-memory pollable registry, and the durable {@link
 * TrainingRunRecord} counterpart on top.
 *
 * <h2>Scope</h2>
 * {@link #start} requires {@link Authority#mayManageOrg()} — any manager/admin
 * (docs/plans/active/CV-SETTINGS-PLAN.md §8 OQ9: relaxed from {@code canAdminister()} — the training
 * host is shared but the act is team-scoped; a group manager who can label a dataset must also be
 * able to train it). {@link #jobs()}/{@link #job(String)} stay unscoped, unaudited reads — any
 * authenticated caller may poll a job's progress, mirroring {@code ModelRegistryService#models()}'s
 * "any authenticated caller may read" precedent. {@link #runs}/{@link #run} are gated on {@link
 * Authority#mayManageOrg()} too — unlike the in-memory poll, the persisted run history is a
 * privileged administrative view (a deployment-wide record, not owned by any one dataset's group),
 * so lacking {@code mayManageOrg()} means "may not view this at all", not "this run isn't yours" —
 * {@link com.drones.vision.platform.AccessDeniedException}, not a hiding 404.
 */
public interface TrainingJobService {

    /**
     * Starts a fine-tune job off-thread and returns immediately with the id a poller uses to track
     * it — never blocks on the (potentially long-running, multi-epoch) training stream itself. The
     * job's dataset is uploaded to the training host as part of the off-thread run (docs/plans/done/CV-TRAINING-V2-PLAN.md
     * §4) — see {@link DefaultTrainingJobService}'s own javadoc for the upload-then-train sequence
     * and its synchronous pre-check. The returned id is also the {@link TrainingRunId} the
     * persisted run is tracked under — see {@link #run(TrainingRunId, UserId, Authority)}.
     *
     * @param spec  the job to run
     * @param actor who is starting it, for the audit trail
     * @param scope the acting user's authority; must satisfy {@link Authority#mayManageOrg()}
     * @return the locally generated job id; poll it via {@link #job(String)} or {@link
     *         #run(TrainingRunId, UserId, Authority)}
     * @throws com.drones.vision.platform.AccessDeniedException             if {@code scope} may not manage the organization
     *                                             (audited as a denial before this method throws),
     *                                             or the job's dataset is outside {@code scope}
     * @throws java.util.NoSuchElementException  if the job's dataset is unknown
     * @throws IllegalArgumentException          if the dataset id is malformed, or the dataset has
     *                                             no {@link com.drones.vision.learning.domain.model.SampleStatus#LABELED}
     *                                             samples to train on — in every one of these cases
     *                                             the job is never registered or started
     */
    String start(TrainingJobSpec spec, UserId actor, Authority scope);

    /**
     * Every tracked job, newest-first by {@link TrainingJobView#startedAt()}. Bounded — see {@link
     * DefaultTrainingJobService}'s own javadoc for the finished-job retention policy.
     */
    List<TrainingJobView> jobs();

    /**
     * One tracked job's current state.
     *
     * @param jobId the id {@link #start} returned
     * @return the job's latest known state, or {@link Optional#empty()} if unknown — never
     *         started, or a finished job evicted under the retention policy
     */
    Optional<TrainingJobView> job(String jobId);

    /**
     * Every persisted training run, newest-first by {@link TrainingRunRecord#startedAt()} —
     * unbounded by the in-memory retention policy {@link #jobs()} applies (docs/plans/active/CV-SETTINGS-PLAN.md
     * §3.3).
     *
     * @param limit maximum number of runs to return; must be positive
     * @param actor who is asking, for the audit trail on a denial
     * @param scope the acting user's authority; must satisfy {@link Authority#mayManageOrg()}
     * @return the most recently started runs, up to {@code limit}
     * @throws com.drones.vision.platform.AccessDeniedException if {@code scope} may not manage the
     *                                organization (audited as a denial before this method throws)
     * @throws IllegalArgumentException if {@code limit} is not positive
     */
    List<TrainingRunRecord> runs(int limit, UserId actor, Authority scope);

    /**
     * One persisted training run.
     *
     * @param runId the run id (the same id {@link #start} returned, wrapped as a {@link TrainingRunId})
     * @param actor who is asking, for the audit trail on a denial
     * @param scope the acting user's authority; must satisfy {@link Authority#mayManageOrg()}
     * @return the run's latest persisted state
     * @throws com.drones.vision.platform.AccessDeniedException if {@code scope} may not manage the
     *                                organization (audited as a denial before this method throws)
     * @throws java.util.NoSuchElementException if no run with that id has ever been persisted
     */
    TrainingRunRecord run(TrainingRunId runId, UserId actor, Authority scope);
}
