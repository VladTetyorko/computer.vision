package com.drones.vision.adapter.persistence;

import com.drones.vision.domain.model.Annotation;
import com.drones.vision.domain.model.AnnotationSource;
import com.drones.vision.domain.model.Asset;
import com.drones.vision.domain.model.AssetId;
import com.drones.vision.domain.model.AssetImage;
import com.drones.vision.domain.model.AssetUsage;
import com.drones.vision.domain.model.BoundingBox;
import com.drones.vision.domain.model.Capability;
import com.drones.vision.domain.model.CategoryId;
import com.drones.vision.domain.model.Dataset;
import com.drones.vision.domain.model.DatasetId;
import com.drones.vision.domain.model.DatasetStatus;
import com.drones.vision.domain.model.Detection;
import com.drones.vision.domain.model.DetectionQuery;
import com.drones.vision.domain.model.DetectionResult;
import com.drones.vision.domain.model.Device;
import com.drones.vision.domain.model.DeviceCategory;
import com.drones.vision.domain.model.DeviceId;
import com.drones.vision.domain.model.FlightState;
import com.drones.vision.domain.model.GeoPosition;
import com.drones.vision.domain.model.GeofenceZone;
import com.drones.vision.domain.model.Group;
import com.drones.vision.domain.model.GroupId;
import com.drones.vision.domain.model.LifecycleState;
import com.drones.vision.domain.model.Mark;
import com.drones.vision.domain.model.MarkId;
import com.drones.vision.domain.model.MarkKind;
import com.drones.vision.domain.model.MarkSource;
import com.drones.vision.domain.model.MarkStatus;
import com.drones.vision.domain.model.Membership;
import com.drones.vision.domain.model.ModelRef;
import com.drones.vision.domain.model.Ownership;
import com.drones.vision.domain.model.Role;
import com.drones.vision.domain.model.SampleImage;
import com.drones.vision.domain.model.SampleStatus;
import com.drones.vision.domain.model.StreamDescriptor;
import com.drones.vision.domain.model.StreamId;
import com.drones.vision.domain.model.Telemetry;
import com.drones.vision.domain.model.TrainingSample;
import com.drones.vision.domain.model.TrainingSampleId;
import com.drones.vision.domain.model.UsageId;
import com.drones.vision.domain.model.User;
import com.drones.vision.domain.model.UserId;
import com.drones.vision.domain.model.ZoneId;
import com.drones.vision.domain.model.ZoneKind;
import com.drones.vision.domain.port.out.AssetImageRepositoryPort;
import com.drones.vision.domain.port.out.AssetRepositoryPort;
import com.drones.vision.domain.port.out.AssetUsageRepositoryPort;
import com.drones.vision.domain.port.out.AssignmentRepositoryPort;
import com.drones.vision.domain.port.out.CategoryRepositoryPort;
import com.drones.vision.domain.port.out.DatasetRepositoryPort;
import com.drones.vision.domain.port.out.DetectionRepositoryPort;
import com.drones.vision.domain.port.out.DeviceRepositoryPort;
import com.drones.vision.domain.port.out.GeofenceRepositoryPort;
import com.drones.vision.domain.port.out.GroupRepositoryPort;
import com.drones.vision.domain.port.out.MarkRepositoryPort;
import com.drones.vision.domain.port.out.SampleImageStorePort;
import com.drones.vision.domain.port.out.TelemetryRepositoryPort;
import com.drones.vision.domain.port.out.TrainingSampleRepositoryPort;
import com.drones.vision.domain.port.out.UserRepositoryPort;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.DockerClientFactory;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Round-trips every {@code Jpa*Repository} against a real Postgres container, plus a
 * restart-survival check (write through one {@link EntityManagerFactory}, read through a fresh
 * one against the same database).
 *
 * <p>Skips cleanly (not a failure) when no Docker daemon is reachable — same
 * {@code @EnabledIf}-gated contract as {@code adapter-rtsp}/{@code adapter-publish-hls}'s
 * {@code MediamtxDockerIntegrationTest}, found via the {@code docker} CLI there; this is the
 * first Testcontainers-based test in the repo, so the availability probe is Testcontainers' own
 * canonical one ({@link DockerClientFactory#isDockerAvailable()}) rather than re-implementing a
 * {@code docker info} CLI check a second time.
 */
@Testcontainers
@EnabledIf(value = "dockerAvailable", disabledReason = "docker is not available in this environment")
class PostgresDockerIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    /**
     * Truncated to milliseconds: {@code timestamptz} round-trips millisecond precision exactly,
     * but {@link Instant#now()} can carry finer-grained (e.g. microsecond) precision that would
     * make a direct post-round-trip {@code assertEquals} flaky depending on the host clock.
     */
    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.MILLIS);

    private static EntityManagerFactory entityManagerFactory;

    @BeforeAll
    static void migrateAndOpen() {
        entityManagerFactory = PersistenceUnit.start(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword());
    }

    @AfterAll
    static void close() {
        if (entityManagerFactory != null) {
            entityManagerFactory.close();
        }
    }

    static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Exception e) {
            return false;
        }
    }

    @Nested
    class CategoryRepositoryTests {

        private final CategoryRepositoryPort repository = new JpaCategoryRepository(entityManagerFactory);

        @Test
        void unknownIdReturnsEmptyOptional() {
            assertTrue(repository.findById(new CategoryId("no-such-category")).isEmpty());
        }

        @Test
        void savedTopLevelCategoryRoundTrips() {
            DeviceCategory category = new DeviceCategory(new CategoryId("cat-toplevel"), "Top Level", null,
                    List.of("hint-a", "hint-b"));

            repository.save(category);

            Optional<DeviceCategory> found = repository.findById(category.id());
            assertTrue(found.isPresent());
            assertEquals(category, found.get());
        }

        @Test
        void savedChildCategoryRoundTripsWithParent() {
            DeviceCategory parent = new DeviceCategory(new CategoryId("cat-parent"), "Parent", null, List.of());
            repository.save(parent);
            DeviceCategory child = new DeviceCategory(new CategoryId("cat-child"), "Child", parent.id(),
                    List.of("hint"));
            repository.save(child);

            Optional<DeviceCategory> found = repository.findById(child.id());
            assertTrue(found.isPresent());
            assertEquals(parent.id(), found.get().parent());
        }

        @Test
        void saveIsAnUpsert() {
            CategoryId id = new CategoryId("cat-upsert");
            repository.save(new DeviceCategory(id, "Original Name", null, List.of("a")));
            repository.save(new DeviceCategory(id, "Renamed", null, List.of("a", "b")));

            Optional<DeviceCategory> found = repository.findById(id);
            assertTrue(found.isPresent());
            assertEquals("Renamed", found.get().name());
            assertEquals(List.of("a", "b"), found.get().attributeHints());
        }

        @Test
        void findAllIncludesSavedCategory() {
            DeviceCategory category = new DeviceCategory(new CategoryId("cat-findall"), "Find All", null, List.of());
            repository.save(category);

            List<DeviceCategory> all = repository.findAll();
            assertTrue(all.stream().anyMatch(c -> c.id().equals(category.id())));
        }
    }

    @Nested
    class DeviceRepositoryTests {

        private final DeviceRepositoryPort repository = new JpaDeviceRepository(entityManagerFactory);

        @Test
        void unknownIdReturnsEmptyOptional() {
            assertTrue(repository.findById(DeviceId.random()).isEmpty());
        }

        @Test
        void savedDeviceRoundTripsCapabilitiesStreamAndState() {
            Device device = new Device(DeviceId.random(), "camera-1", Set.of(Capability.VIDEO, Capability.PTZ),
                    new StreamDescriptor("rtsp", URI.create("rtsp://example/cam1"), Map.of("fps", "15")),
                    LifecycleState.DEACTIVATED);

            repository.save(device);

            Optional<Device> found = repository.findById(device.id());
            assertTrue(found.isPresent());
            assertEquals(device, found.get());
        }

        @Test
        void saveIsAnUpsert() {
            DeviceId id = DeviceId.random();
            Device original = new Device(id, "original-name", Set.of(Capability.VIDEO),
                    new StreamDescriptor("sim", URI.create("sim://x"), Map.of()));
            repository.save(original);
            Device renamed = original.withDetails("renamed", Set.of(Capability.VIDEO, Capability.AUDIO),
                    original.stream());
            repository.save(renamed);

            Optional<Device> found = repository.findById(id);
            assertTrue(found.isPresent());
            assertEquals("renamed", found.get().name());
            assertEquals(Set.of(Capability.VIDEO, Capability.AUDIO), found.get().capabilities());
        }

        @Test
        void deleteByIdIsIdempotent() {
            Device device = new Device(DeviceId.random(), "to-delete", Set.of(Capability.VIDEO),
                    new StreamDescriptor("sim", URI.create("sim://del"), Map.of()));
            repository.save(device);

            repository.deleteById(device.id());
            assertTrue(repository.findById(device.id()).isEmpty());

            // second call on an already-absent id must not throw
            repository.deleteById(device.id());
        }

        @Test
        void findAllIncludesSavedDevice() {
            Device device = new Device(DeviceId.random(), "findall-device", Set.of(Capability.TELEMETRY),
                    new StreamDescriptor("sim", URI.create("sim://findall"), Map.of()));
            repository.save(device);

            List<Device> all = repository.findAll();
            assertTrue(all.stream().anyMatch(d -> d.id().equals(device.id())));
        }
    }

    @Nested
    class AssetRepositoryTests {

        private final AssetRepositoryPort repository = new JpaAssetRepository(entityManagerFactory);
        private final DeviceRepositoryPort deviceRepository = new JpaDeviceRepository(entityManagerFactory);

        @Test
        void unknownIdReturnsEmptyOptional() {
            assertTrue(repository.findById(AssetId.random()).isEmpty());
        }

        @Test
        void savedAssetRoundTripsOwnershipAttributesAndDevices() {
            DeviceId deviceId = DeviceId.random();
            deviceRepository.save(new Device(deviceId, "asset-device", Set.of(Capability.VIDEO),
                    new StreamDescriptor("sim", URI.create("sim://asset-device"), Map.of())));
            Ownership ownership = new Ownership(UserId.random(), GroupId.random());
            Asset asset = new Asset(AssetId.random(), "my drone", new CategoryId("drone"), ownership,
                    Set.of(deviceId), Map.of("weight-kg", "1.2"));

            repository.save(asset);

            Optional<Asset> found = repository.findById(asset.id());
            assertTrue(found.isPresent());
            assertEquals(asset, found.get());
        }

        @Test
        void saveIsAnUpsertAndPreservesLifecycleState() {
            AssetId id = AssetId.random();
            DeviceId deviceId = DeviceId.random();
            Ownership ownership = new Ownership(UserId.random(), GroupId.random());
            Asset original = new Asset(id, "original", new CategoryId("drone"), ownership, Set.of(deviceId),
                    Map.of());
            repository.save(original);

            Asset deactivated = original.withState(LifecycleState.DEACTIVATED);
            repository.save(deactivated);

            Optional<Asset> found = repository.findById(id);
            assertTrue(found.isPresent());
            assertEquals(LifecycleState.DEACTIVATED, found.get().state());
        }

        @Test
        void findByDeviceIdLocatesOwningAsset() {
            DeviceId deviceId = DeviceId.random();
            Ownership ownership = new Ownership(UserId.random(), GroupId.random());
            Asset asset = new Asset(AssetId.random(), "device-owner", new CategoryId("drone"), ownership,
                    Set.of(deviceId), Map.of());
            repository.save(asset);

            Optional<Asset> found = repository.findByDeviceId(deviceId);
            assertTrue(found.isPresent());
            assertEquals(asset.id(), found.get().id());
        }

        @Test
        void findByDeviceIdReturnsEmptyForUnownedDevice() {
            assertTrue(repository.findByDeviceId(DeviceId.random()).isEmpty());
        }

        @Test
        void deleteByIdIsIdempotent() {
            Ownership ownership = new Ownership(UserId.random(), GroupId.random());
            Asset asset = new Asset(AssetId.random(), "to-delete", new CategoryId("drone"), ownership,
                    Set.of(DeviceId.random()), Map.of());
            repository.save(asset);

            repository.deleteById(asset.id());
            assertTrue(repository.findById(asset.id()).isEmpty());

            // second call on an already-absent id must not throw
            repository.deleteById(asset.id());
        }

        @Test
        void findAllIncludesSavedAsset() {
            Ownership ownership = new Ownership(UserId.random(), GroupId.random());
            Asset asset = new Asset(AssetId.random(), "findall-asset", new CategoryId("drone"), ownership,
                    Set.of(DeviceId.random()), Map.of());
            repository.save(asset);

            List<Asset> all = repository.findAll();
            assertTrue(all.stream().anyMatch(a -> a.id().equals(asset.id())));
        }
    }

    @Nested
    class AssetUsageRepositoryTests {

        private final AssetUsageRepositoryPort repository = new JpaAssetUsageRepository(entityManagerFactory);

        @Test
        void unknownIdReturnsEmptyOptional() {
            assertTrue(repository.findById(UsageId.random()).isEmpty());
        }

        @Test
        void savedOpenUsageWithNoPositionsYetRoundTrips() {
            AssetUsage usage = new AssetUsage(UsageId.random(), AssetId.random(), NOW, null, null, null, 0);

            repository.save(usage);

            Optional<AssetUsage> found = repository.findById(usage.id());
            assertTrue(found.isPresent());
            assertEquals(usage, found.get());
        }

        @Test
        void savedClosedUsageRoundTripsPositionsAndSampleCount() {
            GeoPosition start = new GeoPosition(50.45, 30.52, 120.0);
            GeoPosition last = new GeoPosition(50.46, 30.53, null);
            AssetUsage usage = new AssetUsage(UsageId.random(), AssetId.random(), NOW,
                    NOW.plusSeconds(60), start, last, 42);

            repository.save(usage);

            Optional<AssetUsage> found = repository.findById(usage.id());
            assertTrue(found.isPresent());
            assertEquals(usage, found.get());
        }

        @Test
        void savedUsageWithNoStreamIdRoundTripsAsNull() {
            AssetUsage usage = new AssetUsage(UsageId.random(), AssetId.random(), NOW, null, null, null, 0);

            repository.save(usage);

            Optional<AssetUsage> found = repository.findById(usage.id());
            assertTrue(found.isPresent());
            assertNull(found.get().streamId(), "docs/MVP2-PLAN.md R-a2's V4 column is additive/nullable");
        }

        @Test
        void savedUsageWithAStreamIdRoundTrips() {
            StreamId streamId = StreamId.random();
            AssetUsage usage = new AssetUsage(UsageId.random(), AssetId.random(), NOW, NOW.plusSeconds(60),
                    new GeoPosition(1.0, 2.0, null), new GeoPosition(3.0, 4.0, null), 7, streamId);

            repository.save(usage);

            Optional<AssetUsage> found = repository.findById(usage.id());
            assertTrue(found.isPresent());
            assertEquals(usage, found.get());
            assertEquals(streamId, found.get().streamId());
        }

        @Test
        void saveIsAnUpsertThatCanCloseAnOpenUsage() {
            UsageId id = UsageId.random();
            AssetId assetId = AssetId.random();
            StreamId streamId = StreamId.random();
            AssetUsage open = new AssetUsage(id, assetId, NOW, null, null, null, 0, streamId);
            repository.save(open);

            AssetUsage closed = open.withPositions(new GeoPosition(1.0, 2.0, null), new GeoPosition(3.0, 4.0, null))
                    .withSampleCount(5)
                    .closed(NOW.plusSeconds(30));
            repository.save(closed);

            Optional<AssetUsage> found = repository.findById(id);
            assertTrue(found.isPresent());
            assertEquals(closed, found.get());
            assertEquals(streamId, found.get().streamId(), "closing/upserting must preserve the recorded streamId");
        }

        @Test
        void findRecentByAssetReturnsNewestFirstBoundedByLimit() {
            AssetId assetId = AssetId.random();
            AssetUsage oldest = new AssetUsage(UsageId.random(), assetId, NOW, NOW.plusSeconds(1), null, null, 0);
            AssetUsage middle = new AssetUsage(UsageId.random(), assetId, NOW.plusSeconds(10),
                    NOW.plusSeconds(11), null, null, 0);
            AssetUsage newest = new AssetUsage(UsageId.random(), assetId, NOW.plusSeconds(20),
                    NOW.plusSeconds(21), null, null, 0);
            repository.save(oldest);
            repository.save(newest);
            repository.save(middle);

            List<AssetUsage> recent = repository.findRecentByAsset(assetId, 2);

            assertEquals(List.of(newest.id(), middle.id()), recent.stream().map(AssetUsage::id).toList());
        }

        @Test
        void findOpenByAssetReturnsOnlyTheCurrentlyOpenUsage() {
            AssetId assetId = AssetId.random();
            AssetUsage closed = new AssetUsage(UsageId.random(), assetId, NOW, NOW.plusSeconds(1), null, null, 0);
            AssetUsage open = new AssetUsage(UsageId.random(), assetId, NOW.plusSeconds(10), null, null, null, 0);
            repository.save(closed);
            repository.save(open);

            Optional<AssetUsage> found = repository.findOpenByAsset(assetId);
            assertTrue(found.isPresent());
            assertEquals(open.id(), found.get().id());
        }

        @Test
        void findOpenByAssetReturnsEmptyWhenEveryUsageIsClosed() {
            AssetId assetId = AssetId.random();
            repository.save(new AssetUsage(UsageId.random(), assetId, NOW, NOW.plusSeconds(1), null, null, 0));

            assertTrue(repository.findOpenByAsset(assetId).isEmpty());
        }
    }

    @Nested
    class TelemetryRepositoryTests {

        private final TelemetryRepositoryPort repository = new JpaTelemetryRepository(entityManagerFactory);

        @Test
        void findByUsageReturnsEmptyListForUnknownUsage() {
            assertTrue(repository.findByUsage(UsageId.random(), 10).isEmpty());
        }

        @Test
        void savedSampleRoundTripsEveryField() {
            UsageId usageId = UsageId.random();
            Telemetry telemetry = new Telemetry(DeviceId.random(), NOW, 50.45, 30.52, 120.0, 90.0, 76.5,
                    Map.of("rssi", -55.0));

            repository.save(usageId, telemetry);

            List<Telemetry> found = repository.findByUsage(usageId, 10);
            assertEquals(List.of(telemetry), found);
        }

        @Test
        void savedSampleWithOnlyRequiredFieldsRoundTrips() {
            UsageId usageId = UsageId.random();
            Telemetry telemetry = new Telemetry(DeviceId.random(), NOW, null, null, null, null, null, Map.of());

            repository.save(usageId, telemetry);

            List<Telemetry> found = repository.findByUsage(usageId, 10);
            assertEquals(List.of(telemetry), found);
        }

        /**
         * Mirrors {@code InMemoryTelemetryRepository}'s exact (surprising) semantics: {@code
         * limit} bounds the <strong>earliest</strong> samples, not the most recent — see {@link
         * JpaTelemetryRepository}'s javadoc.
         */
        @Test
        void findByUsageReturnsEarliestSamplesFirstUpToLimit() {
            UsageId usageId = UsageId.random();
            DeviceId deviceId = DeviceId.random();
            List<Telemetry> saved = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                Telemetry sample = new Telemetry(deviceId, NOW.plusSeconds(i), (double) i, (double) i, null, null,
                        null, Map.of());
                saved.add(sample);
                repository.save(usageId, sample);
            }

            List<Telemetry> found = repository.findByUsage(usageId, 3);

            assertEquals(saved.subList(0, 3), found);
        }

        @Test
        void findByUsageIsolatesSamplesPerUsage() {
            UsageId usageA = UsageId.random();
            UsageId usageB = UsageId.random();
            repository.save(usageA, new Telemetry(DeviceId.random(), NOW, null, null, null, null, null, Map.of()));
            repository.save(usageB, new Telemetry(DeviceId.random(), NOW, null, null, null, null, null, Map.of()));

            assertEquals(1, repository.findByUsage(usageA, 10).size());
            assertEquals(1, repository.findByUsage(usageB, 10).size());
        }

        /**
         * docs/FC-INTEGRATIONS-PLAN.md F-b: {@code flight_state} round-trips a full {@link
         * FlightState} — including its own nullable sub-fields and a non-empty {@code
         * armingBlockers} — through the jsonb column via the same {@code @JdbcTypeCode(SqlTypes.JSON)}
         * idiom {@code DetectionResultEntity#detections} already uses for a plain record tree.
         */
        @Test
        void savedSampleWithFlightStateRoundTripsIt() {
            UsageId usageId = UsageId.random();
            FlightState flightState = new FlightState("ardupilot", "RTL", true, true, 3, 12, 0.9, 87,
                    List.of("Arm: Compass not calibrated"));
            Telemetry telemetry = new Telemetry(DeviceId.random(), NOW, 50.45, 30.52, 120.0, 90.0, 76.5,
                    Map.of("rssi", -55.0), flightState);

            repository.save(usageId, telemetry);

            List<Telemetry> found = repository.findByUsage(usageId, 10);
            assertEquals(List.of(telemetry), found);
            assertEquals(flightState, found.get(0).flightState());
        }

        /**
         * A sample with no {@code flightState} at all (the pre-existing 8-arg {@code Telemetry}
         * convenience ctor, same shape every pre-F-b row in this table has) must read back with
         * {@code flightState() == null} — the honest-null contract a real pre-migration row would
         * also satisfy, since the column itself is nullable (see {@link
         * #v6MigrationAddsANullableFlightStateColumnOnTopOfV1ThroughV5}).
         */
        @Test
        void savedSampleWithoutFlightStateRoundTripsAsNull() {
            UsageId usageId = UsageId.random();
            Telemetry telemetry = new Telemetry(DeviceId.random(), NOW, null, null, null, null, null, Map.of());

            repository.save(usageId, telemetry);

            List<Telemetry> found = repository.findByUsage(usageId, 10);
            assertNull(found.get(0).flightState());
        }
    }

    @Nested
    class DetectionRepositoryTests {

        private final DetectionRepositoryPort repository = new JpaDetectionRepository(entityManagerFactory);

        @Test
        void queryReturnsEmptyListForUnknownStream() {
            assertTrue(repository.query(new DetectionQuery(StreamId.random(), null, null, null, 10)).isEmpty());
        }

        @Test
        void savedResultRoundTripsDetectionsAndLatency() {
            StreamId streamId = StreamId.random();
            DetectionResult result = new DetectionResult(streamId, 7, NOW,
                    List.of(new Detection("person", 0.87, new BoundingBox(0.1, 0.2, 0.3, 0.4),
                            new ModelRef("yolo", "v1"))),
                    Duration.ofMillis(42));

            repository.save(result);

            List<DetectionResult> found = repository.query(new DetectionQuery(streamId, null, null, null, 10));
            assertEquals(List.of(result), found);
        }

        @Test
        void queryFiltersByStreamId() {
            StreamId streamA = StreamId.random();
            StreamId streamB = StreamId.random();
            repository.save(emptyDetectionResult(streamA, 1, NOW));
            repository.save(emptyDetectionResult(streamB, 1, NOW));

            List<DetectionResult> found = repository.query(new DetectionQuery(streamA, null, null, null, 10));
            assertEquals(1, found.size());
            assertEquals(streamA, found.get(0).streamId());
        }

        /**
         * The in-memory reference implementation treats {@link DetectionQuery#to()} as an
         * <strong>inclusive</strong> bound in practice (despite its javadoc calling it exclusive)
         * — this class mirrors that actual behavior rather than the javadoc. See {@link
         * JpaDetectionRepository}'s javadoc.
         */
        @Test
        void queryTimeRangeIsInclusiveOnBothEndsMatchingInMemoryBehavior() {
            StreamId streamId = StreamId.random();
            repository.save(emptyDetectionResult(streamId, 1, NOW));
            repository.save(emptyDetectionResult(streamId, 2, NOW.plusSeconds(10)));

            List<DetectionResult> found = repository.query(
                    new DetectionQuery(streamId, NOW, NOW.plusSeconds(10), null, 10));

            assertEquals(2, found.size());
        }

        @Test
        void queryFiltersByLabel() {
            StreamId streamId = StreamId.random();
            repository.save(new DetectionResult(streamId, 1, NOW,
                    List.of(new Detection("person", 0.9, new BoundingBox(0, 0, 0.1, 0.1), new ModelRef("yolo", "v1"))),
                    Duration.ZERO));
            repository.save(new DetectionResult(streamId, 2, NOW.plusSeconds(1),
                    List.of(new Detection("car", 0.8, new BoundingBox(0, 0, 0.1, 0.1), new ModelRef("yolo", "v1"))),
                    Duration.ZERO));

            List<DetectionResult> found = repository.query(new DetectionQuery(streamId, null, null, "car", 10));

            assertEquals(1, found.size());
            assertEquals(2L, found.get(0).frameSequence());
        }

        @Test
        void queryOrdersNewestFirstAndAppliesLimit() {
            StreamId streamId = StreamId.random();
            repository.save(emptyDetectionResult(streamId, 1, NOW));
            repository.save(emptyDetectionResult(streamId, 2, NOW.plusSeconds(1)));
            repository.save(emptyDetectionResult(streamId, 3, NOW.plusSeconds(2)));

            List<DetectionResult> found = repository.query(new DetectionQuery(streamId, null, null, null, 2));

            assertEquals(List.of(3L, 2L), found.stream().map(DetectionResult::frameSequence).toList());
        }

        private DetectionResult emptyDetectionResult(StreamId streamId, long frameSequence, Instant capturedAt) {
            return new DetectionResult(streamId, frameSequence, capturedAt, List.of(), Duration.ZERO);
        }
    }

    /** docs/UX-REWORK-PLAN.md §U-d item 3: the asset image store. */
    @Nested
    class AssetImageRepositoryTests {

        private final AssetImageRepositoryPort repository = new JpaAssetImageRepository(entityManagerFactory);

        @Test
        void unknownAssetIdReturnsEmptyOptionalAndDoesNotExist() {
            AssetId unknown = AssetId.random();

            assertTrue(repository.findByAssetId(unknown).isEmpty());
            assertFalse(repository.existsByAssetId(unknown));
        }

        @Test
        void savedImageRoundTripsBytesAndContentType() {
            AssetId assetId = AssetId.random();
            byte[] data = {1, 2, 3, 4, 5};

            repository.save(assetId, new AssetImage(data, "image/jpeg"));

            Optional<AssetImage> found = repository.findByAssetId(assetId);
            assertTrue(found.isPresent());
            assertArrayEquals(data, found.get().data());
            assertEquals("image/jpeg", found.get().contentType());
            assertTrue(repository.existsByAssetId(assetId));
        }

        @Test
        void saveIsAnUpsert() {
            AssetId assetId = AssetId.random();
            repository.save(assetId, new AssetImage(new byte[]{1}, "image/jpeg"));
            repository.save(assetId, new AssetImage(new byte[]{2, 2}, "image/png"));

            Optional<AssetImage> found = repository.findByAssetId(assetId);
            assertTrue(found.isPresent());
            assertArrayEquals(new byte[]{2, 2}, found.get().data());
            assertEquals("image/png", found.get().contentType());
        }

        @Test
        void deleteByAssetIdIsIdempotentAndRemovesTheImage() {
            AssetId assetId = AssetId.random();
            repository.save(assetId, new AssetImage(new byte[]{9}, "image/png"));

            repository.deleteByAssetId(assetId);
            assertTrue(repository.findByAssetId(assetId).isEmpty());
            assertFalse(repository.existsByAssetId(assetId));

            // second call on an already-absent id must not throw
            repository.deleteByAssetId(assetId);
        }
    }

    /** docs/OPS-CORE-PLAN.md §G — every {@link GeofenceRepositoryPort} method, upsert semantics. */
    @Nested
    class GeofenceRepositoryTests {

        private final GeofenceRepositoryPort repository = new JpaGeofenceRepository(entityManagerFactory);

        private List<GeoPosition> triangle() {
            return List.of(
                    new GeoPosition(10.0, 20.0, null),
                    new GeoPosition(10.0, 21.0, null),
                    new GeoPosition(11.0, 20.5, null));
        }

        @Test
        void unknownIdReturnsEmptyOptional() {
            assertTrue(repository.findById(ZoneId.random()).isEmpty());
        }

        @Test
        void savedKeepOutZoneRoundTripsWithAltitudeCeiling() {
            GeofenceZone zone = new GeofenceZone(ZoneId.random(), "Airport", ZoneKind.KEEP_OUT, triangle(), 50.0,
                    true);

            repository.save(zone);

            Optional<GeofenceZone> found = repository.findById(zone.id());
            assertTrue(found.isPresent());
            assertEquals(zone, found.get());
        }

        @Test
        void savedKeepInZoneWithNoAltitudeCeilingRoundTripsWithNullMaxAltitude() {
            GeofenceZone zone = new GeofenceZone(ZoneId.random(), "Site", ZoneKind.KEEP_IN, triangle(), null, false);

            repository.save(zone);

            Optional<GeofenceZone> found = repository.findById(zone.id());
            assertTrue(found.isPresent());
            assertNull(found.get().maxAltitudeMeters());
            assertFalse(found.get().enabled());
        }

        @Test
        void saveIsAnUpsertPreservingId() {
            ZoneId id = ZoneId.random();
            repository.save(new GeofenceZone(id, "Original", ZoneKind.KEEP_OUT, triangle(), null, true));
            repository.save(new GeofenceZone(id, "Renamed", ZoneKind.KEEP_IN, triangle(), 30.0, false));

            Optional<GeofenceZone> found = repository.findById(id);
            assertTrue(found.isPresent());
            assertEquals("Renamed", found.get().name());
            assertEquals(ZoneKind.KEEP_IN, found.get().kind());
            assertEquals(30.0, found.get().maxAltitudeMeters());
            assertFalse(found.get().enabled());
        }

        @Test
        void findAllReturnsEverySavedZone() {
            GeofenceZone first = new GeofenceZone(ZoneId.random(), "Zone A", ZoneKind.KEEP_OUT, triangle(), null,
                    true);
            GeofenceZone second = new GeofenceZone(ZoneId.random(), "Zone B", ZoneKind.KEEP_IN, triangle(), 20.0,
                    true);
            repository.save(first);
            repository.save(second);

            List<GeofenceZone> all = repository.findAll();
            assertTrue(all.contains(first));
            assertTrue(all.contains(second));
        }

        @Test
        void deleteByIdIsIdempotentAndRemovesTheZone() {
            GeofenceZone zone = new GeofenceZone(ZoneId.random(), "Temp", ZoneKind.KEEP_OUT, triangle(), null, true);
            repository.save(zone);

            repository.deleteById(zone.id());
            assertTrue(repository.findById(zone.id()).isEmpty());

            // second call on an already-absent id must not throw
            repository.deleteById(zone.id());
        }
    }

    /** docs/U-AUTH-PLAN.md wave 3 — users, incl. jsonb memberships + case-insensitive findByUsername. */
    @Nested
    class UserRepositoryTests {

        private final UserRepositoryPort repository = new JpaUserRepository(entityManagerFactory);
        private final GroupId groupA = GroupId.random();

        @Test
        void unknownIdAndUsernameReturnEmptyOptional() {
            assertTrue(repository.findById(UserId.random()).isEmpty());
            assertTrue(repository.findByUsername("nobody").isEmpty());
        }

        @Test
        void savedUserWithMembershipsRoundTrips() {
            User user = new User(UserId.random(), "Round.Trip.User", "Round Trip", "rt@vision.local",
                    "hash-value", true, List.of(new Membership(groupA, Role.MANAGER)));

            repository.save(user);

            Optional<User> found = repository.findById(user.id());
            assertTrue(found.isPresent());
            // username is stored lower-cased by the domain, so equality is on the normalized value
            assertEquals("round.trip.user", found.get().username());
            assertEquals(List.of(new Membership(groupA, Role.MANAGER)), found.get().memberships());
            assertEquals("hash-value", found.get().passwordHash());
            assertTrue(found.get().enabled());
        }

        @Test
        void findByUsernameIsCaseInsensitive() {
            User user = new User(UserId.random(), "casetest", "Case", "case@vision.local", "h", true, List.of());
            repository.save(user);

            assertTrue(repository.findByUsername("CaseTest").isPresent());
            assertTrue(repository.findByUsername("CASETEST").isPresent());
            assertEquals(user.id(), repository.findByUsername("casetest").orElseThrow().id());
        }

        @Test
        void saveUpsertsById() {
            UserId id = UserId.random();
            repository.save(new User(id, "upsertuser", "First", "u@vision.local", "h1", true, List.of()));
            repository.save(new User(id, "upsertuser", "Second", "u@vision.local", "h2", false,
                    List.of(new Membership(groupA, Role.PILOT))));

            Optional<User> found = repository.findById(id);
            assertTrue(found.isPresent());
            assertEquals("Second", found.get().displayName());
            assertEquals("h2", found.get().passwordHash());
            assertFalse(found.get().enabled());
            assertEquals(List.of(new Membership(groupA, Role.PILOT)), found.get().memberships());
        }

        @Test
        void findAllReturnsEverySavedUser() {
            User first = new User(UserId.random(), "findall-a", "A", "a@vision.local", "h", true, List.of());
            User second = new User(UserId.random(), "findall-b", "B", "b@vision.local", "h", true, List.of());
            repository.save(first);
            repository.save(second);

            List<User> all = repository.findAll();
            assertTrue(all.stream().anyMatch(u -> u.id().equals(first.id())));
            assertTrue(all.stream().anyMatch(u -> u.id().equals(second.id())));
        }
    }

    /** docs/U-AUTH-PLAN.md wave 3 — groups (org-chart nodes). */
    @Nested
    class GroupRepositoryTests {

        private final GroupRepositoryPort repository = new JpaGroupRepository(entityManagerFactory);

        @Test
        void unknownIdReturnsEmptyOptional() {
            assertTrue(repository.findById(GroupId.random()).isEmpty());
        }

        @Test
        void savedRootAndChildGroupsRoundTrip() {
            Group root = new Group(GroupId.random(), "Root", null);
            Group child = new Group(GroupId.random(), "Child", root.id());
            repository.save(root);
            repository.save(child);

            assertEquals(root, repository.findById(root.id()).orElseThrow());
            Optional<Group> foundChild = repository.findById(child.id());
            assertTrue(foundChild.isPresent());
            assertEquals(root.id(), foundChild.get().parentGroupId());
        }

        @Test
        void saveUpsertsById() {
            GroupId id = GroupId.random();
            repository.save(new Group(id, "Original", null));
            repository.save(new Group(id, "Renamed", null));

            assertEquals("Renamed", repository.findById(id).orElseThrow().name());
        }

        @Test
        void findAllReturnsEverySavedGroup() {
            Group first = new Group(GroupId.random(), "Group One", null);
            Group second = new Group(GroupId.random(), "Group Two", null);
            repository.save(first);
            repository.save(second);

            List<Group> all = repository.findAll();
            assertTrue(all.contains(first));
            assertTrue(all.contains(second));
        }
    }

    @Nested
    class AssignmentRepositoryTests {

        private final AssignmentRepositoryPort repository = new JpaAssignmentRepository(entityManagerFactory);

        @Test
        void assignIsIdempotentUpsertQueryableBothDirections() {
            UserId pilot = UserId.random();
            AssetId asset = AssetId.random();

            repository.assign(pilot, asset);
            repository.assign(pilot, asset); // idempotent upsert — no duplicate row, no error

            assertTrue(repository.isAssigned(pilot, asset));
            assertEquals(Set.of(asset), repository.assetsForPilot(pilot));
            assertEquals(Set.of(pilot), repository.pilotsForAsset(asset));
        }

        @Test
        void unassignIsIdempotentDelete() {
            UserId pilot = UserId.random();
            AssetId asset = AssetId.random();
            repository.assign(pilot, asset);

            repository.unassign(pilot, asset);
            repository.unassign(pilot, asset); // idempotent — deleting a missing link is a no-op

            assertFalse(repository.isAssigned(pilot, asset));
            assertTrue(repository.assetsForPilot(pilot).isEmpty());
            assertTrue(repository.pilotsForAsset(asset).isEmpty());
        }

        @Test
        void tracksMultiplePilotsAndAssetsIndependently() {
            UserId alice = UserId.random();
            UserId bob = UserId.random();
            AssetId droneOne = AssetId.random();
            AssetId droneTwo = AssetId.random();

            repository.assign(alice, droneOne);
            repository.assign(alice, droneTwo);
            repository.assign(bob, droneOne);

            assertEquals(Set.of(droneOne, droneTwo), repository.assetsForPilot(alice));
            assertEquals(Set.of(droneOne), repository.assetsForPilot(bob));
            assertEquals(Set.of(alice, bob), repository.pilotsForAsset(droneOne));
            assertEquals(Set.of(alice), repository.pilotsForAsset(droneTwo));
        }

        @Test
        void unknownPilotOrAssetYieldsEmptySets() {
            assertTrue(repository.assetsForPilot(UserId.random()).isEmpty());
            assertTrue(repository.pilotsForAsset(AssetId.random()).isEmpty());
            assertFalse(repository.isAssigned(UserId.random(), AssetId.random()));
        }
    }

    /** docs/TACTICAL-MARKS-PLAN.md §3 — every {@link MarkRepositoryPort} method, upsert semantics. */
    @Nested
    class MarkRepositoryTests {

        private final MarkRepositoryPort repository = new JpaMarkRepository(entityManagerFactory);

        private Ownership ownership() {
            return new Ownership(UserId.random(), GroupId.random());
        }

        @Test
        void unknownIdReturnsEmptyOptional() {
            assertTrue(repository.findById(MarkId.random()).isEmpty());
        }

        @Test
        void savedMarkRoundTripsWithAltitudeAndNote() {
            Mark mark = new Mark(MarkId.random(), new GeoPosition(50.45, 30.52, 100.0), MarkKind.TARGET,
                    "Bunker", "Reinforced, two entrances", ownership(), NOW, MarkStatus.ACTIVE, MarkSource.MANUAL);

            repository.save(mark);

            Optional<Mark> found = repository.findById(mark.id());
            assertTrue(found.isPresent());
            assertEquals(mark, found.get());
        }

        @Test
        void detectionSourcedMarkWithNoAltitudeOrNoteRoundTripsWithNullFields() {
            Mark mark = new Mark(MarkId.random(), new GeoPosition(50.45, 30.52, null), MarkKind.HAZARD,
                    "Estimated hazard", null, ownership(), NOW, MarkStatus.ACTIVE, MarkSource.DETECTION);

            repository.save(mark);

            Optional<Mark> found = repository.findById(mark.id());
            assertTrue(found.isPresent());
            assertNull(found.get().position().altitudeMeters());
            assertNull(found.get().note());
            assertEquals(MarkSource.DETECTION, found.get().source());
        }

        @Test
        void saveIsAnUpsertPreservingId() {
            MarkId id = MarkId.random();
            Ownership ownership = ownership();
            repository.save(new Mark(id, new GeoPosition(10.0, 20.0, null), MarkKind.POI, "Original", null,
                    ownership, NOW, MarkStatus.ACTIVE, MarkSource.MANUAL));
            repository.save(new Mark(id, new GeoPosition(11.0, 21.0, 5.0), MarkKind.FRIENDLY, "Renamed",
                    "Updated note", ownership, NOW, MarkStatus.CLEARED, MarkSource.MANUAL));

            Optional<Mark> found = repository.findById(id);
            assertTrue(found.isPresent());
            assertEquals("Renamed", found.get().label());
            assertEquals(MarkKind.FRIENDLY, found.get().kind());
            assertEquals("Updated note", found.get().note());
            assertEquals(MarkStatus.CLEARED, found.get().status());
            assertEquals(new GeoPosition(11.0, 21.0, 5.0), found.get().position());
        }

        @Test
        void findAllReturnsEverySavedMark() {
            Mark first = new Mark(MarkId.random(), new GeoPosition(10.0, 20.0, null), MarkKind.TARGET, "First",
                    null, ownership(), NOW, MarkStatus.ACTIVE, MarkSource.MANUAL);
            Mark second = new Mark(MarkId.random(), new GeoPosition(11.0, 21.0, null), MarkKind.HAZARD, "Second",
                    null, ownership(), NOW, MarkStatus.ACTIVE, MarkSource.DETECTION);
            repository.save(first);
            repository.save(second);

            List<Mark> all = repository.findAll();
            assertTrue(all.contains(first));
            assertTrue(all.contains(second));
        }

        @Test
        void deleteByIdIsIdempotentAndRemovesTheMark() {
            Mark mark = new Mark(MarkId.random(), new GeoPosition(10.0, 20.0, null), MarkKind.TARGET, "Temp", null,
                    ownership(), NOW, MarkStatus.ACTIVE, MarkSource.MANUAL);
            repository.save(mark);

            repository.deleteById(mark.id());
            assertTrue(repository.findById(mark.id()).isEmpty());

            // second call on an already-absent id must not throw
            repository.deleteById(mark.id());
        }
    }

    /** docs/CV-TRAINING-PLAN.md §1, Wave T3 — every {@link DatasetRepositoryPort} method, upsert semantics. */
    @Nested
    class DatasetRepositoryTests {

        private final DatasetRepositoryPort repository = new JpaDatasetRepository(entityManagerFactory);

        private Ownership ownership() {
            return new Ownership(UserId.random(), GroupId.random());
        }

        private Dataset dataset(DatasetId id, DatasetStatus status) {
            return new Dataset(id, "Buildings", new CategoryId("building"), List.of("building", "tower"),
                    ownership(), status, NOW);
        }

        @Test
        void unknownIdReturnsEmptyOptional() {
            assertTrue(repository.findById(DatasetId.random()).isEmpty());
        }

        @Test
        void savedDatasetRoundTripsTargetCategoryAndClasses() {
            Dataset dataset = dataset(DatasetId.random(), DatasetStatus.OPEN);

            repository.save(dataset);

            Optional<Dataset> found = repository.findById(dataset.id());
            assertTrue(found.isPresent());
            assertEquals(dataset, found.get());
        }

        @Test
        void savedDatasetWithNoTargetCategoryAndNoClassesRoundTripsAsEmpty() {
            Dataset dataset = new Dataset(DatasetId.random(), "Uncategorized", null, List.of(), ownership(),
                    DatasetStatus.OPEN, NOW);

            repository.save(dataset);

            Optional<Dataset> found = repository.findById(dataset.id());
            assertTrue(found.isPresent());
            assertNull(found.get().targetCategory());
            assertTrue(found.get().classes().isEmpty());
        }

        @Test
        void saveIsAnUpsertPreservingId() {
            DatasetId id = DatasetId.random();
            repository.save(dataset(id, DatasetStatus.OPEN));
            repository.save(new Dataset(id, "Renamed", new CategoryId("tower"), List.of("tower"), ownership(),
                    DatasetStatus.ARCHIVED, NOW));

            Optional<Dataset> found = repository.findById(id);
            assertTrue(found.isPresent());
            assertEquals("Renamed", found.get().name());
            assertEquals(DatasetStatus.ARCHIVED, found.get().status());
            assertEquals(List.of("tower"), found.get().classes());
        }

        @Test
        void findAllReturnsEverySavedDataset() {
            Dataset first = dataset(DatasetId.random(), DatasetStatus.OPEN);
            Dataset second = dataset(DatasetId.random(), DatasetStatus.ARCHIVED);
            repository.save(first);
            repository.save(second);

            List<Dataset> all = repository.findAll();
            assertTrue(all.contains(first));
            assertTrue(all.contains(second));
        }

        @Test
        void deleteIsIdempotentAndRemovesTheDataset() {
            Dataset dataset = dataset(DatasetId.random(), DatasetStatus.OPEN);
            repository.save(dataset);

            repository.delete(dataset.id());
            assertTrue(repository.findById(dataset.id()).isEmpty());

            // second call on an already-absent id must not throw
            repository.delete(dataset.id());
        }
    }

    /**
     * docs/CV-TRAINING-PLAN.md §1, Wave T3 — every {@link TrainingSampleRepositoryPort} method
     * incl. the dataset/status filter {@code idx_training_samples_dataset_status} serves.
     */
    @Nested
    class TrainingSampleRepositoryTests {

        private final TrainingSampleRepositoryPort repository =
                new JpaTrainingSampleRepository(entityManagerFactory);

        private TrainingSample sample(TrainingSampleId id, DatasetId datasetId, SampleStatus status) {
            List<Annotation> annotations = List.of(
                    new Annotation("building", new BoundingBox(0.1, 0.2, 0.3, 0.25), AnnotationSource.MODEL));
            return new TrainingSample(id, datasetId, StreamId.random(), AssetId.random(), NOW, 1920, 1080,
                    annotations, status, null, null);
        }

        @Test
        void unknownIdReturnsEmptyOptional() {
            assertTrue(repository.findById(TrainingSampleId.random()).isEmpty());
        }

        @Test
        void savedSampleRoundTripsAnnotationsAndDimensions() {
            TrainingSample sample = sample(TrainingSampleId.random(), DatasetId.random(), SampleStatus.PENDING);

            repository.save(sample);

            Optional<TrainingSample> found = repository.findById(sample.id());
            assertTrue(found.isPresent());
            assertEquals(sample, found.get());
        }

        @Test
        void savedSampleWithNoAssetIdAndNoAnnotationsRoundTripsAsEmpty() {
            TrainingSample sample = new TrainingSample(TrainingSampleId.random(), DatasetId.random(),
                    StreamId.random(), null, NOW, 640, 480, List.of(), SampleStatus.PENDING, null, null);

            repository.save(sample);

            Optional<TrainingSample> found = repository.findById(sample.id());
            assertTrue(found.isPresent());
            assertNull(found.get().assetId());
            assertTrue(found.get().annotations().isEmpty());
        }

        @Test
        void saveIsAnUpsertMovingPendingToLabeledAndStampingTheReviewer() {
            TrainingSampleId id = TrainingSampleId.random();
            DatasetId datasetId = DatasetId.random();
            repository.save(sample(id, datasetId, SampleStatus.PENDING));

            UserId reviewer = UserId.random();
            List<Annotation> corrected = List.of(new Annotation("building",
                    new BoundingBox(0.11, 0.19, 0.32, 0.27), AnnotationSource.OPERATOR));
            TrainingSample labeled = new TrainingSample(id, datasetId, StreamId.random(), AssetId.random(), NOW,
                    1920, 1080, corrected, SampleStatus.LABELED, reviewer, NOW);

            repository.save(labeled);

            Optional<TrainingSample> found = repository.findById(id);
            assertTrue(found.isPresent());
            assertEquals(SampleStatus.LABELED, found.get().status());
            assertEquals(reviewer, found.get().labeledBy());
            assertEquals(corrected, found.get().annotations());
        }

        @Test
        void findByDatasetFiltersByStatusAndBoundsByLimit() {
            DatasetId datasetId = DatasetId.random();
            repository.save(sample(TrainingSampleId.random(), datasetId, SampleStatus.PENDING));
            repository.save(sample(TrainingSampleId.random(), datasetId, SampleStatus.LABELED));
            repository.save(sample(TrainingSampleId.random(), datasetId, SampleStatus.LABELED));
            repository.save(sample(TrainingSampleId.random(), DatasetId.random(), SampleStatus.LABELED));

            List<TrainingSample> labeled = repository.findByDataset(datasetId, SampleStatus.LABELED, 10);
            assertEquals(2, labeled.size());
            assertTrue(labeled.stream().allMatch(s -> s.status() == SampleStatus.LABELED));

            List<TrainingSample> everyStatus = repository.findByDataset(datasetId, null, 10);
            assertEquals(3, everyStatus.size());

            List<TrainingSample> bounded = repository.findByDataset(datasetId, null, 1);
            assertEquals(1, bounded.size());
        }

        @Test
        void countByDatasetMatchesFindByDatasetsFilter() {
            DatasetId datasetId = DatasetId.random();
            repository.save(sample(TrainingSampleId.random(), datasetId, SampleStatus.PENDING));
            repository.save(sample(TrainingSampleId.random(), datasetId, SampleStatus.LABELED));
            repository.save(sample(TrainingSampleId.random(), datasetId, SampleStatus.DISCARDED));

            assertEquals(3, repository.countByDataset(datasetId, null));
            assertEquals(1, repository.countByDataset(datasetId, SampleStatus.PENDING));
            assertEquals(1, repository.countByDataset(datasetId, SampleStatus.LABELED));
            assertEquals(1, repository.countByDataset(datasetId, SampleStatus.DISCARDED));
        }

        @Test
        void findByDatasetAndCountByDatasetOnAnUnknownDatasetAreEmpty() {
            assertTrue(repository.findByDataset(DatasetId.random(), null, 10).isEmpty());
            assertEquals(0, repository.countByDataset(DatasetId.random(), null));
        }

        @Test
        void deleteIsIdempotentAndRemovesTheSample() {
            TrainingSample sample = sample(TrainingSampleId.random(), DatasetId.random(), SampleStatus.PENDING);
            repository.save(sample);

            repository.delete(sample.id());
            assertTrue(repository.findById(sample.id()).isEmpty());

            // second call on an already-absent id must not throw
            repository.delete(sample.id());
        }
    }

    /**
     * docs/CV-TRAINING-PLAN.md §1/§C, Wave T3 — every {@link SampleImageStorePort} method, the
     * {@code AssetImageRepositoryPort} shape reused for training-sample frames.
     */
    @Nested
    class SampleImageStoreTests {

        private final SampleImageStorePort store = new JpaSampleImageStore(entityManagerFactory);

        @Test
        void unknownSampleIdReturnsEmptyOptional() {
            assertTrue(store.findById(TrainingSampleId.random()).isEmpty());
        }

        @Test
        void savedImageRoundTripsBytesAndContentType() {
            TrainingSampleId id = TrainingSampleId.random();
            byte[] data = {1, 2, 3, 4, 5};

            store.save(id, new SampleImage(data, "image/jpeg"));

            Optional<SampleImage> found = store.findById(id);
            assertTrue(found.isPresent());
            assertArrayEquals(data, found.get().data());
            assertEquals("image/jpeg", found.get().contentType());
        }

        @Test
        void saveIsAnUpsert() {
            TrainingSampleId id = TrainingSampleId.random();
            store.save(id, new SampleImage(new byte[]{1}, "image/jpeg"));
            store.save(id, new SampleImage(new byte[]{2, 2}, "image/png"));

            Optional<SampleImage> found = store.findById(id);
            assertTrue(found.isPresent());
            assertArrayEquals(new byte[]{2, 2}, found.get().data());
            assertEquals("image/png", found.get().contentType());
        }

        @Test
        void deleteIsIdempotentAndRemovesTheImage() {
            TrainingSampleId id = TrainingSampleId.random();
            store.save(id, new SampleImage(new byte[]{9}, "image/png"));

            store.delete(id);
            assertTrue(store.findById(id).isEmpty());

            // second call on an already-absent id must not throw
            store.delete(id);
        }
    }

    /**
     * docs/MVP2-PLAN.md P-b's retention guard, in test form: uses the small-cap constructor
     * overload (rather than the production {@value JpaTelemetryRepository#DEFAULT_RETENTION_LIMIT_PER_USAGE}
     * default) so the pruning path exercises without inserting six figures of rows.
     */
    @Test
    void telemetryRetentionPrunesOldestSamplesOnceCapExceeded() {
        TelemetryRepositoryPort repository = new JpaTelemetryRepository(entityManagerFactory, 3);
        UsageId usageId = UsageId.random();
        DeviceId deviceId = DeviceId.random();
        for (int i = 0; i < 5; i++) {
            repository.save(usageId, new Telemetry(deviceId, NOW.plusSeconds(i), null, null, null, null, null,
                    Map.of()));
        }

        List<Telemetry> remaining = repository.findByUsage(usageId, 10);

        assertEquals(3, remaining.size());
        // the 3 kept must be the 3 newest (seconds offsets 2, 3, 4), oldest-first as findByUsage returns them
        assertEquals(List.of(NOW.plusSeconds(2), NOW.plusSeconds(3), NOW.plusSeconds(4)),
                remaining.stream().map(Telemetry::at).toList());
    }

    /** Same guard, keyed by stream instead of usage — see {@link JpaDetectionRepository}. */
    @Test
    void detectionRetentionPrunesOldestResultsOnceCapExceeded() {
        DetectionRepositoryPort repository = new JpaDetectionRepository(entityManagerFactory, 3);
        StreamId streamId = StreamId.random();
        for (int i = 0; i < 5; i++) {
            repository.save(new DetectionResult(streamId, i, NOW.plusSeconds(i), List.of(), Duration.ZERO));
        }

        List<DetectionResult> remaining = repository.query(new DetectionQuery(streamId, null, null, null, 10));

        assertEquals(3, remaining.size());
        // query is newest-first; the 3 kept must be frame sequences 4, 3, 2 (the 2 oldest pruned)
        assertEquals(List.of(4L, 3L, 2L), remaining.stream().map(DetectionResult::frameSequence).toList());
    }

    /**
     * docs/MVP2-PLAN.md P-b's done criterion in test form: a finished flight's telemetry and
     * detections are queryable after a restart — same "write through one {@link
     * EntityManagerFactory}, read through a brand-new one against the same still-running
     * container" technique as {@link #assetSurvivesAFreshEntityManagerFactoryAgainstTheSameDatabase}.
     */
    @Test
    void historyOfAFinishedUsageSurvivesAFreshEntityManagerFactory() {
        AssetId assetId = AssetId.random();
        UsageId usageId = UsageId.random();
        DeviceId deviceId = DeviceId.random();
        StreamId streamId = StreamId.random();

        AssetUsage usage = new AssetUsage(usageId, assetId, NOW, NOW.plusSeconds(120),
                new GeoPosition(50.45, 30.52, 100.0), new GeoPosition(50.50, 30.60, 110.0), 2, streamId);
        new JpaAssetUsageRepository(entityManagerFactory).save(usage);

        Telemetry sample = new Telemetry(deviceId, NOW, 50.45, 30.52, 100.0, 0.0, 95.0, Map.of());
        new JpaTelemetryRepository(entityManagerFactory).save(usageId, sample);

        DetectionResult detection = new DetectionResult(streamId, 1, NOW,
                List.of(new Detection("person", 0.75, new BoundingBox(0.1, 0.1, 0.2, 0.2), new ModelRef("yolo", "v1"))),
                Duration.ofMillis(30));
        new JpaDetectionRepository(entityManagerFactory).save(detection);

        EntityManagerFactory freshContext = PersistenceUnit.start(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword());
        try {
            Optional<AssetUsage> foundUsage = new JpaAssetUsageRepository(freshContext).findById(usageId);
            assertTrue(foundUsage.isPresent());
            assertEquals(usage, foundUsage.get());

            List<Telemetry> foundTelemetry = new JpaTelemetryRepository(freshContext).findByUsage(usageId, 10);
            assertEquals(List.of(sample), foundTelemetry);

            List<DetectionResult> foundDetections = new JpaDetectionRepository(freshContext)
                    .query(new DetectionQuery(streamId, null, null, null, 10));
            assertEquals(List.of(detection), foundDetections);
        } finally {
            freshContext.close();
        }
    }

    /**
     * docs/MVP2-PLAN.md P-a's done criterion in test form: register an asset through one
     * application "run" ({@code entityManagerFactory}, opened in {@link #migrateAndOpen}), then
     * open a brand-new {@link EntityManagerFactory} against the same still-running container (no
     * re-migration needed — Flyway's own history table makes {@link PersistenceUnit#start} a
     * no-op migration the second time) and confirm the asset is still there.
     */
    @Test
    void assetSurvivesAFreshEntityManagerFactoryAgainstTheSameDatabase() {
        DeviceId deviceId = DeviceId.random();
        new JpaDeviceRepository(entityManagerFactory).save(new Device(deviceId, "restart-device",
                Set.of(Capability.VIDEO), new StreamDescriptor("sim", URI.create("sim://restart"), Map.of())));
        Ownership ownership = new Ownership(UserId.random(), GroupId.random());
        Asset asset = new Asset(AssetId.random(), "restart-survivor", new CategoryId("drone"), ownership,
                Set.of(deviceId), Map.of("note", "written-before-restart"));
        new JpaAssetRepository(entityManagerFactory).save(asset);

        EntityManagerFactory freshContext = PersistenceUnit.start(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword());
        try {
            Optional<Asset> found = new JpaAssetRepository(freshContext).findById(asset.id());
            assertTrue(found.isPresent());
            assertEquals(asset, found.get());
            assertFalse(found.get().attributes().isEmpty());
        } finally {
            freshContext.close();
        }
    }

    /**
     * docs/MVP2-PLAN.md R-a2: {@code V4__usage_stream_id.sql} must apply cleanly on top of the
     * V1-V3 schema {@link #migrateAndOpen} already migrated for every other test in this class,
     * adding {@code asset_usages.stream_id} as a nullable column (additive, no backfill — see the
     * migration's own comment) rather than requiring a fresh database. Checked against {@code
     * information_schema} directly rather than only round-tripping a null-{@code streamId} usage
     * (already covered by {@code AssetUsageRepositoryTests}), so this specifically proves the
     * column's nullability at the schema level, not just one Java-side null value happening to
     * round-trip.
     */
    @Test
    void v4MigrationAddsANullableStreamIdColumnOnTopOfV1ThroughV3() {
        EntityManager em = entityManagerFactory.createEntityManager();
        try {
            Object[] column = (Object[]) em.createNativeQuery(
                            "select is_nullable, data_type from information_schema.columns "
                                    + "where table_name = 'asset_usages' and column_name = 'stream_id'")
                    .getSingleResult();
            assertEquals("YES", column[0], "stream_id must stay nullable so pre-V4 rows keep reading back as null");
            assertEquals("uuid", column[1]);
        } finally {
            em.close();
        }
    }

    /**
     * docs/FC-INTEGRATIONS-PLAN.md F-b: {@code V6__telemetry_flight_state.sql} must apply cleanly
     * on top of the V1-V5 schema {@link #migrateAndOpen} already migrated for every other test in
     * this class, adding {@code telemetry_samples.flight_state} as a nullable jsonb column
     * (additive, no backfill) rather than requiring a fresh database — same shape/rationale as
     * {@link #v4MigrationAddsANullableStreamIdColumnOnTopOfV1ThroughV3}.
     */
    @Test
    void v6MigrationAddsANullableFlightStateColumnOnTopOfV1ThroughV5() {
        EntityManager em = entityManagerFactory.createEntityManager();
        try {
            Object[] column = (Object[]) em.createNativeQuery(
                            "select is_nullable, data_type from information_schema.columns "
                                    + "where table_name = 'telemetry_samples' and column_name = 'flight_state'")
                    .getSingleResult();
            assertEquals("YES", column[0],
                    "flight_state must stay nullable so pre-V6 rows keep reading back as null");
            assertEquals("jsonb", column[1]);
        } finally {
            em.close();
        }
    }

    /**
     * docs/OPS-CORE-PLAN.md §G: {@code V7__geofence_zones.sql} must apply cleanly on top of the
     * V1-V6 schema {@link #migrateAndOpen} already migrated for every other test in this class,
     * creating the new {@code geofence_zones} table (a brand-new table, nothing else changed) —
     * same "prove the schema itself, not just a round-trip" reasoning as {@link
     * #v4MigrationAddsANullableStreamIdColumnOnTopOfV1ThroughV3}/{@link
     * #v6MigrationAddsANullableFlightStateColumnOnTopOfV1ThroughV5}.
     */
    @Test
    void v7MigrationCreatesTheGeofenceZonesTableOnTopOfV1ThroughV6() {
        EntityManager em = entityManagerFactory.createEntityManager();
        try {
            Object[] polygonColumn = (Object[]) em.createNativeQuery(
                            "select is_nullable, data_type from information_schema.columns "
                                    + "where table_name = 'geofence_zones' and column_name = 'polygon'")
                    .getSingleResult();
            assertEquals("NO", polygonColumn[0], "polygon is required");
            assertEquals("jsonb", polygonColumn[1]);

            String maxAltitudeNullable = (String) em.createNativeQuery(
                            "select is_nullable from information_schema.columns where table_name = 'geofence_zones' "
                                    + "and column_name = 'max_altitude_meters'")
                    .getSingleResult();
            assertEquals("YES", maxAltitudeNullable, "max_altitude_meters must be nullable (no ceiling)");
        } finally {
            em.close();
        }
    }

    /**
     * docs/U-AUTH-PLAN.md wave 3 — proves {@code V8__users_groups.sql} applied on top of V1-V7:
     * {@code users.memberships} is a required {@code jsonb} column and {@code groups.parent_id} is a
     * nullable {@code uuid} (root groups have none), same shape of check as the V7 test above.
     */
    @Test
    void v8MigrationCreatesUsersAndGroupsOnTopOfV1ThroughV7() {
        EntityManager em = entityManagerFactory.createEntityManager();
        try {
            Object[] membershipsColumn = (Object[]) em.createNativeQuery(
                            "select is_nullable, data_type from information_schema.columns "
                                    + "where table_name = 'users' and column_name = 'memberships'")
                    .getSingleResult();
            assertEquals("NO", membershipsColumn[0], "users.memberships is required");
            assertEquals("jsonb", membershipsColumn[1]);

            Object[] parentIdColumn = (Object[]) em.createNativeQuery(
                            "select is_nullable, data_type from information_schema.columns "
                                    + "where table_name = 'groups' and column_name = 'parent_id'")
                    .getSingleResult();
            assertEquals("YES", parentIdColumn[0], "groups.parent_id must be nullable (root groups)");
            assertEquals("uuid", parentIdColumn[1]);
        } finally {
            em.close();
        }
    }

    /**
     * docs/U-SCOPE-PLAN.md slice 2 — same schema-shape proof as the V4/V6/V7/V8 tests: the
     * brand-new {@code pilot_assignments} table exists on top of V1-V8 with a composite
     * ({@code pilot_user_id}, {@code asset_id}) primary key (both required {@code uuid} columns).
     */
    @Test
    void v9MigrationCreatesThePilotAssignmentsTableOnTopOfV1ThroughV8() {
        EntityManager em = entityManagerFactory.createEntityManager();
        try {
            Object[] pilotColumn = (Object[]) em.createNativeQuery(
                            "select is_nullable, data_type from information_schema.columns "
                                    + "where table_name = 'pilot_assignments' and column_name = 'pilot_user_id'")
                    .getSingleResult();
            assertEquals("NO", pilotColumn[0], "pilot_assignments.pilot_user_id is part of the PK, required");
            assertEquals("uuid", pilotColumn[1]);

            Object[] assetColumn = (Object[]) em.createNativeQuery(
                            "select is_nullable, data_type from information_schema.columns "
                                    + "where table_name = 'pilot_assignments' and column_name = 'asset_id'")
                    .getSingleResult();
            assertEquals("NO", assetColumn[0], "pilot_assignments.asset_id is part of the PK, required");
            assertEquals("uuid", assetColumn[1]);

            long pkColumns = ((Number) em.createNativeQuery(
                            "select count(*) from information_schema.table_constraints tc "
                                    + "join information_schema.key_column_usage kcu "
                                    + "on tc.constraint_name = kcu.constraint_name "
                                    + "where tc.table_name = 'pilot_assignments' "
                                    + "and tc.constraint_type = 'PRIMARY KEY'")
                    .getSingleResult()).longValue();
            assertEquals(2, pkColumns, "the primary key must be the composite (pilot_user_id, asset_id)");
        } finally {
            em.close();
        }
    }

    /**
     * docs/TACTICAL-MARKS-PLAN.md §3 — same schema-shape proof as the V4/V6/V7/V8/V9 tests: the
     * brand-new {@code marks} table exists on top of V1-V9, with {@code altitude_meters} and
     * {@code note} staying nullable (ground point unknown / no note given) while {@code latitude}
     * is required.
     */
    @Test
    void v10MigrationCreatesTheMarksTableOnTopOfV1ThroughV9() {
        EntityManager em = entityManagerFactory.createEntityManager();
        try {
            Object[] altitudeColumn = (Object[]) em.createNativeQuery(
                            "select is_nullable, data_type from information_schema.columns "
                                    + "where table_name = 'marks' and column_name = 'altitude_meters'")
                    .getSingleResult();
            assertEquals("YES", altitudeColumn[0], "altitude_meters must be nullable (ground point unknown)");
            assertEquals("double precision", altitudeColumn[1]);

            Object[] latitudeColumn = (Object[]) em.createNativeQuery(
                            "select is_nullable, data_type from information_schema.columns "
                                    + "where table_name = 'marks' and column_name = 'latitude'")
                    .getSingleResult();
            assertEquals("NO", latitudeColumn[0], "latitude is required");
            assertEquals("double precision", latitudeColumn[1]);

            String noteNullable = (String) em.createNativeQuery(
                            "select is_nullable from information_schema.columns where table_name = 'marks' "
                                    + "and column_name = 'note'")
                    .getSingleResult();
            assertEquals("YES", noteNullable, "note is optional");
        } finally {
            em.close();
        }
    }

    /**
     * docs/CV-TRAINING-PLAN.md §1, Wave T3 — same schema-shape proof as the V4/V6/V7/V8/V9/V10
     * tests: the three brand-new training-pipeline tables exist on top of V1-V10, with {@code
     * target_category}/{@code asset_id} staying nullable (no target category / stream not yet
     * resolved to an asset) while {@code stream_id} and {@code sample_images.data} stay required.
     */
    @Test
    void v11MigrationCreatesTheTrainingDatasetsTablesOnTopOfV1ThroughV10() {
        EntityManager em = entityManagerFactory.createEntityManager();
        try {
            String targetCategoryNullable = (String) em.createNativeQuery(
                            "select is_nullable from information_schema.columns where table_name = 'datasets' "
                                    + "and column_name = 'target_category'")
                    .getSingleResult();
            assertEquals("YES", targetCategoryNullable, "target_category is optional");

            String assetIdNullable = (String) em.createNativeQuery(
                            "select is_nullable from information_schema.columns "
                                    + "where table_name = 'training_samples' and column_name = 'asset_id'")
                    .getSingleResult();
            assertEquals("YES", assetIdNullable, "asset_id is unknown until the stream resolves to an asset");

            String streamIdNullable = (String) em.createNativeQuery(
                            "select is_nullable from information_schema.columns "
                                    + "where table_name = 'training_samples' and column_name = 'stream_id'")
                    .getSingleResult();
            assertEquals("NO", streamIdNullable, "stream_id is required");

            Object[] dataColumn = (Object[]) em.createNativeQuery(
                            "select is_nullable, data_type from information_schema.columns "
                                    + "where table_name = 'sample_images' and column_name = 'data'")
                    .getSingleResult();
            assertEquals("NO", dataColumn[0], "sample_images.data is required");
            assertEquals("bytea", dataColumn[1]);
        } finally {
            em.close();
        }
    }
}
