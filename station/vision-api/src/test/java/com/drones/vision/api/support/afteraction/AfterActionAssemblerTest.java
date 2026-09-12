package com.drones.vision.api.support.afteraction;

import com.drones.vision.events.application.ReplayService;
import com.drones.vision.events.application.UsageRecording;
import com.drones.vision.events.application.UsageTimeline;
import com.drones.vision.flight.application.VehicleProfileService;
import com.drones.vision.flight.domain.model.FlightPassport;
import com.drones.vision.flight.domain.model.ParameterDrift;
import com.drones.vision.flight.domain.model.VehicleProfile;
import com.drones.vision.identity.domain.model.Role;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.BoundingBox;
import com.drones.vision.kernel.CategoryId;
import com.drones.vision.kernel.DeviceId;
import com.drones.vision.kernel.GeoPosition;
import com.drones.vision.kernel.GroupId;
import com.drones.vision.kernel.LifecycleState;
import com.drones.vision.kernel.Ownership;
import com.drones.vision.kernel.StreamDescriptor;
import com.drones.vision.kernel.StreamId;
import com.drones.vision.kernel.Telemetry;
import com.drones.vision.kernel.UsageId;
import com.drones.vision.kernel.UsageOrigin;
import com.drones.vision.warehouse.domain.model.UsagePhase;
import com.drones.vision.kernel.UserId;
import com.drones.vision.map.application.MapAccessPolicy.Viewer;
import com.drones.vision.map.application.mark.MarkService;
import com.drones.vision.map.domain.model.Affiliation;
import com.drones.vision.map.domain.model.LayerId;
import com.drones.vision.map.domain.model.Mark;
import com.drones.vision.map.domain.model.MarkId;
import com.drones.vision.map.domain.model.MarkKind;
import com.drones.vision.map.domain.model.MarkSource;
import com.drones.vision.map.domain.model.MarkStatus;
import com.drones.vision.map.domain.model.Verification;
import com.drones.vision.perception.domain.model.Detection;
import com.drones.vision.perception.domain.model.DetectionResult;
import com.drones.vision.perception.domain.model.ModelRef;
import com.drones.vision.platform.AccessDeniedException;
import com.drones.vision.platform.AuditAction;
import com.drones.vision.platform.AuditEntry;
import com.drones.vision.platform.AuditId;
import com.drones.vision.platform.AuditTargetType;
import com.drones.vision.platform.AuditTrailPort;
import com.drones.vision.platform.Authority;
import com.drones.vision.platform.Capability;
import com.drones.vision.platform.VisibilityScope;
import com.drones.vision.warehouse.application.asset.AssetDeletion;
import com.drones.vision.warehouse.application.asset.AssetDetails;
import com.drones.vision.warehouse.application.asset.AssetEdit;
import com.drones.vision.warehouse.application.asset.AssetService;
import com.drones.vision.warehouse.application.asset.AssetSpec;
import com.drones.vision.warehouse.application.asset.AssetStatus;
import com.drones.vision.warehouse.application.asset.AssetSummary;
import com.drones.vision.warehouse.application.asset.DuplicateDeviceMatch;
import com.drones.vision.warehouse.domain.model.Asset;
import com.drones.vision.warehouse.domain.model.Custody;
import com.drones.vision.warehouse.domain.model.Identity;
import com.drones.vision.warehouse.domain.model.InventoryState;
import com.drones.vision.warehouse.domain.model.AssetUsage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plain-JUnit coverage of {@link AfterActionAssembler} (docs/plans/done/AFTER-ACTION-PLAN.md,
 * Wave W1) against hand-written fakes of its four collaborators — no Spring, no Mockito, matching
 * the class's own "framework-free, constructor-injected collaborators only" design (this module's
 * java-clean-code skill, and {@link AfterActionAssembler}'s own javadoc).
 *
 * <p>Every {@code resolve*}/helper method is package-private specifically so it can be exercised
 * in isolation here, including the {@code audit} part's {@code FORBIDDEN} branch, which the
 * assembler's own javadoc documents as structurally unreachable end-to-end via {@link
 * AfterActionAssembler#assemble} under today's role model (see {@code resolveAuditForbidden*}
 * below, and this wave's report).
 */
class AfterActionAssemblerTest {

    private static final Instant STARTED_AT = Instant.parse("2026-08-18T09:00:00Z");
    private static final Instant ENDED_AT = Instant.parse("2026-08-18T09:30:00Z");

