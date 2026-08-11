package com.drones.vision.app;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * docs/plans/done/RC-CONTROL-PHASE1-PLAN.md §4: with {@code vision.auth.enabled} unset (its default {@code
 * false}), {@code /ws/manual-control} rides the same permit-all chain as everything else — the WS
 * handshake reaches {@code ManualControlWebSocketHandler}'s own handler mapping unauthenticated,
 * exactly like {@link AuthDisabledSecurityTest} proves for {@code /api/**}.
 *
 * <p>A plain {@code GET} (no {@code Upgrade}/{@code Connection} headers, so no real WebSocket
 * handshake happens) is enough to prove the <em>security</em> layer's verdict: it must not be
 * {@code 401}/{@code 403}. What status the WebSocket handler-mapping itself answers a non-upgrade
 * GET with is not this test's concern (Spring's {@code WebSocketHttpRequestHandler} answers a
 * {@code 400} for a request missing the handshake headers — asserted here as the concrete, stable
 * "not blocked by security" signal on this endpoint).
 */
@SpringBootTest(properties = "vision.publish.enabled=false")
class ManualControlSecurityDisabledTest {

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
    void manualControlWebSocketEndpointIsNotBlockedByTheSecurityLayerWhenAuthIsDisabled() throws Exception {
        mockMvc.perform(get("/ws/manual-control"))
                .andExpect(status().is(400)); // rejected by the WS handshake handler, not by security
    }
}
