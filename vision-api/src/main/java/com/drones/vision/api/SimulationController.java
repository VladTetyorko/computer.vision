package com.drones.vision.api;

import com.drones.vision.api.dto.SimulationResponse;
import com.drones.vision.api.dto.StartSimulationRequest;
import com.drones.vision.application.SimulatedAsset;
import com.drones.vision.application.SimulationService;
import com.drones.vision.domain.model.StreamId;
import com.drones.vision.domain.port.out.StreamPublisherPort;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.Objects;

/**
 * Driving REST adapter for the one-call, zero-hardware simulation entry point
 * (docs/CYCLES-PLAN.md §0-1): a video file path in, a registered, categorized, optionally
 * already-streaming asset out.
 *
 * <p>Constructor-injected with {@link SimulationService}, {@link CurrentUser}, and one driven port
 * used read-only — {@link StreamPublisherPort}, to resolve {@code viewUrl} exactly like {@link
 * AssetController}/{@link StreamController} already do. Per the hexagonal dependency rule
 * (ARCHITECTURE.md §2, enforced by ArchUnit), this module depends only on {@code vision-domain}
 * and {@code vision-application} — never on an adapter.
 *
 * <h2>Who the change is attributed to</h2>
 * The acting user comes from {@link CurrentUser}, mirroring {@link AssetController}.
 *
 * <h2>Status codes</h2>
 * A bad {@code videoPath} (blank, or failing {@code SimulationService}'s filesystem checks —
 * missing, not a regular file, unreadable) surfaces as {@link IllegalArgumentException} → 400; an
 * unseeded {@code simulated} category surfaces as {@link IllegalStateException} → 409 — both via
 * {@link ApiExceptionHandler}, the same mapping every other controller here relies on.
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
     * @return the created asset's id, and — if streaming — its stream id and viewer URL
     */
    @PostMapping("/api/simulations")
    @ResponseStatus(HttpStatus.CREATED)
    public SimulationResponse simulate(@RequestBody StartSimulationRequest request) {
        SimulatedAsset simulated =
                simulationService.simulate(request.toSpec(), currentUser.ownership(), currentUser.userId());
        StreamId streamId = simulated.streamId();
        return new SimulationResponse(simulated.assetId().value().toString(),
                streamId == null ? null : streamId.value().toString(),
                streamId == null ? null : viewUrl(streamId));
    }

    private String viewUrl(StreamId streamId) {
        return streamPublisherPort.viewUrl(streamId).map(URI::toString).orElse(null);
    }
}
