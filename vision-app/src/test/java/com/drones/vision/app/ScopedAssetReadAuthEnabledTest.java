package com.drones.vision.app;

import com.drones.vision.application.asset.AssetService;
import com.drones.vision.application.asset.AssetSpec;
import com.drones.vision.application.identity.AssignmentService;
import com.drones.vision.application.device.DeviceRegistration;
import com.drones.vision.application.identity.GroupService;
import com.drones.vision.application.identity.GroupSpec;
import com.drones.vision.application.identity.UserService;
import com.drones.vision.application.scope.VisibilityScope;
import com.drones.vision.domain.model.Asset;
import com.drones.vision.domain.model.Capability;
import com.drones.vision.domain.model.CategoryId;
import com.drones.vision.domain.model.Group;
import com.drones.vision.domain.model.Ownership;
import com.drones.vision.domain.model.StreamDescriptor;
import com.drones.vision.domain.model.User;
import com.drones.vision.domain.model.UserId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;

/**
 * docs/U-SCOPE-PLAN.md U-e slice 2, feature 1+2 with {@code vision.auth.enabled=true}: proves the
 * scoped {@code GET /api/assets} read actually bites once auth is on — a MANAGER sees only assets
 * owned by their group subtree, and a PILOT sees only the assets explicitly assigned to them.
 *
 * <p>Uses the seeded {@code manager}/{@code pilot} accounts (both members of the seeded {@code Root}
 * group — {@code AuthSeedRunner}), plus data this test creates through the application services: a
 * second, unrelated group and one asset owned by each group. The security filter chain is applied to
 * MockMvc exactly as {@link AuthEnabledFlowTest} does; the session from login is carried forward via
 * {@link MockHttpSession}.
 *
 * <p><strong>Assertions are membership-based, not exact-size.</strong> A {@code @SpringBootTest}
 * context (and its in-memory repositories) is shared across every test class with identical
 * properties, so absolute asset counts accumulate across methods/classes; asserting "the response
 * contains the in-scope asset and does <em>not</em> contain the out-of-scope one" is the invariant
 * that actually proves scoping and is immune to that accumulation.
 */
@SpringBootTest(properties = {"vision.auth.enabled=true", "vision.publish.enabled=false"})
class ScopedAssetReadAuthEnabledTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private FilterChainProxy springSecurityFilterChain;

    @Autowired
    private GroupService groupService;

    @Autowired
    private UserService userService;

    @Autowired
    private AssetService assetService;

    @Autowired
    private AssignmentService assignmentService;

    private MockMvc mockMvc;
    private Asset rootAsset;
    private Asset otherAsset;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .addFilters(springSecurityFilterChain)
                .build();

        Group root = groupOf("Root");
        Group other = groupService.create(new GroupSpec("Other Division", null), VisibilityScope.unbounded());

        // One asset owned by each group; ownerId is immaterial to group-scope, only groupId matters.
        rootAsset = assetService.create(assetSpec("Root Drone"),
                new Ownership(UserId.random(), root.id()), UserId.random());
        otherAsset = assetService.create(assetSpec("Other Drone"),
                new Ownership(UserId.random(), other.id()), UserId.random());
    }

    @Test
    void managerSeesTheirOwnGroupsAssetButNotAnotherGroups() throws Exception {
        MockHttpSession session = login("manager", "manager");

        mockMvc.perform(get("/api/assets").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$..assetId", hasItem(rootAsset.id().value().toString())))
                .andExpect(jsonPath("$..assetId", not(hasItem(otherAsset.id().value().toString()))));
    }

    @Test
    void pilotSeesOnlyAssignedAssetsNotTheirGroupsOrOthers() throws Exception {
        String otherId = otherAsset.id().value().toString();
        String rootId = rootAsset.id().value().toString();

        // Before assignment, the freshly-created out-of-group asset is invisible to the pilot.
        MockHttpSession before = login("pilot", "pilot");
        mockMvc.perform(get("/api/assets").session(before))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$..assetId", not(hasItem(otherId))));

        // Assign the pilot to the OTHER group's asset — a pilot flies assigned aircraft, not a group subtree.
        assignmentService.assign(pilotId(), otherAsset.id(), VisibilityScope.unbounded());

        MockHttpSession after = login("pilot", "pilot");
        mockMvc.perform(get("/api/assets").session(after))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$..assetId", hasItem(otherId)))
                // the pilot's own group's asset stays invisible — assignment, not group, is the pilot's scope
                .andExpect(jsonPath("$..assetId", not(hasItem(rootId))));
    }

    private Group groupOf(String name) {
        return groupService.list(VisibilityScope.unbounded()).stream()
                .filter(g -> g.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("seeded group not found: " + name));
    }

    private UserId pilotId() {
        return userService.list(VisibilityScope.unbounded()).stream()
                .filter((User u) -> u.username().equals("pilot"))
                .map(User::id)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("seeded pilot not found"));
    }

    private AssetSpec assetSpec(String displayName) {
        DeviceRegistration device = new DeviceRegistration("cam-" + displayName, Set.of(Capability.VIDEO),
                new StreamDescriptor("sim", URI.create("sim://" + displayName.replace(' ', '-')), Map.of()));
        return new AssetSpec(displayName, new CategoryId("drone"), Map.of(), List.of(device));
    }

    private MockHttpSession login(String username, String password) throws Exception {
        MvcResult login = mockMvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return (MockHttpSession) login.getRequest().getSession(false);
    }
}
