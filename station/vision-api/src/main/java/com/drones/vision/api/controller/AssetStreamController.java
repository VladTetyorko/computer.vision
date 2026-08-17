package com.drones.vision.api.controller;

import com.drones.vision.api.dto.StartAssetStreamRequest;
import com.drones.vision.api.dto.StartStreamResponse;
import com.drones.vision.api.exception.ApiExceptionHandler;
import com.drones.vision.api.support.StreamViewerLinks;
import com.drones.vision.warehouse.application.asset.AssetService;
import com.drones.vision.perception.application.stream.AssetStreamService;
import com.drones.vision.perception.application.stream.StreamService;
import com.drones.vision.perception.application.stream.TrackingConfigPatch;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.DeviceId;
import com.drones.vision.perception.domain.model.PipelineConfig;
import com.drones.vision.kernel.StreamId;
import com.drones.vision.perception.domain.port.StreamPublisherPort;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;
import com.drones.vision.api.security.CurrentUser;

/**
 * Driving REST adapter for asset-level stream lifecycle: {@code POST}/{@code DELETE
 * /api/assets/{id}/stream}.
 *
 * <p>Split off {@link AssetController} in docs/plans/active/DOMAIN-SEPARATION-W1.md §15, W1.6e:
 * that wave moved stream-starting off {@code AssetService} onto {@link AssetStreamService} (the
 * CRUD context should not import the runtime's config types into its own published interface —
 * see {@code AssetStreamService}'s own javadoc, vision-application). Adding {@link
 * AssetStreamService} as a sixth {@link AssetController} constructor parameter would have broken
 * this codebase's five-parameter ceiling (.claude/skills/java-clean-code/SKILL.md §3) — the same
 * reasoning that already split {@link AssetStatsController} off. {@code DELETE .../stream}
 * (stopping) moves here too, purely so the two halves of one sub-resource's lifecycle stay on one
 * controller — {@link AssetService#stopStream} itself did not move and is unaffected.
 *
 * <h2>docs/plans/active/CV-DEMAND-PLAN.md §3.8 — one more collaborator, no more slots</h2>
 * {@link #startStream} needed a deployment-default {@link PipelineConfig} to merge request
 * overrides onto (the same change {@link StreamController#start} got), but this constructor was
 * already at this codebase's five-parameter ceiling. {@link StreamPublisherPort}/{@link
 * StreamService} — here only to resolve {@code viewUrl}/{@code whepUrl}/{@code burnedIn} for the
 * response — are replaced by {@link StreamViewerLinks}, which wraps exactly those three reads
 * behind one collaborator; that frees the slot {@code defaultConfig} needed. {@link StreamService}
 * itself stays a direct dependency of {@link StreamController} (it does far more there than these
 * three reads), so this narrowing is specific to this controller's own, smaller surface.
 *
 * <h2>Who the change is attributed to</h2>
 * The acting user comes from {@link CurrentUser}, matching {@link AssetController}'s own
 * convention.
 *
 * <h2>Visibility scoping</h2>
 * {@link #startStream} re-reads the asset through {@link CurrentUser#scope()} before mutating —
 * the same "read-scope guards the write" posture {@link AssetController} documents at length.
 * {@link #stopStream} is unscoped, mirroring {@link AssetService#stopStream}'s own no-op-for-
 * unknown-asset contract (there is nothing to hide — an out-of-scope caller stopping a stream they
 * cannot otherwise see is not yet gated, matching {@code AssetController}'s pre-existing posture
 * for this same endpoint before the split).
 *
 * <h2>Status codes</h2>
 * An unknown/out-of-scope asset surfaces as {@link java.util.NoSuchElementException} from {@link
 * AssetService#details(com.drones.vision.platform.VisibilityScope, AssetId)} and maps to 404
 * through {@link ApiExceptionHandler}; a malformed UUID fails earlier in {@code AssetId.of}/{@code
 * DeviceId.of} and maps to 400, as do genuine validation failures such as an ambiguous device or a
 * device that does not belong to the asset.
 */
