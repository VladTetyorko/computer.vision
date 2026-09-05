package com.drones.vision.api.security;

import com.drones.vision.api.dto.SeatsResponse;
import com.drones.vision.api.support.SeatSupport;
import com.drones.vision.flight.application.seat.DefaultSeatService;
import com.drones.vision.flight.application.seat.SeatService;
import com.drones.vision.flight.domain.model.SeatKind;
import com.drones.vision.identity.domain.model.Role;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.GroupId;
import com.drones.vision.kernel.Ownership;
import com.drones.vision.kernel.UserId;
import com.drones.vision.map.application.MapAccessPolicy;
import com.drones.vision.platform.AccessDeniedException;
import com.drones.vision.platform.Authority;
import com.drones.vision.platform.AuditTrailPort;
import com.drones.vision.platform.VisibilityScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.NoSuchElementException;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Test matrix for the one seat-enforcement collaborator (docs/plans/active/CREW-CONTROL-PLAN.md
 * &sect;3.2/&sect;3.3/&sect;3.8, wave W2) — the flag-off pass-through, and each of the five rules
 * against a real {@link DefaultSeatService} (not mocked: its own take/preempt/forceRelease
 * semantics are exactly what {@link SeatAccess} composes).
 */
class SeatAccessTest {

    private static final long TTL_MS = 15_000L;

    private final AssetId assetId = AssetId.random();
    private final UserId actor = UserId.random();
    private final UserId other = UserId.random();
    private final Ownership ownership = new Ownership(actor, GroupId.random());

    private SeatService seatService;
    private AssetAuthority assetAuthority;
    private SeatSupport seatSupport;
    private CurrentUser currentUser;

    @BeforeEach
    void setUp() {
        seatService =
                new DefaultSeatService(Clock.fixed(Instant.now(), ZoneOffset.UTC), mock(AuditTrailPort.class), TTL_MS);
        assetAuthority = mock(AssetAuthority.class);
        when(assetAuthority.mayFly(assetId)).thenReturn(true);
        when(assetAuthority.mayOperateCamera(assetId)).thenReturn(true);
        seatSupport = mock(SeatSupport.class);
        currentUser = new CurrentUser(ownership);
    }

    private SeatAccess seatAccess(boolean enabled) {
        return new SeatAccess(seatService, assetAuthority, currentUser, seatSupport,
                new SeatAccessSettings(enabled, TTL_MS));
    }

    // ---- §3.8: disabled is a pass-through ----

    @Test
    void disabledRequireFlightSeatNeverTakesTheSeat() {
        seatAccess(false).requireFlightSeat(assetId);

        assertTrue(seatService.holder(assetId, SeatKind.FLIGHT).isEmpty());
    }

    @Test
    void disabledRequireCameraSeatNeverTakesTheSeat() {
        seatAccess(false).requireCameraSeat(assetId);

        assertTrue(seatService.holder(assetId, SeatKind.CAMERA).isEmpty());
    }

    @Test
    void disabledReleaseSeatIsANoOpEvenWhenHeldByAnother() {
        seatService.take(assetId, SeatKind.FLIGHT, other);

        seatAccess(false).releaseSeat(assetId, SeatKind.FLIGHT);

        assertEquals(other, seatService.holder(assetId, SeatKind.FLIGHT).orElseThrow().holder());
    }

    @Test
    void disabledSeatsReportsBothFreeWithEveryMayFlagTrue() {
        SeatsResponse response = seatAccess(false).seats(assetId);

        assertNull(response.flight().holderUserId());
        assertNull(response.camera().holderUserId());
        assertTrue(response.mayTakeFlight());
        assertTrue(response.mayTakeCamera());
        assertTrue(response.mayForceSeat());
    }

    @Test
    void disabledTakeSeatReturnsSeatsUnchanged() {
        SeatsResponse response = seatAccess(false).takeSeat(assetId, SeatKind.FLIGHT, true);

        assertNull(response.flight().holderUserId());
        assertTrue(seatService.holder(assetId, SeatKind.FLIGHT).isEmpty());
    }

    // ---- rule 2: free means free, but only for someone with authority ----

    @Test
    void requireFlightSeatDeniedWhenCallerMayNotFly() {
        when(assetAuthority.mayFly(assetId)).thenReturn(false);

        assertThrows(AccessDeniedException.class, () -> seatAccess(true).requireFlightSeat(assetId));
        assertTrue(seatService.holder(assetId, SeatKind.FLIGHT).isEmpty());
    }

