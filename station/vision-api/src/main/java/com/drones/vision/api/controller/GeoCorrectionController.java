package com.drones.vision.api.controller;

import com.drones.vision.api.dto.CorrectionListResponse;
import com.drones.vision.api.dto.CorrectionResponse;
import com.drones.vision.api.security.CurrentUser;
import com.drones.vision.api.support.VisualGeoProperties;
import com.drones.vision.flight.application.TrackCorrectionService;
import com.drones.vision.flight.domain.model.TrackCorrection;
import com.drones.vision.kernel.UsageId;
import com.drones.vision.platform.VisibilityScope;
import com.drones.vision.warehouse.application.asset.AssetService;
import com.drones.vision.warehouse.domain.model.AssetUsage;
import com.drones.vision.warehouse.domain.port.AssetUsageRepositoryPort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;

/**
 * Driving REST adapter for {@code /api/geo/corrections} (docs/plans/active/VISUAL-GEO-V2-PLAN.md
 * §3.3, H5) — the corrected track {@link TrackCorrectionService} produces, read back for the live
 * cockpit chip ({@link #live()}) and for replay ({@link #forUsage}).
 *
 * <h2>Flag gate (D9)</h2>
 * Both methods call {@link VisualGeoProperties#requireEnabled()} first — while {@code
 * vision.geo.visual.enabled=false} every route answers {@code 409} with the frozen D9 body.
 *
 * <h2>Visibility scoping — GROUPS enforced here, not in the application layer</h2>
 * {@code DefaultTrackCorrectionService#isVisible} only enforces {@code UNBOUNDED}/{@code
 * ASSIGNED_ASSETS} internally; a {@code GROUPS} scope is deferred entirely to this edge (the same
 * amendment {@code contexts/vision-flight}'s H2a wave documented). {@link #live()} therefore starts
 * from {@link AssetService#assets(VisibilityScope, boolean)} — which does apply {@code GROUPS}
 * filtering — and reads one correction per already-scoped asset, rather than asking the service for
 * "every asset's latest" unscoped. {@link #forUsage} resolves the usage's owning asset and calls
 * {@link AssetService#details(VisibilityScope, com.drones.vision.kernel.AssetId)} purely as a scope
 * guard (throws {@link NoSuchElementException} → 404 for an unknown or out-of-scope usage, the same
 * "hide, don't 403" rule every other scoped read in this codebase follows) before delegating to
 * {@link TrackCorrectionService#forUsage}.
 */
@RestController
@RequestMapping("/api/geo/corrections")
public class GeoCorrectionController {

    /** Default {@code limit} for {@link #forUsage}, matching the plan's own example URL. */
    static final int DEFAULT_LIMIT = 2000;
    /** §3.3's frozen ceiling: {@code limit outside [1,10000]} is a {@code 400}. */
    static final int MAX_LIMIT = 10_000;

    private final TrackCorrectionService trackCorrectionService;
    private final AssetService assetService;
    private final AssetUsageRepositoryPort assetUsageRepositoryPort;
    private final CurrentUser currentUser;
    private final VisualGeoProperties properties;

    public GeoCorrectionController(TrackCorrectionService trackCorrectionService, AssetService assetService,
                                    AssetUsageRepositoryPort assetUsageRepositoryPort, CurrentUser currentUser,
                                    VisualGeoProperties properties) {
        this.trackCorrectionService =
                Objects.requireNonNull(trackCorrectionService, "trackCorrectionService must not be null");
        this.assetService = Objects.requireNonNull(assetService, "assetService must not be null");
        this.assetUsageRepositoryPort =
                Objects.requireNonNull(assetUsageRepositoryPort, "assetUsageRepositoryPort must not be null");
        this.currentUser = Objects.requireNonNull(currentUser, "currentUser must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
    }

    /** {@code GET /api/geo/corrections/live} — the latest correction per asset the viewer may see. */
    @GetMapping("/live")
    public CorrectionListResponse live() {
        properties.requireEnabled();
        VisibilityScope scope = currentUser.scope();
        List<CorrectionResponse> corrections = assetService.assets(scope, false).stream()
                .map(summary -> trackCorrectionService.latest(summary.asset().id(), scope))
                .flatMap(Optional::stream)
                .map(CorrectionResponse::from)
                .toList();
        return new CorrectionListResponse(corrections);
    }

    /** {@code GET /api/geo/corrections?usageId=&limit=} — one usage's corrections, oldest to newest. */
    @GetMapping
    public CorrectionListResponse forUsage(@RequestParam String usageId,
                                            @RequestParam(defaultValue = "" + DEFAULT_LIMIT) int limit) {
        properties.requireEnabled();
        requireValidLimit(limit);
        UsageId id = UsageId.of(usageId);
        VisibilityScope scope = currentUser.scope();
        AssetUsage usage = assetUsageRepositoryPort.findById(id)
                .orElseThrow(() -> new NoSuchElementException("unknown usage: " + usageId));
        assetService.details(scope, usage.assetId()); // out-of-scope -> NoSuchElementException -> 404 (hide)
        List<TrackCorrection> corrections = trackCorrectionService.forUsage(id, limit, scope);
        return new CorrectionListResponse(corrections.stream().map(CorrectionResponse::from).toList());
    }

    private static void requireValidLimit(int limit) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be within [1," + MAX_LIMIT + "]: " + limit);
        }
    }
}
