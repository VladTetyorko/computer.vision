package com.drones.vision.api.controller;

import com.drones.vision.api.dto.SimulationResponse;
import com.drones.vision.api.dto.StartSimulationRequest;
import com.drones.vision.api.exception.ApiExceptionHandler;
import com.drones.vision.platform.AccessDeniedException;
import com.drones.vision.simulation.application.SimulatedAsset;
import com.drones.vision.simulation.application.SimulationService;
import com.drones.vision.warehouse.application.asset.AssetService;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.StreamId;
import com.drones.vision.perception.domain.port.StreamPublisherPort;
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
 * <h2>Authority (docs/plans/active/LIVE-SCOPE-PLAN.md §2, W2)</h2>
 * {@link #simulate} registers a brand-new asset exactly like {@link AssetController#create} does —
 * it gates on the same {@link com.drones.vision.platform.VisibilityScope#canManageOrg()
 * scope().canManageOrg()}, closing an asymmetry the LIVE-SCOPE audit found: this sibling endpoint
 * had no gate at all, so any authenticated caller (including a PILOT) could register and
 * auto-start a fleet asset. {@link #stop} is now scoped — "may this caller touch this asset at
 * all", the same visibility question {@link AssetStreamController#stopStream} answers for its own
 * stop endpoint, <b>not</b> an exclusive-claim/arbitration question: two authorized operators
 * contending for one simulated aircraft is deliberately out of scope here and stays with
 * {@code CREW-CONTROL-PLAN.md} §2.6/§4.5.
 *
 * <h2>Status codes</h2>
 * A bad {@code videoPath} (failing {@code SimulationService}'s filesystem checks — missing, not a
 * regular file, unreadable), an unrecognized {@code transport} name, a {@code null}/blank {@code
 * videoPath} combined with a non-{@code direct} {@code transport} (docs/main/CYCLES-PLAN.md §9, CU-a —
 * a synthetic simulation has no in-process renderer output to push over the wire), or
 * (docs/main/CYCLES-PLAN.md §3, §5) a {@code transport=rtsp}/{@code mjpeg} request no registered {@code
 * FeedTransmitterPort} supports, all surface as {@link IllegalArgumentException} → 400; an
 * unseeded {@code simulated} category surfaces as {@link IllegalStateException} → 409; a caller
 * whose scope may not {@code canManageOrg()} surfaces as {@link AccessDeniedException} → 403 — all
 * via {@link ApiExceptionHandler}, the same mapping every other controller here relies on. {@link
 * #stop} is idempotent for a visible asset and a malformed {@code assetId} (via {@code
 * AssetId#of}) is the same 400 as before; an out-of-scope or unknown asset now 404s instead.
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
    /** Used only for {@link #stop}'s scoped read (docs/plans/active/LIVE-SCOPE-PLAN.md §2, W2) — the
     * same "re-read through scope before mutating" idiom {@link AssetStreamController} uses. */
    private final AssetService assetService;

    public SimulationController(SimulationService simulationService, CurrentUser currentUser,
                                 StreamPublisherPort streamPublisherPort, AssetService assetService) {
        this.simulationService = Objects.requireNonNull(simulationService, "simulationService must not be null");
        this.currentUser = Objects.requireNonNull(currentUser, "currentUser must not be null");
        this.streamPublisherPort =
                Objects.requireNonNull(streamPublisherPort, "streamPublisherPort must not be null");
        this.assetService = Objects.requireNonNull(assetService, "assetService must not be null");
    }

    /**
     * Creates a simulated asset from a video file, optionally starting it immediately
     * ({@link StartSimulationRequest#autoStart()} defaults to {@code true}).
     *
     * @param request the video file and home point to simulate
     * @return the created asset's id, and — if streaming — its stream id and viewer URLs
     * @throws AccessDeniedException if the caller's scope may not {@code canManageOrg()}
     *                                (docs/plans/active/LIVE-SCOPE-PLAN.md §2, W2 — closes the
     *                                asymmetry with {@link AssetController#create}'s own gate)
     */
    @PostMapping("/api/simulations")
    @ResponseStatus(HttpStatus.CREATED)
    public SimulationResponse simulate(@RequestBody StartSimulationRequest request) {
        if (!currentUser.scope().canManageOrg()) {
            throw new AccessDeniedException("Not permitted to register new assets");
        }
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
     * simulation, its transmitted feed too (docs/main/CYCLES-PLAN.md §3, §5) — idempotent for an
     * asset the caller's scope may reach: an already-stopped asset is still a 204. An out-of-scope
     * or unknown asset now 404s instead (docs/plans/active/LIVE-SCOPE-PLAN.md §2, W2) — scoped to
     * "may this caller touch this asset at all", deliberately not an exclusive-claim check; see
     * this class's own "Authority" section for the CREW-CONTROL boundary.
     *
     * @param assetId canonical UUID string of the asset to stop
     * @throws java.util.NoSuchElementException if the caller's scope may not reach this asset
     */
    @DeleteMapping("/api/simulations/{assetId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void stop(@PathVariable String assetId) {
        AssetId id = AssetId.of(assetId);
        assetService.details(currentUser.scope(), id);
        simulationService.stop(id);
    }
}
