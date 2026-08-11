package com.drones.vision.map.application;

import com.drones.vision.map.domain.model.AccessLevel;
import com.drones.vision.kernel.GroupId;
import com.drones.vision.map.domain.model.LayerGrant;
import com.drones.vision.map.domain.model.LayerGrant.SubjectType;
import com.drones.vision.map.domain.model.LayerId;
import com.drones.vision.map.domain.model.LayerKind;
import com.drones.vision.map.domain.model.MapLayer;
import com.drones.vision.kernel.Ownership;
import com.drones.vision.identity.domain.model.Role;
import com.drones.vision.kernel.UserId;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import com.drones.vision.map.application.MapAccessPolicy.Viewer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers every rule row of docs/plans/done/MAP-REWORK-PLAN.md §3's access table, plus combinations.
 */
class MapAccessPolicyTest {

    private final MapAccessPolicy policy = new MapAccessPolicy();

    private static MapLayer layer(LayerKind kind, Ownership ownership, List<LayerGrant> grants) {
        return new MapLayer(LayerId.random(), "layer", kind, ownership, grants, Instant.now());
    }

    // --- rule 1: ADMIN manages everything -----------------------------------

    @Test
    void adminManagesAnyLayerRegardlessOfKindOrOwnership() {
        MapLayer teamLayer = layer(LayerKind.TEAM, new Ownership(UserId.random(), GroupId.random()), List.of());
        Viewer admin = new Viewer(UserId.random(), Set.of(), Role.ADMIN);

        assertTrue(policy.canManage(admin, teamLayer));
        assertTrue(policy.canContribute(admin, teamLayer));
        assertTrue(policy.canView(admin, teamLayer));
    }

    // --- rule 2: COP -------------------------------------------------------

    @Test
    void copIsViewableByAnyoneAndManagedByManagerOrAdmin() {
        MapLayer cop = layer(LayerKind.COP, new Ownership(UserId.random(), GroupId.random()), List.of());

        Viewer pilot = new Viewer(UserId.random(), Set.of(), Role.PILOT);
        assertTrue(policy.canView(pilot, cop));
        assertFalse(policy.canContribute(pilot, cop));

        Viewer manager = new Viewer(UserId.random(), Set.of(), Role.MANAGER);
        assertTrue(policy.canManage(manager, cop));

        Viewer admin = new Viewer(UserId.random(), Set.of(), Role.ADMIN);
        assertTrue(policy.canManage(admin, cop));
    }

    @Test
    void managerManagesCopEvenWithoutAnyGroupOverlap() {
        MapLayer cop = layer(LayerKind.COP, new Ownership(UserId.random(), GroupId.random()), List.of());
        Viewer manager = new Viewer(UserId.random(), Set.of(GroupId.random()), Role.MANAGER);

        assertEquals(AccessLevel.MANAGE, policy.accessTo(manager, cop));
    }

    // --- rule 3: creator manages their own layer ----------------------------

    @Test
    void layerCreatorAlwaysManagesItRegardlessOfKindOrRole() {
        UserId owner = UserId.random();
        MapLayer personal = layer(LayerKind.PERSONAL, new Ownership(owner, GroupId.random()), List.of());
        Viewer creatorViewer = new Viewer(owner, Set.of(), Role.PILOT);

        assertTrue(policy.canManage(creatorViewer, personal));
    }

    // --- rule 4: TEAM member contributes -------------------------------------

    @Test
    void teamMemberContributesToTheirOwnTeamLayer() {
        GroupId teamGroup = GroupId.random();
        MapLayer team = layer(LayerKind.TEAM, new Ownership(UserId.random(), teamGroup), List.of());
        Viewer member = new Viewer(UserId.random(), Set.of(teamGroup), Role.PILOT);

        assertEquals(AccessLevel.CONTRIBUTE, policy.accessTo(member, team));
    }

    @Test
    void nonMemberHasNoAccessToATeamLayer() {
        MapLayer team = layer(LayerKind.TEAM, new Ownership(UserId.random(), GroupId.random()), List.of());
        Viewer outsider = new Viewer(UserId.random(), Set.of(GroupId.random()), Role.PILOT);

        assertNull(policy.accessTo(outsider, team));
        assertFalse(policy.canView(outsider, team));
    }

    // --- rule 5: MANAGER manages a layer owned by their scope group ---------

    @Test
    void managerManagesATeamLayerOwnedByAGroupInTheirScope() {
        GroupId teamGroup = GroupId.random();
        MapLayer team = layer(LayerKind.TEAM, new Ownership(UserId.random(), teamGroup), List.of());
        Viewer manager = new Viewer(UserId.random(), Set.of(teamGroup), Role.MANAGER);

        assertEquals(AccessLevel.MANAGE, policy.accessTo(manager, team));
    }

