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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The prime directive of docs/plans/done/U-AUTH-PLAN.md wave 3, proven: with {@code vision.auth.enabled}
 * unset (its default {@code false}), the Spring Security filter chain permits everything, so a
 * secured-looking {@code /api/**} path succeeds with no authentication and {@code /api/auth/me}
 * reports the dev admin with {@code authEnabled=false} — the exact pre-auth behavior.
 *
 * <p>Unlike the other full-context MockMvc smoke tests in this module (which build MockMvc from the
 * context <em>without</em> the security filter), this one deliberately adds the {@code
 * springSecurityFilterChain} so the permit-all chain is actually exercised.
 */
@SpringBootTest(properties = "vision.publish.enabled=false")
class AuthDisabledSecurityTest {

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
    void securedLookingApiPathSucceedsWithoutAuthenticationWhenAuthIsDisabled() throws Exception {
        mockMvc.perform(get("/api/categories")).andExpect(status().isOk());
    }

    @Test
    void meReportsTheDevAdminWithAuthDisabled() throws Exception {
        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("admin"))
                .andExpect(jsonPath("$.topRole").value("ADMIN"))
                .andExpect(jsonPath("$.authEnabled").value(false));
    }

    @Test
    void loginIsANoOpReportingTheDevAdminWithAuthDisabled() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content("{\"username\":\"whatever\",\"password\":\"whatever\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authEnabled").value(false))
                .andExpect(jsonPath("$.username").value("admin"));
    }
}
