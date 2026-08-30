package com.drones.vision.flight.application;

import com.drones.vision.warehouse.domain.model.Asset;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.platform.AuditEntry;
import com.drones.vision.platform.AuditTargetType;
import com.drones.vision.kernel.Capability;
import com.drones.vision.kernel.CategoryId;
import com.drones.vision.flight.domain.model.ControlProfile;
import com.drones.vision.flight.domain.model.ControlProfileId;
import com.drones.vision.flight.domain.model.FeatureReadiness;
import com.drones.vision.flight.domain.model.FeatureRequirement;
import com.drones.vision.flight.domain.model.FeatureStatus;
import com.drones.vision.flight.domain.model.ReadinessReport;
import com.drones.vision.flight.domain.model.ReadinessVerdict;
import com.drones.vision.flight.domain.model.RemedyKind;
import com.drones.vision.flight.domain.model.UnidentifiedReason;
import com.drones.vision.flight.domain.model.VehicleKind;
import com.drones.vision.warehouse.domain.model.Device;
import com.drones.vision.kernel.DeviceId;
import com.drones.vision.kernel.GroupId;
import com.drones.vision.kernel.LifecycleState;
import com.drones.vision.kernel.Ownership;
import com.drones.vision.flight.domain.model.RcChannels;
import com.drones.vision.kernel.StreamDescriptor;
import com.drones.vision.kernel.UserId;
import com.drones.vision.platform.AuditTrailPort;
import com.drones.vision.flight.domain.port.ManualControlLink;
import com.drones.vision.flight.domain.port.ManualControlPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import com.drones.vision.warehouse.application.asset.AssetDetails;
import com.drones.vision.warehouse.application.asset.AssetService;
import com.drones.vision.warehouse.application.asset.AssetStatus;
import com.drones.vision.warehouse.application.asset.AssetSummary;
import com.drones.vision.warehouse.domain.model.Custody;
import com.drones.vision.warehouse.domain.model.Identity;
import com.drones.vision.warehouse.domain.model.InventoryState;
import com.drones.vision.platform.AccessDeniedException;
import com.drones.vision.platform.VisibilityScope;

/**
 * Unit tests for {@link DefaultManualControlService}. {@code assetService} is a Mockito mock
 * (mirrors {@code DefaultFlightCommandServiceTest} exactly — a large interface this service only
 * ever calls {@code details(AssetId)} on); {@code ManualControlPort}/{@code AuditTrailPort} are
 * hand-rolled in-memory fakes, and the watchdog {@link Clock}/{@link ScheduledExecutorService} are
 * a mutable test clock and a recording fake scheduler (mirrors {@code SupervisedPublisherTest}'s
 * own {@code RecordingScheduler}) so the watchdog fires deterministically, with no real sleeps
 * anywhere in this suite.
 */
class DefaultManualControlServiceTest {

    private static final CategoryId DRONE = new CategoryId("drone");
    private static final long TIMEOUT_MS = 300L;

    private AssetService assetService;
    private FakeManualControlPort manualControlPort;
    private FakeAuditTrailPort auditTrail;
    private FakeReadinessService readinessService;
    private MutableClock clock;
    private RecordingScheduler scheduler;
    private DefaultManualControlService service;

    private final UserId actor = UserId.random();
    private final AssetId assetId = AssetId.random();
    private Device device;

    @BeforeEach
    void setUp() {
        assetService = mock(AssetService.class);
        manualControlPort = new FakeManualControlPort();
        auditTrail = new FakeAuditTrailPort();
        readinessService = new FakeReadinessService();
        clock = new MutableClock(Instant.parse("2026-07-31T00:00:00Z"));
        scheduler = new RecordingScheduler();
        service = new DefaultManualControlService(assetService, manualControlPort, auditTrail, readinessService,
                clock, scheduler);

        device = new Device(DeviceId.random(), "FC", Set.of(Capability.TELEMETRY),
                new StreamDescriptor("mavlink", URI.create("udp://127.0.0.1:14550"), Map.of()));
    }

    private void stubDetails(Device... devices) {
        Asset asset = Asset.register(assetId, "Drone 1", DRONE, new Ownership(actor, GroupId.random()),
                Set.of(devices[0].id()), Map.of(), Identity.NONE, Custody.NONE);
        AssetSummary summary = new AssetSummary(asset, "Drone", AssetStatus.OFFLINE, null, null,
                InventoryState.IN_STOCK, Identity.NONE, Custody.NONE);
        when(assetService.details(assetId)).thenReturn(new AssetDetails(summary, List.of(devices), List.of()));
    }

    // -- engage ---------------------------------------------------------------