@RestController
public class AssetStreamController {

    private final AssetService assetService;
    private final AssetStreamService assetStreamService;
    private final CurrentUser currentUser;
    private final StreamViewerLinks streamViewerLinks;
    /** The deployment's default {@link PipelineConfig} for a newly started stream (docs/plans/active/CV-DEMAND-PLAN.md §3.7/§3.8). */
    private final PipelineConfig defaultConfig;

    public AssetStreamController(AssetService assetService, AssetStreamService assetStreamService,
                                  CurrentUser currentUser, StreamViewerLinks streamViewerLinks,
                                  PipelineConfig defaultConfig) {
        this.assetService = Objects.requireNonNull(assetService, "assetService must not be null");
        this.assetStreamService = Objects.requireNonNull(assetStreamService, "assetStreamService must not be null");
        this.currentUser = Objects.requireNonNull(currentUser, "currentUser must not be null");
        this.streamViewerLinks = Objects.requireNonNull(streamViewerLinks, "streamViewerLinks must not be null");
        this.defaultConfig = Objects.requireNonNull(defaultConfig, "defaultConfig must not be null");
    }

    /**
     * Starts a stream for one of the asset's devices. The optional request
     * body's fields, if present, override the corresponding defaults (see
     * {@link StartAssetStreamRequest}); {@code deviceId} absent means "the
     * asset's single video-capable device".
     *
     * <p>What the body says about {@code tracking} travels as its own patch, folded onto the
     * deployment's tracking seed inside the application layer — the same path {@code
     * StreamController#start} and the simulation service take, so the deployment default never
     * depends on which button the operator pressed (docs/extracts/TRACKING-ORCHESTRATION.md §4.1).
     *
     * @param id      the asset to stream from, as a canonical UUID string
     * @param request optional overrides; {@code null}/absent means use every default
     * @return the started stream's id and (if available) its viewer URLs
     */
    @PostMapping("/api/assets/{id}/stream")
    @ResponseStatus(HttpStatus.CREATED)
    public StartStreamResponse startStream(@PathVariable String id,
                                            @RequestBody(required = false) StartAssetStreamRequest request) {
        AssetId assetId = AssetId.of(id);
        StartAssetStreamRequest effective = request == null ? StartAssetStreamRequest.EMPTY : request;
        DeviceId device = effective.deviceIdOrNull(); // malformed device id / config is a 400, before the scope 404
        PipelineConfig config = effective.mergeOnto(defaultConfig);
        TrackingConfigPatch tracking = effective.trackingPatch();
        requireInScope(assetId);

        StreamId streamId = assetStreamService.startStream(assetId, device, config, tracking);
        return new StartStreamResponse(streamId.value().toString(), streamViewerLinks.viewUrl(streamId),
                streamViewerLinks.whepUrl(streamId), streamViewerLinks.burnedIn(streamId));
    }

    /**
     * Stops the asset's active stream(s), if any. Idempotent — an asset with
     * no active stream (or an unknown asset id) is a no-op, mirroring {@link
     * AssetService#stopStream(AssetId)}'s contract, so there is no 404 case
     * here.
     *
     * @param id the asset to stop streaming, as a canonical UUID string
     */
    @DeleteMapping("/api/assets/{id}/stream")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void stopStream(@PathVariable String id) {
        assetService.stopStream(AssetId.of(id));
    }

    /**
     * Guards {@link #startStream}: re-reads {@code id} through the caller's scope so an
     * out-of-scope (or unknown) asset 404s ({@link java.util.NoSuchElementException}) before the
     * mutation runs. Cheap — the same scoped read {@link AssetController#details} does — and the
     * deliberate write-path posture until the asset services take a {@code VisibilityScope} on
     * writes directly (see {@link AssetController}'s own class javadoc).
     */
    private void requireInScope(AssetId id) {
        assetService.details(currentUser.scope(), id);
    }
}
