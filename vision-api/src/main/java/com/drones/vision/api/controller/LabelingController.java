package com.drones.vision.api.controller;

import com.drones.vision.api.dto.CaptureFromReplayRequest;
import com.drones.vision.api.dto.CaptureSampleRequest;
import com.drones.vision.api.dto.LabelAnnotationsRequest;
import com.drones.vision.api.dto.SampleResponse;
import com.drones.vision.api.dto.SamplesResponse;
import com.drones.vision.api.exception.ApiExceptionHandler;
import com.drones.vision.learning.application.CaptureSpec;
import com.drones.vision.learning.application.LabelingService;
import com.drones.vision.events.application.ReplayCaptureSpec;
import com.drones.vision.learning.domain.model.DatasetId;
import com.drones.vision.learning.domain.model.SampleImage;
import com.drones.vision.learning.domain.model.SampleStatus;
import com.drones.vision.kernel.StreamId;
import com.drones.vision.learning.domain.model.TrainingSample;
import com.drones.vision.learning.domain.model.TrainingSampleId;
import com.drones.vision.kernel.UsageId;
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

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import com.drones.vision.api.security.CurrentUser;

/**
 * Driving REST adapter for capture/labeling (docs/plans/done/CV-TRAINING-PLAN.md §3's frozen wire contract,
 * as delta'd by docs/plans/done/CV-TRAINING-V2-PLAN.md §5) — the operator-in-the-loop half of the CV
 * model-improvement loop, over {@link LabelingService}.
 *
 * <p>Gated by {@code vision.training.enabled} (default {@code false}), same as {@link
 * DatasetController} — see that class's own javadoc.
 *
 * <p>The manual export/download routes this controller used to carry
 * (docs/plans/done/CV-TRAINING-PLAN.md §3) are gone (docs/plans/done/CV-TRAINING-V2-PLAN.md §A): dataset delivery to the
 * training host is now an implicit part of {@code POST /api/datasets/{id}/train} ({@link
 * TrainingJobController}), over a gRPC upload — see {@link LabelingService#uploadForTraining}. This
 * controller's constructor dropped its {@code DatasetService}/{@code DatasetExportPort}
 * collaborators along with those two handlers, since nothing else here ever needed them.
 *
 * <p>Error mapping is entirely {@link LabelingService}'s own exceptions surfacing through {@link
 * ApiExceptionHandler}: {@link com.drones.vision.platform.AccessDeniedException} (a dataset, or
 * its resolvable source asset, outside the caller's scope) → 403; {@link
 * java.util.NoSuchElementException} (unknown dataset/sample/usage, a stream with no frame published
 * yet, a usage with no recorded video stream, or no recorded frame at the requested replay instant)
 * → 404; {@link IllegalArgumentException} (an annotation label outside the dataset's class
 * vocabulary, an unrecognized {@code status}/annotation {@code source}, a malformed id, or a replay
 * {@code atSeconds} past the usage's recorded window) → 400.
 */
@RestController
@ConditionalOnProperty(prefix = "vision.training", name = "enabled", havingValue = "true")
public class LabelingController {

    /** Default {@code limit} for {@link #samples} when the query parameter is absent. */
    private static final int DEFAULT_SAMPLES_LIMIT = 50;

    private final LabelingService labelingService;
    private final CurrentUser currentUser;

    public LabelingController(LabelingService labelingService, CurrentUser currentUser) {
        this.labelingService = Objects.requireNonNull(labelingService, "labelingService must not be null");
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
     * Captures a training sample from a finished usage's recorded replay at a specific instant —
     * the replay counterpart to {@link #capture}'s live "Add to dataset" gesture
     * (docs/plans/done/CV-TRAINING-V2-PLAN.md §4/§5), invoked from the Replay page rather than a stream picker.
     *
     * @param usageId the finished usage to pull a recorded frame from, as a canonical UUID string
     * @param request the target dataset and the replay offset (seconds past the usage's own {@code
     *                startedAt}) to capture from
     * @return the newly captured, {@code PENDING} sample — same wire shape {@link #capture} returns,
     *         with suggested annotations pre-filled from the nearest stored detection
     */
    @PostMapping("/api/usages/{usageId}/samples")
    @ResponseStatus(HttpStatus.CREATED)
    public SampleResponse captureFromReplay(@PathVariable String usageId,
                                             @RequestBody CaptureFromReplayRequest request) {
        ReplayCaptureSpec spec =
                new ReplayCaptureSpec(UsageId.of(usageId), request.toDatasetId(), request.atSeconds());
        TrainingSample captured = labelingService.captureFromReplay(spec, currentUser.userId(), currentUser.scope());
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