    @Test
    void engageResolvesDeviceOpensLinkAuditsEngageAndReturnsActiveSession() {
        stubDetails(device);

        ManualControlSession session = service.engage(assetId, actor, VisibilityScope.unbounded(), () -> { });

        assertTrue(session.active());
        assertEquals(1, manualControlPort.engagedDevices.size());
        assertEquals(device, manualControlPort.engagedDevices.get(0));
        assertEquals(ControlProfile.forKind(FakeManualControlPort.FakeLink.KIND), session.controlProfile());

        assertEquals(1, auditTrail.recorded.size());
        AuditEntry entry = auditTrail.recorded.get(0);
        assertEquals(AuditTargetType.ASSET, entry.targetType());
        assertEquals(assetId.value().toString(), entry.targetId());
        assertEquals("MANUAL_CONTROL", entry.details().get("command"));
        assertEquals("ENGAGE", entry.details().get("result"));
    }

    /**
     * The seam that makes controller setup mean anything (docs/plans/active/
     * CONTROLLER-SETUP-CONTEXT.md C6): a session engages with the operator's own active layout,
     * resolved against the kind the <em>link</em> is reporting — never a kind stored anywhere.
     */
    @Test
    void engageEngagesWithTheProfileTheSelectorResolvesForThisOperatorAndVehicle() {
        stubDetails(device);
        ControlProfile saved = ControlProfile.forKind(FakeManualControlPort.FakeLink.KIND)
                .copyAs(ControlProfileId.random(), "Field rover");
        List<UserId> askedFor = new ArrayList<>();
        List<VehicleKind> askedAbout = new ArrayList<>();
        DefaultManualControlService withProfiles = new DefaultManualControlService(assetService, manualControlPort,
                auditTrail, readinessService, clock, scheduler, 300L, (owner, kind) -> {
                    askedFor.add(owner);
                    askedAbout.add(kind);
                    return saved;
                });

        ManualControlSession session = withProfiles.engage(assetId, actor, VisibilityScope.unbounded(), () -> { });

        assertEquals(saved, session.controlProfile());
        assertEquals(List.of(actor), askedFor);
        assertEquals(List.of(FakeManualControlPort.FakeLink.KIND), askedAbout);
    }

    /**
     * A session must never engage with no map at all — there is no safe way to fail open on a
     * throttle — so a selector that answers nothing is the built-in, not a null profile.
     */
    @Test
    void engageFallsBackToTheBuiltInWhenTheSelectorResolvesNothing() {
        stubDetails(device);
        DefaultManualControlService withProfiles = new DefaultManualControlService(assetService, manualControlPort,
                auditTrail, readinessService, clock, scheduler, 300L, (owner, kind) -> null);

        ManualControlSession session = withProfiles.engage(assetId, actor, VisibilityScope.unbounded(), () -> { });

        assertEquals(ControlProfile.forKind(FakeManualControlPort.FakeLink.KIND), session.controlProfile());
    }

    @Test
    void engageDeniedWhenAssetIsOutOfScopeAndAuditsTheDenial() {
        stubDetails(device);

        AccessDeniedException ex = assertThrows(AccessDeniedException.class,
                () -> service.engage(assetId, actor, VisibilityScope.groups(Set.of()), () -> { }));
        assertTrue(ex.getMessage().contains(assetId.value().toString()));

        assertTrue(manualControlPort.engagedDevices.isEmpty());
        assertEquals(1, auditTrail.recorded.size());
        assertEquals("DENIED:out of scope", auditTrail.recorded.get(0).details().get("result"));
    }

    @Test
    void engageThrowsNoSuchElementForAnUnknownAsset() {
        when(assetService.details(assetId)).thenThrow(new NoSuchElementException("Unknown asset: " + assetId.value()));

        assertThrows(NoSuchElementException.class,
                () -> service.engage(assetId, actor, VisibilityScope.unbounded(), () -> { }));
        assertTrue(auditTrail.recorded.isEmpty());
    }

    @Test
    void engageThrowsIllegalStateWhenNoActiveDeviceIsSupported() {
        stubDetails(device.withState(LifecycleState.DEACTIVATED));
        manualControlPort.supportsResult = true;

        assertThrows(IllegalStateException.class,
                () -> service.engage(assetId, actor, VisibilityScope.unbounded(), () -> { }));
        assertTrue(manualControlPort.engagedDevices.isEmpty());
        assertTrue(auditTrail.recorded.isEmpty());
    }