    @Test
    void requireCameraSeatDeniedWhenCallerMayNotOperateCamera() {
        when(assetAuthority.mayOperateCamera(assetId)).thenReturn(false);

        assertThrows(AccessDeniedException.class, () -> seatAccess(true).requireCameraSeat(assetId));
        assertTrue(seatService.holder(assetId, SeatKind.CAMERA).isEmpty());
    }

    @Test
    void requireFlightSeatTakesTheFreeSeatWhenAuthorized() {
        seatAccess(true).requireFlightSeat(assetId);

        assertEquals(actor, seatService.holder(assetId, SeatKind.FLIGHT).orElseThrow().holder());
    }

    // ---- rule 3: the FLIGHT-seat holder always wins the CAMERA seat ----

    @Test
    void requireCameraSeatNeverConflictsForTheFlightSeatHolderAndPreemptsAnyPriorHolder() {
        seatService.take(assetId, SeatKind.FLIGHT, actor);
        seatService.take(assetId, SeatKind.CAMERA, other);
        // The caller has no standing to take the camera on their own -- rule 3 must bypass this
        // entirely rather than deny.
        when(assetAuthority.mayOperateCamera(assetId)).thenReturn(false);

        seatAccess(true).requireCameraSeat(assetId);

        assertEquals(actor, seatService.holder(assetId, SeatKind.CAMERA).orElseThrow().holder());
    }

    @Test
    void takeSeatCameraNeverConflictsForTheFlightSeatHolder() {
        seatService.take(assetId, SeatKind.FLIGHT, actor);
        seatService.take(assetId, SeatKind.CAMERA, other);

        SeatsResponse response = seatAccess(true).takeSeat(assetId, SeatKind.CAMERA, false);

        assertEquals(actor.value().toString(), response.camera().holderUserId());
    }

    // ---- rule 4: only a manager forces a seat ----

    @Test
    void takeSeatWithForceDisplacesAnotherHolderWhenCallerMayForce() {
        seatService.take(assetId, SeatKind.FLIGHT, other);
        when(assetAuthority.mayForceSeat(assetId)).thenReturn(true);

        SeatsResponse response = seatAccess(true).takeSeat(assetId, SeatKind.FLIGHT, true);

        assertEquals(actor.value().toString(), response.flight().holderUserId());
        verify(seatSupport).auditForce(actor, assetId, SeatKind.FLIGHT);
    }

    @Test
    void takeSeatWithForceIsIgnoredWhenCallerMayNotForceAndConflicts() {
        seatService.take(assetId, SeatKind.FLIGHT, other);
        when(assetAuthority.mayForceSeat(assetId)).thenReturn(false);

        assertThrows(IllegalStateException.class, () -> seatAccess(true).takeSeat(assetId, SeatKind.FLIGHT, true));
        assertEquals(other, seatService.holder(assetId, SeatKind.FLIGHT).orElseThrow().holder());
    }

    @Test
    void releaseSeatByManagerDisplacesAnotherHolder() {
        seatService.take(assetId, SeatKind.FLIGHT, other);
        when(assetAuthority.mayForceSeat(assetId)).thenReturn(true);

        seatAccess(true).releaseSeat(assetId, SeatKind.FLIGHT);

        assertTrue(seatService.holder(assetId, SeatKind.FLIGHT).isEmpty());
        verify(seatSupport).auditForce(actor, assetId, SeatKind.FLIGHT);
    }

    @Test
    void releaseSeatDeniedWhenHeldByAnotherAndCallerMayNotForce() {
        seatService.take(assetId, SeatKind.FLIGHT, other);
        when(assetAuthority.mayForceSeat(assetId)).thenReturn(false);

        assertThrows(AccessDeniedException.class, () -> seatAccess(true).releaseSeat(assetId, SeatKind.FLIGHT));
        assertEquals(other, seatService.holder(assetId, SeatKind.FLIGHT).orElseThrow().holder());
    }

    // ---- idempotent release ----

    @Test
    void releaseSeatOfOwnHoldReleasesIt() {
        seatService.take(assetId, SeatKind.FLIGHT, actor);

        seatAccess(true).releaseSeat(assetId, SeatKind.FLIGHT);

        assertTrue(seatService.holder(assetId, SeatKind.FLIGHT).isEmpty());
    }

