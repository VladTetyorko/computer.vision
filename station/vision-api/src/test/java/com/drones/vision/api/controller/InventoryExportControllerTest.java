package com.drones.vision.api.controller;

import com.drones.vision.api.exception.ApiExceptionHandler;
import com.drones.vision.api.security.CurrentUser;
import com.drones.vision.api.support.InventoryExportService;
import com.drones.vision.kernel.GroupId;
import com.drones.vision.kernel.Ownership;
import com.drones.vision.kernel.UserId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class InventoryExportControllerTest {

    private InventoryExportService inventoryExportService;
    private CurrentUser currentUser;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        inventoryExportService = mock(InventoryExportService.class);
        currentUser = new CurrentUser(new Ownership(UserId.random(), GroupId.random()));
        mockMvc = MockMvcBuilders.standaloneSetup(new InventoryExportController(inventoryExportService, currentUser))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void exportDefaultsToCsvAndReturnsTheBodyAsAnAttachment() throws Exception {
        when(inventoryExportService.toCsv(currentUser.scope())).thenReturn("id,name\n1,Drone\n");

        mockMvc.perform(get("/api/inventory/export"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("inventory.csv")))
                .andExpect(content().string("id,name\n1,Drone\n"));
    }

    @Test
    void exportAcceptsAnExplicitCsvFormat() throws Exception {
        when(inventoryExportService.toCsv(currentUser.scope())).thenReturn("id,name\n");

        mockMvc.perform(get("/api/inventory/export").param("format", "csv"))
                .andExpect(status().isOk());
    }

    @Test
    void exportRejectsAnUnsupportedFormat() throws Exception {
        mockMvc.perform(get("/api/inventory/export").param("format", "xlsx"))
                .andExpect(status().isBadRequest());
    }
}
