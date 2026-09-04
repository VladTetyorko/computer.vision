package com.drones.vision.api.controller;

import com.drones.vision.api.dto.PromoteModelRequest;
import com.drones.vision.api.dto.PromotionResultResponse;
import com.drones.vision.api.exception.ApiExceptionHandler;
import com.drones.vision.learning.application.ModelRegistryService;
import com.drones.vision.learning.application.PromotionResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;
import com.drones.vision.api.security.CurrentUser;

/**
 * Driving REST adapter for the CV model registry's promote/rollback control plane
 * (docs/plans/done/CV-TRAINING-PLAN.md §7/§8 Phase 2, joined/governed per docs/plans/active/CV-SETTINGS-PLAN.md
 * §3.2/§5.2) — {@code POST /api/cv/registry/models/{id}/promote} promotes one model to production,
 * {@code POST /api/cv/registry/rollback} restores whichever model that promotion demoted.
 *
 * <p>Gated by {@code vision.cv.registry.enabled} (docs/plans/active/CV-SETTINGS-CONTEXT.md's W4-app
 * &rarr; W5 handoff) — no longer {@code vision.training.enabled}: this whole controller is absent
 * from the context, every route 404ing like any other unmapped path, when off. That flag defaults to
 * {@code vision.cv.enabled}'s own value (application.yaml's {@code registry.enabled:
 * ${vision.cv.enabled:false}} placeholder), so a deployment that already turned detection on gets the
 * registry for free unless it explicitly opts out.
 *
 * <p><strong>{@code GET /api/cv/registry/models} no longer exists</strong> — the roster read folded
 * into {@link CvModelsController}'s widened {@code GET /api/cv/models} (docs/plans/active/CV-SETTINGS-PLAN.md
 * §8 OQ5: one roster read, tagged with where it came from, rather than two separate endpoints for a
 * static picker and a dynamic registry).
 *
 * <p><strong>Not the same roster as {@link CvModelsController}</strong>: that endpoint serves any
 * authenticated caller a read-only roster (falling back to the static config catalogue when this
 * controller is absent or the worker is unreachable); this one is the mutation surface — promote and
 * rollback both require {@link com.drones.vision.platform.Authority#mayAdminister()}, ADMIN
 * only (docs/plans/active/AUTH-ROLES-PLAN.md wave B6, superseding the bare {@code
 * VisibilityScope#canAdminister()} check this used before; see {@link ModelRegistryService}'s own
 * javadoc, "Scope").
 *
 * <p>Error mapping is entirely {@link ModelRegistryService#promote}/{@link
 * ModelRegistryService#rollback}'s own exceptions surfacing through {@link ApiExceptionHandler}:
 * {@link com.drones.vision.platform.AccessDeniedException} (caller may not administer) → 403; {@link
 * IllegalStateException} (cv-service refuses the promotion, or {@link #rollback} has no previously-
 * retired model to restore) → 409; {@link IllegalArgumentException} ({@code modelId}/{@code version}
 * blank, {@link #promote} only) → 400.
 */
@RestController
@ConditionalOnProperty(prefix = "vision.cv.registry", name = "enabled", havingValue = "true")
public class ModelRegistryController {

    private final ModelRegistryService modelRegistryService;
    private final CurrentUser currentUser;

    public ModelRegistryController(ModelRegistryService modelRegistryService, CurrentUser currentUser) {
        this.modelRegistryService =
                Objects.requireNonNull(modelRegistryService, "modelRegistryService must not be null");
        this.currentUser = Objects.requireNonNull(currentUser, "currentUser must not be null");
    }

    /**
     * Promotes a model to production: sets it {@code LIVE} and demotes whichever row was previously
     * {@code LIVE} to {@code RETIRED}. Requires the caller to administer the organization (ADMIN).
     *
     * @param id      the model identifier, from the path
     * @param request carries the {@code version} to resolve the full model reference
     * @return the promotion outcome
     */
    @PostMapping("/api/cv/registry/models/{id}/promote")
    public PromotionResultResponse promote(@PathVariable String id, @RequestBody PromoteModelRequest request) {
        PromotionResult result =
                modelRegistryService.promote(id, request.version(), currentUser.userId(), currentUser.authority());
        return PromotionResultResponse.from(result);
    }

    /**
     * Restores whichever model {@link #promote}'s most recent call demoted to {@code RETIRED} back to
     * {@code LIVE}, retiring the current one in its place. Requires the caller to administer the
     * organization (ADMIN).
     *
     * @return the rollback outcome
     */
    @PostMapping("/api/cv/registry/rollback")
    public PromotionResultResponse rollback() {
        PromotionResult result = modelRegistryService.rollback(currentUser.userId(), currentUser.authority());
        return PromotionResultResponse.from(result);
    }
}
