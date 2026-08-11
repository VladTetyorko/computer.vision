package com.drones.vision.api.controller;

import com.drones.vision.api.dto.SimulationResponse;
import com.drones.vision.api.dto.StartSimulationRequest;
import com.drones.vision.api.exception.ApiExceptionHandler;
import com.drones.vision.application.simulation.SimulatedAsset;
import com.drones.vision.application.simulation.SimulationService;
import com.drones.vision.domain.model.AssetId;
import com.drones.vision.domain.model.StreamId;
import com.drones.vision.domain.port.out.StreamPublisherPort;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.Objects;
import com.drones.vision.api.security.CurrentUser;

/**
 * Driving REST adapter for the one-call, zero-hardware simulation entry point
 * (docs/main/CYCLES-PLAN.md §0-1): a video file path in, a registered, categorized, optionally
 * already-streaming asset out.
 *
 * <p>Constructor-injected with {@link SimulationService}, {@link CurrentUser}, and one driven port
 * used read-only — {@link StreamPublisherPort}, to resolve {@code viewUrl}/{@code whepUrl} exactly
 * like {@link AssetController}/{@link StreamController} already do. Per the hexagonal dependency rule
 * (ARCHITECTURE.md §2, enforced by ArchUnit), this module depends only on {@code vision-domain}
 * and {@code vision-application} — never on an adapter.
 *
 * <h2>Who the change is attributed to</h2>
 * The acting user comes from {@link CurrentUser}, mirroring {@link AssetController}.
 *
 * <h2>Status codes</h2>
 * A bad {@code videoPath} (failing {@code SimulationService}'s filesystem checks — missing, not a
 * regular file, unreadable), an unrecognized {@code transport} name, a {@code null}/blank {@code
 * videoPath} combined with a non-{@code direct} {@code transport} (docs/main/CYCLES-PLAN.md §9, CU-a —
 * a synthetic simulation has no in-process renderer output to push over the wire), or
 * (docs/main/CYCLES-PLAN.md §3, §5) a {@code transport=rtsp}/{@code mjpeg} request no registered {@code
 * FeedTransmitterPort} supports, all surface as {@link IllegalArgumentException} → 400; an
 * unseeded {@code simulated} category surfaces as {@link IllegalStateException} → 409 — all via
 * {@link ApiExceptionHandler}, the same mapping every other controller here relies on. {@link
 * #stop} is idempotent and a malformed {@code assetId} (via {@code AssetId#of}) is the same 400.
 *
 * <p>A {@code null}/blank {@code videoPath} with the default {@code direct} transport is not an
 * error (docs/main/CYCLES-PLAN.md §9, CU-a): {@code POST /api/simulations {}} yields a fully synthetic,
 * moving simulated drone — no video file required.
 */
@RestController
public class SimulationController {

    private final SimulationService simulationService;
    private final CurrentUser currentUser;
    private final StreamPublisherPort streamPublisherPort;

    public SimulationController(SimulationService simulationService, CurrentUser currentUser,
                                 StreamPublisherPort streamPublisherPort) {
        this.simulationService = Objects.requireNonNull(simulationService, "simulationService must not be null");
        this.currentUser = Objects.requireNonNull(currentUser, "currentUser must not be null");
        this.streamPublisherPort =
                Objects.requireNonNull(streamPublisherPort, "streamPublisherPort must not be null");
    }

    /**
     * Creates a simulated asset from a video file, optionally starting it immediately
     * ({@link StartSimulationRequest#autoStart()} defaults to {@code true}).
     *
     * @param request the video file and home point to simulate
     * @return the created asset's id, and — if streaming — its stream id and viewer URLs
     */
    @PostMapping("/api/simulations")
    @ResponseStatus(HttpStatus.CREATED)
    public SimulationResponse simulate(@RequestBody StartSimulationRequest request) {
        SimulatedAsset simulated =
                simulationService.simulate(request.toSpec(), currentUser.ownership(), currentUser.userId());
        StreamId streamId = simulated.streamId();
        return new SimulationResponse(simulated.assetId().value().toString(),
                streamId == null ? null : streamId.value().toString(),
                streamId == null ? null : viewUrl(streamId),
                streamId == null ? null : whepUrl(streamId));
    }

    private String viewUrl(StreamId streamId) {
        return streamPublisherPort.viewUrl(streamId).map(URI::toString).orElse(null);
    }

    private String whepUrl(StreamId streamId) {
        return streamPublisherPort.whepUrl(streamId).map(URI::toString).orElse(null);
    }

    /**
     * Stops a simulated asset's stream and, for a wired-transport ({@code rtsp}/{@code mjpeg})
     * simulation, its transmitted feed too (docs/main/CYCLES-PLAN.md §3, §5) — idempotent, mirroring {@code
     * AssetController}'s {@code DELETE /api/assets/{id}/stream}: an unknown or already-stopped
     * asset is still a 204, not a 404.
     *
     * @param assetId canonical UUID string of the asset to stop
     */
    @DeleteMapping("/api/simulations/{assetId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void stop(@PathVariable String assetId) {
        simulationService.stop(AssetId.of(assetId));
    }
}
