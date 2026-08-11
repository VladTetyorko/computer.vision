package com.drones.vision.api.controller;

import com.drones.vision.platform.AuditAction;
import com.drones.vision.platform.AuditEntry;
import com.drones.vision.platform.AuditTargetType;
import com.drones.vision.identity.application.ActivityService;
import com.drones.vision.kernel.Ownership;
import com.drones.vision.kernel.UserId;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import com.drones.vision.api.security.CurrentUser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Standalone MockMvc tests for {@link ActivityController} (docs/plans/done/U-SCOPE-PLAN.md, U-e slice 2,
 * feature 7): the acting user's own audit entries, with the {@code limit} default/cap/floor logic.
 */
class ActivityControllerTest {

    private final ActivityService activityService = mock(ActivityService.class);
    private final UserId actor = UserId.random();
    private final CurrentUser currentUser =
            new CurrentUser(new Ownership(actor, com.drones.vision.kernel.GroupId.random()));

    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new ActivityController(activityService, currentUser))
            .build();

    @Test
    void returnsTheCallersActivityMappedToResponses() throws Exception {
        AuditEntry entry = AuditEntry.of(actor, AuditAction.CREATED, AuditTargetType.ASSET, "asset-1", "created it");
        when(activityService.myActivity(eq(actor), eq(50))).thenReturn(List.of(entry));

        mockMvc.perform(get("/api/me/activity"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].action").value("CREATED"))
                .andExpect(jsonPath("$[0].summary").value("created it"))
                .andExpect(jsonPath("$[0].actor").value(actor.value().toString()));
    }

    @Test
    void limitDefaultsTo50() throws Exception {
        when(activityService.myActivity(eq(actor), eq(50))).thenReturn(List.of());
        mockMvc.perform(get("/api/me/activity")).andExpect(status().isOk());
        verify(activityService).myActivity(eq(actor), eq(50));
    }

    @Test
    void limitIsCappedAt500() throws Exception {
        when(activityService.myActivity(eq(actor), eq(500))).thenReturn(List.of());
        mockMvc.perform(get("/api/me/activity").param("limit", "100000")).andExpect(status().isOk());
        verify(activityService).myActivity(eq(actor), eq(500));
    }

    @Test
    void nonPositiveLimitIsClampedToOne() throws Exception {
        ArgumentCaptor<Integer> limit = ArgumentCaptor.forClass(Integer.class);
        when(activityService.myActivity(eq(actor), limit.capture())).thenReturn(List.of());
        mockMvc.perform(get("/api/me/activity").param("limit", "0")).andExpect(status().isOk());
        assertEquals(1, limit.getValue());
    }
}