    @Test
    void managerWithoutThatGroupInScopeOnlyGetsWhateverElseApplies() {
        GroupId teamGroup = GroupId.random();
        MapLayer team = layer(LayerKind.TEAM, new Ownership(UserId.random(), teamGroup), List.of());
        Viewer manager = new Viewer(UserId.random(), Set.of(GroupId.random()), Role.MANAGER);

        assertNull(policy.accessTo(manager, team));
    }

    // --- rules 6/7: explicit grants ------------------------------------------

    @Test
    void explicitUserGrantAppliesRegardlessOfKindOrGroup() {
        UserId grantee = UserId.random();
        MapLayer personal = layer(LayerKind.PERSONAL, new Ownership(UserId.random(), GroupId.random()),
                List.of(new LayerGrant(SubjectType.USER, grantee.value(), AccessLevel.CONTRIBUTE)));
        Viewer viewer = new Viewer(grantee, Set.of(), Role.PILOT);

        assertEquals(AccessLevel.CONTRIBUTE, policy.accessTo(viewer, personal));
    }

    @Test
    void explicitGroupGrantAppliesWhenViewerIsAMember() {
        GroupId grantedGroup = GroupId.random();
        MapLayer personal = layer(LayerKind.PERSONAL, new Ownership(UserId.random(), GroupId.random()),
                List.of(new LayerGrant(SubjectType.GROUP, grantedGroup.value(), AccessLevel.MANAGE)));
        Viewer viewer = new Viewer(UserId.random(), Set.of(grantedGroup), Role.PILOT);

        assertEquals(AccessLevel.MANAGE, policy.accessTo(viewer, personal));
    }

    @Test
    void explicitGroupGrantDoesNotApplyToANonMember() {
        MapLayer personal = layer(LayerKind.PERSONAL, new Ownership(UserId.random(), GroupId.random()),
                List.of(new LayerGrant(SubjectType.GROUP, GroupId.random().value(), AccessLevel.MANAGE)));
        Viewer viewer = new Viewer(UserId.random(), Set.of(), Role.PILOT);

        assertNull(policy.accessTo(viewer, personal));
    }

    // --- max-of-rules combination ---------------------------------------------

    @Test
    void accessIsTheMaxAcrossEveryApplicableRule() {
        GroupId teamGroup = GroupId.random();
        UserId userId = UserId.random();
        // A TEAM layer this viewer is a plain member of (rule 4 -> CONTRIBUTE) but also has an
        // explicit MANAGE grant on (rule 6) -> the max, MANAGE, wins.
        MapLayer team = layer(LayerKind.TEAM, new Ownership(UserId.random(), teamGroup),
                List.of(new LayerGrant(SubjectType.USER, userId.value(), AccessLevel.MANAGE)));
        Viewer viewer = new Viewer(userId, Set.of(teamGroup), Role.PILOT);

        assertEquals(AccessLevel.MANAGE, policy.accessTo(viewer, team));
    }

    // --- pilot COP + own team, not another team -------------------------------

    @Test
    void pilotSeesCopAndOwnTeamLayerButNotAnotherTeams() {
        GroupId ownGroup = GroupId.random();
        MapLayer cop = layer(LayerKind.COP, new Ownership(UserId.random(), GroupId.random()), List.of());
        MapLayer ownTeam = layer(LayerKind.TEAM, new Ownership(UserId.random(), ownGroup), List.of());
        MapLayer otherTeam = layer(LayerKind.TEAM, new Ownership(UserId.random(), GroupId.random()), List.of());
        Viewer pilot = new Viewer(UserId.random(), Set.of(ownGroup), Role.PILOT);

        assertTrue(policy.canView(pilot, cop));
        assertTrue(policy.canView(pilot, ownTeam));
        assertFalse(policy.canView(pilot, otherTeam));
    }

    // --- accessTo/canView/canContribute/canManage null-arg validation ----------

    @Test
    void accessorsRejectNullArguments() {
        MapLayer layer = layer(LayerKind.PERSONAL, new Ownership(UserId.random(), GroupId.random()), List.of());
        Viewer viewer = new Viewer(UserId.random(), Set.of(), Role.PILOT);

        assertThrows(NullPointerException.class, () -> policy.accessTo(null, layer));
        assertThrows(NullPointerException.class, () -> policy.accessTo(viewer, null));
    }

    @Test
    void viewerConstructorValidatesAndDefensivelyCopiesGroups() {
        assertThrows(NullPointerException.class, () -> new Viewer(null, Set.of(), Role.PILOT));
        assertThrows(NullPointerException.class, () -> new Viewer(UserId.random(), Set.of(), null));

        Viewer withNullGroups = new Viewer(UserId.random(), null, Role.PILOT);
        assertTrue(withNullGroups.groups().isEmpty());
    }
}