    private final AssetId assetId = AssetId.random();
    private final UsageId usageId = UsageId.random();
    private final DeviceId deviceId = DeviceId.random();
    private final StreamId streamId = StreamId.random();
    private final UserId ownerId = UserId.random();
    private final GroupId groupId = GroupId.random();
    private final Ownership ownership = new Ownership(ownerId, groupId);

    private FakeAssetService assetService;
    private FakeReplayService replayService;
    private FakeMarkService markService;
    private FakeVehicleProfileService vehicleProfileService;
    private FakeAuditTrailPort auditTrailPort;
    private AfterActionAssembler assembler;

    @BeforeEach
    void setUp() {
        assetService = new FakeAssetService();
        replayService = new FakeReplayService();
        markService = new FakeMarkService();
        vehicleProfileService = new FakeVehicleProfileService();
        auditTrailPort = new FakeAuditTrailPort();

        assetService.detailsResult = assetDetails(ownership);
        replayService.timeline = timelineWith(List.of(), List.of());
        vehicleProfileService.passportResult = new FlightPassport(usageId, assetId, null, null);
        auditTrailPort.entries = List.of();

        AfterActionSources sources = new AfterActionSources(markService, vehicleProfileService, auditTrailPort);
        assembler = new AfterActionAssembler(assetService, replayService, sources,
                new AfterActionProperties(3, 200));
    }

    // ---- assemble() happy path ----

    @Test
    void assembleReturnsAllSixPartsInFixedOrderForAFullyPopulatedFlight() {
        replayService.timeline = timelineWith(
                List.of(telemetryAt(STARTED_AT.plusSeconds(60))),
                List.of(detectionResultAt(STARTED_AT.plusSeconds(90), "person")));
        replayService.recording = Optional.of(new UsageRecording(java.net.URI.create("rtsp://mediamtx/clip"),
                STARTED_AT, 1800));
        markService.marks = List.of(markAt(STARTED_AT.plusSeconds(120)));
        vehicleProfileService.passportResult =
                new FlightPassport(usageId, assetId, minimalProfile(STARTED_AT), minimalProfile(ENDED_AT));
        auditTrailPort.entries = List.of(auditEntry());

        AfterActionPackage pkg = assembler.assemble(assetId, usageId, Authority.full(), viewer(Role.ADMIN),
                "referee");

        assertEquals(List.of(AfterActionPartKind.TELEMETRY, AfterActionPartKind.DETECTIONS,
                        AfterActionPartKind.MARKS, AfterActionPartKind.RECORDING, AfterActionPartKind.PASSPORT,
                        AfterActionPartKind.AUDIT),
                pkg.parts().stream().map(AfterActionPart::part).toList());
        assertTrue(pkg.parts().stream().allMatch(p -> p.state() == AfterActionPartState.PRESENT));
        assertTrue(pkg.complete());
        assertEquals("referee", pkg.scopedTo());
        assertEquals(assetId, pkg.assetId());
        assertEquals(usageId, pkg.usageId());
        assertFalse(pkg.open());
    }

    @Test
    void assembleReportsAStillOpenUsageWithoutThrowing() {
        AssetUsage openUsage = new AssetUsage(usageId, assetId, STARTED_AT, null, null, null, 0, streamId,
                UsagePhase.PREFLIGHT, UsageOrigin.STREAM, null);
        replayService.timeline = new UsageTimeline(openUsage, STARTED_AT, Instant.now(), List.of(), List.of());

        AfterActionPackage pkg = assembler.assemble(assetId, usageId, Authority.full(), viewer(Role.ADMIN),
                "referee");

        assertNull(pkg.endedAt());
        assertTrue(pkg.open());
    }

    @Test
    void assembleManifestForAFlightWithNoRecordingAndNoPassportReportsBothAbsent() {
        replayService.timeline = timelineWith(List.of(telemetryAt(STARTED_AT.plusSeconds(1))), List.of());
        replayService.recording = Optional.empty();
        vehicleProfileService.passportResult = new FlightPassport(usageId, assetId, null, null);

        AfterActionPackage pkg = assembler.assemble(assetId, usageId, Authority.full(), viewer(Role.ADMIN),
                "referee");

        AfterActionPart recording = partOf(pkg, AfterActionPartKind.RECORDING);
        assertEquals(AfterActionPartState.ABSENT, recording.state());
        assertEquals(0, recording.count());
        assertEquals("no recording is configured for this stream", recording.note());

        AfterActionPart passport = partOf(pkg, AfterActionPartKind.PASSPORT);
        assertEquals(AfterActionPartState.ABSENT, passport.state());
        assertEquals(0, passport.count());
        assertEquals("no PREFLIGHT or POSTFLIGHT vehicle-profile snapshot was captured for this flight",
                passport.note());

        assertFalse(pkg.complete());
    }

