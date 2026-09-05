package com.drones.vision.flight.application.seat;

import com.drones.vision.flight.domain.model.Seat;
import com.drones.vision.flight.domain.model.SeatKind;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.UserId;
import com.drones.vision.platform.AuditAction;
import com.drones.vision.platform.AuditEntry;
import com.drones.vision.platform.AuditTargetType;
import com.drones.vision.platform.AuditTrailPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link DefaultSeatService}. {@code auditTrail} is a hand-rolled in-memory fake
 * (mirrors {@code DefaultManualControlServiceTest}'s own {@code FakeAuditTrailPort}) and {@code
 * clock} is a mutable test clock, so lazy expiry is proven by advancing time deterministically —
 * no real sleeps anywhere in this suite.
 */
class DefaultSeatServiceTest {

    private static final long TTL_MS = 15_000L;

    private FakeAuditTrailPort auditTrail;
    private MutableClock clock;
    private SeatService seatService;

    private final AssetId assetId = AssetId.random();
    private final UserId pilot = UserId.random();
    private final UserId crew = UserId.random();

    @BeforeEach
    void setUp() {
        auditTrail = new FakeAuditTrailPort();
        clock = new MutableClock(Instant.parse("2026-09-04T10:12:03Z"));
        seatService = new DefaultSeatService(clock, auditTrail, TTL_MS);
    }

    // -- construction -----------------------------------------------------------------------

    @Test
    void constructorRejectsNullClock() {
        assertThrows(NullPointerException.class, () -> new DefaultSeatService(null, auditTrail, TTL_MS));
    }

    @Test
    void constructorRejectsNullAuditTrail() {
        assertThrows(NullPointerException.class, () -> new DefaultSeatService(clock, null, TTL_MS));
    }

    @Test
    void constructorRejectsNonPositiveTtl() {
        assertThrows(IllegalArgumentException.class, () -> new DefaultSeatService(clock, auditTrail, 0L));
        assertThrows(IllegalArgumentException.class, () -> new DefaultSeatService(clock, auditTrail, -1L));
    }

    // -- holder: free / never-seen -----------------------------------------------------------

    @Test
    void holderIsEmptyForAnAssetNeverTouched() {
        assertTrue(seatService.holder(assetId, SeatKind.FLIGHT).isEmpty());
        assertTrue(seatService.holder(assetId, SeatKind.CAMERA).isEmpty());
    }

    // -- take: free seat ----------------------------------------------------------------------

    @Test
    void takeFreeSeatStampsAcquiredAndExpiresAndAudits() {
        Seat seat = seatService.take(assetId, SeatKind.FLIGHT, pilot);

        assertEquals(assetId, seat.assetId());
        assertEquals(SeatKind.FLIGHT, seat.kind());
        assertEquals(pilot, seat.holder());
        assertEquals(clock.instant(), seat.acquiredAt());
        assertEquals(clock.instant().plusMillis(TTL_MS), seat.expiresAt());
        assertEquals(Optional.of(seat), seatService.holder(assetId, SeatKind.FLIGHT));

        assertEquals(1, auditTrail.recorded.size());
        AuditEntry entry = auditTrail.recorded.get(0);
        assertEquals(pilot, entry.actor());
        assertEquals(AuditAction.UPDATED, entry.action());
        assertEquals(AuditTargetType.ASSET, entry.targetType());
        assertEquals(assetId.value().toString(), entry.targetId());
        assertEquals(assetId.value().toString(), entry.details().get("assetId"));
        assertEquals("SEAT", entry.details().get("command"));
        assertEquals("TAKE:FLIGHT", entry.details().get("result"));
    }

    @Test
    void takeFreeCameraSeatAudits() {
        seatService.take(assetId, SeatKind.CAMERA, crew);

        assertEquals(1, auditTrail.recorded.size());
        assertEquals("TAKE:CAMERA", auditTrail.recorded.get(0).details().get("result"));
    }

    // -- take: renewal by current holder -------------------------------------------------------

