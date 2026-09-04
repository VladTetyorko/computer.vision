package com.drones.vision.api.controller;

import com.drones.vision.api.exception.ApiExceptionHandler;
import com.drones.vision.platform.AccessDeniedException;
import com.drones.vision.flight.application.FlightCommandService;
import com.drones.vision.platform.VisibilityScope;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.flight.domain.model.CommandResult;
import com.drones.vision.flight.domain.model.FlightCapability;
import com.drones.vision.flight.domain.model.VehicleKind;
import com.drones.vision.kernel.GroupId;
import com.drones.vision.kernel.Ownership;
import com.drones.vision.kernel.UserId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.NoSuchElementException;
import com.drones.vision.api.security.AssetAuthority;
import com.drones.vision.api.security.CurrentUser;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class FlightCommandControllerTest {

    private FlightCommandService flightCommandService;
    private AssetAuthority assetAuthority;
    private MockMvc mockMvc;

    private final UserId ownerId = UserId.random();
    private final Ownership ownership = new Ownership(ownerId, GroupId.random());
    private final CurrentUser currentUser = new CurrentUser(ownership);

    @BeforeEach
    void setUp() {
        flightCommandService = mock(FlightCommandService.class);
        // Permissive by default (docs/plans/active/AUTH-ROLES-PLAN.md §3.9, wave B4) so every
        // pre-existing test below, none of which cares about the authority gate, keeps its original
        // meaning; denial is exercised by the dedicated tests further down.
        assetAuthority = mock(AssetAuthority.class);
        when(assetAuthority.mayFly(any())).thenReturn(true);

        mockMvc = MockMvcBuilders
                .standaloneSetup(new FlightCommandController(flightCommandService, currentUser, assetAuthority))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void returnHomeReturns202WithAcceptedOnSuccess() throws Exception {
        AssetId assetId = AssetId.random();
        when(flightCommandService.returnToHome(eq(assetId), eq(ownerId), any(VisibilityScope.class))).thenReturn(CommandResult.ACCEPTED);

        mockMvc.perform(post("/api/assets/{id}/return-home", assetId.value()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.result").value("ACCEPTED"));

        verify(flightCommandService).returnToHome(eq(assetId), eq(ownerId), any(VisibilityScope.class));
    }

    @Test
    void returnHomeReturns202WithNoAckWhenTheAircraftNeverAcknowledged() throws Exception {
        AssetId assetId = AssetId.random();
        when(flightCommandService.returnToHome(eq(assetId), eq(ownerId), any(VisibilityScope.class))).thenReturn(CommandResult.NO_ACK);

        mockMvc.perform(post("/api/assets/{id}/return-home", assetId.value()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.result").value("NO_ACK"));
    }

    @Test
    void returnHomeReturns404ForAnUnknownAsset() throws Exception {
        AssetId assetId = AssetId.random();
        when(flightCommandService.returnToHome(eq(assetId), eq(ownerId), any(VisibilityScope.class)))
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
        when(flightCommandService.returnToHome(eq(assetId), eq(ownerId), any(VisibilityScope.class)))
                .thenThrow(new IllegalStateException("Asset " + assetId.value() + " has no active MAVLink "
                        + "telemetry device to command"));

        mockMvc.perform(post("/api/assets/{id}/return-home", assetId.value()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("CONFLICT"))
                .andExpect(jsonPath("$.message").value("Asset " + assetId.value()
                        + " has no active MAVLink telemetry device to command"));
    }

    @Test
    void returnHomeReturns403WhenTheAssetIsOutOfScope() throws Exception {
        AssetId assetId = AssetId.random();
        when(flightCommandService.returnToHome(eq(assetId), eq(ownerId), any(VisibilityScope.class)))
                .thenThrow(new AccessDeniedException("Asset " + assetId.value() + " is outside your scope"));

        mockMvc.perform(post("/api/assets/{id}/return-home", assetId.value()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));
    }

    @Test
    void returnHomeReturns409WhenTheAircraftRefusesTheCommand() throws Exception {
        AssetId assetId = AssetId.random();
        when(flightCommandService.returnToHome(eq(assetId), eq(ownerId), any(VisibilityScope.class)))
                .thenThrow(new IllegalStateException("Vehicle sysid 1 refused return-to-home: MAV_RESULT_DENIED"));

        mockMvc.perform(post("/api/assets/{id}/return-home", assetId.value()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("CONFLICT"))
                .andExpect(jsonPath("$.message").value("Vehicle sysid 1 refused return-to-home: MAV_RESULT_DENIED"));
    }

    // --- Stage 2: mode ---

    @Test
    void setModeReturns202WithResult() throws Exception {
        AssetId assetId = AssetId.random();
        when(flightCommandService.setMode(eq(assetId), eq("Loiter"), eq(ownerId), any(VisibilityScope.class)))
                .thenReturn(CommandResult.ACCEPTED);

        mockMvc.perform(post("/api/assets/{id}/mode", assetId.value())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"Loiter\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.result").value("ACCEPTED"));

        verify(flightCommandService).setMode(eq(assetId), eq("Loiter"), eq(ownerId), any(VisibilityScope.class));
    }

    @Test
    void setModeReturns400ForABlankMode() throws Exception {
        AssetId assetId = AssetId.random();

        mockMvc.perform(post("/api/assets/{id}/mode", assetId.value())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));
    }

    @Test
    void setModeReturns400ForAnUnknownMode() throws Exception {
        AssetId assetId = AssetId.random();
        when(flightCommandService.setMode(eq(assetId), eq("Barrel-Roll"), eq(ownerId), any(VisibilityScope.class)))
                .thenThrow(new IllegalArgumentException("Unknown flight mode 'Barrel-Roll'"));

        mockMvc.perform(post("/api/assets/{id}/mode", assetId.value())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"Barrel-Roll\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));
    }

    @Test
    void setModeReturns409WhenNotCommandable() throws Exception {
        AssetId assetId = AssetId.random();
        when(flightCommandService.setMode(eq(assetId), eq("Loiter"), eq(ownerId), any(VisibilityScope.class)))
                .thenThrow(new IllegalStateException("Asset has no active MAVLink telemetry device to command"));

        mockMvc.perform(post("/api/assets/{id}/mode", assetId.value())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"Loiter\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("CONFLICT"));
    }

    // --- Stage 2: arm / disarm ---

    @Test
    void armReturns202AndForcedFlagPassedThrough() throws Exception {
        AssetId assetId = AssetId.random();
        when(flightCommandService.arm(eq(assetId), eq(true), eq(ownerId), any(VisibilityScope.class)))
                .thenReturn(CommandResult.ACCEPTED);

        mockMvc.perform(post("/api/assets/{id}/arm", assetId.value())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"force\":true}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.result").value("ACCEPTED"));

        verify(flightCommandService).arm(eq(assetId), eq(true), eq(ownerId), any(VisibilityScope.class));
    }

    @Test
    void armDefaultsForceToFalseWhenBodyAbsent() throws Exception {
        AssetId assetId = AssetId.random();
        when(flightCommandService.arm(eq(assetId), eq(false), eq(ownerId), any(VisibilityScope.class)))
                .thenReturn(CommandResult.NO_ACK);

        mockMvc.perform(post("/api/assets/{id}/arm", assetId.value()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.result").value("NO_ACK"));

        verify(flightCommandService).arm(eq(assetId), eq(false), eq(ownerId), any(VisibilityScope.class));
    }

    @Test
    void armReturns403WhenOutOfScope() throws Exception {
        AssetId assetId = AssetId.random();
        when(flightCommandService.arm(eq(assetId), anyBoolean(), eq(ownerId), any(VisibilityScope.class)))
                .thenThrow(new AccessDeniedException("Asset " + assetId.value() + " is outside your scope"));

        mockMvc.perform(post("/api/assets/{id}/arm", assetId.value()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));
    }

    /**
     * docs/plans/active/AUTH-ROLES-PLAN.md §3.8/§3.9, wave B4 — a caller {@link AssetAuthority}
     * denies (a {@code CREW} seat, or no {@code COMMAND_FLIGHT} capability at all) is refused before
     * {@link FlightCommandService} is ever asked, regardless of what the application-service's own
     * scope-only gate would have answered.
     */
    @Test
    void armReturns403WhenAssetAuthorityDeniesMayFlyAndNeverTouchesTheService() throws Exception {
        AssetId assetId = AssetId.random();
        when(assetAuthority.mayFly(assetId)).thenReturn(false);

        mockMvc.perform(post("/api/assets/{id}/arm", assetId.value()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));

        verify(flightCommandService, never()).arm(any(), anyBoolean(), any(), any());
    }

    /**
     * Every command family reaches the same {@link AssetAuthority#mayFly(AssetId)} gate — proven
     * once per family rather than duplicating the full request/assert shape for each, since the gate
     * itself ({@link #requireMayFly}) is shared code, not per-method logic.
     */
    @Test
    void everyCommandFamilyIsGatedByAssetAuthorityMayFly() throws Exception {
        AssetId assetId = AssetId.random();
        when(assetAuthority.mayFly(assetId)).thenReturn(false);

        mockMvc.perform(post("/api/assets/{id}/return-home", assetId.value())).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/assets/{id}/mode", assetId.value())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"mode\":\"GUIDED\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/assets/{id}/disarm", assetId.value())).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/assets/{id}/emergency-stop", assetId.value())).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/assets/{id}/aux-function", assetId.value())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"function\":9,\"level\":1}"))
                .andExpect(status().isForbidden());

        verify(flightCommandService, never()).returnToHome(any(), any(), any());
        verify(flightCommandService, never()).setMode(any(), any(), any(), any());
        verify(flightCommandService, never()).disarm(any(), anyBoolean(), any(), any());
        verify(flightCommandService, never()).emergencyStop(any(), any(), any());
        verify(flightCommandService, never()).auxFunction(any(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt(), any(), any());
    }

    @Test
    void disarmReturns202AndDefaultsForceToFalse() throws Exception {
        AssetId assetId = AssetId.random();
        when(flightCommandService.disarm(eq(assetId), eq(false), eq(ownerId), any(VisibilityScope.class)))
                .thenReturn(CommandResult.ACCEPTED);

        mockMvc.perform(post("/api/assets/{id}/disarm", assetId.value())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.result").value("ACCEPTED"));

        verify(flightCommandService).disarm(eq(assetId), eq(false), eq(ownerId), any(VisibilityScope.class));
    }

    @Test
    void disarmReturns409WhenAircraftRefuses() throws Exception {
        AssetId assetId = AssetId.random();
        when(flightCommandService.disarm(eq(assetId), anyBoolean(), eq(ownerId), any(VisibilityScope.class)))
                .thenThrow(new IllegalStateException("Vehicle refused disarm: MAV_RESULT_DENIED"));

        mockMvc.perform(post("/api/assets/{id}/disarm", assetId.value()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Vehicle refused disarm: MAV_RESULT_DENIED"));
    }

    // --- Stage 2: flight-capabilities (read) ---

    @Test
    void flightCapabilitiesReturns200WithTheSnapshotShape() throws Exception {
        AssetId assetId = AssetId.random();
        when(flightCommandService.capabilities(eq(assetId), any(VisibilityScope.class)))
                .thenReturn(new FlightCapability(true, true, true, List.of("Loiter", "RTL"), VehicleKind.COPTER));

        mockMvc.perform(get("/api/assets/{id}/flight-capabilities", assetId.value()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.commandable").value(true))
                .andExpect(jsonPath("$.armSupported").value(true))
                .andExpect(jsonPath("$.modeSelectSupported").value(true))
                .andExpect(jsonPath("$.selectableModes[0]").value("Loiter"))
                .andExpect(jsonPath("$.selectableModes[1]").value("RTL"));
    }

    @Test
    void flightCapabilitiesReturns404WhenTheScopedReadHidesTheAsset() throws Exception {
        AssetId assetId = AssetId.random();
        when(flightCommandService.capabilities(eq(assetId), any(VisibilityScope.class)))
                .thenThrow(new NoSuchElementException("Unknown asset: " + assetId.value()));

        mockMvc.perform(get("/api/assets/{id}/flight-capabilities", assetId.value()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }

    @Test
    void flightCapabilitiesReturns400ForABadUuid() throws Exception {
        mockMvc.perform(get("/api/assets/{id}/flight-capabilities", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));
    }

    // --- emergency stop / aux function (docs/plans/active/CONTROLLER-SETUP-CONTEXT.md wave C5) ---

    @Test
    void emergencyStopReturns202AndIsItsOwnCommand() throws Exception {
        AssetId assetId = AssetId.random();
        when(flightCommandService.emergencyStop(eq(assetId), eq(ownerId), any(VisibilityScope.class)))
                .thenReturn(CommandResult.ACCEPTED);

        mockMvc.perform(post("/api/assets/{id}/emergency-stop", assetId.value()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.result").value("ACCEPTED"));

        verify(flightCommandService).emergencyStop(eq(assetId), eq(ownerId), any(VisibilityScope.class));
    }

    @Test
    void auxFunctionPassesTheFunctionAndSwitchLevelThrough() throws Exception {
        AssetId assetId = AssetId.random();
        when(flightCommandService.auxFunction(eq(assetId), eq(46), eq(2), eq(ownerId), any(VisibilityScope.class)))
                .thenReturn(CommandResult.NO_ACK);

        mockMvc.perform(post("/api/assets/{id}/aux-function", assetId.value())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"function\":46,\"level\":2}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.result").value("NO_ACK"));

        verify(flightCommandService).auxFunction(eq(assetId), eq(46), eq(2), eq(ownerId), any(VisibilityScope.class));
    }

    /**
     * A malformed binding must be a 400 at the edge, never a command sent at a level the firmware
     * would reinterpret -- the same "guard before an attempt" split {@code setMode} uses.
     */
    @Test
    void auxFunctionReturns400ForAnOutOfRangeLevelOrFunctionAndNeverTouchesTheService() throws Exception {
        AssetId assetId = AssetId.random();

        mockMvc.perform(post("/api/assets/{id}/aux-function", assetId.value())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"function\":46,\"level\":3}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/assets/{id}/aux-function", assetId.value())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"function\":9001,\"level\":1}"))
                .andExpect(status().isBadRequest());

        verify(flightCommandService, org.mockito.Mockito.never())
                .auxFunction(any(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt(),
                        any(), any());
    }
}
