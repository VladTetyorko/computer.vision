package com.drones.vision.api;

import com.drones.vision.application.FlightCommandService;
import com.drones.vision.domain.model.AssetId;
import com.drones.vision.domain.model.CommandResult;
import com.drones.vision.domain.model.GroupId;
import com.drones.vision.domain.model.Ownership;
import com.drones.vision.domain.model.UserId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.NoSuchElementException;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class FlightCommandControllerTest {

    private FlightCommandService flightCommandService;
    private MockMvc mockMvc;

    private final UserId ownerId = UserId.random();
    private final Ownership ownership = new Ownership(ownerId, GroupId.random());
    private final CurrentUser currentUser = new CurrentUser(ownership);

    @BeforeEach
    void setUp() {
        flightCommandService = mock(FlightCommandService.class);

        mockMvc = MockMvcBuilders
                .standaloneSetup(new FlightCommandController(flightCommandService, currentUser))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void returnHomeReturns202WithAcceptedOnSuccess() throws Exception {
        AssetId assetId = AssetId.random();
        when(flightCommandService.returnToHome(eq(assetId), eq(ownerId))).thenReturn(CommandResult.ACCEPTED);

        mockMvc.perform(post("/api/assets/{id}/return-home", assetId.value()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.result").value("ACCEPTED"));

        verify(flightCommandService).returnToHome(assetId, ownerId);
    }

    @Test
    void returnHomeReturns202WithNoAckWhenTheAircraftNeverAcknowledged() throws Exception {
        AssetId assetId = AssetId.random();
        when(flightCommandService.returnToHome(eq(assetId), eq(ownerId))).thenReturn(CommandResult.NO_ACK);

        mockMvc.perform(post("/api/assets/{id}/return-home", assetId.value()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.result").value("NO_ACK"));
    }

    @Test
    void returnHomeReturns404ForAnUnknownAsset() throws Exception {
        AssetId assetId = AssetId.random();
        when(flightCommandService.returnToHome(eq(assetId), eq(ownerId)))
                .thenThrow(new NoSuchElementException("Unknown asset: " + assetId.value()));

        mockMvc.perform(post("/api/assets/{id}/return-home", assetId.value()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }

    @Test
    void returnHomeReturns400ForABadUuidAndNeverTouchesTheService() throws Exception {
        mockMvc.perform(post("/api/assets/{id}/return-home", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));
    }

    @Test
    void returnHomeReturns409WhenTheAssetHasNoCommandableDevice() throws Exception {
        AssetId assetId = AssetId.random();
        when(flightCommandService.returnToHome(eq(assetId), eq(ownerId)))
                .thenThrow(new IllegalStateException("Asset " + assetId.value() + " has no active MAVLink "
                        + "telemetry device to command"));

        mockMvc.perform(post("/api/assets/{id}/return-home", assetId.value()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("CONFLICT"))
                .andExpect(jsonPath("$.message").value("Asset " + assetId.value()
                        + " has no active MAVLink telemetry device to command"));
    }

    @Test
    void returnHomeReturns409WhenTheAircraftRefusesTheCommand() throws Exception {
        AssetId assetId = AssetId.random();
        when(flightCommandService.returnToHome(eq(assetId), eq(ownerId)))
                .thenThrow(new IllegalStateException("Vehicle sysid 1 refused return-to-home: MAV_RESULT_DENIED"));

        mockMvc.perform(post("/api/assets/{id}/return-home", assetId.value()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("CONFLICT"))
                .andExpect(jsonPath("$.message").value("Vehicle sysid 1 refused return-to-home: MAV_RESULT_DENIED"));
    }
}
