package com.drones.vision.domain.port.out;

import com.drones.vision.domain.model.JobState;
import com.drones.vision.domain.model.TrainingJobSpec;
import com.drones.vision.domain.model.TrainingProgress;

import java.util.function.Consumer;

/**
 * Driven port: run a CV model fine-tune and observe its progress (docs/plans/done/CV-TRAINING-PLAN.md §6/§7,
 * Phase 2) — the Java side of {@code cv.proto}'s {@code Training.StartTraining} server-streaming
 * RPC. Optional, GPU-training-host-only in production (never the GB4005 inference box — see the
 * plan's "constraint that shapes everything"); a default/offline implementation may simply refuse.
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>{@link #startTraining(TrainingJobSpec, Consumer)} blocks, consuming the underlying gRPC
 *       stream and invoking {@code onProgress} once for every {@link TrainingProgress} message as
 *       it arrives, until the stream terminates: either a terminal {@link JobState#SUCCEEDED}/
 *       {@link JobState#FAILED} progress is delivered, or the call returns/throws because the
 *       transport itself ended (an implementation surfaces an unrecoverable transport error as a
 *       thrown exception, not as a synthesized terminal progress).</li>
 *   <li>Cancellation ends the stream with no terminal message — {@code onProgress} simply stops
 *       being called and {@code startTraining} returns; a caller cannot distinguish "cancelled" from
 *       "the stream closed early" purely from this port and must not assume either terminal state
 *       was reached unless it actually observed one.</li>
 * </ul>
 *
 * <h2>Threading</h2>
 * This method blocks for the lifetime of a (potentially long-running, multi-epoch) job — the caller
 * (an application-layer service) is responsible for running it on its own executor, not the calling
 * thread. {@code onProgress} is invoked synchronously from whatever thread is consuming the stream.
 * Implementations must be safe to call concurrently for different jobs (a training host may run more
 * than one job at a time); a single {@code startTraining} call is not itself meant to be invoked
 * concurrently for the same job.
 *
 * <p>This deliberately mirrors a callback shape rather than returning a {@code Stream}/{@code
 * Iterator} of progress messages, keeping this module free of any streaming/reactive framework type.
 */
public interface TrainingPort {

    /**
     * Starts a fine-tune job and blocks, delivering every {@link TrainingProgress} message to
     * {@code onProgress} until the stream terminates. See the type-level Contract/Threading notes.
     *
     * @param spec       the job to run
     * @param onProgress invoked for every progress message as it arrives
     */
    void startTraining(TrainingJobSpec spec, Consumer<TrainingProgress> onProgress);
}
