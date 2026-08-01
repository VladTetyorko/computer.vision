package com.drones.vision.api;

import com.drones.vision.api.dto.CaptureSampleRequest;
import com.drones.vision.api.dto.DatasetExportResponse;
import com.drones.vision.api.dto.LabelAnnotationsRequest;
import com.drones.vision.api.dto.SampleResponse;
import com.drones.vision.api.dto.SamplesResponse;
import com.drones.vision.application.CaptureSpec;
import com.drones.vision.application.DatasetService;
import com.drones.vision.application.LabelingService;
import com.drones.vision.domain.model.DatasetExport;
import com.drones.vision.domain.model.DatasetId;
import com.drones.vision.domain.model.SampleImage;
import com.drones.vision.domain.model.SampleStatus;
import com.drones.vision.domain.model.StreamId;
import com.drones.vision.domain.model.TrainingSample;
import com.drones.vision.domain.model.TrainingSampleId;
import com.drones.vision.domain.port.out.DatasetExportPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Driving REST adapter for capture/labeling/export (docs/CV-TRAINING-PLAN.md §3's frozen wire
 * contract) — the operator-in-the-loop half of the CV model-improvement loop, over {@link
 * LabelingService}.
 *
 * <p>Gated by {@code vision.training.enabled} (default {@code false}), same as {@link
 * DatasetController} — see that class's own javadoc.
 *
 * <p>Also takes {@link DatasetService} and {@link DatasetExportPort} directly: {@link
 * #downloadExport} has no {@link LabelingService} method of its own to resolve a
 * previously-written export's bytes (only {@link LabelingService#export} produces a fresh one), so
 * it first re-runs {@link DatasetService#get} purely for its scope check (discarding the result) —
 * the same deliberate non-hiding 403 {@link DatasetController#get} surfaces — then resolves the
 * zip directly through the driven port, mirroring the "driving-port service plus a read-only
 * driven port" exception {@link DatasetController}'s own javadoc documents.
 *
 * <p>Error mapping is entirely {@link LabelingService}'s own exceptions surfacing through {@link
 * ApiExceptionHandler}: {@link com.drones.vision.application.AccessDeniedException} (a dataset, or
 * its resolvable source asset, outside the caller's scope) → 403; {@link
 * java.util.NoSuchElementException} (unknown dataset/sample, a stream with no frame published yet,
 * or an unresolvable export) → 404; {@link IllegalArgumentException} (an annotation label outside
 * the dataset's class vocabulary, an unrecognized {@code status}/annotation {@code source}, or a
 * malformed id) → 400.
 */
@RestController
@ConditionalOnProperty(prefix = "vision.training", name = "enabled", havingValue = "true")
public class LabelingController {

    /** Default {@code limit} for {@link #samples} when the query parameter is absent. */
    private static final int DEFAULT_SAMPLES_LIMIT = 50;

    private final LabelingService labelingService;
    private final DatasetService datasetService;
    private final DatasetExportPort datasetExportPort;
    private final CurrentUser currentUser;

    public LabelingController(LabelingService labelingService, DatasetService datasetService,
                               DatasetExportPort datasetExportPort, CurrentUser currentUser) {
        this.labelingService = Objects.requireNonNull(labelingService, "labelingService must not be null");
        this.datasetService = Objects.requireNonNull(datasetService, "datasetService must not be null");
        this.datasetExportPort = Objects.requireNonNull(datasetExportPort, "datasetExportPort must not be null");
        this.currentUser = Objects.requireNonNull(currentUser, "currentUser must not be null");
    }

    /**
     * Captures a training sample from a stream's current raw frame + detections — the operator's
     * "Add to dataset" gesture.
     *
     * @param streamId the stream to capture from, as a canonical UUID string
     * @param request  the target dataset
     * @return the newly captured, {@code PENDING} sample
     */
    @PostMapping("/api/streams/{streamId}/samples")
    @ResponseStatus(HttpStatus.CREATED)
    public SampleResponse capture(@PathVariable String streamId, @RequestBody CaptureSampleRequest request) {
        CaptureSpec spec = new CaptureSpec(StreamId.of(streamId), request.toDatasetId());
        TrainingSample captured = labelingService.capture(spec, currentUser.userId(), currentUser.scope());
        return SampleResponse.from(captured);
    }

    /**
     * Lists one dataset's samples.
     *
     * @param id     the dataset id, as a canonical UUID string
     * @param status restrict to one status ({@code PENDING}/{@code LABELED}/{@code DISCARDED}),
     *               absent for every status
     * @param limit  maximum number of samples to return; absent defaults to {@value
     *               #DEFAULT_SAMPLES_LIMIT}
     * @return a snapshot of matching samples
     */
    @GetMapping("/api/datasets/{id}/samples")
    public SamplesResponse samples(@PathVariable String id, @RequestParam(required = false) String status,
                                    @RequestParam(required = false) Integer limit) {
        List<TrainingSample> found = labelingService.samples(DatasetId.of(id), toStatusOrNull(status),
                limit == null ? DEFAULT_SAMPLES_LIMIT : limit, currentUser.userId(), currentUser.scope());
        return new SamplesResponse(found.stream().map(SampleResponse::from).toList());
    }

    /**
     * Streams one sample's stored image bytes.
     *
     * @param id the sample id, as a canonical UUID string
     * @return the JPEG bytes, never cached — every fetch wants the actual stored image, not a
     *         browser- or intermediary-cached one, same reasoning as {@code
     *         StreamController#snapshot}
     */
    @GetMapping("/api/samples/{id}/image")
    public ResponseEntity<byte[]> image(@PathVariable String id) {
        SampleImage image = labelingService.image(TrainingSampleId.of(id), currentUser.userId(), currentUser.scope());
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(image.contentType()))
                .cacheControl(CacheControl.noStore())
                .body(image.data());
    }

    /**
     * Confirms or corrects one sample's annotations.
     *
     * @param id      the sample id, as a canonical UUID string
     * @param request the corrected annotations plus the review outcome
     * @return the updated sample
     */
    @PutMapping("/api/samples/{id}/annotations")
    public SampleResponse label(@PathVariable String id, @RequestBody LabelAnnotationsRequest request) {
        TrainingSample updated = labelingService.label(TrainingSampleId.of(id), request.toSpec(),
                currentUser.userId(), currentUser.scope());
        return SampleResponse.from(updated);
    }

    /**
     * Exports every {@code LABELED} sample in a dataset as a YOLO-format zip.
     *
     * @param id the dataset id, as a canonical UUID string
     * @return the completed export's manifest, including its download URL
     */
    @PostMapping("/api/datasets/{id}/export")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public DatasetExportResponse export(@PathVariable String id) {
        DatasetExport export = labelingService.export(DatasetId.of(id), currentUser.userId(), currentUser.scope());
        return DatasetExportResponse.from(export);
    }

    /**
     * Downloads a previously completed export's zip archive.
     *
     * @param id       the dataset id, as a canonical UUID string
     * @param exportId the export id, as returned by {@link #export}
     * @return the zip bytes
     * @throws java.util.NoSuchElementException if the dataset or the export is unknown (404)
     */
    @GetMapping(value = "/api/datasets/{id}/export/{exportId}", produces = "application/zip")
    public ResponseEntity<byte[]> downloadExport(@PathVariable String id, @PathVariable String exportId) {
        DatasetId datasetId = DatasetId.of(id);
        // Scope check only -- DatasetService#get deliberately 403s (not hides) an out-of-scope
        // dataset and 404s an unknown one; the returned Dataset itself is unused, since
        // LabelingService has no by-exportId read of its own.
        datasetService.get(datasetId, currentUser.userId(), currentUser.scope());
        Path zipPath = datasetExportPort.resolve(datasetId, exportId)
                .orElseThrow(() -> new NoSuchElementException("Unknown export: " + exportId));
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/zip")).body(readBytes(zipPath));
    }

    private static byte[] readBytes(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read dataset export: " + path, e);
        }
    }

    private static SampleStatus toStatusOrNull(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        for (SampleStatus candidate : SampleStatus.values()) {
            if (candidate.name().equalsIgnoreCase(status)) {
                return candidate;
            }
        }
        throw new IllegalArgumentException("Unknown status: " + status + " (valid values: "
                + Arrays.stream(SampleStatus.values()).map(Enum::name).collect(Collectors.joining(", ")) + ")");
    }
}