    @Test
    void renewalByHolderRestampsExpiresAtKeepsAcquiredAtAndIsNotAudited() {
        Seat first = seatService.take(assetId, SeatKind.FLIGHT, pilot);
        clock.advance(Duration.ofSeconds(5));

        Seat renewed = seatService.take(assetId, SeatKind.FLIGHT, pilot);

        assertEquals(first.acquiredAt(), renewed.acquiredAt(), "acquiredAt must not move on a renewal");
        assertEquals(clock.instant().plusMillis(TTL_MS), renewed.expiresAt());
        assertTrue(renewed.expiresAt().isAfter(first.expiresAt()), "expiresAt must have moved forward");
        assertEquals(1, auditTrail.recorded.size(), "a renewal must not add a second audit entry");
    }

    // -- take: held by someone else -------------------------------------------------------------

    @Test
    void takeByNonHolderWhenHeldThrowsNamingTheHolder() {
        seatService.take(assetId, SeatKind.FLIGHT, pilot);
        auditTrail.recorded.clear();

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> seatService.take(assetId, SeatKind.FLIGHT, crew));

        assertTrue(thrown.getMessage().contains(pilot.value().toString()),
                "message must name the current holder: " + thrown.getMessage());
        assertTrue(auditTrail.recorded.isEmpty(), "a denied take must not be audited by the seat service itself");
        // the original holder is unaffected
        assertEquals(Optional.of(pilot), seatService.holder(assetId, SeatKind.FLIGHT).map(Seat::holder));
    }

    // -- lazy expiry ------------------------------------------------------------------------

    @Test
    void holderReadsEmptyOncePastTtl() {
        seatService.take(assetId, SeatKind.FLIGHT, pilot);
        clock.advance(Duration.ofMillis(TTL_MS));

        assertTrue(seatService.holder(assetId, SeatKind.FLIGHT).isEmpty());
    }

    @Test
    void holderStillPresentOneMillisecondBeforeExpiry() {
        seatService.take(assetId, SeatKind.FLIGHT, pilot);
        clock.advance(Duration.ofMillis(TTL_MS - 1));

        assertTrue(seatService.holder(assetId, SeatKind.FLIGHT).isPresent());
    }

    @Test
    void takeSucceedsForAnotherUserOnceTheHolderHasLazilyExpired() {
        seatService.take(assetId, SeatKind.FLIGHT, pilot);
        clock.advance(Duration.ofMillis(TTL_MS));
        auditTrail.recorded.clear();

        Seat seat = seatService.take(assetId, SeatKind.FLIGHT, crew);

        assertEquals(crew, seat.holder());
        assertEquals(clock.instant(), seat.acquiredAt(), "a take after expiry is a fresh hold, not a renewal");
        assertEquals(1, auditTrail.recorded.size(), "the fresh take must be audited");
        assertEquals("TAKE:FLIGHT", auditTrail.recorded.get(0).details().get("result"));
    }

    @Test
    void lazyExpiryItselfWritesNoAuditEntry() {
        seatService.take(assetId, SeatKind.FLIGHT, pilot);
        auditTrail.recorded.clear();
        clock.advance(Duration.ofMillis(TTL_MS));

        seatService.holder(assetId, SeatKind.FLIGHT); // the read that observes the expiry

        assertTrue(auditTrail.recorded.isEmpty());
    }

    // -- preempt: rule 3, unconditional --------------------------------------------------------

    @Test
    void preemptCameraTakesItFromTheCurrentHolderUnconditionallyAndAudits() {
        seatService.take(assetId, SeatKind.CAMERA, crew);
        auditTrail.recorded.clear();

        Seat seat = seatService.preempt(assetId, SeatKind.CAMERA, pilot);

        assertEquals(pilot, seat.holder());
        assertEquals(clock.instant(), seat.acquiredAt(), "preempt always starts a fresh hold");
        assertEquals(Optional.of(pilot), seatService.holder(assetId, SeatKind.CAMERA).map(Seat::holder));
        assertEquals(1, auditTrail.recorded.size());
        assertEquals("PREEMPT:CAMERA", auditTrail.recorded.get(0).details().get("result"));
        assertEquals(pilot, auditTrail.recorded.get(0).actor());
    }

    @Test
    void preemptCameraFiresOnPreemptedListenersSynchronously() {
        seatService.take(assetId, SeatKind.CAMERA, crew);
        AtomicInteger fired = new AtomicInteger();
        seatService.onPreempted(assetId, SeatKind.CAMERA, fired::incrementAndGet);

        seatService.preempt(assetId, SeatKind.CAMERA, pilot);

        assertEquals(1, fired.get(), "the listener must have run synchronously inside the preempt call");
    }

    @Test
    void preemptDoesNotThrowEvenWhenTheActorAlreadyHoldsTheSeat() {
        seatService.take(assetId, SeatKind.CAMERA, pilot);

        Seat seat = seatService.preempt(assetId, SeatKind.CAMERA, pilot);

        assertEquals(pilot, seat.holder());
    }

    @Test
    void preemptWorksOnAnyKindBecauseAuthorizationIsTheCallersJob() {
        // preempt() itself does not special-case FLIGHT vs CAMERA -- the caller (the enforcement
        // layer, not this service) is the one who only ever calls it for CAMERA in practice.
        seatService.take(assetId, SeatKind.FLIGHT, crew);

        Seat seat = seatService.preempt(assetId, SeatKind.FLIGHT, pilot);

        assertEquals(pilot, seat.holder());
    }

    @Test
    void onPreemptedListenerNotRegisteredForThatKindIsNeverCalled() {
        AtomicInteger fired = new AtomicInteger();
        seatService.onPreempted(assetId, SeatKind.FLIGHT, fired::incrementAndGet);

        seatService.preempt(assetId, SeatKind.CAMERA, pilot);

        assertEquals(0, fired.get());
    }

    // -- release: rule "releases only if held by actor", idempotent ---------------------------

    @Test
    void releaseByHolderClearsTheSeatAndAudits() {
        seatService.take(assetId, SeatKind.FLIGHT, pilot);
        auditTrail.recorded.clear();

        seatService.release(assetId, SeatKind.FLIGHT, pilot);

        assertTrue(seatService.holder(assetId, SeatKind.FLIGHT).isEmpty());
        assertEquals(1, auditTrail.recorded.size());
        assertEquals("RELEASE:FLIGHT", auditTrail.recorded.get(0).details().get("result"));
        assertEquals(pilot, auditTrail.recorded.get(0).actor());
    }

    @Test
    void releaseByNonHolderIsANoOp() {
        seatService.take(assetId, SeatKind.FLIGHT, pilot);
        auditTrail.recorded.clear();

        seatService.release(assetId, SeatKind.FLIGHT, crew);

        assertEquals(Optional.of(pilot), seatService.holder(assetId, SeatKind.FLIGHT).map(Seat::holder));
        assertTrue(auditTrail.recorded.isEmpty());
    }

    @Test
    void releaseOfAFreeSeatIsANoOp() {
        seatService.release(assetId, SeatKind.CAMERA, pilot);

        assertTrue(auditTrail.recorded.isEmpty());
        assertTrue(seatService.holder(assetId, SeatKind.CAMERA).isEmpty());
    }

    @Test
    void releaseIsIdempotent() {
        seatService.take(assetId, SeatKind.FLIGHT, pilot);
        seatService.release(assetId, SeatKind.FLIGHT, pilot);
        auditTrail.recorded.clear();

        seatService.release(assetId, SeatKind.FLIGHT, pilot); // second call: nothing left to release

        assertTrue(auditTrail.recorded.isEmpty());
    }

    @Test
    void releaseDoesNotFireOnPreemptedListeners() {
        seatService.take(assetId, SeatKind.FLIGHT, pilot);
        AtomicInteger fired = new AtomicInteger();
        seatService.onPreempted(assetId, SeatKind.FLIGHT, fired::incrementAndGet);

        seatService.release(assetId, SeatKind.FLIGHT, pilot);

        assertEquals(0, fired.get());
    }

    // -- forceRelease: rule 4, manager path, no actor to audit ---------------------------------

    @Test
    void forceReleaseClearsAHeldSeatAndFiresTheListenerSynchronously() {
        seatService.take(assetId, SeatKind.FLIGHT, pilot);
        AtomicInteger fired = new AtomicInteger();
        seatService.onPreempted(assetId, SeatKind.FLIGHT, fired::incrementAndGet);

        seatService.forceRelease(assetId, SeatKind.FLIGHT);

        assertTrue(seatService.holder(assetId, SeatKind.FLIGHT).isEmpty());
        assertEquals(1, fired.get(), "the RC-release hook must run synchronously inside forceRelease");
    }

    @Test
    void forceReleaseWritesNoAuditEntryBecauseItHasNoActorToAttributeItTo() {
        seatService.take(assetId, SeatKind.FLIGHT, pilot);
        auditTrail.recorded.clear();

        seatService.forceRelease(assetId, SeatKind.FLIGHT);

        assertTrue(auditTrail.recorded.isEmpty());
    }

    @Test
    void forceReleaseOfAFreeSeatIsANoOpAndDoesNotFireTheListener() {
        AtomicInteger fired = new AtomicInteger();
        seatService.onPreempted(assetId, SeatKind.FLIGHT, fired::incrementAndGet);

        seatService.forceRelease(assetId, SeatKind.FLIGHT);

        assertEquals(0, fired.get());
    }

    @Test
    void forceReleaseIsIdempotent() {
        seatService.take(assetId, SeatKind.FLIGHT, pilot);
        AtomicInteger fired = new AtomicInteger();
        seatService.onPreempted(assetId, SeatKind.FLIGHT, fired::incrementAndGet);

        seatService.forceRelease(assetId, SeatKind.FLIGHT);
        seatService.forceRelease(assetId, SeatKind.FLIGHT); // second call: already free

        assertEquals(1, fired.get(), "the listener must not fire a second time for an already-free seat");
    }

    @Test
    void forceReleaseDoesNotDisturbAnExpiredThenRetakenSeat() {
        seatService.take(assetId, SeatKind.FLIGHT, pilot);
        clock.advance(Duration.ofMillis(TTL_MS));
        seatService.take(assetId, SeatKind.FLIGHT, crew); // fresh hold after lazy expiry
        AtomicInteger fired = new AtomicInteger();
        seatService.onPreempted(assetId, SeatKind.FLIGHT, fired::incrementAndGet);

        seatService.forceRelease(assetId, SeatKind.FLIGHT);

        assertTrue(seatService.holder(assetId, SeatKind.FLIGHT).isEmpty());
        assertEquals(1, fired.get());
    }

    // -- the two seats are independent ---------------------------------------------------------

    @Test
    void flightAndCameraSeatsOnTheSameAssetAreIndependent() {
        seatService.take(assetId, SeatKind.FLIGHT, pilot);
        seatService.take(assetId, SeatKind.CAMERA, crew);

        assertEquals(Optional.of(pilot), seatService.holder(assetId, SeatKind.FLIGHT).map(Seat::holder));
        assertEquals(Optional.of(crew), seatService.holder(assetId, SeatKind.CAMERA).map(Seat::holder));

        seatService.release(assetId, SeatKind.FLIGHT, pilot);

        assertTrue(seatService.holder(assetId, SeatKind.FLIGHT).isEmpty());
        assertEquals(Optional.of(crew), seatService.holder(assetId, SeatKind.CAMERA).map(Seat::holder),
                "releasing FLIGHT must not disturb CAMERA");
    }

    @Test
    void takeArgumentsMustNotBeNull() {
        assertThrows(NullPointerException.class, () -> seatService.take(null, SeatKind.FLIGHT, pilot));
        assertThrows(NullPointerException.class, () -> seatService.take(assetId, null, pilot));
        assertThrows(NullPointerException.class, () -> seatService.take(assetId, SeatKind.FLIGHT, null));
    }

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
}
