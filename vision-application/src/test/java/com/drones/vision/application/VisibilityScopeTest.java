package com.drones.vision.application;

import com.drones.vision.domain.model.Asset;
import com.drones.vision.domain.model.AssetId;
import com.drones.vision.domain.model.CategoryId;
import com.drones.vision.domain.model.DeviceId;
import com.drones.vision.domain.model.GroupId;
import com.drones.vision.domain.model.Ownership;
import com.drones.vision.domain.model.UserId;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VisibilityScopeTest {

    private static final CategoryId DRONE = new CategoryId("drone");

    private static Asset assetIn(GroupId group) {
        return new Asset(AssetId.random(), "drone", DRONE, new Ownership(UserId.random(), group),
                Set.of(DeviceId.random()), Map.of());
    }

    @Test
    void unboundedIncludesEverything() {
        VisibilityScope scope = VisibilityScope.unbounded();

        assertTrue(scope.isUnbounded());
        assertTrue(scope.includes(assetIn(GroupId.random())));
    }

    @Test
    void groupScopeIncludesOnlyAssetsInItsGroups() {
        GroupId inScope = GroupId.random();
        VisibilityScope scope = VisibilityScope.groups(Set.of(inScope));

        assertFalse(scope.isUnbounded());
        assertTrue(scope.includes(assetIn(inScope)));
        assertFalse(scope.includes(assetIn(GroupId.random())));
    }

    @Test
    void emptyGroupScopeIncludesNothing() {
        assertFalse(VisibilityScope.groups(Set.of()).includes(assetIn(GroupId.random())));
    }

    @Test
    void assignedAssetsScopeIncludesOnlyAssignedIds() {
        Asset assigned = assetIn(GroupId.random());
        Asset other = assetIn(GroupId.random());
        VisibilityScope scope = VisibilityScope.assignedAssets(Set.of(assigned.id()));

        assertTrue(scope.includes(assigned));
        assertFalse(scope.includes(other));
    }

    @Test
    void emptyAssignedAssetsScopeIncludesNothing() {
        assertFalse(VisibilityScope.assignedAssets(Set.of()).includes(assetIn(GroupId.random())));
    }

    @Test
    void groupSetIsDefensivelyCopied() {
        Set<GroupId> mutable = new HashSet<>();
        GroupId group = GroupId.random();
        mutable.add(group);
        VisibilityScope scope = VisibilityScope.groups(mutable);

        mutable.clear();

        assertTrue(scope.includes(assetIn(group)));
        assertThrows(UnsupportedOperationException.class, () -> scope.groups().add(GroupId.random()));
    }

    @Test
    void assignedAssetsSetIsDefensivelyCopied() {
        Set<AssetId> mutable = new HashSet<>();
        Asset asset = assetIn(GroupId.random());
        mutable.add(asset.id());
        VisibilityScope scope = VisibilityScope.assignedAssets(mutable);

        mutable.clear();

        assertTrue(scope.includes(asset));
        assertThrows(UnsupportedOperationException.class, () -> scope.assignedAssets().add(AssetId.random()));
    }
}
