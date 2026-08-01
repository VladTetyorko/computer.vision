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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * docs/RC-CONTROL-PHASE1-PLAN.md §4: with {@code vision.auth.enabled=true}, the secured chain's
 * {@code authenticated()} rule now also covers {@code /ws/**} (added alongside {@code /api/**} —
 * see {@code SecurityConfig#securedFilterChain}), so an unauthenticated handshake attempt at {@code
 * /ws/manual-control} is rejected with {@code 401} before it ever reaches {@code
 * ManualControlHandshakeInterceptor}/the WebSocket upgrade machinery — mirroring {@link
 * AuthEnabledFlowTest}'s own login-session-carried style for {@code /api/**}.
 */
@SpringBootTest(properties = {"vision.auth.enabled=true", "vision.publish.enabled=false"})
class ManualControlSecurityEnabledTest {

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
    void unauthenticatedManualControlHandshakeIsRejected401BeforeUpgrade() throws Exception {
        mockMvc.perform(get("/ws/manual-control")).andExpect(status().isUnauthorized());
    }

    @Test
    void anAuthenticatedSessionIsNoLongerRejectedBySecurityOnTheManualControlEndpoint() throws Exception {
        MvcResult login = mockMvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content("{\"username\":\"admin\",\"password\":\"admin\"}"))
                .andExpect(status().isOk())
                .andReturn();
        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);

        // Past the security gate now -- rejected by the WS handshake handler for lacking Upgrade
        // headers (a plain GET, not a real handshake), not by security (401/403 would mean the
        // /ws/** authenticated() rule is somehow still blocking an authenticated session).
        mockMvc.perform(get("/ws/manual-control").session(session)).andExpect(status().is(400));
    }
}