    @Test
    void releaseSeatOfAFreeSeatIsANoOp() {
        seatAccess(true).releaseSeat(assetId, SeatKind.FLIGHT);

        assertTrue(seatService.holder(assetId, SeatKind.FLIGHT).isEmpty());
    }

    // ---- conflict message + DENIED:SEAT_HELD audit ----

    @Test
    void requireFlightSeatConflictMessageMatchesTheFrozenWireShapeAndAuditsDenied() {
        seatService.take(assetId, SeatKind.FLIGHT, other);
        when(seatSupport.displayNameOrId(other)).thenReturn("Anna Kovalenko");

        IllegalStateException thrown =
                assertThrows(IllegalStateException.class, () -> seatAccess(true).requireFlightSeat(assetId));

        assertEquals("Asset " + assetId.value() + " flight seat is held by Anna Kovalenko", thrown.getMessage());
        verify(seatSupport).auditDenied(actor, assetId, SeatKind.FLIGHT);
    }

    // ---- the explicit-actor overload (ManualControlWebSocketHandler) ----

    @Test
    void explicitActorRequireFlightSeatTakesTheSeatForThatActorEvenWhenNotTheAmbientCurrentUser() {
        UserId explicitActor = UserId.random(); // deliberately not `actor` (the ambient CurrentUser's id)

        seatAccess(true).requireFlightSeat(explicitActor, assetId);

        assertEquals(explicitActor, seatService.holder(assetId, SeatKind.FLIGHT).orElseThrow().holder());
    }

    @Test
    void explicitActorRequireFlightSeatStillConflictsWhenHeldByAnother() {
        seatService.take(assetId, SeatKind.FLIGHT, other);

        assertThrows(IllegalStateException.class, () -> seatAccess(true).requireFlightSeat(actor, assetId));
    }

    // ---- requireVisibleAsset (unconditional -- not gated by §3.8) ----

    @Test
    void requireVisibleAssetThrowsForAnUnknownAsset() {
        when(seatSupport.ownershipOf(assetId)).thenThrow(new NoSuchElementException("Unknown asset"));

        assertThrows(NoSuchElementException.class, () -> seatAccess(false).requireVisibleAsset(assetId));
    }

    @Test
    void requireVisibleAssetThrowsForAnOutOfScopeAsset() {
        Ownership otherOwnership = new Ownership(UserId.random(), GroupId.random());
        when(seatSupport.ownershipOf(assetId)).thenReturn(otherOwnership);
        CurrentUser scoped = new CurrentUser(
                new PrincipalResolverFixedScope(actor, ownership, VisibilityScope.assignedAssets(Set.of())));
        SeatAccess restricted =
                new SeatAccess(seatService, assetAuthority, scoped, seatSupport, new SeatAccessSettings(false, TTL_MS));

        assertThrows(NoSuchElementException.class, () -> restricted.requireVisibleAsset(assetId));
    }

    @Test
    void requireVisibleAssetPassesWhenInScope() {
        when(seatSupport.ownershipOf(assetId)).thenReturn(ownership);

        seatAccess(false).requireVisibleAsset(assetId); // unbounded scope, no exception
    }

    /**
     * A minimal {@link PrincipalResolver} answering with a fixed actor/ownership/scope, for the
     * out-of-scope test above — the same idiom {@code AssetStreamControllerTest}/{@code
     * StreamControllerTest} establish for their own PILOT/MANAGER-scope tests.
     */
    private static final class PrincipalResolverFixedScope implements PrincipalResolver {
        private final UserId userId;
        private final Ownership ownership;
        private final VisibilityScope scope;

        PrincipalResolverFixedScope(UserId userId, Ownership ownership, VisibilityScope scope) {
            this.userId = userId;
            this.ownership = ownership;
            this.scope = scope;
        }

        @Override
        public UserId userId() {
            return userId;
        }

        @Override
        public Ownership ownership() {
            return ownership;
        }

        @Override
        public VisibilityScope scope() {
            return scope;
        }

        @Override
        public MapAccessPolicy.Viewer viewer() {
            throw new UnsupportedOperationException("SeatAccess never calls viewer()");
        }

        @Override
        public Role role() {
            throw new UnsupportedOperationException("SeatAccess never calls role()");
        }

        @Override
        public Authority authority() {
            throw new UnsupportedOperationException("SeatAccess never calls authority()");
        }
    }
}
