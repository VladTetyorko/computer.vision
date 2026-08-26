package com.drones.vision.simulation.application;

import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.Capability;
import com.drones.vision.kernel.Ownership;
import com.drones.vision.kernel.UserId;
import com.drones.vision.warehouse.domain.model.Device;

import java.util.List;

/**
 * The one-call, zero-hardware simulation entry point (docs/main/CYCLES-PLAN.md §0-1): a video file path
 * in, a registered, categorized, optionally already-streaming asset out.
 *
 * <p>One interface, one implementation ({@link DefaultSimulationService}) — see
 * {@code .claude/skills/java-clean-code/SKILL.md}. Ownership is derived from the acting user at
 * call time, never injected at construction, mirroring {@link com.drones.vision.warehouse.application.asset.AssetService}.
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
     */
    SimulatedAsset simulate(SimulationSpec spec, Ownership ownership, UserId actor);

    /**
     * Fits a synthetic device onto an <em>existing</em> asset — the "the drone has no camera yet"
     * case {@link #simulate} cannot cover, since that always creates a whole new asset
     * (docs/plans/active/ARCHITECTURE-AUDIT-2026-08-26.md R4). The device is registered with {@link
     * com.drones.vision.kernel.DeviceOrigin#SIMULATED} and immediately assigned to {@code assetId},
     * so a real vehicle with a missing sensor can be exercised end-to-end before the real one
     * arrives, without pretending the whole vehicle is synthetic.
     *
     * <p>Always an in-process {@code "sim"}-protocol device — never a wired transport ({@link
     * SimulationTransport#RTSP}/{@link SimulationTransport#MJPEG}): those are demo-grade (ephemeral
     * ports that do not survive a restart, see {@link #resumeAll}'s own "MJPEG is never a candidate"
     * paragraph), which makes them a poor fit for a fitting meant to sit on an otherwise-permanent
     * asset indefinitely.
     *
     * @param assetId    the existing asset to fit the device onto
     * @param capability what the fitted device exposes (e.g. {@link Capability#VIDEO})
     * @param actor      the user performing the fitting
     * @return the newly registered, now-assigned device
     * @throws java.util.NoSuchElementException if no asset has {@code assetId}
     */
    Device fitSimulatedDevice(AssetId assetId, Capability capability, UserId actor);

    /**
     * Stops a simulated asset's stream and, if it was created with {@link
     * SimulationTransport#RTSP}, its transmitted feed too — docs/main/CYCLES-PLAN.md §3's TX-side
     * teardown.
     *
     * <p>Idempotent and tolerant of an unknown asset, mirroring {@link
     * com.drones.vision.warehouse.application.asset.AssetService#stopStream(AssetId)}'s
     * no-op semantics: calling this twice, or for an asset that was never simulated (or never had
     * {@code RTSP} transport), is always safe.
     *
     * @param assetId the simulated asset to stop
     */
    void stop(AssetId assetId);

    /**
     * Restarts the TX feed for every persisted, {@code ACTIVE} asset with a {@link
     * com.drones.vision.kernel.DeviceOrigin#SIMULATED} video device that is one of this app's own
     * {@link SimulationTransport#RTSP} feeds — recognized structurally (its {@code rtsp} device
     * URI's host:port matches this app's own configured mediamtx push target). Checked over every
     * asset regardless of category, not only ones created by {@link #simulate}: {@link
     * #fitSimulatedDevice} can fit a simulated device onto any real asset, so origin — not category
     * — is what distinguishes "one of our own TX-fed simulation feeds" from "a real external RTSP
     * camera".
     *
     * <p>A feed's TX side (the transmit thread pushing a local file to mediamtx) is pure in-process
     * runtime state — nothing durable backs it, so it never survives a JVM restart on its own: the
     * asset/device rows come back exactly as they were (Postgres-backed, unconditionally — see
     * docs/plans/done/POSTGRES-ONLY-CONTEXT.md W2b), but their {@code rtsp} URI points at a path
     * mediamtx has no publisher for anymore, 404-ing forever. Intended to be called once, at boot,
     * after the fleet has been restored from storage but before real traffic (see {@code
     * vision-app}'s {@code ApplicationRunner}, gated on {@code vision.simulation.resume-on-boot}).
     *
     * <p><b>{@code transport=MJPEG} is never a candidate</b>: its TX side serves its own ephemeral
     * HTTP port, freshly randomized on every JVM start, so a persisted {@code mjpeg} device's URI
     * is already permanently stale after any restart — there is no stable target to resume it
     * against, only a brand-new {@link #simulate} call could give it a valid one again.
     *
     * <p>Idempotent: an asset already tracked (resumed earlier in this same process run, or
     * currently simulated) is skipped without restarting its feed a second time. Never throws for
     * one bad asset — an unreadable/missing source file, an unparseable feed id, or no transmitter
     * supporting the rebuilt feed is logged and skipped, so one broken asset never stops the rest of
     * the fleet from resuming.
     *
     * @return the ids of the assets whose feed was actually restarted (a subset of every asset
     *         present — most are skipped, ordinarily for the entirely unremarkable reason that they
     *         were never a simulated RTSP feed in the first place)
     */
    List<AssetId> resumeAll();
}