    @Test
    void assembleThrowsNoSuchElementWhenAssetIsUnknownOrNotVisible() {
        assetService.detailsError = new NoSuchElementException("No asset with id " + assetId.value());

        assertThrows(NoSuchElementException.class, () -> assembler.assemble(assetId, usageId,
                Authority.full(), viewer(Role.ADMIN), "referee"));
    }

    @Test
    void assembleThrowsNoSuchElementWhenUsageDoesNotBelongToAsset() {
        AssetId otherAssetId = AssetId.random();
        AssetUsage foreignUsage = new AssetUsage(usageId, otherAssetId, STARTED_AT, ENDED_AT, null, null, 0, null,
                UsagePhase.PREFLIGHT, UsageOrigin.STREAM, null);
        replayService.timeline = new UsageTimeline(foreignUsage, STARTED_AT, ENDED_AT, List.of(), List.of());

        assertThrows(NoSuchElementException.class, () -> assembler.assemble(assetId, usageId,
                Authority.full(), viewer(Role.ADMIN), "referee"));
    }

    @Test
    void assembleThrowsAccessDeniedWhenTheViewerMaySeeButNotExportTheAsset() {
        // ASSIGNED_ASSETS (PILOT) sees the asset (it is in assignedAssets) but canManage() is
        // always false for that scope kind -- "seeing it is not the same as administering it",
        // regardless of the capabilities held (full capabilities here, so it is the scope, not a
        // missing capability, that fails the mayManageFleet() gate).
        Authority pilotAuthority = new Authority(VisibilityScope.assignedAssets(Set.of(assetId)),
                EnumSet.allOf(Capability.class));

        assertThrows(AccessDeniedException.class, () -> assembler.assemble(assetId, usageId, pilotAuthority,
                viewer(Role.PILOT), "pilot"));
        assertFalse(replayService.timelineCalled, "must not resolve the timeline once the export gate fails");
    }

    // ---- resolveTelemetry (D7) ----

    @Test
    void resolveTelemetryReturnsAbsentForNoSamples() {
        AfterActionPart part = assembler.resolveTelemetry(List.of());
        assertEquals(AfterActionPartState.ABSENT, part.state());
        assertEquals(0, part.count());
    }

    @Test
    void resolveTelemetryReturnsPresentWhenBelowTheCeiling() {
        List<Telemetry> telemetry = List.of(telemetryAt(STARTED_AT), telemetryAt(STARTED_AT.plusSeconds(1)));
        AfterActionPart part = assembler.resolveTelemetry(telemetry);
        assertEquals(AfterActionPartState.PRESENT, part.state());
        assertEquals(2, part.count());
        assertNull(part.note());
    }

    @Test
    void resolveTelemetryReturnsTruncatedWhenCountExactlyMatchesTheCeiling() {
        // AfterActionProperties(3, ...) in setUp() -- three samples means DefaultReplayService#thin
        // actually thinned a larger source series down to exactly the requested ceiling.
        List<Telemetry> telemetry = List.of(telemetryAt(STARTED_AT), telemetryAt(STARTED_AT.plusSeconds(1)),
                telemetryAt(STARTED_AT.plusSeconds(2)));
        AfterActionPart part = assembler.resolveTelemetry(telemetry);
        assertEquals(AfterActionPartState.TRUNCATED, part.state());
        assertEquals(3, part.count());
        assertEquals("thinned to the 3-point ceiling; the source series is larger", part.note());
    }

    // ---- resolveDetections ----

    @Test
    void resolveDetectionsReturnsAbsentForNoDetections() {
        AfterActionPart part = assembler.resolveDetections(List.of());
        assertEquals(AfterActionPartState.ABSENT, part.state());
    }

    @Test
    void resolveDetectionsReturnsPresentWithACountForNonEmptyDetections() {
        DetectionRow row = new DetectionRow(STARTED_AT, "person", 0.9, 0.1, 0.1, 0.2, 0.2, null);
        AfterActionPart part = assembler.resolveDetections(List.of(row));
        assertEquals(AfterActionPartState.PRESENT, part.state());
        assertEquals(1, part.count());
        assertNull(part.note());
    }

