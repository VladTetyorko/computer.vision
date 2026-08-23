package com.drones.vision.api.controller;

import com.drones.vision.api.dto.IngestProgressResponse;
import com.drones.vision.api.dto.RegionIngestRequest;
import com.drones.vision.api.dto.RegionListResponse;
import com.drones.vision.api.dto.RegionResponse;
import com.drones.vision.api.exception.ApiExceptionHandler;
import com.drones.vision.api.exception.GeoServiceUnavailableException;
import com.drones.vision.api.security.CurrentUser;
import com.drones.vision.api.support.VisualGeoProperties;
import com.drones.vision.perception.application.geo.ReferenceRegionService;
import com.drones.vision.perception.domain.model.IngestProgress;
import com.drones.vision.perception.domain.model.ReferenceRegion;
import com.drones.vision.perception.domain.model.RegionIngestSpec;
import com.drones.vision.platform.AccessDeniedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * Driving REST adapter for {@code /api/geo/regions} (docs/plans/done/VISUAL-GEO-V2-PLAN.md §3.3,
 * H5) — a thin proxy onto {@link ReferenceRegionService}, which owns the real merge between
 * cv-service's built regions and Java's own in-memory in-flight ingest jobs (D10).
 *
 * <h2>Flag gate (D9)</h2>
 * Every method calls {@link VisualGeoProperties#requireEnabled()} first, before any other
 * validation or authorization — while {@code vision.geo.visual.enabled=false} every route answers
 * {@code 409} with the frozen D9 body, and {@link ReferenceRegionService} is never called.
 *
 * <h2>Authorization</h2>
 * {@code POST}/{@code DELETE} require {@link com.drones.vision.platform.VisibilityScope#canAdminister()}
 * (§3.3: "a region ingest hits an external imagery provider") — checked <em>after</em> request-body
 * validation, the {@code CameraPoseController} precedent: a malformed body is a {@code 400}, before
 * the authorization guard. {@code GET} routes require no additional authorization; regions are a
 * global map-tile resource, not asset-scoped.
 *
 * <h2>cv-service reachability (§3.3's 503)</h2>
 * {@link ReferenceRegionService#list()}/{@link ReferenceRegionService#delete}'s underlying {@code
 * ReferenceIndexPort} calls let a transport failure propagate uncaught (that adapter's own
 * javadoc) — each method here catches the well-known exception types unchanged (letting {@link
 * ApiExceptionHandler}'s existing mappings apply) and translates anything else to {@link
 * GeoServiceUnavailableException} (→503), since {@code vision-api} must not name a {@code io.grpc}
 * type directly. {@link ReferenceRegionService#ingest} never blocks on cv-service reachability (the
 * build runs on a background thread and reports failure via {@code progress}, not the POST call),
 * so a synchronous 503 from that path is not currently reachable in practice; the same safe-wrapper
 * is applied anyway for symmetry and future-proofing.
 */
@RestController
@RequestMapping("/api/geo/regions")
public class GeoRegionController {

    private final ReferenceRegionService referenceRegionService;
    private final CurrentUser currentUser;
    private final VisualGeoProperties properties;

    public GeoRegionController(ReferenceRegionService referenceRegionService, CurrentUser currentUser,
                                VisualGeoProperties properties) {
        this.referenceRegionService =
                Objects.requireNonNull(referenceRegionService, "referenceRegionService must not be null");
        this.currentUser = Objects.requireNonNull(currentUser, "currentUser must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
    }

    /** {@code GET /api/geo/regions} — every known region, built or building. */
    @GetMapping
    public RegionListResponse list() {
        properties.requireEnabled();
        List<ReferenceRegion> regions = safely(referenceRegionService::list);
        return new RegionListResponse(regions.stream().map(RegionResponse::from).toList());
    }

    /** {@code POST /api/geo/regions} — starts an asynchronous ingest; {@code 202} with status {@code BUILDING}. */
    @PostMapping
    public ResponseEntity<RegionResponse> ingest(@RequestBody RegionIngestRequest request) {
        properties.requireEnabled();
        RegionIngestSpec spec = request.toSpec(); // malformed body -> 400, before the authorization guard
        requireAdminister();
        ReferenceRegion region = safely(() -> referenceRegionService.ingest(spec));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(RegionResponse.from(region));
    }

    /** {@code DELETE /api/geo/regions/{regionId}} — idempotent. */
    @DeleteMapping("/{regionId}")
    public ResponseEntity<Void> delete(@PathVariable String regionId) {
        properties.requireEnabled();
        requireAdminister();
        safely(() -> {
            referenceRegionService.delete(regionId);
            return null;
        });
        return ResponseEntity.noContent().build();
    }

    /** {@code GET /api/geo/regions/{regionId}/progress}. */
    @GetMapping("/{regionId}/progress")
    public IngestProgressResponse progress(@PathVariable String regionId) {
        properties.requireEnabled();
        IngestProgress progress = referenceRegionService.progress(regionId)
                .orElseThrow(() -> new NoSuchElementException(
                        "no in-flight ingest job and no such READY region: " + regionId));
        return IngestProgressResponse.from(progress);
    }

    private void requireAdminister() {
        if (!currentUser.scope().canAdminister()) {
            throw new AccessDeniedException("region ingest requires administer authority");
        }
    }

    /**
     * Runs a {@link ReferenceRegionService} call, translating any transport failure it lets through
     * ({@code io.grpc.StatusRuntimeException}, not nameable here) to {@link
     * GeoServiceUnavailableException} — see class javadoc. Every exception type this class's own
     * collaborators are documented to throw deliberately is rethrown unchanged.
     */
    private <T> T safely(java.util.function.Supplier<T> call) {
        try {
            return call.get();
        } catch (IllegalArgumentException | IllegalStateException | NoSuchElementException
                | AccessDeniedException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new GeoServiceUnavailableException("cv-service is not reachable", e);
        }
    }
}
