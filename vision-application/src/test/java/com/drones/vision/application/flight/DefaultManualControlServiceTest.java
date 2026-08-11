package com.drones.vision.application.flight;

import com.drones.vision.warehouse.domain.model.Asset;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.identity.domain.model.AuditEntry;
import com.drones.vision.identity.domain.model.AuditTargetType;
import com.drones.vision.kernel.Capability;
import com.drones.vision.kernel.CategoryId;
import com.drones.vision.flight.domain.model.ChannelMap;
import com.drones.vision.warehouse.domain.model.Device;
import com.drones.vision.kernel.DeviceId;
import com.drones.vision.kernel.GroupId;
import com.drones.vision.kernel.LifecycleState;
import com.drones.vision.kernel.Ownership;
import com.drones.vision.flight.domain.model.RcChannels;
import com.drones.vision.kernel.StreamDescriptor;
import com.drones.vision.kernel.UserId;
import com.drones.vision.identity.domain.port.AuditTrailPort;
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
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import com.drones.vision.application.asset.AssetDetails;
import com.drones.vision.application.asset.AssetService;
import com.drones.vision.application.asset.AssetStatus;
import com.drones.vision.application.asset.AssetSummary;
import com.drones.vision.application.scope.AccessDeniedException;
import com.drones.vision.application.scope.VisibilityScope;

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
        clock = new MutableClock(Instant.parse("2026-07-31T00:00:00Z"));
        scheduler = new RecordingScheduler();
        service = new DefaultManualControlService(assetService, manualControlPort, auditTrail, clock, scheduler);

        device = new Device(DeviceId.random(), "FC", Set.of(Capability.TELEMETRY),
                new StreamDescriptor("mavlink", URI.create("udp://127.0.0.1:14550"), Map.of()));
    }

    private void stubDetails(Device... devices) {
        Asset asset = new Asset(assetId, "Drone 1", DRONE, new Ownership(actor, GroupId.random()),
                Set.of(devices[0].id()), Map.of());
        AssetSummary summary = new AssetSummary(asset, "Drone", AssetStatus.OFFLINE, null, null);
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
        assertEquals(ChannelMap.defaultMap(), session.channelMap());

        assertEquals(1, auditTrail.recorded.size());
        AuditEntry entry = auditTrail.recorded.get(0);
        assertEquals(AuditTargetType.ASSET, entry.targetType());
        assertEquals(assetId.value().toString(), entry.targetId());
        assertEquals("MANUAL_CONTROL", entry.details().get("command"));
        assertEquals("ENGAGE", entry.details().get("result"));
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

    // -- onChannels -------------------------------------------------------------

    @Test
    void onChannelsForwardsMappedChannelsAndResetsTheWatchdog() {
        stubDetails(device);
        ManualControlSession session = service.engage(assetId, actor, VisibilityScope.unbounded(), () -> { });

        List<Double> axes = List.of(0.0, -0.12, 1.0, 0.0);
        List<Double> buttons = List.of(0.0, 1.0);
        RcChannels expected = ChannelMap.defaultMap().apply(axes, buttons);

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
                () -> new DefaultManualControlService(null, manualControlPort, auditTrail));
        assertThrows(NullPointerException.class,
                () -> new DefaultManualControlService(assetService, null, auditTrail));
        assertThrows(NullPointerException.class,
                () -> new DefaultManualControlService(assetService, manualControlPort, null));
        assertThrows(NullPointerException.class,
                () -> new DefaultManualControlService(assetService, manualControlPort, auditTrail, null, scheduler));
        assertThrows(NullPointerException.class,
                () -> new DefaultManualControlService(assetService, manualControlPort, auditTrail, clock, null));
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
        final List<Device> engagedDevices = new ArrayList<>();
        final List<RcChannels> sentChannels = new ArrayList<>();
        final List<FakeLink> releasedLinks = new ArrayList<>();

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
            return new FakeLink();
        }

        @Override
        public void send(ManualControlLink link, RcChannels channels) {
            sentChannels.add(channels);
        }

        @Override
        public void release(ManualControlLink link) {
            FakeLink fakeLink = (FakeLink) link;
            fakeLink.active = false;
            releasedLinks.add(fakeLink);
        }

        private static final class FakeLink implements ManualControlLink {
            private boolean active = true;

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
