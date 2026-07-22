package com.drones.vision.application;

import com.drones.vision.domain.model.AssetId;
import com.drones.vision.domain.model.Ownership;
import com.drones.vision.domain.model.UserId;

/**
 * The one-call, zero-hardware simulation entry point (docs/CYCLES-PLAN.md §0-1): a video file path
 * in, a registered, categorized, optionally already-streaming asset out.
 *
 * <p>One interface, one implementation ({@link DefaultSimulationService}) — see
 * {@code .claude/skills/java-clean-code/SKILL.md}. Ownership is derived from the acting user at
 * call time, never injected at construction, mirroring {@link AssetService}.
 *
 * <h2>Threading</h2>
 * Implementations must be safe for concurrent use; all shared state lives behind driven ports.
 */
public interface SimulationService {

    /**
     * Registers a simulated asset backed by a local video file, playing it back as a live
     * {@code "file"}-protocol video device paired with a synthetic {@code "sim"}-protocol
     * telemetry device on a circular track around the given home point.
     *
     * @param spec      the video file and home point to simulate
     * @param ownership who will own the created asset
     * @param actor     the user performing the simulation
     * @return the created asset's id, and (if {@code spec.autoStart()}) its started stream's id
     * @throws IllegalArgumentException if {@code spec.videoPath()} does not exist, is not a
     *                                   regular file, or is not readable, or if {@code
     *                                   spec.transport()} is {@link SimulationTransport#RTSP} and
     *                                   no registered {@code FeedTransmitterPort} supports it
     * @throws IllegalStateException    if the {@code simulated} category is not seeded
     */
    SimulatedAsset simulate(SimulationSpec spec, Ownership ownership, UserId actor);

    /**
     * Stops a simulated asset's stream and, if it was created with {@link
     * SimulationTransport#RTSP}, its transmitted feed too — docs/CYCLES-PLAN.md §3's TX-side
     * teardown.
     *
     * <p>Idempotent and tolerant of an unknown asset, mirroring {@link AssetService#stopStream(AssetId)}'s
     * no-op semantics: calling this twice, or for an asset that was never simulated (or never had
     * {@code RTSP} transport), is always safe.
     *
     * @param assetId the simulated asset to stop
     */
    void stop(AssetId assetId);
}
