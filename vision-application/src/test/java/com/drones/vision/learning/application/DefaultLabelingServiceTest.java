package com.drones.vision.learning.application;

import com.drones.vision.learning.domain.model.Annotation;
import com.drones.vision.learning.domain.model.AnnotationSource;
import com.drones.vision.warehouse.domain.model.Asset;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.flight.domain.model.AssetUsage;
import com.drones.vision.platform.AuditAction;
import com.drones.vision.platform.AuditEntry;
import com.drones.vision.platform.AuditTargetType;
import com.drones.vision.kernel.BoundingBox;
import com.drones.vision.kernel.CategoryId;
import com.drones.vision.learning.domain.model.Dataset;
import com.drones.vision.learning.domain.model.DatasetId;
import com.drones.vision.learning.domain.model.DatasetStatus;
import com.drones.vision.learning.domain.model.DatasetUpload;
import com.drones.vision.perception.domain.model.Detection;
import com.drones.vision.perception.domain.model.DetectionQuery;
import com.drones.vision.perception.domain.model.DetectionResult;
import com.drones.vision.kernel.DeviceId;
import com.drones.vision.kernel.GroupId;
import com.drones.vision.perception.domain.model.ModelRef;
import com.drones.vision.kernel.Ownership;
import com.drones.vision.perception.domain.model.PixelFormat;
import com.drones.vision.learning.domain.model.SampleImage;
import com.drones.vision.learning.domain.model.SampleStatus;
import com.drones.vision.kernel.StreamId;
import com.drones.vision.learning.domain.model.TrainingSample;
import com.drones.vision.learning.domain.model.TrainingSampleId;
import com.drones.vision.kernel.UsageId;
import com.drones.vision.kernel.UserId;
import com.drones.vision.perception.domain.model.VideoFrame;
import com.drones.vision.warehouse.domain.port.AssetRepositoryPort;
import com.drones.vision.flight.domain.port.AssetUsageRepositoryPort;
import com.drones.vision.platform.AuditTrailPort;
import com.drones.vision.learning.domain.port.DatasetRepositoryPort;
import com.drones.vision.learning.domain.port.DatasetUploadPort;
import com.drones.vision.events.domain.port.DetectionRepositoryPort;
import com.drones.vision.events.domain.port.ReplayFrameExtractionPort;
import com.drones.vision.learning.domain.port.SampleImageStorePort;
import com.drones.vision.learning.domain.port.TrainingSampleRepositoryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.time.Duration;
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
import com.drones.vision.events.application.ReplayCaptureSpec;
import com.drones.vision.events.application.ReplaySources;
import com.drones.vision.platform.AccessDeniedException;
import com.drones.vision.platform.VisibilityScope;
import com.drones.vision.perception.application.stream.ActiveStream;
import com.drones.vision.perception.application.stream.StreamService;

class DefaultLabelingServiceTest {

    private FakeDatasetRepositoryPort datasetRepository;
    private FakeTrainingSampleRepositoryPort sampleRepository;
    private FakeSampleImageStorePort imageStore;
    private FakeDatasetUploadPort uploadPort;
    private FakeAssetRepositoryPort assetRepository;
    private FakeAssetUsageRepositoryPort usageRepository;
    private FakeDetectionRepositoryPort detectionRepository;
    private FakeReplayFrameExtractionPort frameExtractor;
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
        uploadPort = new FakeDatasetUploadPort();
        assetRepository = new FakeAssetRepositoryPort();
        usageRepository = new FakeAssetUsageRepositoryPort();
        detectionRepository = new FakeDetectionRepositoryPort();
        frameExtractor = new FakeReplayFrameExtractionPort();
        auditTrail = new FakeAuditTrailPort();
        streamService = mock(StreamService.class);
        TrainingStores stores = new TrainingStores(datasetRepository, sampleRepository, imageStore, uploadPort);
        ReplaySources replay = new ReplaySources(usageRepository, detectionRepository, frameExtractor);
        service = new DefaultLabelingService(stores, replay, streamService, assetRepository, auditTrail,
                () -> fixedNow);

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