    @Test
    void engageThrowsIllegalStateWhenPortRejectsAnUnreachableDevice() {
        stubDetails(device);
        manualControlPort.engageFailureMessage = "device heard but source address is stale";

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service.engage(assetId, actor, VisibilityScope.unbounded(), () -> { }));
        assertEquals("device heard but source address is stale", ex.getMessage());
        assertTrue(auditTrail.recorded.isEmpty());
    }

    @Test
    void secondEngageOnTheSameHandleThrowsIllegalStateWithoutTouchingThePort() {
        stubDetails(device);
        service.engage(assetId, actor, VisibilityScope.unbounded(), () -> { });

        assertThrows(IllegalStateException.class,
                () -> service.engage(assetId, actor, VisibilityScope.unbounded(), () -> { }));
        assertEquals(1, manualControlPort.engagedDevices.size());
        assertEquals(1, auditTrail.recorded.size()); // only the first ENGAGE
    }

    @Test
    void engageIsAllowedAgainAfterTheFirstSessionIsReleased() {
        stubDetails(device);
        ManualControlSession first = service.engage(assetId, actor, VisibilityScope.unbounded(), () -> { });
        first.release();

        ManualControlSession second = service.engage(assetId, actor, VisibilityScope.unbounded(), () -> { });

        assertTrue(second.active());
        assertEquals(2, manualControlPort.engagedDevices.size());
    }

    // -- engage: refusing an unidentified vehicle (FLEET-RADIO R2) --------------

    /**
     * The central case: a vehicle this platform has genuinely never seen. Unlike the
     * out-of-scope/no-device guards, the port's link IS already open here (a real relay was
     * started), so it must come back released, not just refused.
     */
    @Test
    void engageRefusesAVehicleThatWasNeverIdentifiedReleasesTheOpenedLinkAndAuditsARefusal() {
        stubDetails(device);
        manualControlPort.kindToEngageAs = VehicleKind.UNKNOWN;
        manualControlPort.unidentifiedReasonToReport = UnidentifiedReason.NEVER_IDENTIFIED;

        VehicleUnidentifiedException ex = assertThrows(VehicleUnidentifiedException.class,
                () -> service.engage(assetId, actor, VisibilityScope.unbounded(), () -> { }));

        assertEquals(UnidentifiedReason.NEVER_IDENTIFIED, ex.reason());
        assertTrue(ex.getMessage().contains("could not be identified"), "got: " + ex.getMessage());

        assertEquals(1, manualControlPort.engagedDevices.size(), "the link was opened before being refused");
        assertEquals(1, manualControlPort.releasedLinks.size(), "an opened-then-refused link must be released");
        assertEquals(1, auditTrail.recorded.size());
        assertEquals("REFUSED:unidentified-vehicle:NEVER_IDENTIFIED",
                auditTrail.recorded.get(0).details().get("result"));
    }

    @Test
    void engageRefusesAnUnsupportedAirframeWithItsOwnDistinctReason() {
        stubDetails(device);
        manualControlPort.kindToEngageAs = VehicleKind.UNKNOWN;
        manualControlPort.unidentifiedReasonToReport = UnidentifiedReason.UNSUPPORTED_VEHICLE;

        VehicleUnidentifiedException ex = assertThrows(VehicleUnidentifiedException.class,
                () -> service.engage(assetId, actor, VisibilityScope.unbounded(), () -> { }));

        assertEquals(UnidentifiedReason.UNSUPPORTED_VEHICLE, ex.reason());
        assertTrue(ex.getMessage().contains("does not support"), "got: " + ex.getMessage());
        assertEquals(1, manualControlPort.releasedLinks.size());
    }

    @Test
    void engageRefusesANotAVehicleLinkWithItsOwnDistinctReason() {
        stubDetails(device);
        manualControlPort.kindToEngageAs = VehicleKind.UNKNOWN;
        manualControlPort.unidentifiedReasonToReport = UnidentifiedReason.NOT_A_VEHICLE;

        VehicleUnidentifiedException ex = assertThrows(VehicleUnidentifiedException.class,
                () -> service.engage(assetId, actor, VisibilityScope.unbounded(), () -> { }));

        assertEquals(UnidentifiedReason.NOT_A_VEHICLE, ex.reason());
        assertTrue(ex.getMessage().contains("is not a vehicle"), "got: " + ex.getMessage());
        assertEquals(1, manualControlPort.releasedLinks.size());
    }

    /**
     * The refusal is the operator's answer; the release is only the cleanup that follows it. A port
     * that throws on the way out must not replace "this vehicle is unidentified" with its own
     * transport error -- nor swallow the refusal's audit record, which is the only trace that a real
     * relay was opened and deliberately refused.
     */
    @Test
    void aReleaseThatFailsDoesNotMaskTheRefusalOrItsAuditRecord() {
        stubDetails(device);
        manualControlPort.kindToEngageAs = VehicleKind.UNKNOWN;
        manualControlPort.unidentifiedReasonToReport = UnidentifiedReason.NOT_A_VEHICLE;
        manualControlPort.releaseFailure = new IllegalStateException("socket already closed");

        VehicleUnidentifiedException ex = assertThrows(VehicleUnidentifiedException.class,
                () -> service.engage(assetId, actor, VisibilityScope.unbounded(), () -> { }));

        assertEquals(UnidentifiedReason.NOT_A_VEHICLE, ex.reason());
        assertEquals(1, ex.getSuppressed().length, "the release failure must survive as a suppressed cause");
        assertEquals("socket already closed", ex.getSuppressed()[0].getMessage());
        assertEquals(1, auditTrail.recorded.size(), "the refusal must still be audited");
        assertEquals("REFUSED:unidentified-vehicle:NOT_A_VEHICLE", auditTrail.recorded.get(0).details().get("result"));
    }

    /**
     * The central design question, proven directly: refusing to engage is correct for all three
     * causes, but a refusal that reads the same for all three tells the operator nothing. Each of
     * the three must produce a genuinely different message.
     */
    @Test
    void theThreeUnidentifiedReasonsProduceThreeDistinctOperatorFacingMessages() {
        Set<String> messages = new HashSet<>();
        for (UnidentifiedReason reason : UnidentifiedReason.values()) {
            stubDetails(device);
            FakeManualControlPort port = new FakeManualControlPort();
            port.kindToEngageAs = VehicleKind.UNKNOWN;
            port.unidentifiedReasonToReport = reason;
            DefaultManualControlService serviceForReason =
                    new DefaultManualControlService(assetService, port, new FakeAuditTrailPort(), readinessService,
                            clock, scheduler);

            VehicleUnidentifiedException ex = assertThrows(VehicleUnidentifiedException.class,
                    () -> serviceForReason.engage(assetId, actor, VisibilityScope.unbounded(), () -> { }));
            messages.add(ex.getMessage());
        }
        assertEquals(3, messages.size(), "each reason must read differently to the operator, got: " + messages);
    }

    /**
     * An implementation that answers {@code UNKNOWN} without ever overriding {@code
     * unidentifiedReason()} (the interface's own default) must still refuse -- it must not fail open
     * just because it cannot name the finer cause. Exercised via a link that only implements the
     * three original {@link ManualControlLink} methods, the same shape every pre-R2 implementation had.
     */
    @Test
    void engageRefusesAnUnknownKindEvenFromALinkThatNeverOverridesUnidentifiedReason() {
        stubDetails(device);
        manualControlPort.linkFactory = () -> new ManualControlLink() {
            @Override
            public boolean active() {
                return true;
            }

            @Override
            public int rateHz() {
                return 10;
            }

            @Override
            public VehicleKind vehicleKind() {
                return VehicleKind.UNKNOWN;
            }
        };

        VehicleUnidentifiedException ex = assertThrows(VehicleUnidentifiedException.class,
                () -> service.engage(assetId, actor, VisibilityScope.unbounded(), () -> { }));

        assertEquals(UnidentifiedReason.NEVER_IDENTIFIED, ex.reason(),
                "the interface's own default must answer NEVER_IDENTIFIED, never a guess at the other two");
    }

    // -- onChannels -------------------------------------------------------------

    @Test
    void onChannelsForwardsMappedChannelsAndResetsTheWatchdog() {
        stubDetails(device);
        ManualControlSession session = service.engage(assetId, actor, VisibilityScope.unbounded(), () -> { });

        List<Double> axes = List.of(0.0, -0.12, 1.0, 0.0);
        List<Double> buttons = List.of(0.0, 1.0);
        RcChannels expected = ControlProfile.forKind(FakeManualControlPort.FakeLink.KIND)
                .channelMap().apply(axes, buttons);

        clock.advance(Duration.ofMillis(50));
        session.onChannels(axes, buttons, 42L, 1_000L);

        assertEquals(List.of(expected), manualControlPort.sentChannels);
    }

    @Test
    void onChannelsAfterReleaseIsANoOp() {
        stubDetails(device);
        ManualControlSession session = service.engage(assetId, actor, VisibilityScope.unbounded(), () -> { });
        session.release();

        session.onChannels(List.of(1.0), List.of(), 1L, 1L);

        assertTrue(manualControlPort.sentChannels.isEmpty());
    }

    // -- release ------------------------------------------------------------

    @Test
    void releaseStopsThePortCancelsTheWatchdogAndAuditsRelease() {
        stubDetails(device);
        ManualControlSession session = service.engage(assetId, actor, VisibilityScope.unbounded(), () -> { });
        RecordingScheduler.Scheduled armed = scheduler.scheduled.get(0);

        session.release();

        assertFalse(session.active());
        assertEquals(1, manualControlPort.releasedLinks.size());
        assertTrue(armed.future().cancelled());
        assertEquals(2, auditTrail.recorded.size()); // ENGAGE, RELEASE
        assertEquals("RELEASE", auditTrail.recorded.get(1).details().get("result"));
    }

    @Test
    void releaseIsIdempotentAndNeverDoubleAudits() {
        stubDetails(device);
        ManualControlSession session = service.engage(assetId, actor, VisibilityScope.unbounded(), () -> { });

        session.release();
        session.release();

        assertEquals(1, manualControlPort.releasedLinks.size());
        assertEquals(2, auditTrail.recorded.size()); // ENGAGE, RELEASE -- not a second RELEASE
    }

    // -- watchdog -------------------------------------------------------------

    @Test
    void watchdogFiresAfterSustainedSilenceAndNotifiesTheListener() {
        stubDetails(device);
        AtomicInteger tripped = new AtomicInteger();
        ManualControlSession session =
                service.engage(assetId, actor, VisibilityScope.unbounded(), tripped::incrementAndGet);

        clock.advance(Duration.ofMillis(TIMEOUT_MS));
        scheduler.runLast(); // fire the armed check

        assertFalse(session.active());
        assertEquals(1, manualControlPort.releasedLinks.size());
        assertEquals(1, tripped.get());
        assertEquals(2, auditTrail.recorded.size()); // ENGAGE, WATCHDOG
        assertEquals("WATCHDOG", auditTrail.recorded.get(1).details().get("result"));
    }

    @Test
    void freshOnChannelsWithinTheWindowDoesNotTripTheWatchdog() {
        stubDetails(device);
        AtomicInteger tripped = new AtomicInteger();
        ManualControlSession session =
                service.engage(assetId, actor, VisibilityScope.unbounded(), tripped::incrementAndGet);

        // 100ms in, a fresh input arrives -- lastInput moves to t+100ms.
        clock.advance(Duration.ofMillis(100));
        session.onChannels(List.of(0.2), List.of(), 1L, 1L);

        // The originally-armed check (due at t+300ms) fires "late" at t+350ms: elapsed since the
        // fresh input is only 250ms (< 300ms timeout) -> must NOT trip, and must reschedule itself
        // for the remaining 50ms rather than releasing.
        clock.advance(Duration.ofMillis(250));
        scheduler.runLast();

        assertTrue(session.active());
        assertTrue(manualControlPort.releasedLinks.isEmpty());
        assertEquals(0, tripped.get());
        assertEquals(2, scheduler.scheduled.size(), "a non-tripping check must reschedule itself once");
        assertEquals(50L, scheduler.scheduled.get(1).delayMillis());

        // Now let the rescheduled check actually elapse: t+450ms, 350ms since the fresh input.
        clock.advance(Duration.ofMillis(100));
        scheduler.runLast();

        assertFalse(session.active());
        assertEquals(1, manualControlPort.releasedLinks.size());
        assertEquals(1, tripped.get());
    }

    @Test
    void explicitReleaseCancelsAPendingWatchdogCheckSoItNeverFiresATrip() {
        stubDetails(device);
        AtomicInteger tripped = new AtomicInteger();
        ManualControlSession session =
                service.engage(assetId, actor, VisibilityScope.unbounded(), tripped::incrementAndGet);

        session.release();
        clock.advance(Duration.ofMillis(TIMEOUT_MS));
        // The scheduler is a fake -- nothing fires unless the test calls runLast() -- but assert the
        // cancellation itself, the real-world equivalent of "the timer never fires again".
        assertTrue(scheduler.scheduled.get(0).future().cancelled());
        assertEquals(0, tripped.get());
    }

    // -- constructor validation -------------------------------------------------

    @Test
    void constructorsRejectNullCollaborators() {
        assertThrows(NullPointerException.class,
                () -> new DefaultManualControlService(null, manualControlPort, auditTrail, readinessService));
        assertThrows(NullPointerException.class,
                () -> new DefaultManualControlService(assetService, null, auditTrail, readinessService));
        assertThrows(NullPointerException.class,
                () -> new DefaultManualControlService(assetService, manualControlPort, null, readinessService));
        assertThrows(NullPointerException.class,
                () -> new DefaultManualControlService(assetService, manualControlPort, auditTrail, null));
        assertThrows(NullPointerException.class,
                () -> new DefaultManualControlService(assetService, manualControlPort, auditTrail, readinessService,
                        null, scheduler));
        assertThrows(NullPointerException.class,
                () -> new DefaultManualControlService(assetService, manualControlPort, auditTrail, readinessService,
                        clock, null));
    }

    // -- engage: a silently misconfigured vehicle is refused too (FLEET-RADIO R6) ----------

    /**
     * Required result 3: the {@code rc-relay} check runs at {@code engage}, not only at
     * probe/preflight -- a MISSING verdict must refuse before any device is resolved or any link
     * opened, and must audit the refusal distinctly from the R2 unidentified-vehicle refusal.
     */
    @Test
    void engageRefusesWhenRcRelayIsMissingWithoutTouchingTheDeviceOrPort() {
        stubDetails(device);
        readinessService.features = List.of(new FeatureReadiness("rc-relay", "RC relay", FeatureStatus.MISSING,
                "SYSID_MYGCS is 1, not the required 255.", RemedyKind.PARAM_WRITE));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service.engage(assetId, actor, VisibilityScope.unbounded(), () -> { }));

        assertTrue(ex.getMessage().contains("SYSID_MYGCS"), "got: " + ex.getMessage());
        assertTrue(manualControlPort.engagedDevices.isEmpty(), "no link should be opened when not-ready");
        assertEquals(1, auditTrail.recorded.size());
        assertEquals("REFUSED:not-ready:rc-relay", auditTrail.recorded.get(0).details().get("result"));
    }

    @Test
    void engageProceedsWhenRcRelayIsReady() {
        stubDetails(device);
        readinessService.features = List.of(
                new FeatureReadiness("rc-relay", "RC relay", FeatureStatus.READY, "Ready.", null));

        ManualControlSession session = service.engage(assetId, actor, VisibilityScope.unbounded(), () -> { });

        assertTrue(session.active());
        assertEquals(1, readinessService.evaluatedFor.size());
        assertEquals(assetId, readinessService.evaluatedFor.get(0));
    }

    /**
     * "Absence of evidence is not evidence of readiness" (this service's own javadoc, mirroring
     * {@code ReadinessService}'s rule): a never-probed vehicle must still be able to engage, exactly
     * as it could before this wave.
     */
    @Test
    void engageProceedsWhenRcRelayIsUnknown() {
        stubDetails(device);
        readinessService.features = List.of(
                new FeatureReadiness("rc-relay", "RC relay", FeatureStatus.UNKNOWN, "Never probed.", null));

        assertTrue(service.engage(assetId, actor, VisibilityScope.unbounded(), () -> { }).active());
    }

    /** Only MISSING refuses -- a merely DEGRADED rc-relay row must not block engage. */
    @Test
    void engageProceedsWhenRcRelayIsDegraded() {
        stubDetails(device);
        readinessService.features = List.of(
                new FeatureReadiness("rc-relay", "RC relay", FeatureStatus.DEGRADED, "Below threshold.", null));

        assertTrue(service.engage(assetId, actor, VisibilityScope.unbounded(), () -> { }).active());
    }

    /** The scope gate runs first: an out-of-scope engage must never even query readiness. */
    @Test
    void engageDeniedForOutOfScopeNeverEvaluatesReadiness() {
        stubDetails(device);

        assertThrows(AccessDeniedException.class,
                () -> service.engage(assetId, actor, VisibilityScope.groups(Set.of()), () -> { }));

        assertTrue(readinessService.evaluatedFor.isEmpty());
    }

    // -- engage: refusing a maintenance-grounded asset (WAREHOUSE-UX-CONTEXT.md D6/OQ1) -----

    /**
     * The "may this asset fly / engage" predicate OQ1 asks for: a {@link ReadinessReport#blockers()}
     * entry prefixed {@link DefaultReadinessService#MAINTENANCE_BLOCKER_PREFIX} (exactly what {@code
     * DefaultReadinessService#evaluate} produces for an open, flight-blocking warehouse maintenance
     * record) must refuse {@code engage} before any device is resolved or any link opened, and audit
     * distinctly from both the R2 unidentified-vehicle and R6 not-ready refusals.
     */
    @Test
    void engageRefusesWhenAssetIsMaintenanceGroundedWithoutTouchingTheDeviceOrPort() {
        stubDetails(device);
        readinessService.blockers =
                List.of(DefaultReadinessService.MAINTENANCE_BLOCKER_PREFIX + "GROUNDING:Propeller crack found");

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service.engage(assetId, actor, VisibilityScope.unbounded(), () -> { }));

        assertTrue(ex.getMessage().contains("Propeller crack found"), "got: " + ex.getMessage());
        assertTrue(manualControlPort.engagedDevices.isEmpty(), "no link should be opened when grounded");
        assertEquals(1, auditTrail.recorded.size());
        assertEquals("REFUSED:maintenance-grounded", auditTrail.recorded.get(0).details().get("result"));
    }

    @Test
    void engageProceedsWhenNoMaintenanceBlockerExists() {
        stubDetails(device);

        assertTrue(service.engage(assetId, actor, VisibilityScope.unbounded(), () -> { }).active());
    }

    // -- thread-safety smoke --------------------------------------------------

    @Test
    void concurrentOnChannelsAndReleaseNeverDoubleReleaseOrDoubleAudit() throws InterruptedException {
        stubDetails(device);
        ManualControlSession session = service.engage(assetId, actor, VisibilityScope.unbounded(), () -> { });

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        Runnable pumpChannels = () -> {
            ready.countDown();
            awaitLatch(go);
            for (int i = 0; i < 200; i++) {
                session.onChannels(List.of(0.1, 0.2, 0.0, 0.0), List.of(), i, i);
            }
        };
        Runnable releaseTwice = () -> {
            ready.countDown();
            awaitLatch(go);
            session.release();
            session.release();
        };
        Thread channelsThread = new Thread(pumpChannels, "test-channels");
        Thread releaseThread = new Thread(releaseTwice, "test-release");
        channelsThread.start();
        releaseThread.start();
        ready.await();
        go.countDown();
        channelsThread.join(5_000);
        releaseThread.join(5_000);

        assertFalse(session.active());
        assertEquals(1, manualControlPort.releasedLinks.size());
        long releaseAudits = auditTrail.recorded.stream()
                .filter(e -> "RELEASE".equals(e.details().get("result")))
                .count();
        assertEquals(1, releaseAudits);
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    // -- test doubles -----------------------------------------------------------

    /** In-memory {@link ManualControlPort}: records every call, never touches a real wire. */
    private static final class FakeManualControlPort implements ManualControlPort {
        boolean supportsResult = true;
        String engageFailureMessage;

        /** What {@link #engage} hands back, by default -- a plain {@link FakeLink} for this kind. */
        VehicleKind kindToEngageAs = FakeLink.KIND;

        /** Rides the returned {@link FakeLink}'s {@code unidentifiedReason()}; {@code null} lets the
         * {@link ManualControlLink} interface's own default answer instead (FLEET-RADIO R2). */
        UnidentifiedReason unidentifiedReasonToReport;

        /** When set, overrides {@code kindToEngageAs}/{@code unidentifiedReasonToReport} entirely --
         * lets a test hand back a link shape of its own choosing (e.g. one that implements only the
         * three original {@link ManualControlLink} methods, to exercise the interface's own default). */
        Supplier<ManualControlLink> linkFactory;

        /** When set, {@link #release} throws it -- the shape of a port whose socket died between
         * engage and release. */
        RuntimeException releaseFailure;

        final List<Device> engagedDevices = new ArrayList<>();
        final List<RcChannels> sentChannels = new ArrayList<>();
        final List<ManualControlLink> releasedLinks = new ArrayList<>();

        @Override
        public boolean supports(Device device) {
            return supportsResult;
        }

        @Override
        public ManualControlLink engage(Device device) {
            if (engageFailureMessage != null) {
                throw new IllegalArgumentException(engageFailureMessage);
            }
            engagedDevices.add(device);
            return linkFactory != null ? linkFactory.get() : new FakeLink(kindToEngageAs, unidentifiedReasonToReport);
        }

        @Override
        public void send(ManualControlLink link, RcChannels channels) {
            sentChannels.add(channels);
        }

        @Override
        public void release(ManualControlLink link) {
            if (link instanceof FakeLink fakeLink) {
                fakeLink.active = false;
            }
            releasedLinks.add(link);
            if (releaseFailure != null) {
                throw releaseFailure;
            }
        }

        private static final class FakeLink implements ManualControlLink {

            /** Any positive value -- these tests assert plumbing, not a particular cadence. */
            static final int RATE_HZ = 33;

            /** The kind these tests engage as by default. Not UNKNOWN, so a profile actually gets
             * chosen from it -- the UNKNOWN-refusal tests override this explicitly. */
            static final VehicleKind KIND = VehicleKind.ROVER;

            private final VehicleKind kind;
            private final UnidentifiedReason reasonOverride;
            private boolean active = true;

            FakeLink() {
                this(KIND, null);
            }

            FakeLink(VehicleKind kind, UnidentifiedReason reasonOverride) {
                this.kind = kind;
                this.reasonOverride = reasonOverride;
            }

            @Override
            public VehicleKind vehicleKind() {
                return kind;
            }

            @Override
            public Optional<UnidentifiedReason> unidentifiedReason() {
                return reasonOverride != null ? Optional.of(reasonOverride) : ManualControlLink.super.unidentifiedReason();
            }

            @Override
            public int rateHz() {
                return RATE_HZ;
            }

            @Override
            public boolean active() {
                return active;
            }
        }
    }

    /** In-memory {@link AuditTrailPort}: {@code record} is the only method this suite exercises. */
    private static final class FakeAuditTrailPort implements AuditTrailPort {
        final List<AuditEntry> recorded = new ArrayList<>();

        @Override
        public AuditEntry record(AuditEntry entry) {
            recorded.add(entry);
            return entry;
        }

        @Override
        public List<AuditEntry> findRecent(int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<AuditEntry> findByTarget(AuditTargetType targetType, String targetId, int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<AuditEntry> findByActor(UserId actor, int limit) {
            throw new UnsupportedOperationException();
        }
    }

    /**
     * In-memory {@link ReadinessService}: defaults to every frozen feature key {@code READY}, the
     * common case where this collaborator must never interfere with an engage a test is not
     * specifically exercising. A test that cares about the {@code rc-relay} gate overwrites {@link
     * #features} directly, mirroring {@code FakeFeatureRequirementRepositoryPort}'s own style in
     * {@code DefaultReadinessServiceTest}.
     */
    private static final class FakeReadinessService implements ReadinessService {
        List<FeatureReadiness> features = FeatureRequirement.FEATURE_KEYS.stream()
                .map(key -> new FeatureReadiness(key, key, FeatureStatus.READY, "Ready.", null))
                .toList();

        /**
         * WAREHOUSE-UX-CONTEXT.md D6/OQ1: a test exercising the maintenance-grounded refusal sets
         * this directly, mirroring how {@link #features} drives the rc-relay tests -- empty (the
         * default) must never interfere with an engage a test is not specifically exercising.
         */
        List<String> blockers = List.of();

        final List<AssetId> evaluatedFor = new ArrayList<>();

        @Override
        public ReadinessReport evaluate(AssetId assetId, VisibilityScope scope) {
            evaluatedFor.add(assetId);
            ReadinessVerdict verdict = blockers.isEmpty() ? ReadinessVerdict.GO : ReadinessVerdict.NO_GO;
            return new ReadinessReport(assetId, verdict, Instant.EPOCH, Instant.EPOCH, features, blockers);
        }
    }

    /** A {@link Clock} a test can advance on demand -- no real time ever passes. */
    private static final class MutableClock extends Clock {
        private Instant instant;

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    /**
     * A {@link ScheduledExecutorService} test double that never runs a real timer: {@link
     * #schedule(Runnable, long, TimeUnit)} just records the request (delay in millis + a
     * cancellable {@link ScheduledFuture}) so a test can assert on exactly what was requested and,
     * via {@link #runLast()}, choose precisely when (if ever) the most-recently-armed check
     * actually executes. Mirrors {@code SupervisedPublisherTest}'s own {@code RecordingScheduler}.
     * Every other {@code ScheduledExecutorService} method is unused by {@code
     * DefaultManualControlService} and throws if ever called.
     */
    private static final class RecordingScheduler implements ScheduledExecutorService {
        final List<Scheduled> scheduled = new ArrayList<>();

        void runLast() {
            scheduled.get(scheduled.size() - 1).command().run();
        }

        record Scheduled(Runnable command, long delayMillis, FakeFuture future) {
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            FakeFuture future = new FakeFuture();
            scheduled.add(new Scheduled(command, unit.toMillis(delay), future));
            return future;
        }

        private static final class FakeFuture implements ScheduledFuture<Object> {
            private boolean cancelled;

            boolean cancelled() {
                return cancelled;
            }

            @Override
            public boolean cancel(boolean mayInterruptIfRunning) {
                cancelled = true;
                return true;
            }

            @Override
            public long getDelay(TimeUnit unit) {
                throw new UnsupportedOperationException();
            }

            @Override
            public int compareTo(java.util.concurrent.Delayed o) {
                throw new UnsupportedOperationException();
            }

            @Override
            public boolean isCancelled() {
                return cancelled;
            }

            @Override
            public boolean isDone() {
                throw new UnsupportedOperationException();
            }

            @Override
            public Object get() {
                throw new UnsupportedOperationException();
            }

            @Override
            public Object get(long timeout, TimeUnit unit) {
                throw new UnsupportedOperationException();
            }
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay, TimeUnit unit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void shutdown() {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<Runnable> shutdownNow() {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isShutdown() {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isTerminated() {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> Future<T> submit(Callable<T> task) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> Future<T> submit(Runnable task, T result) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Future<?> submit(Runnable task) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> T invokeAny(Collection<? extends Callable<T>> tasks) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void execute(Runnable command) {
            throw new UnsupportedOperationException();
        }
    }
}
