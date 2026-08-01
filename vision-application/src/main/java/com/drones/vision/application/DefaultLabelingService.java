package com.drones.vision.application;

import com.drones.vision.domain.model.Annotation;
import com.drones.vision.domain.model.AnnotationSource;
import com.drones.vision.domain.model.Asset;
import com.drones.vision.domain.model.AssetId;
import com.drones.vision.domain.model.AuditAction;
import com.drones.vision.domain.model.AuditEntry;
import com.drones.vision.domain.model.AuditTargetType;
import com.drones.vision.domain.model.Dataset;
import com.drones.vision.domain.model.DatasetExport;
import com.drones.vision.domain.model.DatasetId;
import com.drones.vision.domain.model.Detection;
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
import com.drones.vision.domain.port.out.DatasetExportPort;

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
 * case, not an error.
 *
 * <h2>Threading</h2>
 * Holds no mutable state — all shared state is reached through the injected collaborators.
 */
public final class DefaultLabelingService implements LabelingService {

    /** Effectively-unbounded fetch for an export's labeled-sample pass. */
    private static final int EXPORT_SAMPLE_LIMIT = Integer.MAX_VALUE;

    private static final String ACTION_CAPTURE = "CAPTURE";
    private static final String ACTION_SAMPLES = "SAMPLES";
    private static final String ACTION_IMAGE = "IMAGE";
    private static final String ACTION_LABEL = "LABEL";
    private static final String ACTION_EXPORT = "EXPORT";
    private static final String DENIED_OUT_OF_SCOPE = "DENIED:out of scope";
    private static final String CONTENT_TYPE_JPEG = "image/jpeg";
    private static final String ATTR_DATASET_ID = "datasetId";
    private static final String ATTR_ACTION = "action";
    private static final String ATTR_RESULT = "result";

    private final TrainingStores stores;
    private final StreamService streamService;
    private final AssetRepositoryPort assetRepository;
    private final AuditTrailPort auditTrail;
    private final Supplier<Instant> clock;

    public DefaultLabelingService(TrainingStores stores, StreamService streamService,
                                   AssetRepositoryPort assetRepository, AuditTrailPort auditTrail) {
        this(stores, streamService, assetRepository, auditTrail, Instant::now);
    }

    /**
     * Test seam: same as the 4-argument constructor, with an injectable clock so {@code labeledAt}
     * is deterministic in tests instead of depending on wall-clock time.
     */
    DefaultLabelingService(TrainingStores stores, StreamService streamService, AssetRepositoryPort assetRepository,
                            AuditTrailPort auditTrail, Supplier<Instant> clock) {
        this.stores = Objects.requireNonNull(stores, "stores must not be null");
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
        if (resolvedAsset != null && !scope.includes(resolvedAsset)) {
            auditDenied(actor, dataset.id(), ACTION_CAPTURE, "Denied " + ACTION_CAPTURE + " on dataset "
                    + dataset.id().value() + ": asset " + resolvedAsset.id().value() + " out of scope");
            throw new AccessDeniedException("Asset " + resolvedAsset.id().value()
                    + " is outside your scope; you may not capture from it");
        }

        VideoFrame frame = streamService.latestRawFrame(spec.streamId())
                .orElseThrow(() -> new NoSuchElementException("No frame available for stream "
                        + spec.streamId().value()));
        List<Detection> detections = streamService.latestDetections(spec.streamId());
        List<Annotation> annotations = detections.stream()
                .map(d -> new Annotation(d.label(), d.box(), AnnotationSource.MODEL))
                .toList();

        TrainingSampleId sampleId = TrainingSampleId.random();
        TrainingSample sample = new TrainingSample(sampleId, dataset.id(), spec.streamId(),
                resolvedAsset == null ? null : resolvedAsset.id(), frame.capturedAt(), frame.width(),
                frame.height(), annotations, SampleStatus.PENDING, null, null);
        TrainingSample saved = stores.samples().save(sample);
        stores.images().save(sampleId, new SampleImage(TrainingFrameEncoder.encode(frame), CONTENT_TYPE_JPEG));

        audit(actor, dataset.id(), AuditAction.UPDATED, ACTION_CAPTURE, "CAPTURED",
                "Captured sample " + sampleId.value() + " from stream " + spec.streamId().value());
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

        for (Annotation annotation : spec.annotations()) {
            if (!dataset.classes().contains(annotation.label())) {
                throw new IllegalArgumentException("Annotation label '" + annotation.label()
                        + "' is not a member of dataset '" + dataset.name() + "' classes: " + dataset.classes());
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
    public DatasetExport export(DatasetId id, UserId actor, VisibilityScope scope) {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(scope, "scope must not be null");

        Dataset dataset = requireDataset(id);
        requireDatasetVisible(dataset, actor, scope, ACTION_EXPORT);

        List<TrainingSample> labeled = stores.samples().findByDataset(id, SampleStatus.LABELED, EXPORT_SAMPLE_LIMIT);
        List<DatasetExportPort.ExportEntry> entries = new ArrayList<>(labeled.size());
        for (TrainingSample sample : labeled) {
            SampleImage image = stores.images().findById(sample.id())
                    .orElseThrow(() -> new IllegalStateException(
                            "Labeled sample " + sample.id().value() + " has no stored image"));
            entries.add(YoloDatasetWriter.toEntry(sample, image, dataset.classes()));
        }
        DatasetExport export = stores.exports().write(id, dataset.classes(), entries);

        audit(actor, id, AuditAction.UPDATED, ACTION_EXPORT, "EXPORTED:" + entries.size(),
                "Exported " + entries.size() + " labeled sample(s) from dataset '" + dataset.name() + "'");
        return export;
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