    private static VideoFrame jpegFrame(StreamId onStream, Instant capturedAt, byte[] bytes) {
        return new VideoFrame(onStream, 0, capturedAt, 1920, 1080, PixelFormat.JPEG, ByteBuffer.wrap(bytes));
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

    // --- captureFromReplay -----------------------------------------------------

    private static final Instant USAGE_STARTED_AT = Instant.parse("2026-08-01T09:00:00Z");
    private static final Instant USAGE_ENDED_AT = Instant.parse("2026-08-01T09:10:00Z");
    private static final double AT_SECONDS = 120.0; // -> 09:02:00Z
    private static final Instant AT = USAGE_STARTED_AT.plusSeconds((long) AT_SECONDS);

    private AssetUsage openUsage(AssetId assetId, StreamId onStream) {
        AssetUsage usage = new AssetUsage(UsageId.random(), assetId, USAGE_STARTED_AT, USAGE_ENDED_AT, null, null, 0,
                onStream);
        return usageRepository.save(usage);
    }

    @Test
    void captureFromReplayBuildsAPendingSampleWithModelAnnotationsFromTheNearestInWindowDetection() {
        Dataset dataset = dataset(ownership, List.of("building"));
        Asset owningAsset = asset(AssetId.random(), group);
        StreamId replayStreamId = StreamId.random();
        AssetUsage usage = openUsage(owningAsset.id(), replayStreamId);
        VideoFrame frame = jpegFrame(replayStreamId, AT, new byte[]{4, 5, 6});
        frameExtractor.put(replayStreamId, AT, frame);

        Detection detection = new Detection("building", 0.7, new BoundingBox(0.1, 0.1, 0.2, 0.2),
                new ModelRef("yolo26n.pt", "latest"));
        // one second inside the +-2s tolerance window, and a farther-away decoy to prove "nearest" wins
        detectionRepository.save(new DetectionResult(replayStreamId, 1, AT.minusSeconds(1),
                List.of(detection), Duration.ZERO));
        Detection decoy = new Detection("building", 0.5, new BoundingBox(0.9, 0.9, 0.05, 0.05),
                new ModelRef("yolo26n.pt", "latest"));
        detectionRepository.save(new DetectionResult(replayStreamId, 2, AT.plusSeconds(2),
                List.of(decoy), Duration.ZERO));

        TrainingSample sample = service.captureFromReplay(new ReplayCaptureSpec(usage.id(), dataset.id(), AT_SECONDS),
                actor, VisibilityScope.unbounded());

        assertEquals(dataset.id(), sample.datasetId());
        assertEquals(replayStreamId, sample.streamId());
        assertEquals(owningAsset.id(), sample.assetId());
        assertEquals(AT, sample.capturedAt());
        assertEquals(SampleStatus.PENDING, sample.status());
        assertNull(sample.labeledBy());
        assertNull(sample.labeledAt());
        assertEquals(1, sample.annotations().size());
        Annotation annotation = sample.annotations().get(0);
        assertEquals("building", annotation.label());
        assertEquals(AnnotationSource.MODEL, annotation.source());
        assertEquals(detection.box(), annotation.box(), "must pick the nearest detection, not the decoy");

        SampleImage storedImage = imageStore.findById(sample.id()).orElseThrow();
        assertEquals(3, storedImage.data().length);

        AuditEntry entry = onlyEntry();
        assertEquals(AuditAction.UPDATED, entry.action());
        assertEquals("CAPTURE_REPLAY", entry.details().get("action"));
        assertEquals("CAPTURED", entry.details().get("result"));
    }

    @Test
    void captureFromReplayYieldsEmptyAnnotationsWhenNothingWasDetectedInTheWindow() {
        Dataset dataset = dataset(ownership, List.of("building"));
        Asset owningAsset = asset(AssetId.random(), group);
        StreamId replayStreamId = StreamId.random();
        AssetUsage usage = openUsage(owningAsset.id(), replayStreamId);
        frameExtractor.put(replayStreamId, AT, jpegFrame(replayStreamId, AT, new byte[]{1}));
        // no detections saved at all

        TrainingSample sample = service.captureFromReplay(new ReplayCaptureSpec(usage.id(), dataset.id(), AT_SECONDS),
                actor, VisibilityScope.unbounded());

        assertEquals(List.of(), sample.annotations());
    }

    @Test
    void captureFromReplayThrowsNoSuchElementForAnUnknownUsage() {
        Dataset dataset = dataset(ownership, List.of("building"));

        assertThrows(NoSuchElementException.class, () -> service.captureFromReplay(
                new ReplayCaptureSpec(UsageId.random(), dataset.id(), AT_SECONDS), actor,
                VisibilityScope.unbounded()));
    }

    @Test
    void captureFromReplayThrowsNoSuchElementWhenTheUsageHasNoRecordedStream() {
        Dataset dataset = dataset(ownership, List.of("building"));
        Asset owningAsset = asset(AssetId.random(), group);
        AssetUsage usage = new AssetUsage(UsageId.random(), owningAsset.id(), USAGE_STARTED_AT, USAGE_ENDED_AT,
                null, null, 0); // 7-arg convenience ctor -> streamId null
        usageRepository.save(usage);

        assertThrows(NoSuchElementException.class, () -> service.captureFromReplay(
                new ReplayCaptureSpec(usage.id(), dataset.id(), AT_SECONDS), actor, VisibilityScope.unbounded()));
    }

    @Test
    void captureFromReplayThrowsNoSuchElementWhenNoFrameIsRecordedAtThatInstant() {
        Dataset dataset = dataset(ownership, List.of("building"));
        Asset owningAsset = asset(AssetId.random(), group);
        StreamId replayStreamId = StreamId.random();
        AssetUsage usage = openUsage(owningAsset.id(), replayStreamId);
        // frameExtractor has nothing registered for (replayStreamId, AT)

        assertThrows(NoSuchElementException.class, () -> service.captureFromReplay(
                new ReplayCaptureSpec(usage.id(), dataset.id(), AT_SECONDS), actor, VisibilityScope.unbounded()));
    }

    @Test
    void captureFromReplayThrowsIllegalArgumentWhenAtSecondsIsPastTheUsageWindow() {
        Dataset dataset = dataset(ownership, List.of("building"));
        Asset owningAsset = asset(AssetId.random(), group);
        StreamId replayStreamId = StreamId.random();
        AssetUsage usage = openUsage(owningAsset.id(), replayStreamId); // ends 10 minutes after start

        assertThrows(IllegalArgumentException.class, () -> service.captureFromReplay(
                new ReplayCaptureSpec(usage.id(), dataset.id(), 900.0), actor, VisibilityScope.unbounded()));
    }

    @Test
    void captureFromReplayDeniedWhenTheUsagesAssetIsOutOfScope() {
        Dataset dataset = dataset(ownership, List.of("building"));
        Asset owningAsset = asset(AssetId.random(), GroupId.random()); // different group than the scope below
        StreamId replayStreamId = StreamId.random();
        AssetUsage usage = openUsage(owningAsset.id(), replayStreamId);

        assertThrows(AccessDeniedException.class, () -> service.captureFromReplay(
                new ReplayCaptureSpec(usage.id(), dataset.id(), AT_SECONDS), actor,
                VisibilityScope.groups(Set.of(group))));

        assertEquals(0, sampleRepository.store.size());
        AuditEntry entry = onlyEntry();
        assertEquals("DENIED:out of scope", entry.details().get("result"));
    }

    @Test
    void captureFromReplayThrowsNoSuchElementForAnUnknownDataset() {
        assertThrows(NoSuchElementException.class, () -> service.captureFromReplay(
                new ReplayCaptureSpec(UsageId.random(), DatasetId.random(), AT_SECONDS), actor,
                VisibilityScope.unbounded()));
    }

    @Test
    void captureFromReplayDeniedWhenDatasetIsOutOfScope() {
        Dataset dataset = dataset(ownership, List.of("building"));

        assertThrows(AccessDeniedException.class, () -> service.captureFromReplay(
                new ReplayCaptureSpec(UsageId.random(), dataset.id(), AT_SECONDS), actor,
                VisibilityScope.groups(Set.of(GroupId.random()))));
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
    void labelDiscardsASampleWhoseAnnotationLabelIsNotInDatasetClasses() {
        Dataset dataset = dataset(ownership, List.of("building"));
        TrainingSample pending = pendingSample(dataset.id(), null);
        List<Annotation> outOfVocab = List.of(
                new Annotation("tank", new BoundingBox(0.1, 0.1, 0.1, 0.1), AnnotationSource.MODEL));

        TrainingSample discarded = service.label(pending.id(),
                new LabelSpec(outOfVocab, SampleStatus.DISCARDED), actor, VisibilityScope.unbounded());

        assertEquals(SampleStatus.DISCARDED, discarded.status());
        assertEquals(outOfVocab, discarded.annotations());
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

    // --- uploadForTraining -------------------------------------------------------

    @Test
    void uploadForTrainingIncludesOnlyLabeledSamplesAndConvertsBoxesToYoloCenterFormat() {
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

        DatasetUpload upload = service.uploadForTraining(dataset.id(), actor, VisibilityScope.unbounded());

        assertEquals(dataset.id(), upload.datasetId());
        assertEquals(1, upload.sampleCount());
        assertEquals(1, uploadPort.lastEntries.size(), "only the LABELED sample must be uploaded");
        DatasetUploadPort.ExportEntry entry = uploadPort.lastEntries.get(0);
        assertEquals(labeled.id().value() + ".jpg", entry.imageName());
        // class index 1 ("tower"), cx = 0.10 + 0.30/2 = 0.25, cy = 0.20 + 0.40/2 = 0.40
        assertEquals("1 0.250000 0.400000 0.300000 0.400000\n", entry.labelFileText());
        assertEquals("names: [building, tower]\nnc: 2\ntrain: images\nval: images\n", uploadPort.lastDataYaml);

        AuditEntry auditEntry = auditTrail.entries.get(auditTrail.entries.size() - 1);
        assertEquals(AuditAction.UPDATED, auditEntry.action());
        assertEquals("UPLOADED:1", auditEntry.details().get("result"));
    }

    @Test
    void uploadForTrainingThrowsIllegalStateWhenALabeledSampleHasNoStoredImage() {
        Dataset dataset = dataset(ownership, List.of("building"));
        labeledSample(dataset.id(),
                List.of(new Annotation("building", new BoundingBox(0.1, 0.1, 0.1, 0.1), AnnotationSource.OPERATOR)));
        // no image saved for it

        assertThrows(IllegalStateException.class,
                () -> service.uploadForTraining(dataset.id(), actor, VisibilityScope.unbounded()));
    }

    @Test
    void uploadForTrainingDeniedWhenDatasetIsOutOfScope() {
        Dataset dataset = dataset(ownership, List.of("building"));

        assertThrows(AccessDeniedException.class, () -> service.uploadForTraining(dataset.id(), actor,
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

    private static final class FakeDatasetUploadPort implements DatasetUploadPort {
        private List<ExportEntry> lastEntries = List.of();
        private String lastDataYaml = "";

        @Override
        public DatasetUpload upload(DatasetId datasetId, String dataYaml, List<ExportEntry> entries) {
            lastDataYaml = dataYaml;
            lastEntries = List.copyOf(entries);
            long sizeBytes = entries.stream().mapToLong(e -> e.imageBytes().length).sum();
            return new DatasetUpload(datasetId, Instant.now(), entries.size(), sizeBytes);
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

    private static final class FakeAssetUsageRepositoryPort implements AssetUsageRepositoryPort {
        private final Map<UsageId, AssetUsage> store = new LinkedHashMap<>();

        @Override
        public AssetUsage save(AssetUsage usage) {
            store.put(usage.id(), usage);
            return usage;
        }

        @Override
        public Optional<AssetUsage> findById(UsageId id) {
            return Optional.ofNullable(store.get(id));
        }

        @Override
        public List<AssetUsage> findRecentByAsset(AssetId assetId, int limit) {
            throw new UnsupportedOperationException("not exercised by this suite");
        }

        @Override
        public List<AssetUsage> findRecent(int limit) {
            throw new UnsupportedOperationException("not exercised by this suite");
        }

        @Override
        public Optional<AssetUsage> findOpenByAsset(AssetId assetId) {
            throw new UnsupportedOperationException("not exercised by this suite");
        }
    }

    private static final class FakeDetectionRepositoryPort implements DetectionRepositoryPort {
        private final List<DetectionResult> store = new ArrayList<>();

        @Override
        public void save(DetectionResult result) {
            store.add(result);
        }

        @Override
        public List<DetectionResult> query(DetectionQuery query) {
            return store.stream()
                    .filter(r -> query.streamId() == null || r.streamId().equals(query.streamId()))
                    .filter(r -> query.from() == null || !r.capturedAt().isBefore(query.from()))
                    .filter(r -> query.to() == null || r.capturedAt().isBefore(query.to())) // to is exclusive
                    .filter(r -> query.label() == null
                            || r.detections().stream().anyMatch(d -> query.label().equals(d.label())))
                    .limit(query.limit())
                    .toList();
        }
    }

    private static final class FakeReplayFrameExtractionPort implements ReplayFrameExtractionPort {
        private final Map<StreamId, Map<Instant, VideoFrame>> store = new LinkedHashMap<>();

        void put(StreamId streamId, Instant at, VideoFrame frame) {
            store.computeIfAbsent(streamId, k -> new LinkedHashMap<>()).put(at, frame);
        }

        @Override
        public Optional<VideoFrame> frameAt(StreamId streamId, Instant at) {
            Map<Instant, VideoFrame> byInstant = store.get(streamId);
            return byInstant == null ? Optional.empty() : Optional.ofNullable(byInstant.get(at));
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
