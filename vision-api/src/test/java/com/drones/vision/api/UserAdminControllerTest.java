package com.drones.vision.api;

import com.drones.vision.application.UserService;
import com.drones.vision.application.UserSpec;
import com.drones.vision.domain.model.GroupId;
import com.drones.vision.domain.model.Membership;
import com.drones.vision.domain.model.Ownership;
import com.drones.vision.domain.model.Role;
import com.drones.vision.domain.model.User;
import com.drones.vision.domain.model.UserId;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Standalone MockMvc tests for {@link UserAdminController} (docs/U-SCOPE-PLAN.md, U-e slice 2 —
 * org-settings user management). Verifies list/create/setEnabled map through to {@link UserService}
 * and that {@code CreateUserRequest} builds a well-formed {@link UserSpec} (memberships + enabled).
 */
class UserAdminControllerTest {

    private final UserService userService = mock(UserService.class);
    private final GroupId groupId = GroupId.random();
    // A CurrentUser built from a plain Ownership resolves to an unbounded scope, so these
    // wiring/status tests are unaffected by the management gate; scope filtering itself is proven
    // in the application-layer and auth-on tests.
    private final CurrentUser currentUser = new CurrentUser(new Ownership(UserId.random(), groupId));

    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new UserAdminController(userService, currentUser))
            .setControllerAdvice(new ApiExceptionHandler())
            .build();

    private User user(String name, Role role) {
        return new User(UserId.random(), name, name, name + "@vision.local", "hash", true,
                List.of(new Membership(groupId, role)));
    }

    @Test
    void listMapsUsersToResponses() throws Exception {
        when(userService.list(any())).thenReturn(List.of(user("manager", Role.MANAGER)));

        mockMvc.perform(get("/api/users"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].username").value("manager"))
                .andExpect(jsonPath("$[0].topRole").value("MANAGER"))
                .andExpect(jsonPath("$[0].memberships[0].role").value("MANAGER"));
    }

    @Test
    void createBuildsSpecFromRequestAndReturns201() throws Exception {
        User created = user("newpilot", Role.PILOT);
        when(userService.create(any(), any())).thenReturn(created);

        String body = "{\"username\":\"newpilot\",\"displayName\":\"New Pilot\",\"email\":\"np@vision.local\","
                + "\"password\":\"secret\",\"enabled\":false,"
                + "\"memberships\":[{\"groupId\":\"" + groupId.value() + "\",\"role\":\"pilot\"}]}";

        mockMvc.perform(post("/api/users").contentType("application/json").content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").value("newpilot"));

        ArgumentCaptor<UserSpec> spec = ArgumentCaptor.forClass(UserSpec.class);
        org.mockito.Mockito.verify(userService).create(spec.capture(), any());
        assertEquals("newpilot", spec.getValue().username());
        assertFalse(spec.getValue().enabled());
        assertEquals(Role.PILOT, spec.getValue().memberships().get(0).role());
        assertEquals(groupId, spec.getValue().memberships().get(0).groupId());
    }

    @Test
    void createWithUnknownRoleIs400() throws Exception {
        String body = "{\"username\":\"x\",\"displayName\":\"X\",\"email\":\"x@vision.local\","
                + "\"password\":\"secret\",\"memberships\":[{\"groupId\":\"" + groupId.value()
                + "\",\"role\":\"superuser\"}]}";
        mockMvc.perform(post("/api/users").contentType("application/json").content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void setEnabledMapsThrough() throws Exception {
        User u = user("pilot", Role.PILOT);
        when(userService.setEnabled(eq(u.id()), eq(false), any())).thenReturn(u);

        mockMvc.perform(post("/api/users/{id}/enabled", u.id().value())
                        .contentType("application/json").content("{\"enabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("pilot"));
    }
}