    @Test
    void resolveDetectionsReturnsTruncatedWhenCountExactlyMatchesTheCeiling() {
        // The same ceiling thins both series -- DefaultReplayService#timeline passes telemetry and
        // detections through one thin(..., effectiveMaxPoints) call. Reporting PRESENT here would
        // silently omit evidence, which is the failure this part-state machinery exists to prevent.
        DetectionRow row = new DetectionRow(STARTED_AT, "person", 0.9, 0.1, 0.1, 0.2, 0.2, null);
        AfterActionPart part = assembler.resolveDetections(List.of(row, row, row));
        assertEquals(AfterActionPartState.TRUNCATED, part.state());
        assertEquals(3, part.count());
        assertEquals("thinned to the 3-point ceiling; the source series is larger", part.note());
    }

    // ---- resolveMarks (D5) ----

    @Test
    void resolveMarksReturnsAbsentForNoMarksInWindow() {
        AfterActionPart part = assembler.resolveMarks(List.of());
        assertEquals(AfterActionPartState.ABSENT, part.state());
        assertEquals("no marks were created inside this flight's time window", part.note());
    }

    @Test
    void resolveMarksAlwaysCarriesTheNotBoundToAFlightNoteEvenWhenPresent() {
        AfterActionPart part = assembler.resolveMarks(List.of(markAt(STARTED_AT.plusSeconds(10))));
        assertEquals(AfterActionPartState.PRESENT, part.state());
        assertEquals(1, part.count());
        assertEquals("marks created inside the flight window and visible to you; a mark is not bound to a flight",
                part.note());
    }

    // ---- resolveRecording ----

    @Test
    void resolveRecordingReturnsAbsentWhenEmpty() {
        AfterActionPart part = assembler.resolveRecording(Optional.empty());
        assertEquals(AfterActionPartState.ABSENT, part.state());
    }

    @Test
    void resolveRecordingReturnsPresentWithCountOneWhenResolved() {
        UsageRecording recording = new UsageRecording(java.net.URI.create("rtsp://mediamtx/clip"), STARTED_AT, 60);
        AfterActionPart part = assembler.resolveRecording(Optional.of(recording));
        assertEquals(AfterActionPartState.PRESENT, part.state());
        assertEquals(1, part.count());
    }

    // ---- resolvePassport ----

    @Test
    void resolvePassportReturnsAbsentWhenNeitherSnapshotWasCaptured() {
        AfterActionPart part = assembler.resolvePassport(new FlightPassport(usageId, assetId, null, null));
        assertEquals(AfterActionPartState.ABSENT, part.state());
        assertEquals(0, part.count());
    }

    @Test
    void resolvePassportReturnsPresentWithCountOneForOnlyAPreflightSnapshot() {
        AfterActionPart part =
                assembler.resolvePassport(new FlightPassport(usageId, assetId, minimalProfile(STARTED_AT), null));
        assertEquals(AfterActionPartState.PRESENT, part.state());
        assertEquals(1, part.count());
    }

    @Test
    void resolvePassportReturnsPresentWithCountTwoForBothSnapshots() {
        AfterActionPart part = assembler.resolvePassport(
                new FlightPassport(usageId, assetId, minimalProfile(STARTED_AT), minimalProfile(ENDED_AT)));
        assertEquals(AfterActionPartState.PRESENT, part.state());
        assertEquals(2, part.count());
    }

    // ---- resolveAudit ----

    @Test
    void resolveAuditReturnsForbiddenWhenScopeMayNotManageOrg() {
        // Unreachable end-to-end via assemble() under today's three real roles (see class javadoc)
        // -- exercised directly here, which is the whole reason resolveAudit is package-private.
        AfterActionAssembler.AuditResolution resolution =
                assembler.resolveAudit(assetId, new Authority(VisibilityScope.assignedAssets(Set.of(assetId)),
                        EnumSet.allOf(Capability.class)));
        assertEquals(AfterActionPartState.FORBIDDEN, resolution.part().state());
        assertEquals(0, resolution.part().count());
        assertEquals(List.of(), resolution.entries());
    }

    @Test
    void resolveAuditReturnsAbsentWhenAllowedButNoEntriesExist() {
        auditTrailPort.entries = List.of();
        AfterActionAssembler.AuditResolution resolution = assembler.resolveAudit(assetId, Authority.full());
        assertEquals(AfterActionPartState.ABSENT, resolution.part().state());
    }

