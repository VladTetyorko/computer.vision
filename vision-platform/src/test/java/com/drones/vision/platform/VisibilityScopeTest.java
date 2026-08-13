package com.drones.vision.platform;

import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.GroupId;
import com.drones.vision.kernel.Ownership;
import com.drones.vision.kernel.UserId;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VisibilityScopeTest {

    private static Ownership ownershipIn(GroupId group) {
        return new Ownership(UserId.random(), group);
    }

    @Test
    void unboundedIncludesEverything() {
        VisibilityScope scope = VisibilityScope.unbounded();

        assertTrue(scope.isUnbounded());
        assertTrue(scope.includes(AssetId.random(), ownershipIn(GroupId.random())));
    }

    @Test
    void groupScopeIncludesOnlyAssetsInItsGroups() {
        GroupId inScope = GroupId.random();
        VisibilityScope scope = VisibilityScope.groups(Set.of(inScope));

        assertFalse(scope.isUnbounded());
        assertTrue(scope.includes(AssetId.random(), ownershipIn(inScope)));
        assertFalse(scope.includes(AssetId.random(), ownershipIn(GroupId.random())));
    }

    @Test
    void emptyGroupScopeIncludesNothing() {
        assertFalse(VisibilityScope.groups(Set.of()).includes(AssetId.random(), ownershipIn(GroupId.random())));
    }

    @Test
    void assignedAssetsScopeIncludesOnlyAssignedIds() {
        AssetId assigned = AssetId.random();
        AssetId other = AssetId.random();
        Ownership ownership = ownershipIn(GroupId.random());
        VisibilityScope scope = VisibilityScope.assignedAssets(Set.of(assigned));

        assertTrue(scope.includes(assigned, ownership));
        assertFalse(scope.includes(other, ownership));
    }

    @Test
    void emptyAssignedAssetsScopeIncludesNothing() {
        assertFalse(VisibilityScope.assignedAssets(Set.of())
                .includes(AssetId.random(), ownershipIn(GroupId.random())));
    }

    @Test
    void groupSetIsDefensivelyCopied() {
        Set<GroupId> mutable = new HashSet<>();
        GroupId group = GroupId.random();
        mutable.add(group);
        VisibilityScope scope = VisibilityScope.groups(mutable);

        mutable.clear();

        assertTrue(scope.includes(AssetId.random(), ownershipIn(group)));
        assertThrows(UnsupportedOperationException.class, () -> scope.groups().add(GroupId.random()));
    }

    @Test
    void assignedAssetsSetIsDefensivelyCopied() {
        Set<AssetId> mutable = new HashSet<>();
        AssetId assetId = AssetId.random();
        mutable.add(assetId);
        VisibilityScope scope = VisibilityScope.assignedAssets(mutable);

        mutable.clear();

        assertTrue(scope.includes(assetId, ownershipIn(GroupId.random())));
        assertThrows(UnsupportedOperationException.class, () -> scope.assignedAssets().add(AssetId.random()));
    }

    // --- management-authority derivation (docs/plans/done/U-SCOPE-PLAN.md, U-e slice 2 cleanup) ---

    @Test
    void canManageOrgAcrossKinds() {
        assertTrue(VisibilityScope.unbounded().canManageOrg());
        assertTrue(VisibilityScope.groups(Set.of(GroupId.random())).canManageOrg());
        assertFalse(VisibilityScope.assignedAssets(Set.of(AssetId.random())).canManageOrg());
        // an empty groups scope is still a manager (GROUPS kind), so it can manage
        assertTrue(VisibilityScope.groups(Set.of()).canManageOrg());
    }

    @Test
    void includesGroupAcrossKinds() {
        GroupId inScope = GroupId.random();
        GroupId outOfScope = GroupId.random();

        assertTrue(VisibilityScope.unbounded().includesGroup(outOfScope));

        VisibilityScope groups = VisibilityScope.groups(Set.of(inScope));
        assertTrue(groups.includesGroup(inScope));
        assertFalse(groups.includesGroup(outOfScope));

        assertFalse(VisibilityScope.assignedAssets(Set.of(AssetId.random())).includesGroup(inScope));
    }
}
