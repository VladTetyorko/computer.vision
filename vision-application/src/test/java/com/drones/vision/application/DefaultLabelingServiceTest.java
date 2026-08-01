package com.drones.vision.application;

import com.drones.vision.domain.model.Annotation;
import com.drones.vision.domain.model.AnnotationSource;
import com.drones.vision.domain.model.Asset;
import com.drones.vision.domain.model.AssetId;
import com.drones.vision.domain.model.AuditAction;
import com.drones.vision.domain.model.AuditEntry;
import com.drones.vision.domain.model.AuditTargetType;
import com.drones.vision.domain.model.BoundingBox;
import com.drones.vision.domain.model.CategoryId;
import com.drones.vision.domain.model.Dataset;
import com.drones.vision.domain.model.DatasetExport;
import com.drones.vision.domain.model.DatasetId;
import com.drones.vision.domain.model.DatasetStatus;
import com.drones.vision.domain.model.Detection;
import com.drones.vision.domain.model.DeviceId;
import com.drones.vision.domain.model.GroupId;
import com.drones.vision.domain.model.ModelRef;
import com.drones.vision.domain.model.Ownership;
import com.drones.vision.domain.model.PixelFormat;
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
import com.drones.vision.domain.port.out.DatasetRepositoryPort;
import com.drones.vision.domain.port.out.SampleImageStorePort;
import com.drones.vision.domain.port.out.TrainingSampleRepositoryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultLabelingServiceTest {

    private FakeDatasetRepositoryPort datasetRepository;
    private FakeTrainingSampleRepositoryPort sampleRepository;
    private FakeSampleImageStorePort imageStore;
    private FakeDatasetExportPort exportPort;
    private FakeAssetRepositoryPort assetRepository;
    private FakeAuditTrailPort auditTrail;
    private StreamService streamService;
    private LabelingService service;

    private final UserId actor = UserId.random();
    private final GroupId group = GroupId.random();
    private final Ownership ownership = new Ownership(actor, group);
    private final Instant fixedNow = Instant.parse("2026-08-01T10:00:00Z");
    private final StreamId streamId = StreamId.random();
    private final DeviceId deviceId = DeviceId.random();

    @BeforeEach
    void setUp() {
        datasetRepository = new FakeDatasetRepositoryPort();
        sampleRepository = new FakeTrainingSampleRepositoryPort();
        imageStore = new FakeSampleImageStorePort();
        exportPort = new FakeDatasetExportPort();
        assetRepository = new FakeAssetRepositoryPort();
        auditTrail = new FakeAuditTrailPort();
        streamService = mock(StreamService.class);
        TrainingStores stores = new TrainingStores(datasetRepository, sampleRepository, imageStore, exportPort);
        service = new DefaultLabelingService(stores, streamService, assetRepository, auditTrail, () -> fixedNow);

        when(streamService.streams()).thenReturn(List.of());
    }

    private Dataset dataset(Ownership owner, List<String> classes) {
        Dataset dataset = new Dataset(DatasetId.random(), "Buildings", new CategoryId("building"), classes, owner,
                DatasetStatus.OPEN, Instant.now());
        datasetRepository.save(dataset);
        return dataset;
    }

    private static VideoFrame jpegFrame(byte[] bytes) {
        return new VideoFrame(StreamId.random(), 0, Instant.parse("2026-08-01T09:59:00Z"), 1920, 1080,
                PixelFormat.JPEG, ByteBuffer.wrap(bytes));
    }

    private Asset asset(AssetId assetId, GroupId owningGroup) {
        Asset asset = new Asset(assetId, "Drone 1", new CategoryId("drone"), new Ownership(actor, owningGroup),
                Set.of(DeviceId.random()), Map.of());
        assetRepository.save(asset);
        return asset;
    }

    private void stubActiveStream(DeviceId device) {
        when(streamService.streams()).thenReturn(List.of(new ActiveStream(streamId, device, Instant.now())));
    }

    // --- capture -------------------------------------------------------------

    @Test
    void captureCreatesAPendingSampleWithModelAnnotationsAndStoresTheImage() {
        Dataset dataset = dataset(ownership, List.of("building"));
        stubActiveStream(deviceId);
        Asset owningAsset = asset(AssetId.random(), group);
        assetRepository.byDevice.put(deviceId, owningAsset.id());
        VideoFrame frame = jpegFrame(new byte[]{1, 2, 3});
        when(streamService.latestRawFrame(streamId)).thenReturn(Optional.of(frame));
        Detection detection = new Detection("building", 0.8, new BoundingBox(0.1, 0.2, 0.3, 0.4),
                new ModelRef("yolo26n.pt", "latest"));
        when(streamService.latestDetections(streamId)).thenReturn(List.of(detection));

        TrainingSample sample = service.capture(new CaptureSpec(streamId, dataset.id()), actor,
                VisibilityScope.unbounded());

        assertEquals(dataset.id(), sample.datasetId());
        assertEquals(streamId, sample.streamId());
        assertEquals(owningAsset.id(), sample.assetId());
        assertEquals(frame.capturedAt(), sample.capturedAt());
        assertEquals(1920, sample.width());
        assertEquals(1080, sample.height());
        assertEquals(SampleStatus.PENDING, sample.status());
        assertNull(sample.labeledBy());
        assertNull(sample.labeledAt());
        assertEquals(1, sample.annotations().size());
        Annotation annotation = sample.annotations().get(0);
        assertEquals("building", annotation.label());
        assertEquals(AnnotationSource.MODEL, annotation.source());
        assertEquals(detection.box(), annotation.box());

        SampleImage storedImage = imageStore.findById(sample.id()).orElseThrow();
        assertEquals("image/jpeg", storedImage.contentType());
        assertEquals(3, storedImage.data().length);

        AuditEntry entry = onlyEntry();
        assertEquals(AuditAction.UPDATED, entry.action());
        assertEquals(AuditTargetType.DATASET, entry.targetType());
        assertEquals("CAPTURED", entry.details().get("result"));
        assertTrue(entry.summary().contains("Captured"));
    }

    @Test
    void captureLeavesAssetIdNullWhenTheDeviceHasNoOwningAsset() {
        Dataset dataset = dataset(ownership, List.of("building"));
        stubActiveStream(deviceId); // no asset registered for deviceId
        VideoFrame frame = jpegFrame(new byte[]{9});
        when(streamService.latestRawFrame(streamId)).thenReturn(Optional.of(frame));
        when(streamService.latestDetections(streamId)).thenReturn(List.of());

        TrainingSample sample = service.capture(new CaptureSpec(streamId, dataset.id()), actor,
                VisibilityScope.unbounded());

        assertNull(sample.assetId());
        assertEquals(List.of(), sample.annotations());
    }

    @Test
    void captureThrowsNoSuchElementWhenTheStreamHasNoFrameYet() {
        Dataset dataset = dataset(ownership, List.of("building"));
        when(streamService.latestRawFrame(streamId)).thenReturn(Optional.empty());

        assertThrows(NoSuchElementException.class,
                () -> service.capture(new CaptureSpec(streamId, dataset.id()), actor, VisibilityScope.unbounded()));
        assertEquals(0, sampleRepository.store.size());
    }

    @Test
    void captureThrowsNoSuchElementForAnUnknownDataset() {
        assertThrows(NoSuchElementException.class, () -> service.capture(
                new CaptureSpec(streamId, DatasetId.random()), actor, VisibilityScope.unbounded()));
    }

    @Test
    void captureDeniedWhenDatasetIsOutOfScopeAndNeverTouchesTheStream() {
        Dataset dataset = dataset(ownership, List.of("building"));

        assertThrows(AccessDeniedException.class, () -> service.capture(new CaptureSpec(streamId, dataset.id()),
                actor, VisibilityScope.groups(Set.of(GroupId.random()))));

        verify(streamService, never()).latestRawFrame(any());
        AuditEntry entry = onlyEntry();
        assertEquals("DENIED:out of scope", entry.details().get("result"));
    }

    @Test
    void captureDeniedWhenTheResolvedAssetIsOutOfScope() {
        Dataset dataset = dataset(ownership, List.of("building"));
        stubActiveStream(deviceId);
        Asset owningAsset = asset(AssetId.random(), GroupId.random()); // different group
        assetRepository.byDevice.put(deviceId, owningAsset.id());

        assertThrows(AccessDeniedException.class, () -> service.capture(new CaptureSpec(streamId, dataset.id()),
                actor, VisibilityScope.groups(Set.of(group))));

        assertEquals(0, sampleRepository.store.size());
        AuditEntry entry = onlyEntry();
        assertEquals("DENIED:out of scope", entry.details().get("result"));
    }

    @Test
    void capturePermittedForAPilotScopeThatCanSeeTheAssetEvenThoughItCannotSeeTheDatasetsGroup() {
        // The deliberate relaxation this class's javadoc documents: an ASSIGNED_ASSETS scope has no
        // group visibility at all, so it could never pass a strict group-ownership gate on the
        // dataset -- but it may still capture, gated only by asset visibility.
        Dataset dataset = dataset(new Ownership(actor, GroupId.random()), List.of("building"));
        stubActiveStream(deviceId);
        Asset owningAsset = asset(AssetId.random(), GroupId.random());
        assetRepository.byDevice.put(deviceId, owningAsset.id());
        when(streamService.latestRawFrame(streamId)).thenReturn(Optional.of(jpegFrame(new byte[]{1})));
        when(streamService.latestDetections(streamId)).thenReturn(List.of());

        TrainingSample sample = service.capture(new CaptureSpec(streamId, dataset.id()), actor,
                VisibilityScope.assignedAssets(Set.of(owningAsset.id())));

        assertEquals(owningAsset.id(), sample.assetId());
    }

    // --- label -----------------------------------------------------------------

    @Test
    void labelReplacesAnnotationsAndStampsLabeledByAndAt() {
        Dataset dataset = dataset(ownership, List.of("building", "tower"));
        TrainingSample pending = pendingSample(dataset.id(), null);
        List<Annotation> corrected = List.of(
                new Annotation("tower", new BoundingBox(0.05, 0.05, 0.1, 0.1), AnnotationSource.OPERATOR));

        TrainingSample labeled = service.label(pending.id(), new LabelSpec(corrected, SampleStatus.LABELED), actor,
                VisibilityScope.unbounded());

        assertEquals(SampleStatus.LABELED, labeled.status());
        assertEquals(corrected, labeled.annotations());
        assertEquals(actor, labeled.labeledBy());
        assertEquals(fixedNow, labeled.labeledAt());
        assertEquals(labeled, sampleRepository.findById(pending.id()).orElseThrow());
    }

    @Test
    void labelRejectsAnAnnotationLabelNotInDatasetClasses() {
        Dataset dataset = dataset(ownership, List.of("building"));
        TrainingSample pending = pendingSample(dataset.id(), null);
        List<Annotation> badAnnotations = List.of(
                new Annotation("tank", new BoundingBox(0.1, 0.1, 0.1, 0.1), AnnotationSource.OPERATOR));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> service.label(pending.id(),
                new LabelSpec(badAnnotations, SampleStatus.LABELED), actor, VisibilityScope.unbounded()));
        assertTrue(ex.getMessage().contains("tank"));
        assertEquals(pending, sampleRepository.findById(pending.id()).orElseThrow());
    }

    @Test
    void labelAllowsRelabelingAnAlreadyLabeledSample() {
        Dataset dataset = dataset(ownership, List.of("building"));
        TrainingSample pending = pendingSample(dataset.id(), null);
        List<Annotation> first = List.of(
                new Annotation("building", new BoundingBox(0.1, 0.1, 0.1, 0.1), AnnotationSource.OPERATOR));
        TrainingSample labeled = service.label(pending.id(), new LabelSpec(first, SampleStatus.LABELED), actor,
                VisibilityScope.unbounded());

        TrainingSample discarded = service.label(labeled.id(), new LabelSpec(List.of(), SampleStatus.DISCARDED),
                actor, VisibilityScope.unbounded());

        assertEquals(SampleStatus.DISCARDED, discarded.status());
        assertEquals(List.of(), discarded.annotations());
    }

    @Test
    void labelThrowsNoSuchElementForAnUnknownSample() {
        assertThrows(NoSuchElementException.class, () -> service.label(TrainingSampleId.random(),
                new LabelSpec(List.of(), SampleStatus.DISCARDED), actor, VisibilityScope.unbounded()));
    }

    @Test
    void labelDeniedWhenDatasetIsOutOfScope() {
        Dataset dataset = dataset(ownership, List.of("building"));
        TrainingSample pending = pendingSample(dataset.id(), null);

        assertThrows(AccessDeniedException.class, () -> service.label(pending.id(),
                new LabelSpec(List.of(), SampleStatus.DISCARDED), actor,
                VisibilityScope.groups(Set.of(GroupId.random()))));
        AuditEntry entry = onlyEntry();
        assertEquals("DENIED:out of scope", entry.details().get("result"));
    }

    @Test
    void labelDeniedWhenTheSamplesAssetIsOutOfScope() {
        Dataset dataset = dataset(ownership, List.of("building"));
        Asset sampleAsset = asset(AssetId.random(), GroupId.random());
        TrainingSample pending = pendingSample(dataset.id(), sampleAsset.id());

        assertThrows(AccessDeniedException.class, () -> service.label(pending.id(),
                new LabelSpec(List.of(), SampleStatus.DISCARDED), actor, VisibilityScope.groups(Set.of(group))));
    }

    // --- samples / image -------------------------------------------------------

    @Test
    void samplesReturnsTheDatasetsSamplesWhenInScope() {
        Dataset dataset = dataset(ownership, List.of("building"));
        TrainingSample sample = pendingSample(dataset.id(), null);

        assertEquals(List.of(sample), service.samples(dataset.id(), null, 50, actor, VisibilityScope.unbounded()));
    }

    @Test
    void samplesDeniedWhenDatasetIsOutOfScope() {
        Dataset dataset = dataset(ownership, List.of("building"));

        assertThrows(AccessDeniedException.class, () -> service.samples(dataset.id(), null, 50, actor,
                VisibilityScope.groups(Set.of(GroupId.random()))));
    }

    @Test
    void imageReturnsTheStoredImageWhenInScope() {
        Dataset dataset = dataset(ownership, List.of("building"));
        TrainingSample sample = pendingSample(dataset.id(), null);
        imageStore.save(sample.id(), new SampleImage(new byte[]{5, 6}, "image/jpeg"));

        SampleImage image = service.image(sample.id(), actor, VisibilityScope.unbounded());

        assertEquals("image/jpeg", image.contentType());
    }

    @Test
    void imageThrowsNoSuchElementWhenNoImageIsStored() {
        Dataset dataset = dataset(ownership, List.of("building"));
        TrainingSample sample = pendingSample(dataset.id(), null);

        assertThrows(NoSuchElementException.class, () -> service.image(sample.id(), actor, VisibilityScope.unbounded()));
    }

    @Test
    void imageDeniedWhenDatasetIsOutOfScope() {
        Dataset dataset = dataset(ownership, List.of("building"));
        TrainingSample sample = pendingSample(dataset.id(), null);
        imageStore.save(sample.id(), new SampleImage(new byte[]{5}, "image/jpeg"));

        assertThrows(AccessDeniedException.class, () -> service.image(sample.id(), actor,
                VisibilityScope.groups(Set.of(GroupId.random()))));
    }

    // --- export ------------------------------------------------------------------

    @Test
    void exportIncludesOnlyLabeledSamplesAndConvertsBoxesToYoloCenterFormat() {
        Dataset dataset = dataset(ownership, List.of("building", "tower"));
        // A PENDING sample and a DISCARDED sample must both be excluded.
        pendingSample(dataset.id(), null);
        TrainingSample discarded = labeledSample(dataset.id(),
                List.of(new Annotation("tower", new BoundingBox(0, 0, 0.2, 0.2), AnnotationSource.OPERATOR)));
        service.label(discarded.id(), new LabelSpec(discarded.annotations(), SampleStatus.DISCARDED), actor,
                VisibilityScope.unbounded());

        TrainingSample labeled = labeledSample(dataset.id(), List.of(
                new Annotation("tower", new BoundingBox(0.10, 0.20, 0.30, 0.40), AnnotationSource.OPERATOR)));
        imageStore.save(labeled.id(), new SampleImage(new byte[]{7, 7, 7}, "image/jpeg"));

        DatasetExport export = service.export(dataset.id(), actor, VisibilityScope.unbounded());

        assertEquals(dataset.id(), export.datasetId());
        assertEquals(1, exportPort.lastEntries.size(), "only the LABELED sample must be exported");
        DatasetExportPort.ExportEntry entry = exportPort.lastEntries.get(0);
        assertEquals(labeled.id().value() + ".jpg", entry.imageName());
        // class index 1 ("tower"), cx = 0.10 + 0.30/2 = 0.25, cy = 0.20 + 0.40/2 = 0.40
        assertEquals("1 0.250000 0.400000 0.300000 0.400000\n", entry.labelFileText());

        AuditEntry auditEntry = auditTrail.entries.get(auditTrail.entries.size() - 1);
        assertEquals(AuditAction.UPDATED, auditEntry.action());
        assertEquals("EXPORTED:1", auditEntry.details().get("result"));
    }

    @Test
    void exportThrowsIllegalStateWhenALabeledSampleHasNoStoredImage() {
        Dataset dataset = dataset(ownership, List.of("building"));
        labeledSample(dataset.id(),
                List.of(new Annotation("building", new BoundingBox(0.1, 0.1, 0.1, 0.1), AnnotationSource.OPERATOR)));
        // no image saved for it

        assertThrows(IllegalStateException.class, () -> service.export(dataset.id(), actor, VisibilityScope.unbounded()));
    }

    @Test
    void exportDeniedWhenDatasetIsOutOfScope() {
        Dataset dataset = dataset(ownership, List.of("building"));

        assertThrows(AccessDeniedException.class, () -> service.export(dataset.id(), actor,
                VisibilityScope.groups(Set.of(GroupId.random()))));
    }

    // --- helpers -----------------------------------------------------------------

    private TrainingSample pendingSample(DatasetId datasetId, AssetId assetId) {
        TrainingSample sample = new TrainingSample(TrainingSampleId.random(), datasetId, streamId, assetId,
                Instant.now(), 640, 480, List.of(), SampleStatus.PENDING, null, null);
        return sampleRepository.save(sample);
    }

    private TrainingSample labeledSample(DatasetId datasetId, List<Annotation> annotations) {
        TrainingSample sample = new TrainingSample(TrainingSampleId.random(), datasetId, streamId, null,
                Instant.now(), 640, 480, annotations, SampleStatus.LABELED, actor, Instant.now());
        return sampleRepository.save(sample);
    }

    private AuditEntry onlyEntry() {
        assertEquals(1, auditTrail.entries.size(), "expected exactly one audit entry");
        return auditTrail.entries.get(0);
    }

    private static final class FakeDatasetRepositoryPort implements DatasetRepositoryPort {
        private final Map<DatasetId, Dataset> store = new LinkedHashMap<>();

        @Override
        public Dataset save(Dataset dataset) {
            store.put(dataset.id(), dataset);
            return dataset;
        }

        @Override
        public Optional<Dataset> findById(DatasetId id) {
            return Optional.ofNullable(store.get(id));
        }

        @Override
        public List<Dataset> findAll() {
            return List.copyOf(store.values());
        }

        @Override
        public void delete(DatasetId id) {
            store.remove(id);
        }
    }

    private static final class FakeTrainingSampleRepositoryPort implements TrainingSampleRepositoryPort {
        private final Map<TrainingSampleId, TrainingSample> store = new LinkedHashMap<>();

        @Override
        public TrainingSample save(TrainingSample sample) {
            store.put(sample.id(), sample);
            return sample;
        }

        @Override
        public Optional<TrainingSample> findById(TrainingSampleId id) {
            return Optional.ofNullable(store.get(id));
        }

        @Override
        public List<TrainingSample> findByDataset(DatasetId datasetId, SampleStatus statusOrNull, int limit) {
            return store.values().stream()
                    .filter(s -> s.datasetId().equals(datasetId))
                    .filter(s -> statusOrNull == null || s.status() == statusOrNull)
                    .limit(limit)
                    .toList();
        }

        @Override
        public int countByDataset(DatasetId datasetId, SampleStatus statusOrNull) {
            return findByDataset(datasetId, statusOrNull, Integer.MAX_VALUE).size();
        }

        @Override
        public void delete(TrainingSampleId id) {
            store.remove(id);
        }
    }

    private static final class FakeSampleImageStorePort implements SampleImageStorePort {
        private final Map<TrainingSampleId, SampleImage> store = new LinkedHashMap<>();

        @Override
        public void save(TrainingSampleId id, SampleImage image) {
            store.put(id, image);
        }

        @Override
        public Optional<SampleImage> findById(TrainingSampleId id) {
            return Optional.ofNullable(store.get(id));
        }

        @Override
        public void delete(TrainingSampleId id) {
            store.remove(id);
        }
    }

    private static final class FakeDatasetExportPort implements DatasetExportPort {
        private List<ExportEntry> lastEntries = List.of();

        @Override
        public DatasetExport write(DatasetId datasetId, List<String> classes, List<ExportEntry> entries) {
            lastEntries = List.copyOf(entries);
            return new DatasetExport(datasetId, "export-1", Instant.now(), classes, entries.size(), 1024,
                    "/tmp/export-1");
        }

        @Override
        public Optional<java.nio.file.Path> resolve(DatasetId datasetId, String exportId) {
            return Optional.empty();
        }
    }

    private static final class FakeAssetRepositoryPort implements AssetRepositoryPort {
        private final Map<AssetId, Asset> store = new LinkedHashMap<>();
        private final Map<DeviceId, AssetId> byDevice = new LinkedHashMap<>();

        @Override
        public Asset save(Asset asset) {
            store.put(asset.id(), asset);
            return asset;
        }

        @Override
        public Optional<Asset> findById(AssetId id) {
            return Optional.ofNullable(store.get(id));
        }

        @Override
        public List<Asset> findAll() {
            return List.copyOf(store.values());
        }

        @Override
        public Optional<Asset> findByDeviceId(DeviceId deviceId) {
            AssetId assetId = byDevice.get(deviceId);
            return assetId == null ? Optional.empty() : findById(assetId);
        }

        @Override
        public void deleteById(AssetId id) {
            store.remove(id);
        }
    }

    private static final class FakeAuditTrailPort implements AuditTrailPort {
        private final List<AuditEntry> entries = new ArrayList<>();

        @Override
        public AuditEntry record(AuditEntry entry) {
            entries.add(entry);
            return entry;
        }

        @Override
        public List<AuditEntry> findRecent(int limit) {
            return List.copyOf(entries);
        }

        @Override
        public List<AuditEntry> findByTarget(AuditTargetType targetType, String targetId, int limit) {
            return List.of();
        }

        @Override
        public List<AuditEntry> findByActor(UserId actorId, int limit) {
            return List.of();
        }
    }
}
