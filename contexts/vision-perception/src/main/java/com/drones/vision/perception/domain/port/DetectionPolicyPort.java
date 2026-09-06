package com.drones.vision.perception.domain.port;

import com.drones.vision.kernel.AssetId;

/**
 * Driven port: whether an asset has opted into {@code DetectionPolicy.ALWAYS}
 * (docs/plans/active/ALWAYS-ON-FLOW-PLAN.md wave D1) — the third, independent OR-term {@code
 * StreamPipeline} widens its inference gate with, alongside {@link DetectionDemandPort}'s
 * viewer-shaped demand. Deliberately a separate port rather than a fourth OR-term folded into
 * {@link DetectionDemandPort#detectionWanted}: that port's result also drives {@code
 * StreamPipeline}'s <em>live</em> gate (docs/plans/active/ALWAYS-ON-FLOW-PLAN.md &sect;4's "three
 * questions, not two"), and folding policy into it would make an {@code ALWAYS} asset's live gate
 * unable to ever close for lack of a viewer — the exact regression that section warns against.
 * Keeping this a separate fact lets {@code StreamPipeline} OR it only into the wider inference
 * gate, leaving the narrower live gate reading {@link DetectionDemandPort} alone, unchanged.
 *
 * <h2>Contract</h2>
 * Cheap and non-blocking — an in-memory read at most, never network I/O, exactly like {@link
 * DetectionDemandPort}'s own contract. Must not throw: the caller ({@code DefaultStreamService}'s
 * demand-poll task) wraps every call in its own {@code catch (Throwable)} regardless, but an
 * implementation that answers {@code false} instead of throwing keeps that safety net reserved for
 * genuine bugs. Unlike {@link DetectionDemandPort}, an implementation must <b>fail closed</b>: a
 * lookup that cannot determine the asset's policy (unknown asset, a stale/unrefreshed cache, a
 * refresh failure) must answer {@code false} (equivalent to {@code DetectionPolicy.ON_VIEW}), never
 * {@code true} — a failure here must not silently grant an asset free, uncapped, always-on
 * inference against the ceiling docs/plans/active/ALWAYS-ON-FLOW-PLAN.md &sect;3 documents. This is
 * the opposite fail direction from {@link DetectionDemandPort#detectionWanted}, deliberately: that
 * port protects detection that is running <em>today</em> from stopping; this one protects against a
 * new, capacity-costly behavior turning on by accident.
 *
 * <h2>Threading</h2>
 * Called from one dedicated scheduler thread, on a fixed poll interval, never from the video or
 * detection hot path — the same threading contract {@link DetectionDemandPort} states.
 */
public interface DetectionPolicyPort {

    /**
     * Whether {@code assetId} has opted into {@code DetectionPolicy.ALWAYS}.
     *
     * @param assetId the asset to check, or {@code null} for a device-only stream with no resolved
     *                 asset — always {@code false} in that case, since the policy attribute lives on
     *                 the asset
     * @return {@code true} iff this asset's stored policy is {@code ALWAYS}
     */
    boolean alwaysOn(AssetId assetId);
}
