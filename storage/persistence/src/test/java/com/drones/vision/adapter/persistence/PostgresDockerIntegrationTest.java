package com.drones.vision.adapter.persistence;

import com.drones.vision.adapter.persistence.repository.JpaControlProfileRepository;
import com.drones.vision.flight.domain.model.ActionBinding;
import com.drones.vision.flight.domain.model.ActionMap;
import com.drones.vision.flight.domain.model.ChannelMap;
import com.drones.vision.flight.domain.model.ControlAction;
import com.drones.vision.flight.domain.model.ControlBinding;
import com.drones.vision.flight.domain.model.ControlFunction;
import com.drones.vision.flight.domain.model.ControlInputKind;
import com.drones.vision.flight.domain.model.ControlProfile;
import com.drones.vision.flight.domain.model.ControlProfileId;
import com.drones.vision.flight.domain.model.OwnedControlProfile;
import com.drones.vision.flight.domain.model.PositionAction;
import com.drones.vision.flight.domain.model.SwitchPosition;
import com.drones.vision.flight.domain.model.VehicleKind;
import com.drones.vision.flight.domain.port.ControlProfileRepositoryPort;
import com.drones.vision.map.domain.model.AccessLevel;
import com.drones.vision.map.domain.model.Affiliation;
import com.drones.vision.learning.domain.model.Annotation;
import com.drones.vision.learning.domain.model.AnnotationSource;
import com.drones.vision.warehouse.domain.model.Asset;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.warehouse.domain.model.AssetImage;
import com.drones.vision.warehouse.domain.model.AssetUsage;
import com.drones.vision.kernel.BoundingBox;
import com.drones.vision.kernel.Capability;
import com.drones.vision.map.domain.model.CameraPose;
import com.drones.vision.map.domain.model.CameraPoseSource;
import com.drones.vision.kernel.CategoryId;
import com.drones.vision.learning.domain.model.Dataset;
import com.drones.vision.learning.domain.model.DatasetId;
import com.drones.vision.learning.domain.model.DatasetStatus;
import com.drones.vision.perception.domain.model.Detection;
import com.drones.vision.perception.domain.model.DetectionEvent;
import com.drones.vision.perception.domain.model.DetectionEventId;
import com.drones.vision.perception.domain.model.DetectionEventState;
import com.drones.vision.perception.domain.model.DetectionQuery;
import com.drones.vision.perception.domain.model.DetectionResult;
import com.drones.vision.warehouse.domain.model.Custody;
import com.drones.vision.warehouse.domain.model.Device;
import com.drones.vision.warehouse.domain.model.DeviceCategory;
import com.drones.vision.warehouse.domain.model.Identity;
import com.drones.vision.kernel.DeviceId;
import com.drones.vision.kernel.FlightState;
import com.drones.vision.kernel.GeoPosition;
import com.drones.vision.flight.domain.model.CorrectionSource;
import com.drones.vision.flight.domain.model.CorrectionStatus;
import com.drones.vision.flight.domain.model.FeatureRequirement;
import com.drones.vision.flight.domain.model.FlightPhase;
import com.drones.vision.flight.domain.model.GeofenceZone;
import com.drones.vision.identity.domain.model.Group;
import com.drones.vision.kernel.GroupId;
import com.drones.vision.map.domain.model.DrawKind;
import com.drones.vision.map.domain.model.Drawing;
import com.drones.vision.map.domain.model.DrawingId;
import com.drones.vision.map.domain.model.LayerGrant;
import com.drones.vision.map.domain.model.LayerId;
import com.drones.vision.map.domain.model.LayerKind;
import com.drones.vision.kernel.LifecycleState;
import com.drones.vision.map.domain.model.MapLayer;
import com.drones.vision.map.domain.model.Mark;
import com.drones.vision.map.domain.model.MarkId;
import com.drones.vision.map.domain.model.MarkKind;
import com.drones.vision.map.domain.model.MarkSource;
import com.drones.vision.map.domain.model.MarkStatus;
import com.drones.vision.identity.domain.model.Membership;
import com.drones.vision.flight.domain.model.MessageObservation;
import com.drones.vision.flight.domain.model.TrackCorrection;
import com.drones.vision.perception.domain.model.ModelRef;
import com.drones.vision.flight.domain.model.ParameterReading;
import com.drones.vision.kernel.Ownership;
import com.drones.vision.platform.AuditAction;
import com.drones.vision.platform.AuditEntry;
import com.drones.vision.platform.AuditId;
import com.drones.vision.platform.AuditTargetType;
import com.drones.vision.platform.AuditTrailPort;
import com.drones.vision.identity.domain.model.Role;
import com.drones.vision.learning.domain.model.SampleImage;
import com.drones.vision.learning.domain.model.SampleStatus;
import com.drones.vision.kernel.StreamDescriptor;
import com.drones.vision.kernel.StreamId;
import com.drones.vision.perception.domain.model.DetectionSource;
import com.drones.vision.perception.domain.model.DetectorReason;
import com.drones.vision.kernel.Telemetry;
import com.drones.vision.kernel.VisualFixEvidence;
import com.drones.vision.perception.domain.model.TrackRef;
import com.drones.vision.perception.domain.model.TrackState;
import com.drones.vision.perception.domain.model.TrackingTelemetry;
import com.drones.vision.map.domain.model.TrackPoint;
import com.drones.vision.learning.domain.model.TrainingSample;
import com.drones.vision.learning.domain.model.TrainingSampleId;
import com.drones.vision.kernel.UsageId;
import com.drones.vision.warehouse.domain.model.UsagePhase;
import com.drones.vision.kernel.UsageOrigin;
import com.drones.vision.identity.domain.model.User;
import com.drones.vision.kernel.UserId;
import com.drones.vision.flight.domain.model.VehicleProfile;
import com.drones.vision.flight.domain.model.ZoneId;
import com.drones.vision.map.domain.model.Verification;
import com.drones.vision.map.domain.model.Verification.VerificationState;
import com.drones.vision.flight.domain.model.ZoneKind;
import com.drones.vision.warehouse.domain.port.AssetImageRepositoryPort;
import com.drones.vision.warehouse.domain.port.AssetRepositoryPort;
import com.drones.vision.warehouse.domain.port.AssetUsageRepositoryPort;
import com.drones.vision.identity.domain.port.AssignmentRepositoryPort;
import com.drones.vision.map.domain.port.CameraPoseRepositoryPort;
import com.drones.vision.warehouse.domain.port.CategoryRepositoryPort;
import com.drones.vision.learning.domain.port.DatasetRepositoryPort;
import com.drones.vision.perception.domain.port.DetectionEventRepositoryPort;
import com.drones.vision.perception.domain.port.DetectionRepositoryPort;
import com.drones.vision.warehouse.domain.port.DeviceRepositoryPort;
import com.drones.vision.flight.domain.port.FeatureRequirementRepositoryPort;
import com.drones.vision.flight.domain.port.GeofenceRepositoryPort;
import com.drones.vision.map.domain.port.DrawingRepositoryPort;
import com.drones.vision.identity.domain.port.GroupRepositoryPort;
import com.drones.vision.map.domain.port.MapLayerRepositoryPort;
import com.drones.vision.map.domain.port.MarkRepositoryPort;
import com.drones.vision.learning.domain.port.SampleImageStorePort;
import com.drones.vision.flight.domain.port.TelemetryRepositoryPort;
import com.drones.vision.flight.domain.port.TrackCorrectionRepositoryPort;
import com.drones.vision.map.domain.port.TrackTrailRepositoryPort;
import com.drones.vision.learning.domain.port.TrainingSampleRepositoryPort;
import com.drones.vision.identity.domain.port.UserRepositoryPort;
import com.drones.vision.flight.domain.port.VehicleProfileRepositoryPort;

import com.drones.vision.adapter.persistence.config.ClosingDatasourceConnectionProvider;
import com.drones.vision.adapter.persistence.config.PersistencePoolSettings;
import com.drones.vision.adapter.persistence.config.PersistenceUnit;
import com.drones.vision.adapter.persistence.entity.DbAuditLogEntity;
import com.drones.vision.adapter.persistence.entity.DbAuditOperation;
import com.drones.vision.adapter.persistence.mapper.FeatureRequirementMapper;
import com.drones.vision.adapter.persistence.repository.JpaAssetImageRepository;
import com.drones.vision.adapter.persistence.repository.JpaAssetRepository;
import com.drones.vision.adapter.persistence.repository.JpaAssetUsageRepository;
import com.drones.vision.adapter.persistence.repository.JpaAssignmentRepository;
import com.drones.vision.adapter.persistence.repository.JpaAuditTrail;
import com.drones.vision.adapter.persistence.repository.JpaCameraPoseRepository;
import com.drones.vision.adapter.persistence.repository.JpaCategoryRepository;
import com.drones.vision.adapter.persistence.repository.JpaDatasetRepository;
import com.drones.vision.adapter.persistence.repository.JpaDbAuditLogRepository;
import com.drones.vision.adapter.persistence.repository.JpaDetectionEventRepository;
import com.drones.vision.adapter.persistence.repository.JpaDetectionRepository;
import com.drones.vision.adapter.persistence.repository.JpaDeviceRepository;
import com.drones.vision.adapter.persistence.repository.JpaDrawingRepository;
import com.drones.vision.adapter.persistence.repository.JpaFeatureRequirementRepository;
import com.drones.vision.adapter.persistence.repository.JpaGeofenceRepository;
import com.drones.vision.adapter.persistence.repository.JpaGroupRepository;
import com.drones.vision.adapter.persistence.repository.JpaMapLayerRepository;
import com.drones.vision.adapter.persistence.repository.JpaMarkRepository;
import com.drones.vision.adapter.persistence.repository.JpaSampleImageStore;
import com.drones.vision.adapter.persistence.repository.JpaTelemetryRepository;
import com.drones.vision.adapter.persistence.repository.TelemetryBatchSettings;
import com.drones.vision.adapter.persistence.repository.JpaTrackCorrectionRepository;
import com.drones.vision.adapter.persistence.repository.JpaTrackTrailRepository;
import com.drones.vision.adapter.persistence.repository.JpaTrainingSampleRepository;
import com.drones.vision.adapter.persistence.repository.JpaUserRepository;
import com.drones.vision.adapter.persistence.repository.JpaVehicleProfileRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;