    @Test
    void resolveAuditReturnsPresentWithEntriesWhenAllowedAndEntriesExist() {
        auditTrailPort.entries = List.of(auditEntry());
        AfterActionAssembler.AuditResolution resolution = assembler.resolveAudit(assetId, Authority.full());
        assertEquals(AfterActionPartState.PRESENT, resolution.part().state());
        assertEquals(1, resolution.part().count());
        assertEquals(1, resolution.entries().size());
    }

    // ---- flattenDetections / filterMarksInWindow ----

    @Test
    void flattenDetectionsProducesOneRowPerDetectionNotPerFrame() {
        DetectionResult result = new DetectionResult(streamId, 1, STARTED_AT,
                List.of(new Detection("person", 0.9, new BoundingBox(0, 0, 0.1, 0.1), new ModelRef("yolo", "1")),
                        new Detection("car", 0.8, new BoundingBox(0.2, 0.2, 0.1, 0.1), new ModelRef("yolo", "1"))),
                Duration.ofMillis(10), null, null, List.of(), Optional.empty());

        List<DetectionRow> rows = AfterActionAssembler.flattenDetections(List.of(result));

        assertEquals(2, rows.size());
        assertEquals("person", rows.get(0).label());
        assertEquals("car", rows.get(1).label());
        assertNull(rows.get(0).trackId());
    }

    @Test
    void filterMarksInWindowKeepsOnlyMarksInsideInclusiveBounds() {
        Mark before = markAt(STARTED_AT.minusSeconds(1));
        Mark atStart = markAt(STARTED_AT);
        Mark inside = markAt(STARTED_AT.plusSeconds(10));
        Mark atEnd = markAt(ENDED_AT);
        Mark after = markAt(ENDED_AT.plusSeconds(1));

        List<Mark> kept =
                AfterActionAssembler.filterMarksInWindow(List.of(before, atStart, inside, atEnd, after), STARTED_AT,
                        ENDED_AT);

        assertEquals(List.of(atStart, inside, atEnd), kept);
    }

    // ---- fixtures ----

    private Viewer viewer(Role role) {
        return new Viewer(ownerId, Set.of(groupId), role);
    }

    private AssetDetails assetDetails(Ownership ownership) {
        Instant now = Instant.now();
        Asset asset = new Asset(assetId, "Drone One", new CategoryId("drone"), ownership, Set.of(deviceId),
                Map.of(), LifecycleState.ACTIVE, Identity.NONE, Custody.NONE, InventoryState.IN_STOCK, now, now);
        AssetSummary summary = new AssetSummary(asset, "Drone", AssetStatus.OFFLINE, null, null,
                asset.inventoryState(), asset.identity(), asset.custody());
        return new AssetDetails(summary, List.of(), List.of());
    }

    private UsageTimeline timelineWith(List<Telemetry> telemetry, List<DetectionResult> detections) {
        AssetUsage usage = new AssetUsage(usageId, assetId, STARTED_AT, ENDED_AT, null, null, telemetry.size(),
                streamId, UsagePhase.PREFLIGHT, UsageOrigin.STREAM, null);
        return new UsageTimeline(usage, STARTED_AT, ENDED_AT, telemetry, detections);
    }

    private Telemetry telemetryAt(Instant at) {
        return new Telemetry(deviceId, at, 50.0, 30.0, 100.0, 90.0, 80.0, Map.of("groundspeedMps", 5.0));
    }

    private DetectionResult detectionResultAt(Instant at, String label) {
        return new DetectionResult(streamId, 1, at,
                List.of(new Detection(label, 0.9, new BoundingBox(0, 0, 0.1, 0.1), new ModelRef("yolo", "1"))),
                Duration.ofMillis(10), null, null, List.of(), Optional.empty());
    }

    private Mark markAt(Instant createdAt) {
        return new Mark(MarkId.random(), LayerId.random(), new GeoPosition(50.0, 30.0, null), MarkKind.POI,
                Affiliation.UNKNOWN, "mark", null, ownership, createdAt, MarkStatus.ACTIVE, MarkSource.MANUAL,
                Verification.unverified());
    }

    private VehicleProfile minimalProfile(Instant observedAt) {
        return new VehicleProfile("udp://0.0.0.0:14550#7", observedAt, 7, "ardupilot", "4.5.7", "quadcopter", null,
                List.of(), List.of(), List.of(), null, true, null);
    }

    private AuditEntry auditEntry() {
        return new AuditEntry(AuditId.random(), STARTED_AT, ownerId, AuditAction.UPDATED, AuditTargetType.ASSET,
                assetId.value().toString(), "asset updated", Map.of());
    }

