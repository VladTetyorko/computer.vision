package com.drones.vision.app;

import com.drones.vision.identity.application.GroupService;
import com.drones.vision.identity.application.UserService;
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

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * docs/plans/done/U-AUTH-PLAN.md wave 3 exit criteria with {@code vision.auth.enabled=true}: an
 * unauthenticated {@code /api/**} call is {@code 401}, one of the seeded users can log in via
 * {@code /api/auth/login}, the session then reflects that identity through {@code /api/auth/me}, and
 * {@code /api/auth/logout} ends it (a subsequent {@code me} is {@code 401} again).
 *
 * <p>Uses the seeded {@code admin}/{@code admin} account — seeded by {@link DevAccountSeeder} against
 * the in-memory repositories this persistence-disabled profile runs (the production seed is now a
 * Flyway migration, {@code storage/persistence}'s {@code db/seed/dev}, which only fires against a real
 * Postgres — see {@link DevAccountSeeder}'s own javadoc). The security filter chain is applied to
 * MockMvc via {@code springSecurityFilterChain}; the session established on login is carried forward
 * via {@link MockHttpSession}.
 */
@SpringBootTest(properties = {"vision.auth.enabled=true", "vision.publish.enabled=false"})
class AuthEnabledFlowTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private FilterChainProxy springSecurityFilterChain;

    @Autowired
    private UserService userService;

    @Autowired
    private GroupService groupService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        DevAccountSeeder.seedIfAbsent(userService, groupService);
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .addFilters(springSecurityFilterChain)
                .build();
    }

    @Test
    void unauthenticatedApiIsRejectedThenLoginMeLogoutFlowWorks() throws Exception {
        // 1. a secured path (and /api/auth/me) is 401 without a session
        mockMvc.perform(get("/api/categories")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());

        // 2. login with a seeded account -> 200 + session
        MvcResult login = mockMvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content("{\"username\":\"admin\",\"password\":\"admin\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("admin"))
                .andExpect(jsonPath("$.topRole").value("ADMIN"))
                .andExpect(jsonPath("$.authEnabled").value(true))
                .andReturn();
        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);

        // 3. me with the session reflects the authenticated user
        mockMvc.perform(get("/api/auth/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("admin"));

        // a formerly-secured path is now reachable with the session
        mockMvc.perform(get("/api/categories").session(session)).andExpect(status().isOk());

        // 4. logout -> 204, session invalidated
        mockMvc.perform(post("/api/auth/logout").session(session)).andExpect(status().isNoContent());

        // 5. me on the invalidated session is 401 again
        mockMvc.perform(get("/api/auth/me").session(session)).andExpect(status().isUnauthorized());
    }

    @Test
    void loginWithWrongPasswordIs401() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content("{\"username\":\"admin\",\"password\":\"not-the-password\"}"))
                .andExpect(status().isUnauthorized());
    }

    /**
     * {@code /hls/**} joins {@code /api/**}/{@code /ws/**} under the secured chain's {@code
     * authenticated()} rule (docs/plans/active/AUTH-ROLES-PLAN.md D10, wave B4) — half of the fix
     * {@code SecurityConfig}'s own javadoc describes. Proven here rather than in {@code
     * HlsProxyControllerTest} (a {@code standaloneSetup} test with no security filter at all) since
     * this is specifically the filter-chain half, not {@code StreamAccess}'s own gate.
     */
    @Test
    void unauthenticatedHlsPathIsRejected() throws Exception {
        mockMvc.perform(get("/hls/" + UUID.randomUUID() + "/index.m3u8")).andExpect(status().isUnauthorized());
    }

    /**
     * The other half of D10: an authenticated caller naming a {@code streamId} that is not currently
     * running is {@code 404}, not a proxied fetch — {@code StreamAccess#requireVisibleForHlsProxy}
     * fails closed before {@code HlsProxyController#proxy} ever contacts the upstream, so this never
     * risks a real network call to an unconfigured mediamtx.
     */
    @Test
    void authenticatedCallerGetsA404ForAStreamIdThatIsNotCurrentlyRunning() throws Exception {
        MvcResult login = mockMvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content("{\"username\":\"admin\",\"password\":\"admin\"}"))
                .andExpect(status().isOk())
                .andReturn();
        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);

        mockMvc.perform(get("/hls/" + UUID.randomUUID() + "/index.m3u8").session(session))
                .andExpect(status().isNotFound());
    }
}
