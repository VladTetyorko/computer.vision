package com.drones.vision.app;

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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * docs/U-SCOPE-PLAN.md U-e slice 2 — deferred slice-2 cleanup, with {@code vision.auth.enabled=true}:
 * proves the ADMIN/MANAGER management gate and the ≤-own-scope grant rule actually bite once auth is
 * on. Uses the seeded {@code admin}/{@code manager}/{@code pilot} accounts (all members of the seeded
 * {@code Root} group — {@code AuthSeedRunner}). The security filter chain is applied to MockMvc
 * exactly as {@link ScopedAssetReadAuthEnabledTest} does; the session from login is carried forward
 * via {@link MockHttpSession}.
 */
@SpringBootTest(properties = {"vision.auth.enabled=true", "vision.publish.enabled=false"})
class OrgManagementAuthEnabledTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private FilterChainProxy springSecurityFilterChain;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .addFilters(springSecurityFilterChain)
                .build();
    }

    @Test
    void managerCannotGrantAdminWhenCreatingAUser() throws Exception {
        MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content("{\"username\":\"manager\",\"password\":\"manager\"}"))
                .andExpect(status().isOk())
                .andReturn();
        MockHttpSession session = (MockHttpSession) loginResult.getRequest().getSession(false);
        String managerGroupId = groupIdFrom(loginResult.getResponse().getContentAsString());

        String body = "{\"username\":\"escalated\",\"displayName\":\"Escalated\","
                + "\"email\":\"escalated@vision.local\",\"password\":\"secret\","
                + "\"memberships\":[{\"groupId\":\"" + managerGroupId + "\",\"role\":\"admin\"}]}";

        mockMvc.perform(post("/api/users").session(session)
                        .contentType("application/json").content(body))
                .andExpect(status().isForbidden());
    }

    @Test
    void pilotCannotCreateAGroup() throws Exception {
        MockHttpSession session = login("pilot", "pilot");

        mockMvc.perform(post("/api/groups").session(session)
                        .contentType("application/json").content("{\"name\":\"pilots-group\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminMayCreateARootGroup() throws Exception {
        MockHttpSession session = login("admin", "admin");

        mockMvc.perform(post("/api/groups").session(session)
                        .contentType("application/json").content("{\"name\":\"Admin Division\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    void managerCannotCreateARootGroup() throws Exception {
        MockHttpSession session = login("manager", "manager");

        mockMvc.perform(post("/api/groups").session(session)
                        .contentType("application/json").content("{\"name\":\"Manager Root\"}"))
                .andExpect(status().isForbidden());
    }

    /** Extracts the first membership {@code groupId} from a {@code MeResponse} JSON body. */
    private static String groupIdFrom(String meJson) {
        int idx = meJson.indexOf("\"groupId\":\"");
        String after = meJson.substring(idx + "\"groupId\":\"".length());
        return after.substring(0, after.indexOf('"'));
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