    private static AfterActionPart partOf(AfterActionPackage pkg, AfterActionPartKind kind) {
        return pkg.parts().stream().filter(p -> p.part() == kind).findFirst()
                .orElseThrow(() -> new AssertionError("no part " + kind));
    }

    // ---- hand fakes ----

    private static final class FakeAssetService implements AssetService {
        AssetDetails detailsResult;
        RuntimeException detailsError;

        @Override
        public AssetDetails details(VisibilityScope scope, AssetId id) {
            if (detailsError != null) {
                throw detailsError;
            }
            return detailsResult;
        }

        @Override
        public Asset create(AssetSpec spec, Ownership ownership, UserId actor) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Asset createFromCandidate(AssetSpec spec, Ownership ownership, UserId actor) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<DuplicateDeviceMatch> findDuplicateDevice(StreamDescriptor candidate) {
            return Optional.empty();
        }

        @Override
        public List<AssetSummary> assets(boolean includeDeleted) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<AssetSummary> assets(VisibilityScope scope, boolean includeDeleted) {
            throw new UnsupportedOperationException();
        }

        @Override
        public AssetDetails details(AssetId id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Asset update(AssetId id, AssetEdit edit, UserId actor) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Asset setState(AssetId id, LifecycleState state, UserId actor) {
            throw new UnsupportedOperationException();
        }

        @Override
        public AssetDeletion delete(AssetId id, UserId actor) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void stopStream(AssetId id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Asset assignDevice(AssetId id, DeviceId deviceId, UserId actor) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Asset unassignDevice(AssetId id, DeviceId deviceId, UserId actor) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class FakeReplayService implements ReplayService {
        UsageTimeline timeline;
        Optional<UsageRecording> recording = Optional.empty();
        boolean timelineCalled;

        @Override
        public UsageTimeline timeline(UsageId usageId, Instant from, Instant to, int maxPoints) {
            timelineCalled = true;
            return timeline;
        }

        @Override
        public Optional<UsageRecording> recordingFor(UsageId usageId) {
            return recording;
        }
    }

    private static final class FakeMarkService implements MarkService {
        List<Mark> marks = List.of();

        @Override
        public List<Mark> list(Viewer v) {
            return marks;
        }

        @Override
        public Mark create(Viewer v, com.drones.vision.map.application.mark.MarkSpec spec) {
            throw new UnsupportedOperationException();
        }

        @Override
        public com.drones.vision.map.application.mark.GeolocationResult geolocate(Viewer v,
                com.drones.vision.map.application.mark.GeolocateSpec spec) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Mark patch(Viewer v, MarkId id, com.drones.vision.map.application.mark.MarkPatch patch) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Mark verify(Viewer v, MarkId id, Verification.VerificationState decision) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Mark promote(Viewer v, MarkId id, LayerId targetOrNull) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void delete(Viewer v, MarkId id) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class FakeVehicleProfileService implements VehicleProfileService {
        FlightPassport passportResult;

        @Override
        public FlightPassport passport(AssetId assetId, UsageId usageId, VisibilityScope scope) {
            return passportResult;
        }

        @Override
        public VehicleProfile probe(AssetId assetId, Duration window, UserId actor, Authority authority) {
            throw new UnsupportedOperationException();
        }

        @Override
        public VehicleProfile captureSnapshot(AssetId assetId, UsageId usageId,
                com.drones.vision.flight.domain.model.FlightPhase phase, Duration window, UserId actor,
                Authority authority) {
            throw new UnsupportedOperationException();
        }

        @Override
        public VehicleProfile latestProfile(AssetId assetId, VisibilityScope scope) {
            throw new UnsupportedOperationException();
        }

        @Override
        public VehicleProfile probeCandidate(String linkKey, Duration window, UserId actor) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<ParameterDrift> driftFromPreviousFlight(AssetId assetId, UsageId usageId,
                VisibilityScope scope) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class FakeAuditTrailPort implements AuditTrailPort {
        List<AuditEntry> entries = new ArrayList<>();

        @Override
        public AuditEntry record(AuditEntry entry) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<AuditEntry> findRecent(int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<AuditEntry> findByTarget(AuditTargetType targetType, String targetId, int limit) {
            return entries;
        }

        @Override
        public List<AuditEntry> findByActor(UserId actor, int limit) {
            throw new UnsupportedOperationException();
        }
    }
}
