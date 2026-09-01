package com.drones.vision.api.controller;

import com.drones.vision.api.dto.CvCoverageResponse;
import com.drones.vision.api.dto.CvCoverageRowResponse;
import com.drones.vision.api.dto.CvProfileBindingRequest;
import com.drones.vision.api.dto.CvProfileBindingResponse;
import com.drones.vision.api.dto.CvProfileRequest;
import com.drones.vision.api.dto.CvProfileResponse;
import com.drones.vision.api.dto.CvProfilesResponse;
import com.drones.vision.api.dto.EffectiveCvProfileResponse;
import com.drones.vision.api.exception.ApiExceptionHandler;
import com.drones.vision.api.security.CurrentUser;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.CategoryId;
import com.drones.vision.perception.application.profile.CoverageRow;
import com.drones.vision.perception.application.profile.CvProfileService;
import com.drones.vision.perception.application.profile.EffectiveProfile;
import com.drones.vision.perception.application.profile.ProfileSource;
import com.drones.vision.perception.domain.model.CvProfile;
import com.drones.vision.perception.domain.model.CvProfileBinding;
import com.drones.vision.perception.domain.model.CvProfileId;
import com.drones.vision.perception.domain.model.PipelineConfig;
import com.drones.vision.warehouse.application.category.CategoryService;
import com.drones.vision.warehouse.domain.model.DeviceCategory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Driving REST adapter for {@link CvProfile} CRUD, scope bindings, and the two resolved-config
 * reads (docs/plans/active/CV-SETTINGS-PLAN.md &sect;5.2's frozen wire contract) —
 * {@code GET}/{@code POST}/{@code PUT}/{@code DELETE /api/cv/profiles}[/{id}],
 * {@code PUT}/{@code DELETE /api/cv/bindings}, {@code GET /api/cv/profiles/effective},
 * {@code GET /api/cv/coverage}.
 *
 * <p><b>Unconditional, no feature flag</b> — same "ships built-in" posture {@code
 * CvProfileWiringConfiguration}'s own javadoc documents: profiles exist regardless of whether
 * detection or the model registry are turned on.
 *
 * <p>Deliberately excludes a fork endpoint (docs/plans/active/CV-SETTINGS-CONTEXT.md's W2 &rarr; W5
 * handoff, deviation 4) — {@link CvProfileService#fork} has no HTTP surface this wave; a future wave
 * can add {@code POST /api/cv/profiles/{id}/fork} without touching anything here.
 *
 * <p>{@link #effective}/{@link #coverage} both assemble the "platform default" {@link
 * PipelineConfig} server-side (this controller's own {@code platformDefault} collaborator, the same
 * bean {@code StreamController}/{@code AssetStreamController} resolve through {@code
 * StreamDetectionSupport}) and pass it down to {@link CvProfileService#effective}/{@link
 * CvProfileService#coverage} — {@code vision-perception} never invents one itself (deviation 2 of
 * the same handoff). Where the resolved profile is {@link ProfileSource#PLATFORM} (no binding
 * matched at any level), the wire response synthesizes a profile-shaped stand-in via {@link
 * CvProfileResponse#platformDefault}/the {@link CvCoverageRowResponse#PLATFORM_DEFAULT_ID} sentinel
 * rather than send a genuine {@code null} — {@code models.ts} declares every relevant field
 * non-optional for these two endpoints.
 *
 * <p>Error mapping is entirely {@link CvProfileService}'s own exceptions surfacing through {@link
 * ApiExceptionHandler}: {@link com.drones.vision.platform.AccessDeniedException} (a mutation by a
 * caller who may not manage the organization) &rarr; 403; {@link java.util.NoSuchElementException}
 * (unknown profile id, or an asset outside the caller's scope for {@link #effective}) &rarr; 404;
 * {@link IllegalStateException} ({@link #update}/{@link #delete} on a built-in profile, or {@link
 * #delete} on a profile still bound to a scope) &rarr; 409; {@link IllegalArgumentException} (a
 * malformed id, an unknown {@code tracking.mode}/binding {@code scopeKind}, a {@code scopeId} that
 * doesn't parse for its {@code scopeKind}, or a missing {@code profileId} on {@link #bind}) &rarr;
 * 400.
 */
@RestController
public class CvProfileController {

    private final CvProfileService cvProfileService;
    private final CategoryService categoryService;
    private final PipelineConfig platformDefault;
    private final CurrentUser currentUser;

    public CvProfileController(CvProfileService cvProfileService, CategoryService categoryService,
                                PipelineConfig platformDefault, CurrentUser currentUser) {
        this.cvProfileService = Objects.requireNonNull(cvProfileService, "cvProfileService must not be null");
        this.categoryService = Objects.requireNonNull(categoryService, "categoryService must not be null");
        this.platformDefault = Objects.requireNonNull(platformDefault, "platformDefault must not be null");
        this.currentUser = Objects.requireNonNull(currentUser, "currentUser must not be null");
    }

    /**
     * Lists every profile the caller may see — every built-in profile, plus every profile owned by
     * a group the caller's scope includes.
     */
    @GetMapping("/api/cv/profiles")
    public CvProfilesResponse list() {
        List<CvProfileResponse> profiles = cvProfileService.list(currentUser.userId(), currentUser.scope()).stream()
                .map(CvProfileResponse::from).toList();
        return new CvProfilesResponse(profiles);
    }

    /**
     * Reads one profile.
     *
     * @param id the profile id, as a canonical UUID string
     * @return the profile
     */
    @GetMapping("/api/cv/profiles/{id}")
    public CvProfileResponse get(@PathVariable String id) {
        CvProfile profile = cvProfileService.get(CvProfileId.of(id), currentUser.userId(), currentUser.scope());
        return CvProfileResponse.from(profile);
    }

    /**
     * Creates a new, non-built-in profile owned by the caller's own group.
     *
     * @param request the profile's fields
     * @return the created profile
     */
    @PostMapping("/api/cv/profiles")
    @ResponseStatus(HttpStatus.CREATED)
    public CvProfileResponse create(@RequestBody CvProfileRequest request) {
        CvProfile created = cvProfileService.create(request.toSpec(), currentUser.ownership().groupId(),
                currentUser.userId(), currentUser.scope());
        return CvProfileResponse.from(created);
    }

    /**
     * Replaces a non-built-in profile's fields wholesale (the update request carries the same shape
     * as create).
     *
     * @param id      the profile id, as a canonical UUID string
     * @param request the replacement fields
     * @return the updated profile
     */
    @PutMapping("/api/cv/profiles/{id}")
    public CvProfileResponse update(@PathVariable String id, @RequestBody CvProfileRequest request) {
        CvProfile updated = cvProfileService.update(CvProfileId.of(id), request.toSpec(), currentUser.userId(),
                currentUser.scope());
        return CvProfileResponse.from(updated);
    }

    /**
     * Deletes a non-built-in, unbound profile.
     *
     * @param id the profile id, as a canonical UUID string
     */
    @DeleteMapping("/api/cv/profiles/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) {
        cvProfileService.delete(CvProfileId.of(id), currentUser.userId(), currentUser.scope());
    }

    /**
     * Binds a profile to a scope, replacing whatever was previously bound to that exact scope.
     *
     * @param request the scope to bind and the profile to bind it to ({@code profileId} required)
     * @return the persisted binding
     */
    @PutMapping("/api/cv/bindings")
    public CvProfileBindingResponse bind(@RequestBody CvProfileBindingRequest request) {
        CvProfileBinding binding = cvProfileService.bind(request.toScopeKind(), request.scopeId(),
                request.requireProfileId(), currentUser.userId(), currentUser.scope());
        return CvProfileBindingResponse.from(binding);
    }

    /**
     * Removes a scope's binding, if any — idempotent.
     *
     * @param request the scope to clear ({@code profileId} ignored)
     */
    @DeleteMapping("/api/cv/bindings")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unbind(@RequestBody CvProfileBindingRequest request) {
        cvProfileService.unbind(request.toScopeKind(), request.scopeId(), currentUser.userId(), currentUser.scope());
    }

    /**
     * Resolves the one profile {@code assetId} would start with right now.
     *
     * @param assetId the asset to resolve, as a canonical UUID string
     * @return the resolved profile (synthesized for {@link ProfileSource#PLATFORM}) and its source
     */
    @GetMapping("/api/cv/profiles/effective")
    public EffectiveCvProfileResponse effective(@RequestParam String assetId) {
        AssetId id = AssetId.of(assetId);
        EffectiveProfile resolved = cvProfileService.effective(id, platformDefault, currentUser.userId(),
                currentUser.scope());
        CvProfileResponse profile = resolved.source() == ProfileSource.PLATFORM
                ? CvProfileResponse.platformDefault(resolved.config())
                : CvProfileResponse.from(
                        cvProfileService.get(resolved.profileId(), currentUser.userId(), currentUser.scope()));
        return new EffectiveCvProfileResponse(resolved.assetId().value().toString(), profile,
                resolved.source().name());
    }

    /**
     * The fleet-wide "what CV will do on each asset" table.
     *
     * @return one row per asset the caller may see
     */
    @GetMapping("/api/cv/coverage")
    public CvCoverageResponse coverage() {
        Map<CategoryId, String> categoryNames = categoryService.categories().stream()
                .collect(Collectors.toMap(DeviceCategory::id, DeviceCategory::name));
        List<CoverageRow> rows =
                cvProfileService.coverage(platformDefault, currentUser.userId(), currentUser.scope());
        List<CvCoverageRowResponse> response = rows.stream()
                .map(row -> CvCoverageRowResponse.from(row,
                        categoryNames.getOrDefault(row.categoryId(), row.categoryId().slug())))
                .toList();
        return new CvCoverageResponse(response);
    }
}
