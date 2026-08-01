package com.drones.vision.application;

import com.drones.vision.domain.model.Annotation;
import com.drones.vision.domain.model.AnnotationSource;
import com.drones.vision.domain.model.Asset;
import com.drones.vision.domain.model.AssetId;
import com.drones.vision.domain.model.AssetUsage;
import com.drones.vision.domain.model.AuditAction;
import com.drones.vision.domain.model.AuditEntry;
import com.drones.vision.domain.model.AuditTargetType;
import com.drones.vision.domain.model.Dataset;
import com.drones.vision.domain.model.DatasetId;
import com.drones.vision.domain.model.DatasetUpload;
import com.drones.vision.domain.model.Detection;
import com.drones.vision.domain.model.DetectionQuery;
import com.drones.vision.domain.model.DetectionResult;
import com.drones.vision.domain.model.DeviceId;
import com.drones.vision.domain.model.SampleImage;
import com.drones.vision.domain.model.SampleStatus;
import com.drones.vision.domain.model.StreamId;
import com.drones.vision.domain.model.TrainingSample;
import com.drones.vision.domain.model.TrainingSampleId;
import com.drones.vision.domain.model.UserId;
import com.drones.vision.domain.model.VideoFrame;
import com.drones.vision.domain.port.out.AssetRepositoryPort;
import com.drones.vision.domain.port.out.AuditTrailPort;
import com.drones.vision.domain.port.out.DatasetUploadPort;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The one implementation of {@link LabelingService}.
 *
 * <h2>Resolving a capture's source asset</h2>
 * {@link CaptureSpec} carries only a {@link StreamId}, not an {@link
 * com.drones.vision.domain.model.AssetId} — {@link #capture} resolves the owning asset itself by
 * matching {@code streamId} against {@link StreamService#streams()}'s live snapshot to find the
 * device, then {@link AssetRepositoryPort#findByDeviceId}, exactly the device→asset resolution
 * {@code UsageTracker}/{@code DefaultStreamService} already perform internally when a stream
 * starts. Reaching {@link AssetRepositoryPort} directly (rather than through {@link AssetService})
 * mirrors {@code DefaultAssignmentService}'s own precedent — reaching a repository port directly
 * for the one fact this class needs, without pulling in a service's larger surface. A device with
 * no owning asset (or a stream not currently running) leaves the captured sample's {@code assetId}
 * {@code null} — see {@link TrainingSample}'s own javadoc for why that is a legitimate, expected
 * case, not an error. {@link #captureFromReplay} has no such gap — a {@link AssetUsage#assetId()}
 * is never {@code null} — so its own asset gate always applies.
 *
 * <h2>Replay's suggested annotations are looked up server-side (docs/CV-TRAINING-V2-PLAN.md §F)</h2>
 * {@link #captureFromReplay} queries {@link ReplaySources#detections()} for a ±{@link
 * #NEAREST_DETECTION_TOLERANCE} window around the requested instant and picks the nearest {@link
 * DetectionResult} (ties broken toward the earlier one, for determinism) rather than trusting a
 * client-computed "nearest detection" — the replay UI's own timeline is downsampled for display, so
 * a client-derived nearest could be a detection that merely survived thinning. Nothing in the
 * window is an honest "the model saw nothing here": an empty annotation list, never a fabricated
 * box.
 *
 * <h2>Threading</h2>
 * Holds no mutable state — all shared state is reached through the injected collaborators.
 */
public final class DefaultLabelingService implements LabelingService {

    /** Effectively-unbounded fetch for an upload's labeled-sample pass. */
    private static final int UPLOAD_SAMPLE_LIMIT = Integer.MAX_VALUE;

    /** How far around a replay capture's instant to look for a matching stored detection. */
    static final Duration NEAREST_DETECTION_TOLERANCE = Duration.ofSeconds(2);

    /** Bounded fetch for the nearest-detection lookup — a ±2s window never holds many results. */
    static final int NEAREST_DETECTION_FETCH_LIMIT = 200;

    private static final String ACTION_CAPTURE = "CAPTURE";
    private static final String ACTION_CAPTURE_REPLAY = "CAPTURE_REPLAY";
    private static final String ACTION_SAMPLES = "SAMPLES";
    private static final String ACTION_IMAGE = "IMAGE";
    private static final String ACTION_LABEL = "LABEL";
    private static final String ACTION_UPLOAD = "UPLOAD";
    private static final String DENIED_OUT_OF_SCOPE = "DENIED:out of scope";
    private static final String CONTENT_TYPE_JPEG = "image/jpeg";
    private static final String ATTR_DATASET_ID = "datasetId";
    private static final String ATTR_ACTION = "action";
    private static final String ATTR_RESULT = "result";

    private final TrainingStores stores;
    private final ReplaySources replay;
    private final StreamService streamService;
    private final AssetRepositoryPort assetRepository;
    private final AuditTrailPort auditTrail;
    private final Supplier<Instant> clock;

    public DefaultLabelingService(TrainingStores stores, ReplaySources replay, StreamService streamService,
                                   AssetRepositoryPort assetRepository, AuditTrailPort auditTrail) {
        this(stores, replay, streamService, assetRepository, auditTrail, Instant::now);
    }

    /**
     * Test seam: same as the 5-argument constructor, with an injectable clock so {@code labeledAt}
     * and the "still open" fallback for {@link #captureFromReplay}'s window check are deterministic
     * in tests instead of depending on wall-clock time.
     */
    DefaultLabelingService(TrainingStores stores, ReplaySources replay, StreamService streamService,
                            AssetRepositoryPort assetRepository, AuditTrailPort auditTrail, Supplier<Instant> clock) {
        this.stores = Objects.requireNonNull(stores, "stores must not be null");
        this.replay = Objects.requireNonNull(replay, "replay must not be null");
        this.streamService = Objects.requireNonNull(streamService, "streamService must not be null");
        this.assetRepository = Objects.requireNonNull(assetRepository, "assetRepository must not be null");
        this.auditTrail = Objects.requireNonNull(auditTrail, "auditTrail must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public TrainingSample capture(CaptureSpec spec, UserId actor, VisibilityScope scope) {
        Objects.requireNonNull(spec, "spec must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(scope, "scope must not be null");

        Dataset dataset = requireDataset(spec.datasetId());
        requireDatasetVisible(dataset, actor, scope, ACTION_CAPTURE);

        Asset resolvedAsset = resolveAssetForStream(spec.streamId()).orElse(null);
        requireAssetVisible(resolvedAsset, actor, scope, dataset.id(), ACTION_CAPTURE);

        VideoFrame frame = streamService.latestRawFrame(spec.streamId())
                .orElseThrow(() -> new NoSuchElementException("No frame available for stream "
                        + spec.streamId().value()));
        List<Annotation> annotations = toModelAnnotations(streamService.latestDetections(spec.streamId()));

        TrainingSample saved = saveCapturedSample(dataset.id(), spec.streamId(),
                resolvedAsset == null ? null : resolvedAsset.id(), frame, annotations);

        audit(actor, dataset.id(), AuditAction.UPDATED, ACTION_CAPTURE, "CAPTURED",
                "Captured sample " + saved.id().value() + " from stream " + spec.streamId().value());
        return saved;
    }

    @Override
    public TrainingSample captureFromReplay(ReplayCaptureSpec spec, UserId actor, VisibilityScope scope) {
        Objects.requireNonNull(spec, "spec must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(scope, "scope must not be null");

        Dataset dataset = requireDataset(spec.datasetId());
        requireDatasetVisible(dataset, actor, scope, ACTION_CAPTURE_REPLAY);

        AssetUsage usage = replay.usages().findById(spec.usageId())
                .orElseThrow(() -> new NoSuchElementException("Unknown usage: " + spec.usageId().value()));
        if (usage.streamId() == null) {
            throw new NoSuchElementException("Usage " + spec.usageId().value() + " has no recorded video stream");
        }

        Asset asset = assetRepository.findById(usage.assetId()).orElse(null);
        requireAssetVisible(asset, actor, scope, dataset.id(), ACTION_CAPTURE_REPLAY);

        Instant at = usage.startedAt().plusMillis(Math.round(spec.atSeconds() * 1000));
        Instant windowEnd = usage.endedAt() != null ? usage.endedAt() : clock.get();
        if (at.isAfter(windowEnd)) {
            throw new IllegalArgumentException("atSeconds " + spec.atSeconds() + " is past usage "
                    + spec.usageId().value() + "'s recorded window");
        }

        VideoFrame frame = replay.frames().frameAt(usage.streamId(), at)
                .orElseThrow(() -> new NoSuchElementException(
                        "No recorded frame at " + at + " for stream " + usage.streamId().value()));
        List<Annotation> annotations = nearestModelAnnotations(usage.streamId(), at);

        TrainingSample saved = saveCapturedSample(dataset.id(), usage.streamId(), usage.assetId(), frame,
                annotations);

        audit(actor, dataset.id(), AuditAction.UPDATED, ACTION_CAPTURE_REPLAY, "CAPTURED",
                "Captured sample " + saved.id().value() + " from replay of usage " + spec.usageId().value()
                        + " at " + at);
        return saved;
    }

    @Override
    public List<TrainingSample> samples(DatasetId id, SampleStatus statusOrNull, int limit, UserId actor,
                                         VisibilityScope scope) {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(scope, "scope must not be null");
        Dataset dataset = requireDataset(id);
        requireDatasetVisible(dataset, actor, scope, ACTION_SAMPLES);
        return stores.samples().findByDataset(id, statusOrNull, limit);
    }

    @Override
    public SampleImage image(TrainingSampleId id, UserId actor, VisibilityScope scope) {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(scope, "scope must not be null");
        TrainingSample sample = requireSample(id);
        Dataset dataset = requireDataset(sample.datasetId());
        requireDatasetVisible(dataset, actor, scope, ACTION_IMAGE);
        return stores.images().findById(id)
                .orElseThrow(() -> new NoSuchElementException("No image stored for sample: " + id.value()));
    }

    @Override
    public TrainingSample label(TrainingSampleId id, LabelSpec spec, UserId actor, VisibilityScope scope) {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(spec, "spec must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(scope, "scope must not be null");

        TrainingSample sample = requireSample(id);
        Dataset dataset = requireDataset(sample.datasetId());
        requireDatasetVisible(dataset, actor, scope, ACTION_LABEL);
        requireAssetVisibleIfKnown(sample.assetId(), actor, scope, dataset.id(), ACTION_LABEL);

        if (spec.status() == SampleStatus.LABELED) {
            for (Annotation annotation : spec.annotations()) {
                if (!dataset.classes().contains(annotation.label())) {
                    throw new IllegalArgumentException("Annotation label '" + annotation.label()
                            + "' is not a member of dataset '" + dataset.name() + "' classes: " + dataset.classes());
                }
            }
        }

        TrainingSample updated = new TrainingSample(sample.id(), sample.datasetId(), sample.streamId(),
                sample.assetId(), sample.capturedAt(), sample.width(), sample.height(), spec.annotations(),
                spec.status(), actor, clock.get());
        TrainingSample saved = stores.samples().save(updated);
        audit(actor, dataset.id(), AuditAction.UPDATED, ACTION_LABEL, spec.status().name(),
                "Sample " + id.value() + " marked " + spec.status());
        return saved;
    }

    @Override
    public DatasetUpload uploadForTraining(DatasetId id, UserId actor, VisibilityScope scope) {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(scope, "scope must not be null");

        Dataset dataset = requireDataset(id);
        requireDatasetVisible(dataset, actor, scope, ACTION_UPLOAD);

        List<TrainingSample> labeled = stores.samples().findByDataset(id, SampleStatus.LABELED, UPLOAD_SAMPLE_LIMIT);
        List<DatasetUploadPort.ExportEntry> entries = new ArrayList<>(labeled.size());
        for (TrainingSample sample : labeled) {
            SampleImage image = stores.images().findById(sample.id())
                    .orElseThrow(() -> new IllegalStateException(
                            "Labeled sample " + sample.id().value() + " has no stored image"));
            entries.add(YoloDatasetWriter.toEntry(sample, image, dataset.classes()));
        }
        String dataYaml = YoloDatasetWriter.dataYaml(dataset.classes());
        DatasetUpload upload = stores.uploads().upload(id, dataYaml, entries);

        audit(actor, id, AuditAction.UPDATED, ACTION_UPLOAD, "UPLOADED:" + entries.size(),
                "Uploaded " + entries.size() + " labeled sample(s) from dataset '" + dataset.name()
                        + "' for training");
        return upload;
    }

    /**
     * Builds and persists (sample row + JPEG image) a {@link SampleStatus#PENDING} training sample
     * shared by both {@link #capture} and {@link #captureFromReplay} — same shape, same encoding
     * path, differing only in where the frame/asset/annotations came from.
     */
    private TrainingSample saveCapturedSample(DatasetId datasetId, StreamId streamId, AssetId assetId,
                                               VideoFrame frame, List<Annotation> annotations) {
        TrainingSampleId sampleId = TrainingSampleId.random();
        TrainingSample sample = new TrainingSample(sampleId, datasetId, streamId, assetId, frame.capturedAt(),
                frame.width(), frame.height(), annotations, SampleStatus.PENDING, null, null);
        TrainingSample saved = stores.samples().save(sample);
        stores.images().save(sampleId, new SampleImage(TrainingFrameEncoder.encode(frame), CONTENT_TYPE_JPEG));
        return saved;
    }

    private static List<Annotation> toModelAnnotations(List<Detection> detections) {
        return detections.stream()
                .map(d -> new Annotation(d.label(), d.box(), AnnotationSource.MODEL))
                .toList();
    }

    /**
     * The suggested annotations for a replay capture (docs/CV-TRAINING-V2-PLAN.md §4/§F): queries
     * stored detections in a ±{@link #NEAREST_DETECTION_TOLERANCE} window around {@code at} and maps
     * the single nearest {@link DetectionResult} (ties broken toward the earlier one) to {@code
     * MODEL} annotations. Empty when nothing was recorded in the window — never a fabricated box.
     */
    private List<Annotation> nearestModelAnnotations(StreamId streamId, Instant at) {
        DetectionQuery query = new DetectionQuery(streamId, at.minus(NEAREST_DETECTION_TOLERANCE),
                at.plus(NEAREST_DETECTION_TOLERANCE), null, NEAREST_DETECTION_FETCH_LIMIT);
        DetectionResult nearest = null;
        Duration nearestDelta = null;
        for (DetectionResult result : replay.detections().query(query)) {
            Duration delta = Duration.between(at, result.capturedAt()).abs();
            boolean closer = nearest == null || delta.compareTo(nearestDelta) < 0
                    || (delta.compareTo(nearestDelta) == 0 && result.capturedAt().isBefore(nearest.capturedAt()));
            if (closer) {
                nearest = result;
                nearestDelta = delta;
            }
        }
        return nearest == null ? List.of() : toModelAnnotations(nearest.detections());
    }

    private Dataset requireDataset(DatasetId id) {
        return stores.datasets().findById(id)
                .orElseThrow(() -> new NoSuchElementException("Unknown dataset: " + id.value()));
    }

    private TrainingSample requireSample(TrainingSampleId id) {
        return stores.samples().findById(id)
                .orElseThrow(() -> new NoSuchElementException("Unknown training sample: " + id.value()));
    }

    /**
     * Whether {@code scope} may see {@code dataset} — see this class's own javadoc for why a
     * {@link VisibilityScope.Kind#ASSIGNED_ASSETS} (pilot) scope sees every dataset here, unlike
     * {@link DatasetService}'s stricter group-only gate.
     */
    private static boolean canSeeDataset(Dataset dataset, VisibilityScope scope) {
        return scope.kind() != VisibilityScope.Kind.GROUPS || scope.includesGroup(dataset.ownership().groupId());
    }

    private void requireDatasetVisible(Dataset dataset, UserId actor, VisibilityScope scope, String action) {
        if (!canSeeDataset(dataset, scope)) {
            auditDenied(actor, dataset.id(), action,
                    "Denied " + action + " on dataset " + dataset.id().value() + ": out of scope");
            throw new AccessDeniedException("Dataset " + dataset.id().value() + " is outside your scope");
        }
    }

    /**
     * Gates a resolved source {@code asset} against {@code scope}, auditing and throwing on a
     * denial. A {@code null} {@code asset} (unresolved — only possible from {@link #capture}'s live
     * path, see this class's own javadoc) has nothing to gate and is silently permitted.
     */
    private void requireAssetVisible(Asset asset, UserId actor, VisibilityScope scope, DatasetId datasetId,
                                      String action) {
        if (asset != null && !scope.includes(asset)) {
            auditDenied(actor, datasetId, action, "Denied " + action + " on dataset " + datasetId.value()
                    + ": asset " + asset.id().value() + " out of scope");
            throw new AccessDeniedException("Asset " + asset.id().value()
                    + " is outside your scope; you may not capture from it");
        }
    }

    /**
     * Gates {@code assetId} (when known — see this class's own javadoc for why it may be {@code
     * null}) against {@code scope}, auditing and throwing on a denial exactly like {@link #capture}
     * does for the same asset gate. A {@code null} {@code assetId} (unresolved at capture time) has
     * nothing to gate and is silently permitted.
     */
    private void requireAssetVisibleIfKnown(AssetId assetId, UserId actor, VisibilityScope scope,
                                             DatasetId datasetId, String action) {
        if (assetId == null) {
            return;
        }
        Asset asset = assetRepository.findById(assetId).orElse(null);
        if (asset != null && !scope.includes(asset)) {
            auditDenied(actor, datasetId, action,
                    "Denied " + action + " on dataset " + datasetId.value() + ": asset " + assetId.value()
                            + " out of scope");
            throw new AccessDeniedException(
                    "Asset " + assetId.value() + " is outside your scope; you may not label its samples");
        }
    }

    /**
     * Resolves {@code streamId}'s current device from {@link StreamService#streams()}'s live
     * snapshot, then that device's owning asset — see this class's own javadoc for the full
     * reasoning. Empty when the stream is not currently running, or its device belongs to no asset.
     */
    private Optional<Asset> resolveAssetForStream(StreamId streamId) {
        Optional<DeviceId> deviceId = streamService.streams().stream()
                .filter(active -> active.streamId().equals(streamId))
                .map(ActiveStream::deviceId)
                .findFirst();
        return deviceId.flatMap(assetRepository::findByDeviceId);
    }

    private void auditDenied(UserId actor, DatasetId datasetId, String action, String summary) {
        audit(actor, datasetId, AuditAction.UPDATED, action, DENIED_OUT_OF_SCOPE, summary);
    }

    private void audit(UserId actor, DatasetId datasetId, AuditAction auditAction, String action, String result,
                        String summary) {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put(ATTR_DATASET_ID, datasetId.value().toString());
        attributes.put(ATTR_ACTION, action);
        attributes.put(ATTR_RESULT, result);
        auditTrail.record(AuditEntry.of(actor, auditAction, AuditTargetType.DATASET, datasetId.value().toString(),
                summary, attributes));
    }
}
