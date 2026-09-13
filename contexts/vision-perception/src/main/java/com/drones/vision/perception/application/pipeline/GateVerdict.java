package com.drones.vision.perception.application.pipeline;

/**
 * What {@link DetectionGate#classify} decided for one sampled frame (docs/plans/active/
 * CV-ORCHESTRATION-PLAN.md &sect;4.9/K3, wave W8.2) — the sealed replacement for {@code
 * StreamPipeline#maybeDetect}'s inline three-way {@code switch}. {@link StreamPipeline} turns a
 * verdict into exactly the same side effects {@code maybeDetect} used to inline: recording on
 * {@link PipelineTrace} for a {@link Skip}, or {@link StreamPipeline#submitDetection} for a {@link
 * Send} — see {@link DetectionGate#classify}'s own javadoc for why some deadlines produce no
 * verdict at all.
 */
sealed interface GateVerdict {

    /** Submit {@code frame} to the detector — a normal sample, or an outage-recovery probe. */
    record Send(boolean probe, DemandSnapshot demand) implements GateVerdict {
    }

    /** Nothing sent this deadline; {@code reason} is one of the six {@link GateReason}s {@link DetectionGate} can produce. */
    record Skip(GateReason reason, DemandSnapshot demand) implements GateVerdict {
    }
}
