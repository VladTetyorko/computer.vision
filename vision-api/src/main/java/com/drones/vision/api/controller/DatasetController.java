package com.drones.vision.api.controller;

import com.drones.vision.api.dto.CreateDatasetRequest;
import com.drones.vision.api.dto.DatasetResponse;
import com.drones.vision.api.dto.DatasetsResponse;
import com.drones.vision.api.exception.ApiExceptionHandler;
import com.drones.vision.application.training.DatasetService;
import com.drones.vision.domain.model.Dataset;
import com.drones.vision.domain.model.DatasetId;
import com.drones.vision.domain.model.SampleStatus;
import com.drones.vision.domain.port.out.TrainingSampleRepositoryPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import com.drones.vision.api.security.CurrentUser;

/**
 * Driving REST adapter for {@link Dataset} CRUD (docs/CV-TRAINING-PLAN.md §3's frozen wire
 * contract) — {@code POST}/{@code GET}/{@code DELETE /api/datasets}[/{id}].
 *
 * <p>Gated by {@code vision.training.enabled} (default {@code false}): this whole controller is
 * absent from the context — every route 404s, same as any other unmapped path — when off,
 * mirroring {@link LiveController}'s own {@code @ConditionalOnProperty} gating for {@code
 * vision.live.enabled}.
 *
 * <p>Constructor-injected with {@link DatasetService} (the driving port) plus {@link
 * TrainingSampleRepositoryPort} directly (a read-only driven port) so every returned {@link
 * DatasetResponse} can carry its {@code sampleCounts} without growing {@link DatasetService}'s own
 * surface for a controller-only presentation concern — the same "controllers call a driving-port
 * service, driven ports only read-only" exception {@link AssetImageController}'s own javadoc
 * documents for {@link AssetController}'s {@code TelemetryRepositoryPort}/{@code
 * AssetImageRepositoryPort} collaborators.
 *
 * <p>Error mapping is entirely {@link DatasetService}'s own exceptions surfacing through {@link
 * ApiExceptionHandler}: {@link com.drones.vision.application.scope.AccessDeniedException} — {@link
 * #create}/{@link #delete} by a caller who may not manage the organization, or {@link #get} on a
 * dataset outside the caller's scope (deliberately a 403, not the usual hiding 404, per {@link
 * DatasetService#get}'s own javadoc) — → 403; {@link java.util.NoSuchElementException} (unknown
 * dataset id) → 404; {@link IllegalArgumentException} (a blank name, an invalid target-category
 * slug, or a malformed dataset id) → 400.
 */
@RestController
@ConditionalOnProperty(prefix = "vision.training", name = "enabled", havingValue = "true")
public class DatasetController {

    private final DatasetService datasetService;
    private final TrainingSampleRepositoryPort trainingSampleRepositoryPort;
    private final CurrentUser currentUser;

    public DatasetController(DatasetService datasetService,
                              TrainingSampleRepositoryPort trainingSampleRepositoryPort, CurrentUser currentUser) {
        this.datasetService = Objects.requireNonNull(datasetService, "datasetService must not be null");
        this.trainingSampleRepositoryPort =
                Objects.requireNonNull(trainingSampleRepositoryPort, "trainingSampleRepositoryPort must not be null");
        this.currentUser = Objects.requireNonNull(currentUser, "currentUser must not be null");
    }

    /**
     * Creates a new dataset, {@code OPEN} and empty.
     *
     * @param request the dataset's name/target category/class vocabulary
     * @return the created dataset
     */
    @PostMapping("/api/datasets")
    @ResponseStatus(HttpStatus.CREATED)
    public DatasetResponse create(@RequestBody CreateDatasetRequest request) {
        Dataset created = datasetService.create(request.toSpec(), currentUser.ownership(), currentUser.userId(),
                currentUser.scope());
        return toResponse(created);
    }

    /**
     * Lists every dataset the caller may see.
     *
     * @return a scope-filtered snapshot
     */
    @GetMapping("/api/datasets")
    public DatasetsResponse list() {
        List<DatasetResponse> datasets = datasetService.list(currentUser.userId(), currentUser.scope()).stream()
                .map(this::toResponse).toList();
        return new DatasetsResponse(datasets);
    }

    /**
     * Reads one dataset.
     *
     * @param id the dataset id, as a canonical UUID string
     * @return the dataset
     */
    @GetMapping("/api/datasets/{id}")
    public DatasetResponse get(@PathVariable String id) {
        Dataset dataset = datasetService.get(DatasetId.of(id), currentUser.userId(), currentUser.scope());
        return toResponse(dataset);
    }

    /**
     * Deletes a dataset. Does not cascade to its samples/images — see {@link
     * DatasetService#delete}'s own javadoc.
     *
     * @param id the dataset id, as a canonical UUID string
     */
    @DeleteMapping("/api/datasets/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) {
        datasetService.delete(DatasetId.of(id), currentUser.userId(), currentUser.scope());
    }

    private DatasetResponse toResponse(Dataset dataset) {
        Map<String, Integer> sampleCounts = new LinkedHashMap<>();
        for (SampleStatus status : SampleStatus.values()) {
            sampleCounts.put(status.name(), trainingSampleRepositoryPort.countByDataset(dataset.id(), status));
        }
        return DatasetResponse.from(dataset, sampleCounts);
    }
}
