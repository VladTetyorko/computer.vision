package com.drones.vision.api.controller;

import com.drones.vision.api.exception.ApiExceptionHandler;
import com.drones.vision.application.identity.GroupService;
import com.drones.vision.application.identity.GroupSpec;
import com.drones.vision.domain.model.Group;
import com.drones.vision.domain.model.GroupId;
import com.drones.vision.domain.model.Ownership;
import com.drones.vision.domain.model.UserId;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import com.drones.vision.api.security.CurrentUser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Standalone MockMvc tests for {@link GroupAdminController} (docs/plans/done/U-SCOPE-PLAN.md, U-e slice 2 —
 * org-settings group management): list/create map through to {@link GroupService}, and {@code
 * CreateGroupRequest} parses an optional parent id.
 */
class GroupAdminControllerTest {

    private final GroupService groupService = mock(GroupService.class);
    // Unbounded scope from a plain Ownership — these tests assert wiring/status, not scope filtering.
    private final CurrentUser currentUser = new CurrentUser(new Ownership(UserId.random(), GroupId.random()));

    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new GroupAdminController(groupService, currentUser))
            .setControllerAdvice(new ApiExceptionHandler())
            .build();

    @Test
    void listMapsGroupsToResponses() throws Exception {
        Group root = new Group(GroupId.random(), "Root", null);
        Group child = new Group(GroupId.random(), "Child", root.id());
        when(groupService.list(any())).thenReturn(List.of(root, child));

        mockMvc.perform(get("/api/groups"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Root"))
                .andExpect(jsonPath("$[1].parentGroupId").value(root.id().value().toString()));
    }

    @Test
    void createRootGroupReturns201AndPassesNullParent() throws Exception {
        Group created = new Group(GroupId.random(), "New", null);
        when(groupService.create(any(), any())).thenReturn(created);

        mockMvc.perform(post("/api/groups").contentType("application/json").content("{\"name\":\"New\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("New"));

        ArgumentCaptor<GroupSpec> spec = ArgumentCaptor.forClass(GroupSpec.class);
        verify(groupService).create(spec.capture(), any());
        assertEquals("New", spec.getValue().name());
        assertNull(spec.getValue().parentGroupId());
    }

    @Test
    void createChildGroupParsesParentId() throws Exception {
        GroupId parent = GroupId.random();
        Group created = new Group(GroupId.random(), "Child", parent);
        when(groupService.create(any(), any())).thenReturn(created);

        mockMvc.perform(post("/api/groups").contentType("application/json")
                        .content("{\"name\":\"Child\",\"parentGroupId\":\"" + parent.value() + "\"}"))
                .andExpect(status().isCreated());

        ArgumentCaptor<GroupSpec> spec = ArgumentCaptor.forClass(GroupSpec.class);
        verify(groupService).create(spec.capture(), any());
        assertEquals(parent, spec.getValue().parentGroupId());
    }
}
