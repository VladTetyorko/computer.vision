package com.drones.vision.api;

import com.drones.vision.api.dto.PromoteModelRequest;
import com.drones.vision.api.dto.RegisteredModelResponse;
import com.drones.vision.api.dto.RegisteredModelsResponse;
import com.drones.vision.api.exceptions.ApiExceptionHandler;
import com.drones.vision.application.ModelRegistryService;
import com.drones.vision.domain.model.ModelRef;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Objects;

/**
 * Driving REST adapter for the CV model registry (docs/CV-TRAINING-PLAN.md §7/§8, Phase 2) — the
 * ingest/promote control plane that sits behind {@link ModelRegistryService}: {@code GET
 * /api/cv/registry/models} lists every known model reference and which one is active, {@code POST
 * /api/cv/registry/models/{id}/promote} promotes one to production.
 *
 * <p>Gated by {@code vision.training.enabled} (default {@code false}), same as {@link
 * DatasetController}/{@link LabelingController} — this whole controller is absent from the context
 * when off, so every route 404s like any other unmapped path.
 *
 * <p><strong>Not the same roster as {@link CvModelsController}</strong>: that endpoint ({@code GET
 * /api/cv/models}) serves a static, config-backed picker for the Fly cockpit's model dropdown; this
 * one is the dynamic registry — every model reference cv-service actually knows about (across
 * training runs/stages), sourced live over gRPC ({@code GrpcModelRegistryPort}, wired in
 * vision-app), and the one place a model gets promoted to production.
 *
 * <p>Error mapping is entirely {@link ModelRegistryService#promote}'s own exceptions surfacing
 * through {@link ApiExceptionHandler}: {@link com.drones.vision.application.AccessDeniedException}
 * (caller may not manage the organization) → 403; {@link IllegalStateException} (cv-service
 * refuses the promotion — e.g. an unknown model id, "rsync the artifact first" — or no registry
 * reachable at all) → 409; {@link IllegalArgumentException} ({@link ModelRef}'s own blank
 * {@code id}/{@code version} check) → 400. {@link #models()} never throws.
 */
@RestController
@ConditionalOnProperty(prefix = "vision.training", name = "enabled", havingValue = "true")
public class ModelRegistryController {

    private final ModelRegistryService modelRegistryService;
    private final CurrentUser currentUser;

    public ModelRegistryController(ModelRegistryService modelRegistryService, CurrentUser currentUser) {
        this.modelRegistryService =
                Objects.requireNonNull(modelRegistryService, "modelRegistryService must not be null");
        this.currentUser = Objects.requireNonNull(currentUser, "currentUser must not be null");
    }

    /**
     * Lists every known model reference, marking which one (if any) is currently active. Any
     * authenticated caller may read this — see {@link ModelRegistryService}'s own javadoc, "Scope".
     *
     * @return the registry snapshot
     */
    @GetMapping("/api/cv/registry/models")
    public RegisteredModelsResponse models() {
        List<RegisteredModelResponse> models =
                modelRegistryService.models().stream().map(RegisteredModelResponse::from).toList();
        return new RegisteredModelsResponse(models);
    }

    /**
     * Promotes a model reference to production. Requires the caller to manage the organization
     * (any manager/admin).
     *
     * @param id      the model identifier, from the path
     * @param request carries the {@code version} to resolve the full {@link ModelRef}
     * @return the promoted reference, {@code active} always {@code true} on success
     */
    @PostMapping("/api/cv/registry/models/{id}/promote")
    public RegisteredModelResponse promote(@PathVariable String id, @RequestBody PromoteModelRequest request) {
        ModelRef ref = new ModelRef(id, request.version());
        modelRegistryService.promote(ref, currentUser.userId(), currentUser.scope());
        return new RegisteredModelResponse(ref.id(), ref.version(), true);
    }
}
