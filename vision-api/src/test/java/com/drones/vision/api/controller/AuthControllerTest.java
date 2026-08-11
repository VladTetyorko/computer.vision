package com.drones.vision.api.controller;

import com.drones.vision.identity.application.AuthService;
import com.drones.vision.identity.application.GroupService;
import com.drones.vision.identity.domain.model.Group;
import com.drones.vision.kernel.GroupId;
import com.drones.vision.identity.domain.model.Membership;
import com.drones.vision.kernel.Ownership;
import com.drones.vision.identity.domain.model.Role;
import com.drones.vision.identity.domain.model.User;
import com.drones.vision.kernel.UserId;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Optional;
import com.drones.vision.api.security.CurrentUser;
import com.drones.vision.api.security.SessionAuthenticator;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Standalone MockMvc tests for {@link AuthController}'s two modes (docs/plans/done/U-AUTH-PLAN.md, wave 3),
 * with the {@link SessionAuthenticator}/{@link AuthService}/{@link GroupService} seams mocked — no
 * Spring Security, no live context, mirroring every other controller test in this module.
 */
class AuthControllerTest {

    private final AuthService authService = mock(AuthService.class);
    private final GroupService groupService = mock(GroupService.class);
    private final SessionAuthenticator sessionAuthenticator = mock(SessionAuthenticator.class);

    private final UserId userId = UserId.random();
    private final GroupId groupId = GroupId.random();
    private final CurrentUser currentUser = new CurrentUser(new Ownership(userId, groupId));

    private MockMvc mockMvc(boolean authEnabled) {
        return MockMvcBuilders
                .standaloneSetup(new AuthController(authService, groupService, currentUser,
                        sessionAuthenticator, authEnabled))
                .build();
    }

    private User seededUser() {
        return new User(userId, "manager", "Manager", "manager@vision.local", "hash", true,
                List.of(new Membership(groupId, Role.MANAGER)));
    }

    @Test
    void meReturnsTheDevAdminWhenAuthDisabled() throws Exception {
        mockMvc(false).perform(get("/api/auth/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("admin"))
                .andExpect(jsonPath("$.topRole").value("ADMIN"))
                .andExpect(jsonPath("$.authEnabled").value(false));
    }

    @Test
    void loginIsANoOpDevAdminWhenAuthDisabled() throws Exception {
        mockMvc(false).perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content("{\"username\":\"x\",\"password\":\"y\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authEnabled").value(false));
    }

    @Test
    void logoutIs204WhenAuthDisabled() throws Exception {
        mockMvc(false).perform(post("/api/auth/logout")).andExpect(status().isNoContent());
    }

    @Test
    void meResolvesTheAuthenticatedUserWhenAuthEnabled() throws Exception {
        when(authService.find(userId)).thenReturn(Optional.of(seededUser()));
        when(groupService.list(any())).thenReturn(List.of(new Group(groupId, "Root", null)));

        mockMvc(true).perform(get("/api/auth/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("manager"))
                .andExpect(jsonPath("$.topRole").value("MANAGER"))
                .andExpect(jsonPath("$.authEnabled").value(true))
                .andExpect(jsonPath("$.memberships[0].groupName").value("Root"))
                .andExpect(jsonPath("$.memberships[0].role").value("MANAGER"));
    }

    @Test
    void loginSuccessReturnsMeResponseWhenAuthEnabled() throws Exception {
        when(sessionAuthenticator.login(eq("manager"), eq("secret"), any(), any()))
                .thenReturn(Optional.of(seededUser()));
        when(groupService.list(any())).thenReturn(List.of(new Group(groupId, "Root", null)));

        mockMvc(true).perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content("{\"username\":\"manager\",\"password\":\"secret\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("manager"))
                .andExpect(jsonPath("$.authEnabled").value(true));
    }

    @Test
    void loginFailureIs401WhenAuthEnabled() throws Exception {
        when(sessionAuthenticator.login(any(), any(), any(), any())).thenReturn(Optional.empty());

        mockMvc(true).perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content("{\"username\":\"manager\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized());
    }
}