import org.hibernate.HibernateException;
import org.hibernate.engine.jdbc.connections.spi.ConnectionProvider;
import org.hibernate.engine.spi.SessionFactoryImplementor;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    /**
     * {@link Asset#register} stamps {@code createdAt}/{@code updatedAt} with an internal, untestable
     * {@code Instant.now()} — unlike every other timestamp in this file, the test cannot substitute
     * the millisecond-truncated {@link #NOW} for it. Compare with both sides truncated to milliseconds
     * for the same reason {@link #NOW} exists: a raw {@code assertEquals(Asset, Asset)} is flaky
     * because Postgres's {@code TIMESTAMPTZ} keeps only microsecond precision and rounds — not
     * truncates — on the way in, so e.g. {@code .xxx614510} can come back as {@code .xxx615000}.
     */
    private static void assertAssetRoundTrips(Asset expected, Asset actual) {
        assertEquals(millisTruncated(expected), millisTruncated(actual));
    }

    private static Asset millisTruncated(Asset asset) {
        return new Asset(asset.id(), asset.displayName(), asset.category(), asset.ownership(), asset.devices(),
                asset.attributes(), asset.state(), asset.identity(), asset.custody(), asset.inventoryState(),
                asset.createdAt().truncatedTo(ChronoUnit.MILLIS), asset.updatedAt().truncatedTo(ChronoUnit.MILLIS));
    }

    /**
     * The control-plane / configuration tables {@code V21__db_audit_log.sql} attaches {@code
     * trg_audit_*} to — kept here, not just in the migration's own header, so {@link
     * DbAuditLogCoverageTests} fails loudly the moment a future migration adds a table and
     * nobody consciously classifies it. Mirrors that migration's "Included" list, plus {@code
     * camera_poses} added by {@code V22__fixed_camera_geo.sql} (docs/plans/done/FIXED-CAMERA-GEO-PLAN.md
     * decision D4 — a camera's pose is control-plane configuration, not telemetry), {@code
     * control_profiles} added by {@code V24__control_profiles.sql}
     * (docs/plans/active/CONTROLLER-SETUP-CONTEXT.md wave C4 — a saved layout decides what a switch
     * does to an aircraft, which is control-plane configuration by any reading), and {@code
     * maintenance_records}/{@code asset_notes} added by {@code V28__asset_inventory.sql}
     * (docs/plans/active/WAREHOUSE-UX-PLAN.md D7 — grounding/inspection history and crew notes are
     * operator-authored, low-volume, accountability-relevant records, not machine-output telemetry).
     */
    private static final Set<String> AUDITED_TABLES = Set.of(
            "categories", "devices", "device_capabilities", "assets", "asset_devices",
            "asset_usages", "geofence_zones", "groups", "users", "pilot_assignments",
            "marks", "datasets", "map_layers", "map_layer_grants", "map_drawings",
            "vehicle_profiles", "feature_requirements", "camera_poses", "control_profiles",
            "maintenance_records", "asset_notes");

    /**
     * Every other base table in the schema as of V22 — high-volume append-only event tables, the
     * existing domain audit trail, this table's own infrastructure, and Flyway's bookkeeping
     * table. Mirrors {@code V21__db_audit_log.sql}'s "Excluded" list, plus {@code
     * projected_track_points} added by {@code V22__fixed_camera_geo.sql} (docs/plans/active/
     * FIXED-CAMERA-GEO-PLAN.md decision D3 — a decimated trail is telemetry-character machine
     * output, the same classification {@code detection_results}/{@code telemetry_samples} already
     * have); see V21's header for the reasoning behind every other entry, including why {@code
     * asset_images} is grouped with {@code sample_images} rather than with the control-plane set
     * it might otherwise resemble. {@code track_corrections}, added by {@code
     * V23__track_corrections.sql} (docs/plans/done/VISUAL-GEO-V2-PLAN.md §3.7/D12), is the same
     * classification as {@code projected_track_points}: append-only, ~1 Hz per flying asset,
     * telemetry-character machine output.
     */
    private static final Set<String> EXCLUDED_TABLES = Set.of(
            "telemetry_samples", "detection_results", "detection_events",
            "training_samples", "sample_images", "asset_images",
            "audit_entries", "db_audit_log", "flyway_schema_history", "projected_track_points",
            "track_corrections");

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
                    List.of("hint-a", "hint-b"), true);

            repository.save(category);

            Optional<DeviceCategory> found = repository.findById(category.id());
            assertTrue(found.isPresent());
            assertEquals(category, found.get());
        }

        @Test
        void savedChildCategoryRoundTripsWithParent() {
            DeviceCategory parent = new DeviceCategory(new CategoryId("cat-parent"), "Parent", null, List.of(),
                    true);
            repository.save(parent);
            DeviceCategory child = new DeviceCategory(new CategoryId("cat-child"), "Child", parent.id(),
                    List.of("hint"), true);
            repository.save(child);

            Optional<DeviceCategory> found = repository.findById(child.id());
            assertTrue(found.isPresent());
            assertEquals(parent.id(), found.get().parent());
        }

        @Test
        void saveIsAnUpsert() {
            CategoryId id = new CategoryId("cat-upsert");
            repository.save(new DeviceCategory(id, "Original Name", null, List.of("a"), true));
            repository.save(new DeviceCategory(id, "Renamed", null, List.of("a", "b"), true));

            Optional<DeviceCategory> found = repository.findById(id);
            assertTrue(found.isPresent());
            assertEquals("Renamed", found.get().name());
            assertEquals(List.of("a", "b"), found.get().attributeHints());
        }

        @Test
        void findAllIncludesSavedCategory() {
            DeviceCategory category = new DeviceCategory(new CategoryId("cat-findall"), "Find All", null, List.of(),
                    true);
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
                    original.stream(), original.origin());
            repository.save(renamed);

            Optional<Device> found = repository.findById(id);
            assertTrue(found.isPresent());
            assertEquals("renamed", found.get().name());
            assertEquals(Set.of(Capability.VIDEO, Capability.AUDIO), found.get().capabilities());
        }

        @Test
        void savedDeviceRoundTripsOriginAndDefaultsExistingCallersToLive() {
            Device live = new Device(DeviceId.random(), "camera-live", Set.of(Capability.VIDEO),
                    new StreamDescriptor("rtsp", URI.create("rtsp://example/live"), Map.of()));
            Device simulated = new Device(DeviceId.random(), "camera-sim", Set.of(Capability.VIDEO),
                    new StreamDescriptor("sim", URI.create("sim://cam"), Map.of()),
                    LifecycleState.ACTIVE, com.drones.vision.kernel.DeviceOrigin.SIMULATED);

            repository.save(live);
            repository.save(simulated);

            assertEquals(com.drones.vision.kernel.DeviceOrigin.LIVE,
                    repository.findById(live.id()).orElseThrow().origin());
            assertEquals(com.drones.vision.kernel.DeviceOrigin.SIMULATED,
                    repository.findById(simulated.id()).orElseThrow().origin());
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
            Asset asset = Asset.register(AssetId.random(), "my drone", new CategoryId("drone"), ownership,
                    Set.of(deviceId), Map.of("weight-kg", "1.2"), Identity.NONE, Custody.NONE);

            repository.save(asset);

            Optional<Asset> found = repository.findById(asset.id());
            assertTrue(found.isPresent());
            assertAssetRoundTrips(asset, found.get());
        }

        @Test
        void saveIsAnUpsertAndPreservesLifecycleState() {
            AssetId id = AssetId.random();
            DeviceId deviceId = DeviceId.random();
            Ownership ownership = new Ownership(UserId.random(), GroupId.random());
            Asset original = Asset.register(id, "original", new CategoryId("drone"), ownership, Set.of(deviceId),
                    Map.of(), Identity.NONE, Custody.NONE);
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
            Asset asset = Asset.register(AssetId.random(), "device-owner", new CategoryId("drone"), ownership,
                    Set.of(deviceId), Map.of(), Identity.NONE, Custody.NONE);
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
            Asset asset = Asset.register(AssetId.random(), "to-delete", new CategoryId("drone"), ownership,
                    Set.of(DeviceId.random()), Map.of(), Identity.NONE, Custody.NONE);
            repository.save(asset);

            repository.deleteById(asset.id());
            assertTrue(repository.findById(asset.id()).isEmpty());

            // second call on an already-absent id must not throw
            repository.deleteById(asset.id());
        }

        @Test
        void findAllIncludesSavedAsset() {
            Ownership ownership = new Ownership(UserId.random(), GroupId.random());
            Asset asset = Asset.register(AssetId.random(), "findall-asset", new CategoryId("drone"), ownership,
                    Set.of(DeviceId.random()), Map.of(), Identity.NONE, Custody.NONE);
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
            AssetUsage usage = new AssetUsage(UsageId.random(), AssetId.random(), NOW, null, null, null, 0, null,
                    UsagePhase.PREFLIGHT, UsageOrigin.STREAM);

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
                    NOW.plusSeconds(60), start, last, 42, null, UsagePhase.PREFLIGHT, UsageOrigin.STREAM);

            repository.save(usage);

            Optional<AssetUsage> found = repository.findById(usage.id());
            assertTrue(found.isPresent());
            assertEquals(usage, found.get());
        }

        @Test
        void savedUsageWithNoStreamIdRoundTripsAsNull() {
            AssetUsage usage = new AssetUsage(UsageId.random(), AssetId.random(), NOW, null, null, null, 0, null,
                    UsagePhase.PREFLIGHT, UsageOrigin.STREAM);

            repository.save(usage);

            Optional<AssetUsage> found = repository.findById(usage.id());
            assertTrue(found.isPresent());
            assertNull(found.get().streamId(), "docs/plans/done/MVP2-PLAN.md R-a2's V4 column is additive/nullable");
        }

        @Test
        void savedUsageWithAStreamIdRoundTrips() {
            StreamId streamId = StreamId.random();
            AssetUsage usage = new AssetUsage(UsageId.random(), AssetId.random(), NOW, NOW.plusSeconds(60),
                    new GeoPosition(1.0, 2.0, null), new GeoPosition(3.0, 4.0, null), 7, streamId,
                    UsagePhase.PREFLIGHT, UsageOrigin.STREAM);

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
            AssetUsage open = new AssetUsage(id, assetId, NOW, null, null, null, 0, streamId, UsagePhase.PREFLIGHT,
                    UsageOrigin.STREAM);
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
            AssetUsage oldest = new AssetUsage(UsageId.random(), assetId, NOW, NOW.plusSeconds(1), null, null, 0,
                    null, UsagePhase.PREFLIGHT, UsageOrigin.STREAM);
            AssetUsage middle = new AssetUsage(UsageId.random(), assetId, NOW.plusSeconds(10),
                    NOW.plusSeconds(11), null, null, 0, null, UsagePhase.PREFLIGHT, UsageOrigin.STREAM);
            AssetUsage newest = new AssetUsage(UsageId.random(), assetId, NOW.plusSeconds(20),
                    NOW.plusSeconds(21), null, null, 0, null, UsagePhase.PREFLIGHT, UsageOrigin.STREAM);
            repository.save(oldest);
            repository.save(newest);
            repository.save(middle);

            List<AssetUsage> recent = repository.findRecentByAsset(assetId, 2);

            assertEquals(List.of(newest.id(), middle.id()), recent.stream().map(AssetUsage::id).toList());
        }

        @Test
        void findRecentReturnsNewestFirstAcrossEveryAssetBoundedByLimit() {
            // Fleet-wide (unlike findRecentByAsset), so this table also holds rows from every
            // other test in this class -- assert relative order among *this test's own* rows
            // (identified by id) within a large-enough fetch, rather than assuming these are the
            // only/topmost rows in the whole suite.
            AssetId assetA = AssetId.random();
            AssetId assetB = AssetId.random();
            AssetUsage oldest = new AssetUsage(UsageId.random(), assetA, NOW, NOW.plusSeconds(1), null, null, 0, null,
                    UsagePhase.PREFLIGHT, UsageOrigin.STREAM);
            AssetUsage middle = new AssetUsage(UsageId.random(), assetB, NOW.plusSeconds(10),
                    NOW.plusSeconds(11), null, null, 0, null, UsagePhase.PREFLIGHT, UsageOrigin.STREAM);
            AssetUsage newest = new AssetUsage(UsageId.random(), assetA, NOW.plusSeconds(20),
                    NOW.plusSeconds(21), null, null, 0, null, UsagePhase.PREFLIGHT, UsageOrigin.STREAM);
            repository.save(oldest);
            repository.save(newest);
            repository.save(middle);
            Set<UsageId> ours = Set.of(oldest.id(), middle.id(), newest.id());

            List<UsageId> ourOrder = repository.findRecent(10_000).stream()
                    .map(AssetUsage::id)
                    .filter(ours::contains)
                    .toList();

            assertEquals(List.of(newest.id(), middle.id(), oldest.id()), ourOrder,
                    "findRecent must span every asset (not just one) and stay newest-first");
        }

        @Test
        void findByStreamFindsTheUsageThatStreamOpened() {
            StreamId streamId = StreamId.random();
            AssetUsage usage = new AssetUsage(UsageId.random(), AssetId.random(), NOW, NOW.plusSeconds(60),
                    null, null, 3, streamId, UsagePhase.PREFLIGHT, UsageOrigin.STREAM);
            repository.save(usage);

            Optional<AssetUsage> found = repository.findByStream(streamId);

            assertTrue(found.isPresent());
            assertEquals(usage, found.get());
        }

        @Test
        void findByStreamIsEmptyForAnUnknownStreamAndForStreamlessRows() {
            repository.save(new AssetUsage(UsageId.random(), AssetId.random(), NOW, NOW.plusSeconds(1),
                    null, null, 0, null, UsagePhase.PREFLIGHT, UsageOrigin.STREAM));

            assertTrue(repository.findByStream(StreamId.random()).isEmpty(),
                    "a stream nothing recorded is an absence, and a stream_id=NULL row must never match it");
        }

        @Test
        void findOpenByAssetReturnsOnlyTheCurrentlyOpenUsage() {
            AssetId assetId = AssetId.random();
            AssetUsage closed = new AssetUsage(UsageId.random(), assetId, NOW, NOW.plusSeconds(1), null, null, 0,
                    null, UsagePhase.PREFLIGHT, UsageOrigin.STREAM);
            AssetUsage open = new AssetUsage(UsageId.random(), assetId, NOW.plusSeconds(10), null, null, null, 0,
                    null, UsagePhase.PREFLIGHT, UsageOrigin.STREAM);
            repository.save(closed);
            repository.save(open);

            Optional<AssetUsage> found = repository.findOpenByAsset(assetId);
            assertTrue(found.isPresent());
            assertEquals(open.id(), found.get().id());
        }

        @Test
        void findOpenByAssetReturnsEmptyWhenEveryUsageIsClosed() {
            AssetId assetId = AssetId.random();
            repository.save(new AssetUsage(UsageId.random(), assetId, NOW, NOW.plusSeconds(1), null, null, 0, null,
                    UsagePhase.PREFLIGHT, UsageOrigin.STREAM));

            assertTrue(repository.findOpenByAsset(assetId).isEmpty());
        }

        /**
         * docs/plans/active/DRONE-ONBOARDING-PLAN.md §2.3, Wave O5/O7 -- the regression this test
         * guards against: {@code AssetUsageMapper} used to have no {@code phase} field at all, so a
         * phase {@code UsageTracker} had actually computed and saved was silently discarded and every
         * reload came back {@code PREFLIGHT} regardless of what was saved. Explicitly asserts a
         * non-default phase (not {@code PREFLIGHT}, so a mapper that always wrote/read the default
         * could not accidentally pass this test) survives save-then-reload exactly.
         */
        @Test
        void savedUsageWithANonDefaultPhaseRoundTripsExactly() {
            AssetUsage usage = new AssetUsage(UsageId.random(), AssetId.random(), NOW, null, null, null, 0, null,
                    UsagePhase.IN_FLIGHT, UsageOrigin.STREAM);

            repository.save(usage);

            Optional<AssetUsage> found = repository.findById(usage.id());
            assertTrue(found.isPresent());
            assertEquals(UsagePhase.IN_FLIGHT, found.get().phase());
            assertEquals(usage, found.get());
        }

        /**
         * The origin twin of {@link #savedUsageWithANonDefaultPhaseRoundTripsExactly} — proves
         * {@code V26__asset_usage_origin.sql} plus {@code AssetUsageMapper} round-trip a
         * non-default {@link UsageOrigin} (not {@code STREAM}, the column's own database default,
         * so a mapper that always wrote/read the default could not accidentally pass this test)
         * exactly (docs/plans/active/ARCHITECTURE-AUDIT-2026-08-26.md D2, wave R2).
         */
        @Test
        void savedUsageWithANonDefaultOriginRoundTripsExactly() {
            AssetUsage usage = new AssetUsage(UsageId.random(), AssetId.random(), NOW, null, null, null, 0, null,
                    UsagePhase.PREFLIGHT, UsageOrigin.OPERATOR);

            repository.save(usage);

            Optional<AssetUsage> found = repository.findById(usage.id());
            assertTrue(found.isPresent());
            assertEquals(UsageOrigin.OPERATOR, found.get().origin());
            assertEquals(usage, found.get());
        }

        @Test
        void saveIsAnUpsertThatCanTransitionPhase() {
            UsageId id = UsageId.random();
            AssetId assetId = AssetId.random();
            repository.save(
                    new AssetUsage(id, assetId, NOW, null, null, null, 0, null, UsagePhase.PREFLIGHT, UsageOrigin.STREAM));

            repository.save(
                    new AssetUsage(id, assetId, NOW, null, null, null, 0, null, UsagePhase.IN_FLIGHT, UsageOrigin.STREAM));

            Optional<AssetUsage> found = repository.findById(id);
            assertTrue(found.isPresent());
            assertEquals(UsagePhase.IN_FLIGHT, found.get().phase(),
                    "re-saving an existing usage must overwrite phase, not just insert-once");
        }

        /**
         * Simulates a row written before {@code V19__asset_usage_phase.sql} existed (no backfill, so
         * {@code phase} is {@code NULL} on disk) by nulling the column directly after a normal save,
         * bypassing the mapper (which never writes {@code null} itself, since {@link
         * AssetUsage#phase()} is non-null by construction) -- proves {@code AssetUsageMapper#toDomain}
         * honestly defaults a legacy {@code NULL} column to {@link UsagePhase#PREFLIGHT} before
         * passing it to {@link AssetUsage}'s single canonical constructor, per {@code
         * AssetUsageEntity}'s javadoc.
         */
        @Test
        void legacyRowWithNullPhaseColumnMapsToPreflightDefault() {
            AssetUsage usage = new AssetUsage(UsageId.random(), AssetId.random(), NOW, null, null, null, 0, null,
                    UsagePhase.LINK_LOST, UsageOrigin.STREAM);
            repository.save(usage);
            EntityManager em = entityManagerFactory.createEntityManager();
            try {
                em.getTransaction().begin();
                em.createNativeQuery("update asset_usages set phase = null where id = :id")
                        .setParameter("id", usage.id().value())
                        .executeUpdate();
                em.getTransaction().commit();
            } finally {
                em.close();
            }

            Optional<AssetUsage> found = repository.findById(usage.id());

            assertTrue(found.isPresent());
            assertEquals(UsagePhase.PREFLIGHT, found.get().phase());
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
         * docs/plans/done/FC-INTEGRATIONS-PLAN.md F-b: {@code flight_state} round-trips a full {@link
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

        /**
         * docs/plans/done/SCALE-100-PLAN.md S4, item 2: below the size bound and nowhere near the
         * (deliberately huge) time bound, a batching repository must not have written anything yet
         * -- proving {@link #save} genuinely defers the write rather than writing through and
         * merely pretending to batch. The size bound then flushes every buffered sample together in
         * one transaction.
         */
        @Test
        void batchedSaveDefersWritesUntilTheSizeBoundThenFlushesTogether() {
            TelemetryRepositoryPort repository = new JpaTelemetryRepository(entityManagerFactory,
                    JpaTelemetryRepository.DEFAULT_RETENTION_LIMIT_PER_USAGE, new TelemetryBatchSettings(3, 60_000));
            UsageId usageId = UsageId.random();
            DeviceId deviceId = DeviceId.random();

            repository.save(usageId, new Telemetry(deviceId, NOW, null, null, null, null, null, Map.of()));
            repository.save(usageId, new Telemetry(deviceId, NOW.plusSeconds(1), null, null, null, null, null, Map.of()));
            assertTrue(repository.findByUsage(usageId, 10).isEmpty(),
                    "below the size bound and far from the huge window, nothing should be durable yet");

            repository.save(usageId, new Telemetry(deviceId, NOW.plusSeconds(2), null, null, null, null, null, Map.of()));

            assertEquals(3, repository.findByUsage(usageId, 10).size(),
                    "the third save trips the size bound and flushes all three together");
        }

        /**
         * docs/plans/done/SCALE-100-PLAN.md S4's stated trade-off, proven rather than asserted by
         * inspection: a sample below the size bound sits only in heap -- exactly what a crash right
         * now would lose -- but the configured window bounds that loss, flushing it on its own once
         * the deadline passes even though nothing else ever arrived to trip the size bound.
         */
        @Test
        void batchedSaveIsDurableWithinTheConfiguredWindowEvenBelowTheSizeBound() throws InterruptedException {
            long windowMillis = 100;
            TelemetryRepositoryPort repository = new JpaTelemetryRepository(entityManagerFactory,
                    JpaTelemetryRepository.DEFAULT_RETENTION_LIMIT_PER_USAGE,
                    new TelemetryBatchSettings(1000, windowMillis));
            UsageId usageId = UsageId.random();
            DeviceId deviceId = DeviceId.random();

            repository.save(usageId, new Telemetry(deviceId, NOW, null, null, null, null, null, Map.of()));
            assertTrue(repository.findByUsage(usageId, 10).isEmpty(),
                    "immediately after a below-size-bound save the sample is only buffered in memory");

            Thread.sleep(windowMillis * 3);

            assertEquals(1, repository.findByUsage(usageId, 10).size(),
                    "the batch window bounds how long a sample can stay undurable -- it must flush on its own");
        }

        /**
         * The buffer map is keyed by usage and sits on the telemetry hot path, so a drained batch
         * that is not *removed* leaks one entry per flight for the life of the JVM -- the same
         * unbounded-map defect (docs/plans/done/SCALE-100-PLAN.md fact 2f) S2 had to fix in {@code
         * LiveUpdateRegistry}, reintroduced by the change meant to relieve that pressure. Both
         * flush paths are covered because they evict independently: the size bound drains inline on
         * a caller thread, the window drains on the scheduler.
         */
        @Test
        void bothFlushPathsEvictTheirBufferSoTheMapDoesNotGrowPerUsage() throws InterruptedException {
            long windowMillis = 100;
            JpaTelemetryRepository repository = new JpaTelemetryRepository(entityManagerFactory,
                    JpaTelemetryRepository.DEFAULT_RETENTION_LIMIT_PER_USAGE,
                    new TelemetryBatchSettings(2, windowMillis));
            DeviceId deviceId = DeviceId.random();

            UsageId flushedBySize = UsageId.random();
            repository.save(flushedBySize, new Telemetry(deviceId, NOW, null, null, null, null, null, Map.of()));
            assertEquals(1, repository.pendingBatchCount(), "a buffered sample must be visible as pending");
            repository.save(flushedBySize, new Telemetry(deviceId, NOW.plusSeconds(1), null, null, null, null, null, Map.of()));
            assertEquals(0, repository.pendingBatchCount(),
                    "the size bound drained this usage, so its entry must be gone -- not left behind empty");

            UsageId flushedByWindow = UsageId.random();
            repository.save(flushedByWindow, new Telemetry(deviceId, NOW, null, null, null, null, null, Map.of()));
            Thread.sleep(windowMillis * 3);
            assertEquals(0, repository.pendingBatchCount(),
                    "the window flush must evict too, or a usage that never trips the size bound leaks forever");
            assertEquals(1, repository.findByUsage(flushedByWindow, 10).size(),
                    "eviction must mean flushed-then-removed, never dropped");
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

        /**
         * docs/plans/done/TRACKING-PLAN.md &sect;4.C: <b>the regression this whole "no migration" decision rests
         * on.</b> A {@code detection_results} row written <b>before</b> the tracking wave — its
         * {@code detections} jsonb carrying no {@code track} key at all — must still deserialize,
         * with {@link Detection#track()} reading {@code null}.
         *
         * <p>Written as a hand-rolled jsonb literal inserted through native SQL rather than by
         * saving a domain object, deliberately: an untracked {@code Detection} serialized by
         * <i>today's</i> Jackson would prove nothing about a blob produced by <i>yesterday's</i>
         * record shape. This is the actual pre-tracking bytes.
         */
        @Test
        void preTrackingJsonbRowsStillDeserializeWithTrackReadingNull() {
            StreamId streamId = StreamId.random();
            String preTrackingBlob = """
                    [{"label":"person","confidence":0.87,\
                    "box":{"x":0.1,"y":0.2,"width":0.3,"height":0.4},\
                    "model":{"id":"yolo","version":"v1"}}]""";

            EntityManager em = entityManagerFactory.createEntityManager();
            try {
                em.getTransaction().begin();
                em.createNativeQuery("insert into detection_results "
                                + "(id, stream_id, frame_sequence, captured_at, detections, inference_latency_nanos) "
                                + "values (?1, ?2, ?3, ?4, cast(?5 as jsonb), ?6)")
                        .setParameter(1, UUID.randomUUID())
                        .setParameter(2, streamId.value())
                        .setParameter(3, 11L)
                        .setParameter(4, NOW)
                        .setParameter(5, preTrackingBlob)
                        .setParameter(6, Duration.ofMillis(42).toNanos())
                        .executeUpdate();
                em.getTransaction().commit();
            } finally {
                em.close();
            }

            List<DetectionResult> found = repository.query(new DetectionQuery(streamId, null, null, null, 10));

            assertEquals(1, found.size());
            Detection detection = found.get(0).detections().get(0);
            assertNull(detection.track(), "a pre-tracking row must read back as an untracked detection");
            assertEquals("person", detection.label());
            assertEquals(0.87, detection.confidence());
            assertEquals(new BoundingBox(0.1, 0.2, 0.3, 0.4), detection.box());
            assertEquals(new ModelRef("yolo", "v1"), detection.model());
            assertEquals(11L, found.get(0).frameSequence());
            assertEquals(Duration.ofMillis(42), found.get(0).inferenceLatency());
        }

        /**
         * The other half of docs/plans/done/TRACKING-PLAN.md &sect;4.C: a tracked detection rides along in the
         * existing jsonb blob with <b>no schema change at all</b> — {@link TrackRef} is a plain
         * record inside the {@code List<Detection>} Jackson already serializes.
         *
         * <p>Also pins the deliberate omission: {@code DetectionResult#tracking()} (the <i>per-frame</i>
         * telemetry, as opposed to this <i>per-detection</i> track reference) has no column and is
         * <b>not</b> persisted, so it reads back {@code null}. That is a documented drop, not the
         * silent kind docs/extracts/TRACKING-ORCHESTRATION.md &sect;6 rule 6 warns about: the duty-cycle
         * counters it feeds are a live read model ({@code TrackingStatsWindow}), and a durable
         * trajectory/telemetry table is deferred to S2, which is the first thing that would query it.
         */
        @Test
        void aTrackedDetectionRoundTripsThroughTheJsonbBlobWithNoMigration() {
            StreamId streamId = StreamId.random();
            Detection tracked = new Detection("car", 0.82, new BoundingBox(0.31, 0.44, 0.09, 0.07),
                    new ModelRef("yolo26n.pt", "latest"),
                    new TrackRef(7L, TrackState.COASTING, DetectionSource.TRACKER, 0.012, -0.001, 143));

            repository.save(new DetectionResult(streamId, 5, NOW, List.of(tracked), Duration.ofMillis(3),
                    new TrackingTelemetry(true, DetectorReason.CADENCE, Duration.ofNanos(400_000), "lk", 7L)));

            List<DetectionResult> found = repository.query(new DetectionQuery(streamId, null, null, null, 10));

            assertEquals(1, found.size());
            assertEquals(List.of(tracked), found.get(0).detections(),
                    "every TrackRef component must survive the jsonb round trip");
            assertNull(found.get(0).tracking(),
                    "per-frame tracking telemetry has no column and is deliberately not persisted (§4.C)");
        }

        private DetectionResult emptyDetectionResult(StreamId streamId, long frameSequence, Instant capturedAt) {
            return new DetectionResult(streamId, frameSequence, capturedAt, List.of(), Duration.ZERO);
        }
    }

    /** docs/plans/done/UX-REWORK-PLAN.md §U-d item 3: the asset image store. */
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

    /** docs/plans/done/OPS-CORE-PLAN.md §G — every {@link GeofenceRepositoryPort} method, upsert semantics. */
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

    /** docs/plans/done/U-AUTH-PLAN.md wave 3 — users, incl. jsonb memberships + case-insensitive findByUsername. */
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

    /** docs/plans/done/U-AUTH-PLAN.md wave 3 — groups (org-chart nodes). */
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

    /**
     * docs/plans/done/TACTICAL-MARKS-PLAN.md §3, reworked by docs/plans/done/MAP-REWORK-PLAN.md §4.4 — every {@link
     * MarkRepositoryPort} method, upsert semantics, plus the five columns V12 adds (layer,
     * affiliation, and the flattened {@link Verification} triple).
     */
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
            Mark mark = new Mark(MarkId.random(), LayerId.random(), new GeoPosition(50.45, 30.52, 100.0),
                    MarkKind.TARGET, Affiliation.HOSTILE, "Bunker", "Reinforced, two entrances", ownership(),
                    NOW, MarkStatus.ACTIVE, MarkSource.MANUAL, Verification.unverified());

            repository.save(mark);

            Optional<Mark> found = repository.findById(mark.id());
            assertTrue(found.isPresent());
            assertEquals(mark, found.get());
        }

        @Test
        void detectionSourcedMarkWithNoAltitudeOrNoteRoundTripsWithNullFields() {
            Mark mark = new Mark(MarkId.random(), LayerId.random(), new GeoPosition(50.45, 30.52, null),
                    MarkKind.HAZARD, Affiliation.UNKNOWN, "Estimated hazard", null, ownership(), NOW,
                    MarkStatus.ACTIVE, MarkSource.DETECTION, Verification.unverified());

            repository.save(mark);

            Optional<Mark> found = repository.findById(mark.id());
            assertTrue(found.isPresent());
            assertNull(found.get().position().altitudeMeters());
            assertNull(found.get().note());
            assertEquals(MarkSource.DETECTION, found.get().source());
            assertEquals(VerificationState.UNVERIFIED, found.get().verification().state());
            assertNull(found.get().verification().verifiedBy());
            assertNull(found.get().verification().verifiedAt());
        }

        @Test
        void confirmedMarkRoundTripsItsReviewerAndReviewInstant() {
            UserId reviewer = UserId.random();
            Mark mark = new Mark(MarkId.random(), LayerId.random(), new GeoPosition(50.45, 30.52, null),
                    MarkKind.EQUIPMENT, Affiliation.HOSTILE, "Radar", null, ownership(), NOW,
                    MarkStatus.ACTIVE, MarkSource.MANUAL,
                    new Verification(VerificationState.CONFIRMED, reviewer, NOW));

            repository.save(mark);

            Optional<Mark> found = repository.findById(mark.id());
            assertTrue(found.isPresent());
            assertEquals(VerificationState.CONFIRMED, found.get().verification().state());
            assertEquals(reviewer, found.get().verification().verifiedBy());
            assertEquals(NOW, found.get().verification().verifiedAt());
        }

        @Test
        void saveIsAnUpsertPreservingId() {
            MarkId id = MarkId.random();
            LayerId team = LayerId.random();
            LayerId cop = LayerId.random();
            Ownership ownership = ownership();
            UserId reviewer = UserId.random();
            repository.save(new Mark(id, team, new GeoPosition(10.0, 20.0, null), MarkKind.POI,
                    Affiliation.NEUTRAL, "Original", null, ownership, NOW, MarkStatus.ACTIVE,
                    MarkSource.MANUAL, Verification.unverified()));
            repository.save(new Mark(id, cop, new GeoPosition(11.0, 21.0, 5.0), MarkKind.UNIT,
                    Affiliation.FRIENDLY, "Renamed", "Updated note", ownership, NOW, MarkStatus.CLEARED,
                    MarkSource.MANUAL, new Verification(VerificationState.CONFIRMED, reviewer, NOW)));

            Optional<Mark> found = repository.findById(id);
            assertTrue(found.isPresent());
            assertEquals("Renamed", found.get().label());
            assertEquals(MarkKind.UNIT, found.get().kind());
            assertEquals(Affiliation.FRIENDLY, found.get().affiliation());
            assertEquals("Updated note", found.get().note());
            assertEquals(MarkStatus.CLEARED, found.get().status());
            assertEquals(new GeoPosition(11.0, 21.0, 5.0), found.get().position());
            // promotion (withLayer) and verification both survive the upsert
            assertEquals(cop, found.get().layerId());
            assertEquals(VerificationState.CONFIRMED, found.get().verification().state());
        }

        @Test
        void findAllReturnsEverySavedMark() {
            Mark first = new Mark(MarkId.random(), LayerId.random(), new GeoPosition(10.0, 20.0, null),
                    MarkKind.TARGET, Affiliation.HOSTILE, "First", null, ownership(), NOW,
                    MarkStatus.ACTIVE, MarkSource.MANUAL, Verification.unverified());
            Mark second = new Mark(MarkId.random(), LayerId.random(), new GeoPosition(11.0, 21.0, null),
                    MarkKind.HAZARD, Affiliation.UNKNOWN, "Second", null, ownership(), NOW,
                    MarkStatus.ACTIVE, MarkSource.DETECTION, Verification.unverified());
            repository.save(first);
            repository.save(second);

            List<Mark> all = repository.findAll();
            assertTrue(all.contains(first));
            assertTrue(all.contains(second));
        }

        @Test
        void deleteByIdIsIdempotentAndRemovesTheMark() {
            Mark mark = new Mark(MarkId.random(), LayerId.random(), new GeoPosition(10.0, 20.0, null),
                    MarkKind.TARGET, Affiliation.HOSTILE, "Temp", null, ownership(), NOW,
                    MarkStatus.ACTIVE, MarkSource.MANUAL, Verification.unverified());
            repository.save(mark);

            repository.deleteById(mark.id());
            assertTrue(repository.findById(mark.id()).isEmpty());

            // second call on an already-absent id must not throw
            repository.deleteById(mark.id());
        }
    }

    /** docs/plans/done/CV-TRAINING-PLAN.md §1, Wave T3 — every {@link DatasetRepositoryPort} method, upsert semantics. */
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
     * docs/plans/done/CV-TRAINING-PLAN.md §1, Wave T3 — every {@link TrainingSampleRepositoryPort} method
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
     * docs/plans/done/CV-TRAINING-PLAN.md §1/§C, Wave T3 — every {@link SampleImageStorePort} method, the
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
     * docs/plans/done/MVP2-PLAN.md P-b's retention guard, in test form: uses the small-cap constructor
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
     * docs/plans/done/MVP2-PLAN.md P-b's done criterion in test form: a finished flight's telemetry and
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
                new GeoPosition(50.45, 30.52, 100.0), new GeoPosition(50.50, 30.60, 110.0), 2, streamId,
                UsagePhase.PREFLIGHT, UsageOrigin.STREAM);
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
     * docs/plans/done/MVP2-PLAN.md P-a's done criterion in test form: register an asset through one
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
        Asset asset = Asset.register(AssetId.random(), "restart-survivor", new CategoryId("drone"), ownership,
                Set.of(deviceId), Map.of("note", "written-before-restart"), Identity.NONE, Custody.NONE);
        new JpaAssetRepository(entityManagerFactory).save(asset);

        EntityManagerFactory freshContext = PersistenceUnit.start(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword());
        try {
            Optional<Asset> found = new JpaAssetRepository(freshContext).findById(asset.id());
            assertTrue(found.isPresent());
            assertAssetRoundTrips(asset, found.get());
            assertFalse(found.get().attributes().isEmpty());
        } finally {
            freshContext.close();
        }
    }

    /**
     * docs/plans/done/MVP2-PLAN.md R-a2: {@code V4__usage_stream_id.sql} must apply cleanly on top of the
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
     * docs/plans/done/FC-INTEGRATIONS-PLAN.md F-b: {@code V6__telemetry_flight_state.sql} must apply cleanly
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
     * docs/plans/done/OPS-CORE-PLAN.md §G: {@code V7__geofence_zones.sql} must apply cleanly on top of the
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
     * docs/plans/done/U-AUTH-PLAN.md wave 3 — proves {@code V8__users_groups.sql} applied on top of V1-V7:
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
     * docs/plans/done/U-SCOPE-PLAN.md slice 2 — same schema-shape proof as the V4/V6/V7/V8 tests: the
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
     * docs/plans/done/TACTICAL-MARKS-PLAN.md §3 — same schema-shape proof as the V4/V6/V7/V8/V9 tests: the
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
     * docs/plans/done/CV-TRAINING-PLAN.md §1, Wave T3 — same schema-shape proof as the V4/V6/V7/V8/V9/V10
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

    /**
     * docs/plans/done/MAP-REWORK-PLAN.md §2.3/§4.4 — every {@link MapLayerRepositoryPort} method, plus the
     * element-collection grant list's own wholesale-replacement semantics.
     */
    @Nested
    class MapLayerRepositoryTests {

        private final MapLayerRepositoryPort repository = new JpaMapLayerRepository(entityManagerFactory);

        private Ownership ownership() {
            return new Ownership(UserId.random(), GroupId.random());
        }

        @Test
        void unknownIdReturnsEmptyOptional() {
            assertTrue(repository.findById(LayerId.random()).isEmpty());
        }

        @Test
        void layerWithGrantsRoundTripsExactly() {
            MapLayer layer = new MapLayer(LayerId.random(), "Bravo team", LayerKind.TEAM, ownership(),
                    List.of(new LayerGrant(LayerGrant.SubjectType.USER, UUID.randomUUID(), AccessLevel.VIEW),
                            new LayerGrant(LayerGrant.SubjectType.GROUP, UUID.randomUUID(), AccessLevel.MANAGE)),
                    NOW);

            repository.save(layer);

            Optional<MapLayer> found = repository.findById(layer.id());
            assertTrue(found.isPresent());
            assertEquals(layer.name(), found.get().name());
            assertEquals(LayerKind.TEAM, found.get().kind());
            assertEquals(layer.ownership(), found.get().ownership());
            assertEquals(NOW, found.get().createdAt());
            assertEquals(Set.copyOf(layer.grants()), Set.copyOf(found.get().grants()));
        }

        @Test
        void layerWithNoGrantsRoundTripsWithAnEmptyGrantList() {
            MapLayer layer = new MapLayer(LayerId.random(), "Common picture", LayerKind.COP, ownership(),
                    List.of(), NOW);

            repository.save(layer);

            Optional<MapLayer> found = repository.findById(layer.id());
            assertTrue(found.isPresent());
            assertTrue(found.get().grants().isEmpty());
            assertEquals(LayerKind.COP, found.get().kind());
        }

        @Test
        void saveReplacesTheGrantListWholesaleRatherThanMergingIt() {
            UUID keptSubject = UUID.randomUUID();
            MapLayer layer = new MapLayer(LayerId.random(), "Alpha", LayerKind.PERSONAL, ownership(),
                    List.of(new LayerGrant(LayerGrant.SubjectType.USER, keptSubject, AccessLevel.VIEW),
                            new LayerGrant(LayerGrant.SubjectType.USER, UUID.randomUUID(), AccessLevel.MANAGE)),
                    NOW);
            repository.save(layer);

            repository.save(layer.withGrants(
                    List.of(new LayerGrant(LayerGrant.SubjectType.USER, keptSubject, AccessLevel.CONTRIBUTE))));

            Optional<MapLayer> found = repository.findById(layer.id());
            assertTrue(found.isPresent());
            assertEquals(1, found.get().grants().size(), "the dropped grant must be gone, not merged");
            assertEquals(keptSubject, found.get().grants().get(0).subjectId());
            assertEquals(AccessLevel.CONTRIBUTE, found.get().grants().get(0).level(),
                    "the surviving grant's level must be the new one");
        }

        @Test
        void saveIsAnUpsertPreservingId() {
            LayerId id = LayerId.random();
            Ownership ownership = ownership();
            repository.save(new MapLayer(id, "Before", LayerKind.TEAM, ownership, List.of(), NOW));
            repository.save(new MapLayer(id, "After", LayerKind.TEAM, ownership, List.of(), NOW));

            Optional<MapLayer> found = repository.findById(id);
            assertTrue(found.isPresent());
            assertEquals("After", found.get().name());
        }

        @Test
        void findAllReturnsEverySavedLayer() {
            MapLayer first = new MapLayer(LayerId.random(), "First", LayerKind.TEAM, ownership(), List.of(), NOW);
            MapLayer second = new MapLayer(LayerId.random(), "Second", LayerKind.PERSONAL, ownership(),
                    List.of(), NOW);
            repository.save(first);
            repository.save(second);

            List<LayerId> ids = repository.findAll().stream().map(MapLayer::id).toList();
            assertTrue(ids.contains(first.id()));
            assertTrue(ids.contains(second.id()));
        }

        @Test
        void deleteByIdIsIdempotentAndRemovesTheLayerAndItsGrants() {
            MapLayer layer = new MapLayer(LayerId.random(), "Temp", LayerKind.PERSONAL, ownership(),
                    List.of(new LayerGrant(LayerGrant.SubjectType.USER, UUID.randomUUID(), AccessLevel.VIEW)),
                    NOW);
            repository.save(layer);

            repository.deleteById(layer.id());
            assertTrue(repository.findById(layer.id()).isEmpty());

            // the ON DELETE CASCADE / Hibernate collection removal left no orphan grant row behind
            EntityManager em = entityManagerFactory.createEntityManager();
            try {
                Number orphans = (Number) em.createNativeQuery(
                                "select count(*) from map_layer_grants where layer_id = ?1")
                        .setParameter(1, layer.id().value())
                        .getSingleResult();
                assertEquals(0L, orphans.longValue());
            } finally {
                em.close();
            }

            // second call on an already-absent id must not throw
            repository.deleteById(layer.id());
        }
    }

    /** docs/plans/done/MAP-REWORK-PLAN.md §2.3/§4.4 — every {@link DrawingRepositoryPort} method. */
    @Nested
    class DrawingRepositoryTests {

        private final DrawingRepositoryPort repository = new JpaDrawingRepository(entityManagerFactory);

        private Ownership ownership() {
            return new Ownership(UserId.random(), GroupId.random());
        }

        @Test
        void unknownIdReturnsEmptyOptional() {
            assertTrue(repository.findById(DrawingId.random()).isEmpty());
        }

        @Test
        void polygonWithLabelAndColorTokenRoundTripsExactly() {
            Drawing drawing = new Drawing(DrawingId.random(), LayerId.random(), DrawKind.POLYGON,
                    List.of(new GeoPosition(50.0, 30.0, null), new GeoPosition(50.1, 30.0, null),
                            new GeoPosition(50.1, 30.1, 120.0)),
                    "Assembly area", "accent", ownership(), NOW);

            repository.save(drawing);

            Optional<Drawing> found = repository.findById(drawing.id());
            assertTrue(found.isPresent());
            assertEquals(drawing, found.get());
        }

        @Test
        void lineWithNoLabelOrColorTokenRoundTripsWithNullFields() {
            Drawing drawing = new Drawing(DrawingId.random(), LayerId.random(), DrawKind.LINE,
                    List.of(new GeoPosition(50.0, 30.0, null), new GeoPosition(50.5, 30.5, null)),
                    null, null, ownership(), NOW);

            repository.save(drawing);

            Optional<Drawing> found = repository.findById(drawing.id());
            assertTrue(found.isPresent());
            assertNull(found.get().label());
            assertNull(found.get().colorToken());
            assertEquals(2, found.get().points().size());
        }

        @Test
        void saveIsAnUpsertPreservingIdAndReplacingGeometry() {
            DrawingId id = DrawingId.random();
            LayerId layerId = LayerId.random();
            Ownership ownership = ownership();
            repository.save(new Drawing(id, layerId, DrawKind.LINE,
                    List.of(new GeoPosition(1.0, 2.0, null), new GeoPosition(3.0, 4.0, null)),
                    "Before", null, ownership, NOW));
            repository.save(new Drawing(id, layerId, DrawKind.LINE,
                    List.of(new GeoPosition(5.0, 6.0, null), new GeoPosition(7.0, 8.0, null),
                            new GeoPosition(9.0, 10.0, null)),
                    "After", "danger", ownership, NOW));

            Optional<Drawing> found = repository.findById(id);
            assertTrue(found.isPresent());
            assertEquals("After", found.get().label());
            assertEquals("danger", found.get().colorToken());
            assertEquals(3, found.get().points().size(), "geometry is replaced, not appended to");
        }

        @Test
        void findAllReturnsEverySavedDrawing() {
            Drawing first = new Drawing(DrawingId.random(), LayerId.random(), DrawKind.TEXT,
                    List.of(new GeoPosition(1.0, 2.0, null)), "Note", null, ownership(), NOW);
            Drawing second = new Drawing(DrawingId.random(), LayerId.random(), DrawKind.ARROW,
                    List.of(new GeoPosition(1.0, 2.0, null), new GeoPosition(3.0, 4.0, null)),
                    null, null, ownership(), NOW);
            repository.save(first);
            repository.save(second);

            List<Drawing> all = repository.findAll();
            assertTrue(all.contains(first));
            assertTrue(all.contains(second));
        }

        @Test
        void deleteByIdIsIdempotentAndRemovesTheDrawing() {
            Drawing drawing = new Drawing(DrawingId.random(), LayerId.random(), DrawKind.LINE,
                    List.of(new GeoPosition(1.0, 2.0, null), new GeoPosition(3.0, 4.0, null)),
                    null, null, ownership(), NOW);
            repository.save(drawing);

            repository.deleteById(drawing.id());
            assertTrue(repository.findById(drawing.id()).isEmpty());

            // second call on an already-absent id must not throw
            repository.deleteById(drawing.id());
        }
    }

    /**
     * docs/plans/done/MAP-REWORK-PLAN.md §4.4 — same shape as the V7-V11 schema tests, for the three new map
     * tables and the five columns V12 grafts onto {@code marks}. Also asserts the in-migration COP
     * layer row exists with the system ownership {@code LayerResolver} stamps, since that row is
     * what pre-existing marks were backfilled onto.
     */
    @Test
    void v12MigrationCreatesTheMapTablesAndBackfillsMarksOnTopOfV1ThroughV11() {
        EntityManager em = entityManagerFactory.createEntityManager();
        try {
            Object[] pointsColumn = (Object[]) em.createNativeQuery(
                            "select is_nullable, data_type from information_schema.columns "
                                    + "where table_name = 'map_drawings' and column_name = 'points'")
                    .getSingleResult();
            assertEquals("NO", pointsColumn[0], "map_drawings.points is required");
            assertEquals("jsonb", pointsColumn[1]);

            String colorTokenNullable = (String) em.createNativeQuery(
                            "select is_nullable from information_schema.columns "
                                    + "where table_name = 'map_drawings' and column_name = 'color_token'")
                    .getSingleResult();
            assertEquals("YES", colorTokenNullable, "color_token is optional");

            Number grantsPkColumns = (Number) em.createNativeQuery(
                            "select count(*) from information_schema.key_column_usage k "
                                    + "join information_schema.table_constraints c "
                                    + "on k.constraint_name = c.constraint_name "
                                    + "where c.table_name = 'map_layer_grants' "
                                    + "and c.constraint_type = 'PRIMARY KEY'")
                    .getSingleResult();
            assertEquals(3L, grantsPkColumns.longValue(),
                    "map_layer_grants is keyed by (layer_id, subject_type, subject_id)");

            String layerIdNullable = (String) em.createNativeQuery(
                            "select is_nullable from information_schema.columns "
                                    + "where table_name = 'marks' and column_name = 'layer_id'")
                    .getSingleResult();
            assertEquals("NO", layerIdNullable, "every mark is on a layer after the backfill");

            String affiliationNullable = (String) em.createNativeQuery(
                            "select is_nullable from information_schema.columns "
                                    + "where table_name = 'marks' and column_name = 'affiliation'")
                    .getSingleResult();
            assertEquals("NO", affiliationNullable, "every mark has an affiliation after the backfill");

            String verifiedByNullable = (String) em.createNativeQuery(
                            "select is_nullable from information_schema.columns "
                                    + "where table_name = 'marks' and column_name = 'verified_by'")
                    .getSingleResult();
            assertEquals("YES", verifiedByNullable, "verified_by is absent while a mark is UNVERIFIED");

            Object[] copLayer = (Object[]) em.createNativeQuery(
                            "select kind, owner_user_id, group_id from map_layers where id = ?1")
                    .setParameter(1, UUID.fromString("00000000-0000-0000-0000-000000000002"))
                    .getSingleResult();
            assertEquals("COP", copLayer[0]);
            assertEquals(new UUID(0, 0), copLayer[1], "the COP layer is owned by the system principal");
            assertEquals(new UUID(0, 1), copLayer[2]);
        } finally {
            em.close();
        }
    }

    /**
     * docs/plans/done/POSTGRES-ONLY-CONTEXT.md W3 — the durable audit trail: append-only record, plus
     * newest-first {@code findRecent}/{@code findByTarget}/{@code findByActor}.
     */
    @Nested
    class AuditTrailRepositoryTests {

        private final AuditTrailPort repository = new JpaAuditTrail(entityManagerFactory);

        @Test
        void recordedEntryRoundTripsEveryField() {
            String targetId = UUID.randomUUID().toString();
            AuditEntry entry = new AuditEntry(AuditId.random(), NOW, UserId.random(), AuditAction.UPDATED,
                    AuditTargetType.ASSET, targetId, "renamed the asset",
                    Map.of("before", "Old Name", "after", "New Name"));

            repository.record(entry);

            assertEquals(List.of(entry), repository.findByTarget(AuditTargetType.ASSET, targetId, 10));
        }

        @Test
        void findRecentReturnsNewestFirstAcrossEveryTargetBoundedByLimit() {
            // Table-wide (unlike findByTarget/findByActor), so this also holds rows from every
            // other test in this class -- assert relative order among *this test's own* rows
            // (identified by id) within a large-enough fetch, same technique as
            // AssetUsageRepositoryTests#findRecentReturnsNewestFirstAcrossEveryAssetBoundedByLimit.
            UserId actor = UserId.random();
            AuditEntry oldest = new AuditEntry(AuditId.random(), NOW, actor, AuditAction.CREATED,
                    AuditTargetType.ASSET, UUID.randomUUID().toString(), "created", Map.of());
            AuditEntry middle = new AuditEntry(AuditId.random(), NOW.plusSeconds(10), actor, AuditAction.UPDATED,
                    AuditTargetType.ASSET, UUID.randomUUID().toString(), "updated", Map.of());
            AuditEntry newest = new AuditEntry(AuditId.random(), NOW.plusSeconds(20), actor, AuditAction.DELETED,
                    AuditTargetType.ASSET, UUID.randomUUID().toString(), "deleted", Map.of());
            repository.record(oldest);
            repository.record(newest);
            repository.record(middle);
            Set<AuditId> ours = Set.of(oldest.id(), middle.id(), newest.id());

            List<AuditId> ourOrder = repository.findRecent(10_000).stream()
                    .map(AuditEntry::id)
                    .filter(ours::contains)
                    .toList();

            assertEquals(List.of(newest.id(), middle.id(), oldest.id()), ourOrder,
                    "findRecent must span every target (not just one) and stay newest-first");
        }

        @Test
        void findByTargetReturnsOnlyThatTargetsEntriesNewestFirstBoundedByLimit() {
            AuditTargetType targetType = AuditTargetType.DEVICE;
            String targetId = UUID.randomUUID().toString();
            String otherTargetId = UUID.randomUUID().toString();
            AuditEntry oldest = new AuditEntry(AuditId.random(), NOW, UserId.random(), AuditAction.CREATED,
                    targetType, targetId, "created", Map.of());
            AuditEntry middle = new AuditEntry(AuditId.random(), NOW.plusSeconds(10), UserId.random(),
                    AuditAction.UPDATED, targetType, targetId, "updated", Map.of());
            AuditEntry newest = new AuditEntry(AuditId.random(), NOW.plusSeconds(20), UserId.random(),
                    AuditAction.DEACTIVATED, targetType, targetId, "deactivated", Map.of());
            AuditEntry unrelated = new AuditEntry(AuditId.random(), NOW.plusSeconds(30), UserId.random(),
                    AuditAction.CREATED, targetType, otherTargetId, "unrelated", Map.of());
            repository.record(oldest);
            repository.record(newest);
            repository.record(middle);
            repository.record(unrelated);

            List<AuditEntry> found = repository.findByTarget(targetType, targetId, 2);

            assertEquals(List.of(newest.id(), middle.id()), found.stream().map(AuditEntry::id).toList());
        }

        @Test
        void findByActorReturnsOnlyThatActorsEntriesNewestFirst() {
            UserId actor = UserId.random();
            UserId otherActor = UserId.random();
            AuditEntry oldest = new AuditEntry(AuditId.random(), NOW, actor, AuditAction.CREATED,
                    AuditTargetType.MODEL, UUID.randomUUID().toString(), "created", Map.of());
            AuditEntry newest = new AuditEntry(AuditId.random(), NOW.plusSeconds(10), actor, AuditAction.UPDATED,
                    AuditTargetType.MODEL, UUID.randomUUID().toString(), "updated", Map.of());
            AuditEntry others = new AuditEntry(AuditId.random(), NOW.plusSeconds(5), otherActor,
                    AuditAction.CREATED, AuditTargetType.MODEL, UUID.randomUUID().toString(), "created", Map.of());
            repository.record(oldest);
            repository.record(newest);
            repository.record(others);

            List<AuditEntry> found = repository.findByActor(actor, 10);

            assertEquals(List.of(newest.id(), oldest.id()), found.stream().map(AuditEntry::id).toList());
        }
    }

    /**
     * docs/plans/done/POSTGRES-ONLY-CONTEXT.md W3 — {@link DetectionEventRepositoryPort}'s genuine
     * upsert-by-id semantics (unlike {@code DetectionRepositoryPort}'s append-only rows),
     * newest-first {@code findRecent}/{@code findByStream} ordering by {@code lastSeen}, and the
     * {@code sinceInclusive} lower bound.
     */
    @Nested
    class DetectionEventRepositoryTests {

        private final DetectionEventRepositoryPort repository = new JpaDetectionEventRepository(entityManagerFactory);

        private DetectionEvent detectionEvent(StreamId streamId, String label, Instant lastSeen) {
            return new DetectionEvent(DetectionEventId.random(), streamId, null, label, 0.5, lastSeen, lastSeen,
                    DetectionEventState.OPEN, null);
        }

        @Test
        void savedOpenEventWithPositionRoundTripsEveryField() {
            StreamId streamId = StreamId.random();
            AssetId assetId = AssetId.random();
            DetectionEvent event = new DetectionEvent(DetectionEventId.random(), streamId, assetId, "person", 0.87,
                    NOW, NOW.plusSeconds(2), DetectionEventState.OPEN, new GeoPosition(10.0, 20.0, 30.0));

            repository.save(event);

            assertEquals(List.of(event), repository.findByStream(streamId, 10));
        }

        @Test
        void savedEventWithNoAssetAndNoPositionRoundTripsBothAsNull() {
            StreamId streamId = StreamId.random();
            DetectionEvent event = new DetectionEvent(DetectionEventId.random(), streamId, null, "car", 0.5, NOW,
                    NOW, DetectionEventState.OPEN, null);

            repository.save(event);

            List<DetectionEvent> found = repository.findByStream(streamId, 10);
            assertEquals(1, found.size());
            assertNull(found.get(0).assetId());
            assertNull(found.get(0).position());
        }

        @Test
        void saveIsAnUpsertThatAdvancesLastSeenAndPeakConfidenceThenCloses() {
            StreamId streamId = StreamId.random();
            DetectionEventId id = DetectionEventId.random();
            DetectionEvent opened = new DetectionEvent(id, streamId, null, "person", 0.6, NOW, NOW,
                    DetectionEventState.OPEN, null);
            repository.save(opened);

            DetectionEvent advanced = opened.withObservation(NOW.plusSeconds(5), 0.9);
            repository.save(advanced);

            DetectionEvent closed = advanced.closed();
            repository.save(closed);

            List<DetectionEvent> found = repository.findByStream(streamId, 10);
            assertEquals(1, found.size(), "an id seen before must replace the row in place, not append");
            assertEquals(closed, found.get(0));
        }

        @Test
        void findByStreamReturnsEmptyForAnUnknownStream() {
            assertTrue(repository.findByStream(StreamId.random(), 10).isEmpty());
        }

        @Test
        void findByStreamReturnsNewestFirstBoundedByLimit() {
            StreamId streamId = StreamId.random();
            DetectionEvent oldest = detectionEvent(streamId, "a", NOW);
            DetectionEvent middle = detectionEvent(streamId, "b", NOW.plusSeconds(10));
            DetectionEvent newest = detectionEvent(streamId, "c", NOW.plusSeconds(20));
            repository.save(oldest);
            repository.save(newest);
            repository.save(middle);

            List<DetectionEvent> found = repository.findByStream(streamId, 2);

            assertEquals(List.of(newest.id(), middle.id()), found.stream().map(DetectionEvent::id).toList());
        }

        @Test
        void findRecentReturnsNewestFirstAcrossEveryStreamBoundedByLimit() {
            // Table-wide (unlike findByStream), so this also holds rows from every other test in
            // this class -- assert relative order among *this test's own* rows (identified by
            // id) within a large-enough fetch, same technique as
            // AssetUsageRepositoryTests#findRecentReturnsNewestFirstAcrossEveryAssetBoundedByLimit.
            DetectionEvent oldest = detectionEvent(StreamId.random(), "a", NOW);
            DetectionEvent middle = detectionEvent(StreamId.random(), "b", NOW.plusSeconds(10));
            DetectionEvent newest = detectionEvent(StreamId.random(), "c", NOW.plusSeconds(20));
            repository.save(oldest);
            repository.save(newest);
            repository.save(middle);
            Set<DetectionEventId> ours = Set.of(oldest.id(), middle.id(), newest.id());

            List<DetectionEventId> ourOrder = repository.findRecent(null, 10_000).stream()
                    .map(DetectionEvent::id)
                    .filter(ours::contains)
                    .toList();

            assertEquals(List.of(newest.id(), middle.id(), oldest.id()), ourOrder,
                    "findRecent must span every stream (not just one) and stay newest-first");
        }

        @Test
        void findRecentExcludesEventsWithLastSeenStrictlyBeforeSinceInclusive() {
            StreamId streamId = StreamId.random();
            Instant cursor = NOW.plusSeconds(100);
            DetectionEvent before = detectionEvent(streamId, "before", cursor.minusSeconds(1));
            DetectionEvent atCursor = detectionEvent(streamId, "at", cursor);
            DetectionEvent after = detectionEvent(streamId, "after", cursor.plusSeconds(1));
            repository.save(before);
            repository.save(atCursor);
            repository.save(after);
            Set<DetectionEventId> candidates = Set.of(before.id(), atCursor.id(), after.id());

            List<DetectionEventId> found = repository.findRecent(cursor, 10_000).stream()
                    .map(DetectionEvent::id)
                    .filter(candidates::contains)
                    .toList();

            assertEquals(List.of(after.id(), atCursor.id()), found,
                    "sinceInclusive excludes strictly-before events but includes the boundary");
        }
    }

    /**
     * {@link JpaControlProfileRepository} (docs/plans/active/CONTROLLER-SETUP-CONTEXT.md wave C4).
     *
     * <p>The round-trip test is not ceremony here: a profile's bindings are stored as jsonb lists of
     * the <em>domain records themselves</em>, so this is the only place that proves an operator's
     * saved layout survives a write and a read at all — including the enums, the switch positions and
     * the per-position action parameters.
     */
    @Nested
    class ControlProfileRepositoryTests {

        private final ControlProfileRepositoryPort repository = new JpaControlProfileRepository(entityManagerFactory);

        private OwnedControlProfile profileFor(UserId owner, VehicleKind kind, String name, boolean active) {
            ControlProfile layout = ControlProfile.forKind(kind).copyAs(ControlProfileId.random(), name);
            return new OwnedControlProfile(owner, layout, active, NOW);
        }

        @Test
        void findByIdReturnsEmptyForUnknownProfile() {
            assertTrue(repository.findById(ControlProfileId.random()).isEmpty());
        }

        @Test
        void everyBindingShapeSurvivesTheJsonbRoundTrip() {
            UserId owner = UserId.random();
            ControlProfile layout = ControlProfile.forKind(VehicleKind.ROVER)
                    .copyAs(ControlProfileId.random(), "Bench rover")
                    .withBindings(
                            new ChannelMap(List.of(
                                    ControlBinding.centeredAxis(ControlFunction.STEERING, 0, 1),
                                    ControlBinding.switched(ControlFunction.AUX_1, ControlBinding.Source.AXIS,
                                            ControlInputKind.SWITCH_3, 5, 6),
                                    new ControlBinding(ControlBinding.Source.AXIS, ControlInputKind.AXIS,
                                            ControlFunction.THROTTLE, 2, 3, 1100, 1500, 1900, 0.05, true))),
                            new ActionMap(List.of(
                                    ActionBinding.pressButton(0, ControlAction.ARM),
                                    new ActionBinding(ControlBinding.Source.AXIS, ControlInputKind.SWITCH_3, 4,
                                            List.of(new PositionAction(SwitchPosition.LOW, ControlAction.SET_MODE,
                                                            "Manual"),
                                                    new PositionAction(SwitchPosition.MIDDLE,
                                                            ControlAction.AUX_FUNCTION, "46"),
                                                    PositionAction.of(SwitchPosition.HIGH,
                                                            ControlAction.EMERGENCY_STOP))))));

            repository.save(new OwnedControlProfile(owner, layout, false, NOW));

            OwnedControlProfile found = repository.findById(layout.id()).orElseThrow();
            assertEquals(layout, found.profile());
            assertEquals(owner, found.owner());
        }

        @Test
        void savingTwiceUpdatesInPlaceRatherThanAppending() {
            UserId owner = UserId.random();
            OwnedControlProfile first = profileFor(owner, VehicleKind.COPTER, "Bench copter", false);
            repository.save(first);

            repository.save(new OwnedControlProfile(owner,
                    first.profile().copyAs(first.id(), "Field copter"), false, NOW));

            assertEquals("Field copter", repository.findById(first.id()).orElseThrow().profile().displayName());
            assertEquals(1, repository.findAllByOwner(owner).size());
        }

        @Test
        void activatingMovesTheFlagWithinOneKindAndLeavesOtherKindsAlone() {
            UserId owner = UserId.random();
            OwnedControlProfile roverA = profileFor(owner, VehicleKind.ROVER, "Rover A", false);
            OwnedControlProfile roverB = profileFor(owner, VehicleKind.ROVER, "Rover B", false);
            OwnedControlProfile copter = profileFor(owner, VehicleKind.COPTER, "Copter", false);
            repository.save(roverA);
            repository.save(roverB);
            repository.save(copter);

            repository.activate(owner, roverA.id());
            repository.activate(owner, roverB.id());
            repository.activate(owner, copter.id());

            assertEquals(roverB.id(), repository.findActive(owner, VehicleKind.ROVER).orElseThrow().id());
            assertEquals(copter.id(), repository.findActive(owner, VehicleKind.COPTER).orElseThrow().id());
            assertTrue(repository.findActive(owner, VehicleKind.PLANE).isEmpty());
        }

        @Test
        void oneOperatorsProfilesAreInvisibleToAnother() {
            UserId alice = UserId.random();
            UserId bob = UserId.random();
            OwnedControlProfile alices = profileFor(alice, VehicleKind.ROVER, "Alice rover", false);
            repository.save(alices);
            repository.activate(alice, alices.id());

            assertTrue(repository.findAllByOwner(bob).isEmpty());
            assertTrue(repository.findActive(bob, VehicleKind.ROVER).isEmpty());
            assertThrows(NoSuchElementException.class, () -> repository.activate(bob, alices.id()));
        }

        @Test
        void deletingLeavesNoActiveProfileBehind() {
            UserId owner = UserId.random();
            OwnedControlProfile profile = profileFor(owner, VehicleKind.ROVER, "Bench rover", false);
            repository.save(profile);
            repository.activate(owner, profile.id());

            repository.delete(profile.id());

            assertTrue(repository.findById(profile.id()).isEmpty());
            assertTrue(repository.findActive(owner, VehicleKind.ROVER).isEmpty());
        }
    }

    @Nested
    class VehicleProfileRepositoryTests {

        private final VehicleProfileRepositoryPort repository = new JpaVehicleProfileRepository(entityManagerFactory);

        @Test
        void findLatestReturnsEmptyForUnknownDevice() {
            assertTrue(repository.findLatest(DeviceId.random()).isEmpty());
        }

        @Test
        void savedCompleteProfileRoundTripsEveryField() {
            DeviceId deviceId = DeviceId.random();
            VehicleProfile profile = new VehicleProfile("udp://0.0.0.0:14550#7", NOW, 7, "ardupilot", "4.5.7",
                    "quadcopter", 12345L, List.of("MAVLINK2", "MISSION_INT"),
                    List.of(new MessageObservation(33, "GLOBAL_POSITION_INT", 0.9, 9)),
                    List.of(new ParameterReading("SR2_EXTRA2", 0.0, "REAL32")), 2300L, true, null);

            repository.save(deviceId, profile);

            Optional<VehicleProfile> found = repository.findLatest(deviceId);
            assertTrue(found.isPresent());
            assertEquals(profile, found.get());
        }

        @Test
        void savedIncompleteProfileRoundTripsIncompleteReasonAndNullFields() {
            DeviceId deviceId = DeviceId.random();
            VehicleProfile profile = new VehicleProfile("udp://0.0.0.0:14550#3", NOW, null, null, null, null, null,
                    List.of(), List.of(), List.of(), null, false, "AUTOPILOT_VERSION not answered within 3s");

            repository.save(deviceId, profile);

            Optional<VehicleProfile> found = repository.findLatest(deviceId);
            assertTrue(found.isPresent());
            assertEquals(profile, found.get());
        }

        /** {@link com.drones.vision.adapter.persistence.repository.JpaVehicleProfileRepository#save}
         * always inserts a new row (O3's "append-only, never overwrites" contract) — {@link
         * com.drones.vision.adapter.persistence.repository.JpaVehicleProfileRepository#findLatest}
         * must still resolve to the newest one. */
        @Test
        void saveIsAppendOnlyAndFindLatestReturnsTheNewestObservation() {
            DeviceId deviceId = DeviceId.random();
            VehicleProfile older = new VehicleProfile("udp://0.0.0.0:14550#7", NOW, 7, "ardupilot", "4.5.6",
                    "quadcopter", null, List.of(), List.of(), List.of(), null, true, null);
            VehicleProfile newer = new VehicleProfile("udp://0.0.0.0:14550#7", NOW.plusSeconds(60), 7, "ardupilot",
                    "4.5.7", "quadcopter", null, List.of(), List.of(), List.of(), null, true, null);

            repository.save(deviceId, older);
            repository.save(deviceId, newer);

            Optional<VehicleProfile> found = repository.findLatest(deviceId);
            assertTrue(found.isPresent());
            assertEquals(newer, found.get());
        }

        @Test
        void findLatestIsolatesProfilesPerDevice() {
            DeviceId deviceA = DeviceId.random();
            DeviceId deviceB = DeviceId.random();
            VehicleProfile profileA = new VehicleProfile("udp://a#1", NOW, 1, "ardupilot", null, null, null,
                    List.of(), List.of(), List.of(), null, false, "incomplete");
            VehicleProfile profileB = new VehicleProfile("udp://b#2", NOW, 2, "px4", null, null, null,
                    List.of(), List.of(), List.of(), null, false, "incomplete");
            repository.save(deviceA, profileA);
            repository.save(deviceB, profileB);

            assertEquals(profileA, repository.findLatest(deviceA).orElseThrow());
            assertEquals(profileB, repository.findLatest(deviceB).orElseThrow());
        }

        /**
         * docs/plans/active/DRONE-ONBOARDING-PLAN.md O11 -- the tagged {@code save}/{@code
         * findByUsageAndPhase} pair the flight passport is built on, round-tripped end to end through
         * V20's new {@code usage_id}/{@code phase} columns.
         */
        @Test
        void findByUsageAndPhaseReturnsEmptyWhenNeitherThisUsageNorPhaseWasCaptured() {
            assertTrue(repository.findByUsageAndPhase(UsageId.random(), FlightPhase.PREFLIGHT).isEmpty());
        }

        @Test
        void taggedSaveRoundTripsAndIsFoundByItsOwnUsageAndPhase() {
            DeviceId deviceId = DeviceId.random();
            UsageId usageId = UsageId.random();
            VehicleProfile profile = new VehicleProfile("udp://0.0.0.0:14550#7", NOW, 7, "ardupilot", "4.5.7",
                    "quadcopter", 12345L, List.of("MAVLINK2"),
                    List.of(new MessageObservation(33, "GLOBAL_POSITION_INT", 0.9, 9)),
                    List.of(new ParameterReading("SR2_EXTRA2", 0.0, "REAL32")), 2300L, true, null);

            repository.save(deviceId, usageId, FlightPhase.PREFLIGHT, profile);

            Optional<VehicleProfile> found = repository.findByUsageAndPhase(usageId, FlightPhase.PREFLIGHT);
            assertTrue(found.isPresent());
            assertEquals(profile, found.get());
        }

        @Test
        void taggedSaveIsNotFoundUnderADifferentPhaseOfTheSameUsage() {
            DeviceId deviceId = DeviceId.random();
            UsageId usageId = UsageId.random();
            VehicleProfile profile = new VehicleProfile("udp://0.0.0.0:14550#7", NOW, 7, "ardupilot", "4.5.7",
                    "quadcopter", null, List.of(), List.of(), List.of(), null, true, null);

            repository.save(deviceId, usageId, FlightPhase.PREFLIGHT, profile);

            assertTrue(repository.findByUsageAndPhase(usageId, FlightPhase.POSTFLIGHT).isEmpty());
        }

        @Test
        void taggedSaveIsNotFoundUnderADifferentUsageWithTheSamePhase() {
            DeviceId deviceId = DeviceId.random();
            VehicleProfile profile = new VehicleProfile("udp://0.0.0.0:14550#7", NOW, 7, "ardupilot", "4.5.7",
                    "quadcopter", null, List.of(), List.of(), List.of(), null, true, null);

            repository.save(deviceId, UsageId.random(), FlightPhase.PREFLIGHT, profile);

            assertTrue(repository.findByUsageAndPhase(UsageId.random(), FlightPhase.PREFLIGHT).isEmpty());
        }

        /**
         * Two flights of the same asset each capture their own PREFLIGHT/POSTFLIGHT pair -- {@link
         * VehicleProfileRepositoryPort#findByUsageAndPhase} must resolve each cell of that 2x2
         * independently, the exact lookup {@code driftFromPreviousFlight} depends on.
         */
        @Test
        void findByUsageAndPhaseDistinguishesAllFourCellsAcrossTwoFlights() {
            DeviceId deviceId = DeviceId.random();
            UsageId firstUsage = UsageId.random();
            UsageId secondUsage = UsageId.random();
            VehicleProfile firstPre = new VehicleProfile("udp://0.0.0.0:14550#7", NOW, 7, "ardupilot", "4.5.7",
                    "quadcopter", null, List.of(), List.of(),
                    List.of(new ParameterReading("SR2_EXTRA2", 0.0, "REAL32")), null, true, null);
            VehicleProfile firstPost = new VehicleProfile("udp://0.0.0.0:14550#7", NOW.plusSeconds(600), 7,
                    "ardupilot", "4.5.7", "quadcopter", null, List.of(), List.of(),
                    List.of(new ParameterReading("SR2_EXTRA2", 1.0, "REAL32")), null, true, null);
            VehicleProfile secondPre = new VehicleProfile("udp://0.0.0.0:14550#7", NOW.plusSeconds(1200), 7,
                    "ardupilot", "4.5.7", "quadcopter", null, List.of(), List.of(),
                    List.of(new ParameterReading("SR2_EXTRA2", 1.0, "REAL32")), null, true, null);

            repository.save(deviceId, firstUsage, FlightPhase.PREFLIGHT, firstPre);
            repository.save(deviceId, firstUsage, FlightPhase.POSTFLIGHT, firstPost);
            repository.save(deviceId, secondUsage, FlightPhase.PREFLIGHT, secondPre);

            assertEquals(firstPre, repository.findByUsageAndPhase(firstUsage, FlightPhase.PREFLIGHT).orElseThrow());
            assertEquals(firstPost, repository.findByUsageAndPhase(firstUsage, FlightPhase.POSTFLIGHT).orElseThrow());
            assertEquals(secondPre, repository.findByUsageAndPhase(secondUsage, FlightPhase.PREFLIGHT).orElseThrow());
            assertTrue(repository.findByUsageAndPhase(secondUsage, FlightPhase.POSTFLIGHT).isEmpty());
        }
    }

    /**
     * {@link FeatureRequirementRepositoryPort} has no {@code save} — every production row is Flyway
     * seed data ({@code V18__feature_requirements.sql}, D6), so most of these assert against that
     * real seeded data directly rather than against fixtures this test invents, proving the
     * migration's eleven rows are actually reachable through the port. The one exception ({@link
     * #mapperRoundTripsAnEntityNotFromSeedData}) inserts a throwaway row via {@link
     * FeatureRequirementMapper} directly (the port itself has no write method to call) to keep the
     * mapper's round trip under test, per that class's own javadoc.
     */
    @Nested
    class FeatureRequirementRepositoryTests {

        private final FeatureRequirementRepositoryPort repository =
                new JpaFeatureRequirementRepository(entityManagerFactory);

        @Test
        void findByFirmwareReturnsEveryFrozenFeatureKeyForArdupilot() {
            Set<String> found = repository.findByFirmware("ardupilot").stream()
                    .map(FeatureRequirement::featureKey)
                    .collect(java.util.stream.Collectors.toSet());

            assertEquals(FeatureRequirement.FEATURE_KEYS, found);
        }

        /**
         * D13: PX4 stays "generic MAVLink, unverified" until a PX4 SITL run proves otherwise — zero
         * seeded rows is the honest v1 state, not an omission bug (ReadinessService reports every
         * feature {@code UNKNOWN} for a firmware with no rows, per O3 MODULE.md).
         */
        @Test
        void findByFirmwareReturnsEmptyForAnUnseededFirmware() {
            assertTrue(repository.findByFirmware("px4").isEmpty());
        }

        @Test
        void mapPositionRowCarriesThePlanGivenTwoHertzThreshold() {
            FeatureRequirement row = repository.findByFirmware("ardupilot").stream()
                    .filter(r -> r.featureKey().equals("map-position"))
                    .findFirst()
                    .orElseThrow();

            assertEquals(33, row.requiredMessageId(), "GLOBAL_POSITION_INT");
            assertEquals("GLOBAL_POSITION_INT", row.requiredMessageName());
            assertEquals(2.0, row.minimumHz());
        }

        @Test
        void visualGeolocationRowCarriesThePlanGivenFiveHertzThreshold() {
            FeatureRequirement row = repository.findByFirmware("ardupilot").stream()
                    .filter(r -> r.featureKey().equals("visual-geolocation"))
                    .findFirst()
                    .orElseThrow();

            assertEquals(30, row.requiredMessageId(), "ATTITUDE");
            assertEquals(5.0, row.minimumHz());
        }

        @Test
        void fleetIdentityRowIsParameterOnly() {
            FeatureRequirement row = repository.findByFirmware("ardupilot").stream()
                    .filter(r -> r.featureKey().equals("fleet-identity"))
                    .findFirst()
                    .orElseThrow();

            assertNull(row.requiredMessageId());
            assertEquals("SYSID_THISMAV", row.requiredParameterName());
        }

        @Test
        void commandTxRowRequiresNeitherMessageNorParameter() {
            FeatureRequirement row = repository.findByFirmware("ardupilot").stream()
                    .filter(r -> r.featureKey().equals("command-tx"))
                    .findFirst()
                    .orElseThrow();

            assertNull(row.requiredMessageId());
            assertNull(row.requiredParameterName());
        }

        @Test
        void findAllIncludesEverySeededRow() {
            List<FeatureRequirement> all = repository.findAll();
            assertTrue(all.size() >= FeatureRequirement.FEATURE_KEYS.size());
        }

        @Test
        void mapperRoundTripsAnEntityNotFromSeedData() {
            // firmware is varchar(32); keep the throwaway id short, just unique enough not to
            // collide with a seeded row or another run of this same test.
            String firmware = "test-" + UUID.randomUUID().toString().substring(0, 8);
            FeatureRequirement custom =
                    new FeatureRequirement("battery", "Battery", firmware, 1, "SYS_STATUS", 1.0, null, null, null);

            EntityManager em = entityManagerFactory.createEntityManager();
            try {
                em.getTransaction().begin();
                em.persist(FeatureRequirementMapper.toEntity(custom));
                em.getTransaction().commit();
            } finally {
                em.close();
            }

            assertEquals(List.of(custom), repository.findByFirmware(firmware));
        }
    }

    /**
     * docs/plans/done/FIXED-CAMERA-GEO-PLAN.md decision D4 — every {@link CameraPoseRepositoryPort}
     * method against a real Postgres, plus the upsert-by-{@code assetId} contract the port's own
     * javadoc calls out.
     */
    @Nested
    class CameraPoseRepositoryTests {

        private final CameraPoseRepositoryPort repository = new JpaCameraPoseRepository(entityManagerFactory);

        @Test
        void unknownAssetReturnsEmptyOptional() {
            assertTrue(repository.findByAssetId(AssetId.random()).isEmpty());
        }

        @Test
        void savedPoseRoundTripsWithTargetLayerAndRmsError() {
            AssetId assetId = AssetId.random();
            LayerId layer = LayerId.random();
            UserId actor = UserId.random();
            CameraPose pose = new CameraPose(assetId, new GeoPosition(50.45, 30.52, 95.0), 12.0, 214.0, 8.5,
                    62.0, layer, CameraPoseSource.CALIBRATED, 7.3, NOW, actor);

            repository.save(pose);

            Optional<CameraPose> found = repository.findByAssetId(assetId);
            assertTrue(found.isPresent());
            assertEquals(pose, found.get());
        }

        @Test
        void manualPoseWithNoTargetLayerOrRmsErrorRoundTripsWithNullFields() {
            AssetId assetId = AssetId.random();
            CameraPose pose = new CameraPose(assetId, new GeoPosition(50.45, 30.52, null), 10.0, 0.0, 5.0, 70.0,
                    null, CameraPoseSource.MANUAL, null, NOW, UserId.random());

            repository.save(pose);

            Optional<CameraPose> found = repository.findByAssetId(assetId);
            assertTrue(found.isPresent());
            assertNull(found.get().position().altitudeMeters());
            assertNull(found.get().targetLayerId());
            assertNull(found.get().rmsErrorPixels());
            assertEquals(CameraPoseSource.MANUAL, found.get().source());
        }

        @Test
        void saveIsAnUpsertLeavingExactlyOneRowPerAsset() {
            AssetId assetId = AssetId.random();
            UserId actor = UserId.random();
            repository.save(new CameraPose(assetId, new GeoPosition(10.0, 20.0, null), 10.0, 0.0, 5.0, 60.0,
                    null, CameraPoseSource.MANUAL, null, NOW, actor));
            repository.save(new CameraPose(assetId, new GeoPosition(11.0, 21.0, 5.0), 15.0, 180.0, 10.0, 75.0,
                    LayerId.random(), CameraPoseSource.CALIBRATED, 4.2, NOW, actor));

            Optional<CameraPose> found = repository.findByAssetId(assetId);
            assertTrue(found.isPresent());
            assertEquals(15.0, found.get().aglMeters());
            assertEquals(CameraPoseSource.CALIBRATED, found.get().source());

            long rowsForAsset = repository.findAll().stream().filter(p -> p.assetId().equals(assetId)).count();
            assertEquals(1, rowsForAsset, "a second save for the same asset must replace, not add, a row");
        }

        @Test
        void findAllReturnsEverySavedPose() {
            CameraPose first = new CameraPose(AssetId.random(), new GeoPosition(10.0, 20.0, null), 10.0, 0.0,
                    5.0, 60.0, null, CameraPoseSource.MANUAL, null, NOW, UserId.random());
            CameraPose second = new CameraPose(AssetId.random(), new GeoPosition(11.0, 21.0, null), 12.0, 90.0,
                    6.0, 65.0, null, CameraPoseSource.MANUAL, null, NOW, UserId.random());
            repository.save(first);
            repository.save(second);

            List<CameraPose> all = repository.findAll();
            assertTrue(all.contains(first));
            assertTrue(all.contains(second));
        }

        @Test
        void deleteByAssetIdIsIdempotentAndRemovesThePose() {
            AssetId assetId = AssetId.random();
            repository.save(new CameraPose(assetId, new GeoPosition(10.0, 20.0, null), 10.0, 0.0, 5.0, 60.0,
                    null, CameraPoseSource.MANUAL, null, NOW, UserId.random()));

            repository.deleteByAssetId(assetId);
            assertTrue(repository.findByAssetId(assetId).isEmpty());

            // second call on an already-absent asset must not throw
            repository.deleteByAssetId(assetId);
        }
    }

    /**
     * docs/plans/done/FIXED-CAMERA-GEO-PLAN.md decision D3/§7 — every {@link TrackTrailRepositoryPort}
     * method against a real Postgres: append-only inserts, oldest-to-newest ordering, the D7
     * per-track cap ({@link TrackTrailRepositoryPort#trimToMostRecent}), and the D7 retention prune
     * ({@link TrackTrailRepositoryPort#deleteOlderThan}).
     */
    @Nested
    class TrackTrailRepositoryTests {

        private final TrackTrailRepositoryPort repository = new JpaTrackTrailRepository(entityManagerFactory);

        private TrackPoint point(AssetId assetId, long trackId, GeoPosition position, Instant capturedAt) {
            return new TrackPoint(assetId, trackId, "car", LayerId.random(), position, 6.5, capturedAt);
        }

        @Test
        void unknownTrackReturnsEmptyFindLatestAndEmptyList() {
            AssetId assetId = AssetId.random();
            assertTrue(repository.findLatest(assetId, 1L).isEmpty());
            assertTrue(repository.findByTrack(assetId, 1L).isEmpty());
        }

        @Test
        void savedPointRoundTripsWithAltitude() {
            AssetId assetId = AssetId.random();
            TrackPoint saved = point(assetId, 7L, new GeoPosition(50.45, 30.52, 95.0), NOW);

            repository.save(saved);

            List<TrackPoint> found = repository.findByTrack(assetId, 7L);
            assertEquals(1, found.size());
            assertEquals(saved, found.get(0));
        }

        @Test
        void findByTrackReturnsPointsOldestToNewestRegardlessOfInsertOrder() {
            AssetId assetId = AssetId.random();
            TrackPoint p1 = point(assetId, 3L, new GeoPosition(10.0, 20.0, null), NOW.minusSeconds(20));
            TrackPoint p2 = point(assetId, 3L, new GeoPosition(10.001, 20.001, null), NOW.minusSeconds(10));
            TrackPoint p3 = point(assetId, 3L, new GeoPosition(10.002, 20.002, null), NOW);
            repository.save(p2);
            repository.save(p3);
            repository.save(p1);

            List<TrackPoint> found = repository.findByTrack(assetId, 3L);
            assertEquals(List.of(p1, p2, p3), found, "oldest to newest");
        }

        @Test
        void findLatestReturnsTheMostRecentlyCapturedPoint() {
            AssetId assetId = AssetId.random();
            TrackPoint older = point(assetId, 9L, new GeoPosition(10.0, 20.0, null), NOW.minusSeconds(30));
            TrackPoint newer = point(assetId, 9L, new GeoPosition(10.001, 20.001, null), NOW);
            repository.save(older);
            repository.save(newer);

            Optional<TrackPoint> latest = repository.findLatest(assetId, 9L);
            assertTrue(latest.isPresent());
            assertEquals(newer, latest.get());
        }

        @Test
        void trimToMostRecentKeepsOnlyTheNewestPoints() {
            AssetId assetId = AssetId.random();
            List<Instant> capturedAtInOrder = List.of(
                    NOW.minusSeconds(40), NOW.minusSeconds(30), NOW.minusSeconds(20), NOW.minusSeconds(10), NOW);
            for (int i = 0; i < capturedAtInOrder.size(); i++) {
                repository.save(point(assetId, 4L, new GeoPosition(10.0 + i * 0.001, 20.0, null),
                        capturedAtInOrder.get(i)));
            }

            repository.trimToMostRecent(assetId, 4L, 2);

            List<TrackPoint> remaining = repository.findByTrack(assetId, 4L);
            assertEquals(2, remaining.size());
            assertEquals(NOW.minusSeconds(10), remaining.get(0).capturedAt());
            assertEquals(NOW, remaining.get(1).capturedAt());
        }

        @Test
        void trimToMostRecentIsANoOpWhenAlreadyAtOrUnderTheCap() {
            AssetId assetId = AssetId.random();
            repository.save(point(assetId, 5L, new GeoPosition(10.0, 20.0, null), NOW));

            repository.trimToMostRecent(assetId, 5L, 10);

            assertEquals(1, repository.findByTrack(assetId, 5L).size());
        }

        @Test
        void trimToMostRecentOnlyAffectsTheNamedTrack() {
            AssetId assetId = AssetId.random();
            repository.save(point(assetId, 1L, new GeoPosition(10.0, 20.0, null), NOW.minusSeconds(10)));
            repository.save(point(assetId, 1L, new GeoPosition(10.1, 20.1, null), NOW));
            repository.save(point(assetId, 2L, new GeoPosition(30.0, 40.0, null), NOW));

            repository.trimToMostRecent(assetId, 1L, 1);

            assertEquals(1, repository.findByTrack(assetId, 1L).size());
            assertEquals(1, repository.findByTrack(assetId, 2L).size(), "a different track's points are untouched");
        }

        @Test
        void deleteOlderThanRemovesPointsAcrossEveryTrackCapturedBeforeTheCutoff() {
            AssetId assetId = AssetId.random();
            Instant cutoff = NOW.minusSeconds(15);
            TrackPoint old1 = point(assetId, 1L, new GeoPosition(10.0, 20.0, null), NOW.minusSeconds(30));
            TrackPoint old2 = point(assetId, 2L, new GeoPosition(30.0, 40.0, null), NOW.minusSeconds(20));
            TrackPoint recent = point(assetId, 1L, new GeoPosition(10.1, 20.1, null), NOW);
            repository.save(old1);
            repository.save(old2);
            repository.save(recent);

            repository.deleteOlderThan(cutoff);

            assertEquals(List.of(recent), repository.findByTrack(assetId, 1L));
            assertTrue(repository.findByTrack(assetId, 2L).isEmpty());
        }
    }

    /**
     * docs/plans/done/VISUAL-GEO-V2-PLAN.md §3.5/§3.7, H5 — every {@link
     * TrackCorrectionRepositoryPort} method against a real Postgres: append-only inserts,
     * oldest-to-newest ordering, {@link TrackCorrectionRepositoryPort#findLatest}, the retention
     * prune ({@link TrackCorrectionRepositoryPort#deleteOlderThan}) and the per-usage cap ({@link
     * TrackCorrectionRepositoryPort#trimUsageToMostRecent}) -- plus a full round trip proving every
     * nullable field (a {@code NO_FIX} row, and a divergent {@code CONFIRMED} row) survives Postgres
     * unchanged, save for the frozen-schema placeholders {@code TrackCorrectionMapper} documents.
     */
    @Nested
    class TrackCorrectionRepositoryTests {

        private final TrackCorrectionRepositoryPort repository =
                new JpaTrackCorrectionRepository(entityManagerFactory);

        /**
         * {@code cellCalibrated}/{@code sequenceConverged} are deliberately set {@code true} here:
         * H8 gave them real columns, so a round trip that still read {@code false} back would be the
         * regression this fixture exists to catch (docs/plans/done/VISUAL-GEO-V2-PLAN.md §9.11
         * defect 4). {@code candidateCount}/{@code supportingFrames}/{@code baselineMeters} stay at
         * the mapper's documented placeholder values, since those genuinely have no column.
         */
        private VisualFixEvidence evidence() {
            return new VisualFixEvidence(0, 174, 131, 0.75, 0.41, 2.1, true, true, 0, 0.0, true, 38.0, 11, 1.0);
        }

        private TrackCorrection confirmed(AssetId assetId, UsageId usageId, Instant frameAt) {
            return new TrackCorrection(assetId, usageId, frameAt, frameAt.plusMillis(500),
                    CorrectionStatus.CONFIRMED, CorrectionSource.VISUAL_HEAVY,
                    new GeoPosition(50.39411, 30.62870, null), 214.6, 18.4, 96.2,
                    new GeoPosition(50.39402, 30.62851, null), 16.2, 21.0,
                    true, frameAt.minusSeconds(5),
                    "kyiv-pozniaky", "17/76687/44230", "", evidence());
        }

        private TrackCorrection noFix(AssetId assetId, UsageId usageId, Instant frameAt) {
            return new TrackCorrection(assetId, usageId, frameAt, frameAt.plusMillis(500),
                    CorrectionStatus.NO_FIX, CorrectionSource.VISUAL_HEAVY,
                    null, null, null, null,
                    null, null, null,
                    false, null,
                    "", "", "LOW_TEXTURE",
                    new VisualFixEvidence(0, 0, 0, 0.0, 0.0, 0.0, false, false, 0, 0.0, false, 0.0, 0, 1.0));
        }

        @Test
        void unknownAssetReturnsEmptyFindLatestAndEmptyUsageList() {
            assertTrue(repository.findLatest(AssetId.random()).isEmpty());
            assertTrue(repository.findByUsage(UsageId.random(), 10).isEmpty());
        }

        @Test
        void aConfirmedDivergentCorrectionRoundTripsWithEveryNullablePreserved() {
            AssetId assetId = AssetId.random();
            UsageId usageId = UsageId.random();
            TrackCorrection saved = confirmed(assetId, usageId, NOW);

            repository.save(saved);

            List<TrackCorrection> found = repository.findByUsage(usageId, 10);
            assertEquals(1, found.size());
            assertEquals(saved, found.get(0));
        }

        @Test
        void aNoFixCorrectionRoundTripsWithEveryNullableAbsent() {
            AssetId assetId = AssetId.random();
            UsageId usageId = UsageId.random();
            TrackCorrection saved = noFix(assetId, usageId, NOW);

            repository.save(saved);

            List<TrackCorrection> found = repository.findByUsage(usageId, 10);
            assertEquals(1, found.size());
            assertEquals(saved, found.get(0));
        }

        @Test
        void findByUsageReturnsCorrectionsOldestToNewestRegardlessOfInsertOrder() {
            AssetId assetId = AssetId.random();
            UsageId usageId = UsageId.random();
            TrackCorrection c1 = confirmed(assetId, usageId, NOW.minusSeconds(20));
            TrackCorrection c2 = confirmed(assetId, usageId, NOW.minusSeconds(10));
            TrackCorrection c3 = confirmed(assetId, usageId, NOW);
            repository.save(c2);
            repository.save(c3);
            repository.save(c1);

            assertEquals(List.of(c1, c2, c3), repository.findByUsage(usageId, 10), "oldest to newest");
        }

        @Test
        void findLatestReturnsTheMostRecentlyFramedCorrectionAcrossUsages() {
            AssetId assetId = AssetId.random();
            TrackCorrection older = confirmed(assetId, UsageId.random(), NOW.minusSeconds(30));
            TrackCorrection newer = confirmed(assetId, UsageId.random(), NOW);
            repository.save(older);
            repository.save(newer);

            Optional<TrackCorrection> latest = repository.findLatest(assetId);
            assertTrue(latest.isPresent());
            assertEquals(newer, latest.get());
        }

        @Test
        void trimUsageToMostRecentKeepsOnlyTheNewestRowsAndReportsHowManyWereDeleted() {
            AssetId assetId = AssetId.random();
            UsageId usageId = UsageId.random();
            List<Instant> frameAtInOrder = List.of(
                    NOW.minusSeconds(40), NOW.minusSeconds(30), NOW.minusSeconds(20), NOW.minusSeconds(10), NOW);
            for (Instant frameAt : frameAtInOrder) {
                repository.save(confirmed(assetId, usageId, frameAt));
            }

            int deleted = repository.trimUsageToMostRecent(usageId, 2);

            assertEquals(3, deleted);
            List<TrackCorrection> remaining = repository.findByUsage(usageId, 10);
            assertEquals(2, remaining.size());
            assertEquals(NOW.minusSeconds(10), remaining.get(0).frameAt());
            assertEquals(NOW, remaining.get(1).frameAt());
        }

        @Test
        void trimUsageToMostRecentOnlyAffectsTheNamedUsage() {
            AssetId assetId = AssetId.random();
            UsageId usageId1 = UsageId.random();
            UsageId usageId2 = UsageId.random();
            repository.save(confirmed(assetId, usageId1, NOW.minusSeconds(10)));
            repository.save(confirmed(assetId, usageId1, NOW));
            repository.save(confirmed(assetId, usageId2, NOW));

            repository.trimUsageToMostRecent(usageId1, 1);

            assertEquals(1, repository.findByUsage(usageId1, 10).size());
            assertEquals(1, repository.findByUsage(usageId2, 10).size(), "a different usage's rows are untouched");
        }

        @Test
        void deleteOlderThanRemovesCorrectionsAcrossEveryAssetFramedBeforeTheCutoffAndReportsHowMany() {
            AssetId assetId = AssetId.random();
            Instant cutoff = NOW.minusSeconds(15);
            UsageId usage1 = UsageId.random();
            UsageId usage2 = UsageId.random();
            TrackCorrection old1 = confirmed(assetId, usage1, NOW.minusSeconds(30));
            TrackCorrection old2 = confirmed(assetId, usage2, NOW.minusSeconds(20));
            TrackCorrection recent = confirmed(assetId, usage1, NOW);
            repository.save(old1);
            repository.save(old2);
            repository.save(recent);

            // Not asserting an exact `deleted` count here (unlike trimUsageToMostRecent's per-usage-scoped
            // tests above): deleteOlderThan is a genuinely table-wide delete, and this nested class's other
            // tests leave their own past-dated rows behind in this same shared Postgres instance (no
            // per-test truncation -- see TrackTrailRepositoryTests' identically-shaped
            // deleteOlderThanRemovesPointsAcrossEveryTrackCapturedBeforeTheCutoff precedent, which makes
            // the same choice). Only this test's own two usages are asserted.
            repository.deleteOlderThan(cutoff);

            assertEquals(List.of(recent), repository.findByUsage(usage1, 10));
            assertTrue(repository.findByUsage(usage2, 10).isEmpty());
        }

        /**
         * docs/plans/done/VISUAL-GEO-V2-PLAN.md §3.7/D12 — {@code track_corrections} carries no
         * {@code trg_audit_*} trigger at all, the same {@code projected_track_points} precedent (see
         * {@code aTrackTrailInsertProducesNoDbAuditLogRow} above): a flying asset writing at ~1 Hz
         * would flood a table meant for a human's intent, not machine output.
         */
        @Test
        void aTrackCorrectionInsertProducesNoDbAuditLogRow() {
            repository.save(confirmed(AssetId.random(), UsageId.random(), NOW));

            EntityManager em = entityManagerFactory.createEntityManager();
            try {
                long rowsForTable = ((Number) em.createNativeQuery(
                                "select count(*) from db_audit_log where table_name = 'track_corrections'")
                        .getSingleResult()).longValue();
                assertEquals(0L, rowsForTable, "the track_corrections table carries no audit trigger -- an "
                        + "insert must leave db_audit_log untouched, the whole point of excluding it (D12)");
            } finally {
                em.close();
            }
        }
    }

    /**
     * docs/plans/done/POSTGRES-ONLY-CONTEXT.md W3 — same {@code information_schema} shape as the V7-V12
     * schema tests, for the brand-new {@code audit_entries} table: asserts {@code details} is a
     * required {@code jsonb} column and the primary key is exactly {@code id}, proving {@code
     * V14__audit_trail.sql} applied cleanly on top of V1-V13.
     */
    @Test
    void v14MigrationCreatesTheAuditEntriesTableOnTopOfV1ThroughV13() {
        EntityManager em = entityManagerFactory.createEntityManager();
        try {
            Object[] detailsColumn = (Object[]) em.createNativeQuery(
                            "select is_nullable, data_type from information_schema.columns "
                                    + "where table_name = 'audit_entries' and column_name = 'details'")
                    .getSingleResult();
            assertEquals("NO", detailsColumn[0], "details is required");
            assertEquals("jsonb", detailsColumn[1]);

            String targetIdNullable = (String) em.createNativeQuery(
                            "select is_nullable from information_schema.columns "
                                    + "where table_name = 'audit_entries' and column_name = 'target_id'")
                    .getSingleResult();
            assertEquals("NO", targetIdNullable, "target_id is required");
        } finally {
            em.close();
        }
    }

    /**
     * docs/plans/done/POSTGRES-ONLY-CONTEXT.md W3 — same shape as the V14 schema test above, for the
     * brand-new {@code detection_events} table: asserts {@code asset_id}/{@code position_latitude}
     * stay nullable (an event may be assetless and positionless) while {@code last_seen} is
     * required, proving {@code V15__detection_events.sql} applied cleanly on top of V1-V14.
     */
    @Test
    void v15MigrationCreatesTheDetectionEventsTableOnTopOfV1ThroughV14() {
        EntityManager em = entityManagerFactory.createEntityManager();
        try {
            String assetIdNullable = (String) em.createNativeQuery(
                            "select is_nullable from information_schema.columns "
                                    + "where table_name = 'detection_events' and column_name = 'asset_id'")
                    .getSingleResult();
            assertEquals("YES", assetIdNullable, "asset_id is optional -- a stream may not belong to an asset");

            String positionLatitudeNullable = (String) em.createNativeQuery(
                            "select is_nullable from information_schema.columns "
                                    + "where table_name = 'detection_events' and column_name = 'position_latitude'")
                    .getSingleResult();
            assertEquals("YES", positionLatitudeNullable, "position is optional");

            String lastSeenNullable = (String) em.createNativeQuery(
                            "select is_nullable from information_schema.columns "
                                    + "where table_name = 'detection_events' and column_name = 'last_seen'")
                    .getSingleResult();
            assertEquals("NO", lastSeenNullable, "last_seen is required");
        } finally {
            em.close();
        }
    }

    /**
     * docs/plans/active/DRONE-ONBOARDING-PLAN.md O5 — same {@code information_schema} shape as the
     * V14/V15 schema tests above, for the brand-new {@code vehicle_profiles} table: {@code messages}
     * defaults to {@code '[]'::jsonb} so it is required, while {@code sysid} stays optional (a link
     * key may carry no sysid), proving {@code V17__vehicle_profiles.sql} applied cleanly on top of
     * V1-V16.
     */
    @Test
    void v17MigrationCreatesTheVehicleProfilesTableOnTopOfV1ThroughV16() {
        EntityManager em = entityManagerFactory.createEntityManager();
        try {
            String messagesNullable = (String) em.createNativeQuery(
                            "select is_nullable from information_schema.columns "
                                    + "where table_name = 'vehicle_profiles' and column_name = 'messages'")
                    .getSingleResult();
            assertEquals("NO", messagesNullable, "messages is required (defaults to '[]')");

            String sysidNullable = (String) em.createNativeQuery(
                            "select is_nullable from information_schema.columns "
                                    + "where table_name = 'vehicle_profiles' and column_name = 'sysid'")
                    .getSingleResult();
            assertEquals("YES", sysidNullable, "sysid is optional -- a link key may carry no sysid");
        } finally {
            em.close();
        }
    }

    /**
     * docs/plans/active/DRONE-ONBOARDING-PLAN.md O5/D6 — proves {@code V18__feature_requirements.sql}
     * actually seeded one row per frozen v1 feature key (SS8.1) for {@code firmware = 'ardupilot'},
     * on top of V1-V17; {@link FeatureRequirementRepositoryTests} exercises the same data through the
     * port, this asserts the raw row count landed at all.
     *
     * <p>V27 (FLEET-RADIO-PLAN.md R6) retired the single placeholder {@code rc-relay} row V18 seeded
     * and replaced it with two independent, value-aware rows under that same key — see {@link
     * #v27MigrationRetiresThePlaceholderRcRelayRowAndSeedsTwoValueAwareRowsUnderTheSameKey} — so the
     * raw row count is now twelve, one more than the eleven frozen feature keys, not equal to them.
     */
    @Test
    void v18MigrationSeedsTwelveArdupilotFeatureRequirementRows() {
        EntityManager em = entityManagerFactory.createEntityManager();
        try {
            long count = ((Number) em.createNativeQuery(
                            "select count(*) from feature_requirements where firmware = 'ardupilot'")
                    .getSingleResult()).longValue();
            assertEquals(12, count,
                    "eleven frozen v1 feature keys (SS8.1), plus one extra row from V27's two-rows-under-rc-relay");
        } finally {
            em.close();
        }
    }

    /**
     * docs/plans/active/FLEET-RADIO-PLAN.md R6 — V27 must apply cleanly on top of V1-V26, retire the
     * V18 placeholder {@code rc-relay} row, and seed the two new value-aware rows this wave's
     * readiness checks depend on: a GCS-sysid value requirement and an RC_OPTIONS forbidden-bits
     * requirement, both reachable through {@link FeatureRequirementRepositoryPort} exactly like every
     * other seeded row.
     */
    @Test
    void v27MigrationRetiresThePlaceholderRcRelayRowAndSeedsTwoValueAwareRowsUnderTheSameKey() {
        FeatureRequirementRepositoryPort repository = new JpaFeatureRequirementRepository(entityManagerFactory);

        List<FeatureRequirement> rcRelayRows = repository.findByFirmware("ardupilot").stream()
                .filter(r -> r.featureKey().equals("rc-relay"))
                .toList();

        assertEquals(2, rcRelayRows.size(), "two independent rows under the one frozen rc-relay key");

        FeatureRequirement gcsSysid = rcRelayRows.stream()
                .filter(r -> "SYSID_MYGCS".equals(r.requiredParameterName()))
                .findFirst().orElseThrow();
        assertEquals(255.0, gcsSysid.requiredParameterValue());
        assertNull(gcsSysid.forbiddenParameterBits());

        FeatureRequirement rcOptions = rcRelayRows.stream()
                .filter(r -> "RC_OPTIONS".equals(r.requiredParameterName()))
                .findFirst().orElseThrow();
        assertNull(rcOptions.requiredParameterValue());
        assertEquals(2L, rcOptions.forbiddenParameterBits());
    }

    /**
     * docs/plans/active/DRONE-ONBOARDING-PLAN.md O5 — proves {@code V19__asset_usage_phase.sql}
     * applied cleanly on top of V1-V18: {@code phase}/{@code first_armed_at}/{@code last_disarmed_at}
     * are nullable, additive columns with no backfill (every pre-existing row predates the phase
     * concept). Schema-only on purpose — see that migration's own header for why
     * {@code AssetUsageEntity} is not wired to these columns by this wave.
     */
    @Test
    void v19MigrationAddsNullablePhaseColumnsOnTopOfV1ThroughV18() {
        EntityManager em = entityManagerFactory.createEntityManager();
        try {
            String phaseNullable = (String) em.createNativeQuery(
                            "select is_nullable from information_schema.columns "
                                    + "where table_name = 'asset_usages' and column_name = 'phase'")
                    .getSingleResult();
            assertEquals("YES", phaseNullable, "phase is nullable -- no backfill for pre-existing rows");

            String firstArmedNullable = (String) em.createNativeQuery(
                            "select is_nullable from information_schema.columns "
                                    + "where table_name = 'asset_usages' and column_name = 'first_armed_at'")
                    .getSingleResult();
            assertEquals("YES", firstArmedNullable);
        } finally {
            em.close();
        }
    }

    /**
     * docs/plans/active/DRONE-ONBOARDING-PLAN.md O11 -- proves {@code V20__vehicle_profile_usage_link.sql}
     * applied cleanly on top of V1-V19: {@code usage_id}/{@code phase} on {@code vehicle_profiles} are
     * nullable, additive columns (every pre-existing row -- readiness's own ad hoc probes -- predates
     * the passport concept and keeps both columns NULL). Schema-only on purpose, same "prove the
     * migration, not the entity" split as {@link #v19MigrationAddsNullablePhaseColumnsOnTopOfV1ThroughV18};
     * the entity/repository round trip is covered by {@link VehicleProfileRepositoryTests}.
     */
    @Test
    void v20MigrationAddsUsageIdAndPhaseColumnsOnTopOfV1ThroughV19() {
        EntityManager em = entityManagerFactory.createEntityManager();
        try {
            String usageIdNullable = (String) em.createNativeQuery(
                            "select is_nullable from information_schema.columns "
                                    + "where table_name = 'vehicle_profiles' and column_name = 'usage_id'")
                    .getSingleResult();
            assertEquals("YES", usageIdNullable, "usage_id is nullable -- no backfill for pre-existing rows");

            String phaseNullable = (String) em.createNativeQuery(
                            "select is_nullable from information_schema.columns "
                                    + "where table_name = 'vehicle_profiles' and column_name = 'phase'")
                    .getSingleResult();
            assertEquals("YES", phaseNullable, "phase is nullable -- no backfill for pre-existing rows");
        } finally {
            em.close();
        }
    }

    /**
     * docs/plans/done/SCALE-100-PLAN.md S3 -- proves the pool is real, not merely configured. Two
     * independent, mutually-reinforcing proofs: the {@link ConnectionProvider} Hibernate actually
     * runs against is {@link ClosingDatasourceConnectionProvider} (not the built-in unpooled
     * provider that used to serve every request here), and a deliberately tiny pool genuinely caps
     * concurrent physical connections -- if the old unpooled provider were still wired in, the
     * over-limit acquisition below would succeed immediately instead of timing out.
     */
    @Nested
    class ConnectionPoolTests {

        @Test
        void hibernateUsesTheSharedClosingProviderNotTheBuiltInUnpooledOne() {
            ConnectionProvider provider = entityManagerFactory.unwrap(SessionFactoryImplementor.class)
                    .getServiceRegistry().getService(ConnectionProvider.class);

            assertTrue(provider instanceof ClosingDatasourceConnectionProvider,
                    "must use the shared, closeable Hikari-backed provider, not Hibernate's built-in "
                            + "DriverManagerConnectionProvider (the one that logs \"not for production use\")");
        }

        @Test
        void poolCapsConcurrentPhysicalConnectionsAtItsConfiguredMaximum() {
            PersistencePoolSettings tinyPool = new PersistencePoolSettings(2, 0, 500, 0);
            EntityManagerFactory smallPoolContext = PersistenceUnit.start(POSTGRES.getJdbcUrl(),
                    POSTGRES.getUsername(), POSTGRES.getPassword(), false, tinyPool);
            List<EntityManager> holdingTheWholePool = new ArrayList<>();
            try {
                for (int i = 0; i < tinyPool.maximumPoolSize(); i++) {
                    EntityManager em = smallPoolContext.createEntityManager();
                    em.getTransaction().begin();
                    em.createNativeQuery("select 1").getSingleResult(); // forces the physical borrow
                    holdingTheWholePool.add(em);
                }

                EntityManager overLimit = smallPoolContext.createEntityManager();
                try {
                    long startNanos = System.nanoTime();
                    // begin() itself acquires the physical connection for a resource-local
                    // transaction (confirmed by running this test: the exception below actually
                    // comes from here, not from the first query), so the pool is exhausted before
                    // any SQL is even sent.
                    assertThrows(HibernateException.class, () -> overLimit.getTransaction().begin(),
                            "a third connection must be refused once the pool of "
                                    + tinyPool.maximumPoolSize() + " is exhausted");
                    long elapsedMillis = Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
                    assertTrue(elapsedMillis < 5_000,
                            "must fail via the pool's own connectionTimeout (" + tinyPool.connectionTimeoutMillis()
                                    + "ms), not hang indefinitely");
                } finally {
                    overLimit.close();
                }
            } finally {
                for (EntityManager em : holdingTheWholePool) {
                    if (em.getTransaction().isActive()) {
                        em.getTransaction().rollback();
                    }
                    em.close();
                }
                smallPoolContext.close();
            }
        }
    }

    /**
     * The database-level change audit ({@code V21__db_audit_log.sql}) — proves the trigger fires
     * end to end through a real {@code Jpa*Repository} (not a hand-crafted native-SQL write, since
     * the whole point is that the trigger fires no matter <em>how</em> a row changes) and that the
     * coverage the migration's own header claims actually holds against the live schema.
     */
    @Nested
    class DbAuditLogRepositoryTests {

        private final JpaDbAuditLogRepository auditLog = new JpaDbAuditLogRepository(entityManagerFactory);
        private final GeofenceRepositoryPort geofences = new JpaGeofenceRepository(entityManagerFactory);

        private List<GeoPosition> triangle() {
            return List.of(
                    new GeoPosition(10.0, 20.0, null),
                    new GeoPosition(10.0, 21.0, null),
                    new GeoPosition(11.0, 20.5, null));
        }

        @Test
        void insertUpdateAndDeleteThroughAnExistingRepositoryEachLeaveTheirOwnAuditRowNewestFirst() {
            ZoneId zoneId = ZoneId.random();
            String rowId = zoneId.value().toString();

            geofences.save(new GeofenceZone(zoneId, "Audit Zone", ZoneKind.KEEP_OUT, triangle(), 50.0, true));
            geofences.save(new GeofenceZone(zoneId, "Audit Zone Renamed", ZoneKind.KEEP_OUT, triangle(), 50.0, false));
            geofences.deleteById(zoneId);

            List<DbAuditLogEntity> rows = auditLog.findRecentForRow("geofence_zones", rowId, 10);
            assertEquals(3, rows.size(), "one audit row per INSERT/UPDATE/DELETE");

            // newest first: DELETE, UPDATE, INSERT
            DbAuditLogEntity deleteRow = rows.get(0);
            DbAuditLogEntity updateRow = rows.get(1);
            DbAuditLogEntity insertRow = rows.get(2);

            assertEquals(DbAuditOperation.DELETE, deleteRow.operation());
            assertEquals(DbAuditOperation.UPDATE, updateRow.operation());
            assertEquals(DbAuditOperation.INSERT, insertRow.operation());

            assertNull(insertRow.oldRow(), "an INSERT has no prior row image");
            assertNotNull(insertRow.newRow());
            assertEquals("Audit Zone", insertRow.newRow().get("name"));
            assertNull(insertRow.changedColumns(), "changed_columns is only meaningful for an UPDATE");

            assertNotNull(updateRow.oldRow());
            assertNotNull(updateRow.newRow());
            assertEquals("Audit Zone Renamed", updateRow.newRow().get("name"));
            assertNotNull(updateRow.changedColumns(), "an UPDATE must name what changed");
            assertTrue(updateRow.changedColumns().contains("name"), "name was renamed");
            assertTrue(updateRow.changedColumns().contains("enabled"), "enabled flipped true -> false");
            assertFalse(updateRow.changedColumns().contains("id"),
                    "the unchanged primary key must not be reported as a changed column");
            assertEquals(POSTGRES.getUsername(), updateRow.dbUser(),
                    "db_user is session_user, not an application-supplied value");

            assertNotNull(deleteRow.oldRow());
            assertNull(deleteRow.newRow(), "a DELETE has no new row image");
        }

        @Test
        void findRecentSpansEveryAuditedTableNewestFirstBoundedByLimit() {
            // "Own rows within a large fetch" technique (same as AuditTrailRepositoryTests/
            // DetectionEventRepositoryTests above): the container accumulates rows across every
            // test in this class, so this only asserts about rows this test itself just wrote.
            ZoneId first = ZoneId.random();
            ZoneId second = ZoneId.random();
            geofences.save(new GeofenceZone(first, "Recent A", ZoneKind.KEEP_OUT, triangle(), null, true));
            geofences.save(new GeofenceZone(second, "Recent B", ZoneKind.KEEP_IN, triangle(), null, true));

            List<DbAuditLogEntity> recent = auditLog.findRecent(100_000);
            List<String> recentRowIds = recent.stream().map(DbAuditLogEntity::rowId).toList();

            int firstIndex = recentRowIds.indexOf(second.value().toString());
            int secondIndex = recentRowIds.indexOf(first.value().toString());
            assertTrue(firstIndex >= 0 && secondIndex >= 0, "both freshly-inserted rows must appear");
            assertTrue(firstIndex < secondIndex, "the more recently saved zone must sort first (newest-first)");
        }

        /**
         * {@code to_jsonb(NEW)} copies every column verbatim, so auditing {@code users} would
         * otherwise write {@code password_hash} into this table — and on a password change, both the
         * old and the new hash. That is credential material landing in a table with longer retention
         * and a wider read audience than the row it came from, which is why the trigger redacts it.
         *
         * <p>The redaction must not cost information: {@code changed_columns} is computed before it,
         * so "the password changed" is still reported while neither hash is stored.
         */
        @Test
        void aPasswordHashIsNeverCopiedIntoTheAuditLogThoughItsChangeIsStillReported() {
            UserRepositoryPort users = new JpaUserRepository(entityManagerFactory);
            UserId userId = UserId.random();
            String rowId = userId.value().toString();

            users.save(new User(userId, "audit.secret", "Audit Secret", "secret@vision.local",
                    "$2a$10$ORIGINALHASHVALUE", true, List.of()));
            users.save(new User(userId, "audit.secret", "Audit Secret", "secret@vision.local",
                    "$2a$10$ROTATEDHASHVALUE", true, List.of()));

            List<DbAuditLogEntity> rows = auditLog.findRecentForRow("users", rowId, 10);
            assertEquals(2, rows.size(), "one audit row for the INSERT, one for the UPDATE");

            DbAuditLogEntity updateRow = rows.get(0);
            DbAuditLogEntity insertRow = rows.get(1);

            assertEquals("[redacted]", insertRow.newRow().get("password_hash"),
                    "the hash must be replaced, and the key kept so the row's shape stays honest");
            assertEquals("[redacted]", updateRow.oldRow().get("password_hash"), "including the superseded hash");
            assertEquals("[redacted]", updateRow.newRow().get("password_hash"));
            assertTrue(updateRow.changedColumns().contains("password_hash"),
                    "redaction must not hide that the password changed — changed_columns is computed first");
            assertEquals("audit.secret", updateRow.newRow().get("username"),
                    "only the named sensitive columns are redacted; the rest of the row is intact");
        }

        /**
         * docs/plans/done/FIXED-CAMERA-GEO-PLAN.md decision D4/§7 — {@code camera_poses} is on the
         * audited side of V22, opposite {@code projected_track_points} below. Same
         * insert-then-update-then-inspect shape as {@link
         * #insertUpdateAndDeleteThroughAnExistingRepositoryEachLeaveTheirOwnAuditRowNewestFirst}
         * above, but only exercising the update path since that is the exit criterion this wave was
         * built against ("a pose update produces a db_audit_log row naming the changed column").
         */
        @Test
        void aCameraPoseUpdateProducesADbAuditLogRowNamingTheChangedColumn() {
            CameraPoseRepositoryPort poses = new JpaCameraPoseRepository(entityManagerFactory);
            AssetId assetId = AssetId.random();
            UserId actor = UserId.random();
            String rowId = assetId.value().toString();

            poses.save(new CameraPose(assetId, new GeoPosition(50.45, 30.52, null), 10.0, 0.0, 5.0, 60.0,
                    null, CameraPoseSource.MANUAL, null, NOW, actor));
            poses.save(new CameraPose(assetId, new GeoPosition(50.45, 30.52, null), 10.0, 200.0, 5.0, 60.0,
                    null, CameraPoseSource.MANUAL, null, NOW, actor));

            List<DbAuditLogEntity> rows = auditLog.findRecentForRow("camera_poses", rowId, 10);
            assertEquals(2, rows.size(), "one audit row for the INSERT, one for the UPDATE");

            DbAuditLogEntity updateRow = rows.get(0);
            assertEquals(DbAuditOperation.UPDATE, updateRow.operation());
            assertNotNull(updateRow.changedColumns());
            assertTrue(updateRow.changedColumns().contains("yaw_degrees"), "the changed column must be named");
        }

        /**
         * docs/plans/done/FIXED-CAMERA-GEO-PLAN.md decision D3/§7 — {@code projected_track_points}
         * carries no {@code trg_audit_*} trigger at all (V22's own header explains why: a tracked
         * car at ~1 Hz would write thousands of rows per car-hour into a table meant for a human's
         * intent, not machine output). Asserted directly against {@code db_audit_log} itself rather
         * than through {@link JpaDbAuditLogRepository#findRecentForRow}, since a row_id lookup would
         * only prove "no row under this key", not "no row at all" for this table.
         */
        @Test
        void aTrackTrailInsertProducesNoDbAuditLogRow() {
            TrackTrailRepositoryPort trail = new JpaTrackTrailRepository(entityManagerFactory);
            AssetId assetId = AssetId.random();

            trail.save(new TrackPoint(assetId, 1L, "car", LayerId.random(), new GeoPosition(50.45, 30.52, null),
                    6.0, NOW));

            EntityManager em = entityManagerFactory.createEntityManager();
            try {
                long rowsForTable = ((Number) em.createNativeQuery(
                                "select count(*) from db_audit_log where table_name = 'projected_track_points'")
                        .getSingleResult()).longValue();
                assertEquals(0L, rowsForTable, "the trail table carries no audit trigger -- an insert "
                        + "must leave db_audit_log untouched, the whole point of excluding it (D3)");
            } finally {
                em.close();
            }
        }
    }

    /**
     * Reads the live schema and proves {@code V21__db_audit_log.sql}'s own "Included"/"Excluded"
     * lists actually match reality — the coverage test the brief for this feature calls for, so a
     * future migration that adds a table fails this test until someone consciously classifies it
     * as audited or excluded, instead of silently falling through the cracks.
     */
    @Nested
    class DbAuditLogCoverageTests {

        @Test
        void everyPublicBaseTableIsEitherAuditedOrExplicitlyExcluded() {
            EntityManager em = entityManagerFactory.createEntityManager();
            try {
                @SuppressWarnings("unchecked")
                List<String> tableNames = em.createNativeQuery(
                                "select table_name from information_schema.tables "
                                        + "where table_schema = 'public' and table_type = 'BASE TABLE'")
                        .getResultList();
                Set<String> liveTables = new HashSet<>(tableNames);

                Set<String> classified = new HashSet<>(AUDITED_TABLES);
                classified.addAll(EXCLUDED_TABLES);

                Set<String> unclassified = new HashSet<>(liveTables);
                unclassified.removeAll(classified);
                assertTrue(unclassified.isEmpty(),
                        "every table in the live schema must be classified as audited or explicitly "
                                + "excluded -- unclassified: " + unclassified);

                Set<String> staleReferences = new HashSet<>(classified);
                staleReferences.removeAll(liveTables);
                assertTrue(staleReferences.isEmpty(),
                        "AUDITED_TABLES/EXCLUDED_TABLES reference tables that no longer exist: "
                                + staleReferences);
            } finally {
                em.close();
            }
        }

        @Test
        void everyAuditedTableCarriesExactlyTheAuditTriggerAndNoExcludedTableDoes() {
            EntityManager em = entityManagerFactory.createEntityManager();
            try {
                @SuppressWarnings("unchecked")
                List<String> triggeredTables = em.createNativeQuery(
                                "select c.relname from pg_trigger t join pg_class c on c.oid = t.tgrelid "
                                        + "where not t.tgisinternal")
                        .getResultList();

                assertEquals(AUDITED_TABLES, new HashSet<>(triggeredTables),
                        "the live set of triggered tables must equal AUDITED_TABLES exactly -- a "
                                + "missing trigger or a stray one on an excluded table both fail here");
            } finally {
                em.close();
            }
        }
    }

    /**
     * Proves {@code V21__db_audit_log.sql} applied cleanly on top of V1-V20: {@code
     * db_audit_log} exists with the expected required/nullable columns. {@link
     * DbAuditLogCoverageTests} separately proves the trigger attachment itself; this only proves
     * the table shape, same "prove the migration, not the entity" split as the V19/V20 schema
     * tests above.
     */
    @Test
    void v21MigrationCreatesTheDbAuditLogTableOnTopOfV1ThroughV20() {
        EntityManager em = entityManagerFactory.createEntityManager();
        try {
            String tableNameNullable = (String) em.createNativeQuery(
                            "select is_nullable from information_schema.columns "
                                    + "where table_name = 'db_audit_log' and column_name = 'table_name'")
                    .getSingleResult();
            assertEquals("NO", tableNameNullable);

            String rowIdNullable = (String) em.createNativeQuery(
                            "select is_nullable from information_schema.columns "
                                    + "where table_name = 'db_audit_log' and column_name = 'row_id'")
                    .getSingleResult();
            assertEquals("NO", rowIdNullable);

            String oldRowNullable = (String) em.createNativeQuery(
                            "select is_nullable from information_schema.columns "
                                    + "where table_name = 'db_audit_log' and column_name = 'old_row'")
                    .getSingleResult();
            assertEquals("YES", oldRowNullable, "old_row is null for an INSERT");

            String newRowNullable = (String) em.createNativeQuery(
                            "select is_nullable from information_schema.columns "
                                    + "where table_name = 'db_audit_log' and column_name = 'new_row'")
                    .getSingleResult();
            assertEquals("YES", newRowNullable, "new_row is null for a DELETE");
        } finally {
            em.close();
        }
    }

    /**
     * docs/plans/done/FIXED-CAMERA-GEO-PLAN.md decision D4/§7 — proves {@code
     * V22__fixed_camera_geo.sql} applied cleanly on top of V1-V21: {@code camera_poses.asset_id} is
     * the primary key (not nullable), {@code camera_poses.target_layer_id} stays nullable (a pose
     * may target the default COP layer), and {@code projected_track_points.id} is the
     * database-generated identity column, not something the entity supplies. {@link
     * DbAuditLogCoverageTests} separately proves the trigger attachment itself; this only proves
     * the table shape, same "prove the migration, not the entity" split as the V19-V21 schema
     * tests above.
     */
    @Test
    void v22MigrationCreatesTheFixedCameraGeoTablesOnTopOfV1ThroughV21() {
        EntityManager em = entityManagerFactory.createEntityManager();
        try {
            String assetIdNullable = (String) em.createNativeQuery(
                            "select is_nullable from information_schema.columns "
                                    + "where table_name = 'camera_poses' and column_name = 'asset_id'")
                    .getSingleResult();
            assertEquals("NO", assetIdNullable, "asset_id is the primary key");

            String targetLayerNullable = (String) em.createNativeQuery(
                            "select is_nullable from information_schema.columns "
                                    + "where table_name = 'camera_poses' and column_name = 'target_layer_id'")
                    .getSingleResult();
            assertEquals("YES", targetLayerNullable, "null targetLayerId means \"use the default COP layer\"");

            Object[] idColumn = (Object[]) em.createNativeQuery(
                            "select is_nullable, is_identity from information_schema.columns "
                                    + "where table_name = 'projected_track_points' and column_name = 'id'")
                    .getSingleResult();
            assertEquals("NO", idColumn[0]);
            assertEquals("YES", idColumn[1], "id is database-generated, never supplied by the entity");
        } finally {
            em.close();
        }
    }
}
