package com.drones.vision.learning.application;

import com.drones.vision.learning.domain.model.TrainingJobSpec;
import com.drones.vision.kernel.UserId;
import com.drones.vision.learning.domain.port.TrainingPort;

import java.util.List;
import java.util.Optional;
import com.drones.vision.identity.application.scope.VisibilityScope;

/**
 * Starts a CV model fine-tune job and holds its pollable state (docs/plans/done/CV-TRAINING-PLAN.md §6/§7,
 * Phase 2). One interface, one implementation ({@link DefaultTrainingJobService}) — {@link
 * TrainingPort} itself is the substitutable boundary (a GPU-training-host gRPC client, or a
 * refusing offline default); this service adds the manager gate/audit, the off-thread run, the
 * locally-generated job id, and the in-memory pollable registry on top.
 *
 * <h2>Scope</h2>
 * {@link #start} requires {@link VisibilityScope#canManageOrg()} — any manager/admin, mirroring
 * {@code DefaultModelRegistryService#promote}'s gate exactly (starting a training run is a
 * privileged control-plane action, the same footing as promoting a model). {@link #jobs()}/{@link
 * #job(String)} are unscoped, unaudited reads — any authenticated caller may poll a job's
 * progress, mirroring {@code ModelRegistryService#models()}'s "any authenticated caller may read"
 * precedent.
 */
public interface TrainingJobService {

    /**
     * Starts a fine-tune job off-thread and returns immediately with the id a poller uses to track
     * it — never blocks on the (potentially long-running, multi-epoch) training stream itself. The
     * job's dataset is uploaded to the training host as part of the off-thread run (docs/plans/done/CV-TRAINING-V2-PLAN.md
     * §4) — see {@link DefaultTrainingJobService}'s own javadoc for the upload-then-train sequence
     * and its synchronous pre-check.
     *
     * @param spec  the job to run
     * @param actor who is starting it, for the audit trail
     * @param scope the acting user's visibility; must satisfy {@link VisibilityScope#canManageOrg()}
     * @return the locally generated job id; poll it via {@link #job(String)}
     * @throws com.drones.vision.identity.application.scope.AccessDeniedException             if {@code scope} may not manage the organization
     *                                             (audited as a denial before this method throws),
     *                                             or the job's dataset is outside {@code scope}
     * @throws java.util.NoSuchElementException  if the job's dataset is unknown
     * @throws IllegalArgumentException          if the dataset id is malformed, or the dataset has
     *                                             no {@link com.drones.vision.learning.domain.model.SampleStatus#LABELED}
     *                                             samples to train on — in every one of these cases
     *                                             the job is never registered or started
     */
    String start(TrainingJobSpec spec, UserId actor, VisibilityScope scope);

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
}
