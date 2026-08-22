package com.drones.vision.api.live;

import com.drones.vision.api.security.CurrentUser;
import com.drones.vision.api.security.StreamAccess;
import com.drones.vision.identity.application.scope.ScopeResolver;
import com.drones.vision.identity.domain.port.UserRepositoryPort;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.GroupId;
import com.drones.vision.kernel.Ownership;
import com.drones.vision.kernel.UserId;
import com.drones.vision.perception.application.stream.StreamService;
import com.drones.vision.platform.VisibilityScope;
import com.drones.vision.warehouse.domain.port.AssetRepositoryPort;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The auth-disabled dev principal must still see the live plane.
 *
 * <p>Regression test for the defect LIVE-SCOPE W3 shipped: authorization was re-derived by looking
 * the caller up in the users table, but with {@code vision.auth.enabled=false} — the default for a
 * local run — {@code CurrentUser} is a synthetic admin who was never persisted. The lookup missed,
 * the seam failed closed, and every per-asset topic was silently filtered: no telemetry, no boxes,
 * and no detections at all, because detection demand is derived from who is subscribed. W3's own
 * tests could not catch it — they mock the repository to return real users, so the missing-row path
 * was never exercised.
 */
class LiveAssetAccessDevPrincipalTest {

    private static final long TTL_MILLIS = 50;

    private final AssetId assetId = AssetId.random();
    private final UserId devUserId = UserId.random();

    private LiveAssetAccess accessFor(UserRepositoryPort users) {
        AssetRepositoryPort assets = mock(AssetRepositoryPort.class);
        when(assets.findById(any())).thenReturn(Optional.empty());
        StreamAccess streamAccess = new StreamAccess(mock(StreamService.class), assets,
                new CurrentUser(new Ownership(devUserId, GroupId.random())));
        return new LiveAssetAccess(streamAccess, mock(ScopeResolver.class), users, TTL_MILLIS);
    }

    private UserRepositoryPort noSuchUser() {
        UserRepositoryPort users = mock(UserRepositoryPort.class);
        when(users.findById(any())).thenReturn(Optional.empty());
        return users;
    }

    @Test
    void anUnpersistedDevAdminKeepsItsPerAssetTopics() {
        LiveAssetAccess access = accessFor(noSuchUser());
        String topics = "detections:" + assetId.value() + ",telemetry:" + assetId.value();

        assertEquals(topics, access.filterTopicsParam(devUserId, topics, VisibilityScope.unbounded()),
                "the dev admin has no users-table row by design; filtering its topics silently kills "
                        + "the live plane and detection demand with it");
    }

    @Test
    void anUnpersistedDevAdminStillReceivesDeliveries() {
        LiveAssetAccess access = accessFor(noSuchUser());

        assertTrue(access.deliveryPredicate(devUserId, VisibilityScope.unbounded()).test(assetId));
    }

    @Test
    void anUnresolvableUserWithABoundedScopeStillSeesNothing() {
        LiveAssetAccess access = accessFor(noSuchUser());
        VisibilityScope bounded = VisibilityScope.assignedAssets(Set.of(AssetId.random()));
        String topics = "detections:" + assetId.value();

        assertEquals("", access.filterTopicsParam(devUserId, topics, bounded),
                "a real user deleted mid-connection must still fail closed — the fallback is only for "
                        + "the unbounded principal that has no row by design");
        assertFalse(access.deliveryPredicate(devUserId, bounded).test(assetId));
    }
}
