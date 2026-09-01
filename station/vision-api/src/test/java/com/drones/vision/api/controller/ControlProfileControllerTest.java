package com.drones.vision.api.controller;

import com.drones.vision.api.exception.ApiExceptionHandler;
import com.drones.vision.api.security.CurrentUser;
import com.drones.vision.api.support.AuxFunctionCatalog;
import com.drones.vision.flight.application.ControlProfileService;
import com.drones.vision.flight.domain.model.ActionBinding;
import com.drones.vision.flight.domain.model.ActionMap;
import com.drones.vision.flight.domain.model.ChannelMap;
import com.drones.vision.flight.domain.model.ControlAction;
import com.drones.vision.flight.domain.model.ControlProfile;
import com.drones.vision.flight.domain.model.ControlProfileId;
import com.drones.vision.flight.domain.model.OwnedControlProfile;
import com.drones.vision.flight.domain.model.TransmitterView;
import com.drones.vision.flight.domain.model.VehicleKind;
import com.drones.vision.kernel.GroupId;
import com.drones.vision.kernel.Ownership;
import com.drones.vision.kernel.UserId;
import com.drones.vision.platform.AccessDeniedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ControlProfileControllerTest {

    private static final Instant SAVED_AT = Instant.parse("2026-08-24T12:00:00Z");

    private ControlProfileService controlProfileService;
    private MockMvc mockMvc;

    private final UserId ownerId = UserId.random();
    private final CurrentUser currentUser = new CurrentUser(new Ownership(ownerId, GroupId.random()));

    @BeforeEach
    void setUp() {
        controlProfileService = mock(ControlProfileService.class);
        when(controlProfileService.builtIns()).thenReturn(List.of(ControlProfile.forKind(VehicleKind.COPTER),
                ControlProfile.forKind(VehicleKind.ROVER)));
        when(controlProfileService.saved(ownerId)).thenReturn(List.of());

        mockMvc = MockMvcBuilders
                .standaloneSetup(new ControlProfileController(controlProfileService, currentUser,
                        AuxFunctionCatalog.defaults()))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private OwnedControlProfile savedRover(String name, boolean active) {
        ControlProfile layout = ControlProfile.forKind(VehicleKind.ROVER)
                .copyAs(ControlProfileId.random(), name)
                .withBindings(ControlProfile.forKind(VehicleKind.ROVER).channelMap(),
                        new ActionMap(List.of(ActionBinding.pressButton(0, ControlAction.ARM))));
        return new OwnedControlProfile(ownerId, layout, active, SAVED_AT);
    }

    // --- list ---

    @Test
    void listReturnsSavedProfilesAndTheBuiltInsBehindThem() throws Exception {
        OwnedControlProfile mine = savedRover("Bench rover", true);
        when(controlProfileService.saved(ownerId)).thenReturn(List.of(mine));

        mockMvc.perform(get("/api/control-profiles"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].id").value(mine.id().value().toString()))
                .andExpect(jsonPath("$[0].source").value("SAVED"))
                .andExpect(jsonPath("$[0].active").value(true))
                .andExpect(jsonPath("$[0].actionMap[0].positions[0].action").value("ARM"))
                .andExpect(jsonPath("$[1].source").value("BUILT_IN"))
                .andExpect(jsonPath("$[1].kind").value("COPTER"));
    }

    /**
     * The built-in is the layout in force until the operator activates one of their own, so the list
     * must say so — otherwise a fresh operator is shown nothing marked active while a complete map
     * is already flying their vehicle (decision C7).
     */
    @Test
    void aBuiltInIsActiveExactlyWhenNoSavedProfileHasClaimedItsVehicleKind() throws Exception {
        when(controlProfileService.saved(ownerId)).thenReturn(List.of(savedRover("Bench rover", true)));

        mockMvc.perform(get("/api/control-profiles"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[1].kind").value("COPTER"))
                .andExpect(jsonPath("$[1].active").value(true))
                .andExpect(jsonPath("$[2].kind").value("ROVER"))
                .andExpect(jsonPath("$[2].active").value(false));
    }

    @Test
    void aSavedProfileThatIsMerelyStoredDoesNotDisplaceItsBuiltIn() throws Exception {
        when(controlProfileService.saved(ownerId)).thenReturn(List.of(savedRover("Bench rover", false)));

        mockMvc.perform(get("/api/control-profiles"))
                .andExpect(jsonPath("$[0].active").value(false))
                .andExpect(jsonPath("$[2].kind").value("ROVER"))
                .andExpect(jsonPath("$[2].active").value(true));
    }

    @Test
    void aBuiltInCarriesNoSavedTimestampBecauseItWasNeverSaved() throws Exception {
        mockMvc.perform(get("/api/control-profiles"))
                .andExpect(jsonPath("$[0].source").value("BUILT_IN"))
                .andExpect(jsonPath("$[0].updatedAt").doesNotExist());
    }

    // --- catalog ---

    @Test
    void catalogOffersEveryInputKindActionAndPositionTheDomainDefines() throws Exception {
        mockMvc.perform(get("/api/control-profiles/catalog"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inputKinds.length()").value(4))
                .andExpect(jsonPath("$.positions.length()").value(3))
                .andExpect(jsonPath("$.actions[?(@.name=='EMERGENCY_STOP')].dangerous").value(true))
                .andExpect(jsonPath("$.actions[?(@.name=='SET_MODE')].parameter").value("MODE_NAME"))
                .andExpect(jsonPath("$.vehicleKinds[?(@.name=='ROVER')].label").value("Ground vehicle"));
    }

    /**
     * A three-position switch reports on the axes array; a button cannot report three positions.
     * The catalogue has to say that, because it is what stops a setup page offering a binding the
     * domain would then refuse (decision C1).
     */
    @Test
    void catalogSaysAThreePositionSwitchCanOnlyBeReadFromAnAxis() throws Exception {
        mockMvc.perform(get("/api/control-profiles/catalog"))
                .andExpect(jsonPath("$.inputKinds[?(@.name=='SWITCH_3')].sources.length()").value(1))
                .andExpect(jsonPath("$.inputKinds[?(@.name=='SWITCH_3')].sources[0]").value("AXIS"))
                .andExpect(jsonPath("$.inputKinds[?(@.name=='SWITCH_2')].sources.length()").value(2))
                .andExpect(jsonPath("$.inputKinds[?(@.name=='AXIS')].positions.length()").value(0));
    }

    /** So a client never has to hardcode that HIGH is ArduPilot's level 2. */
    @Test
    void catalogCarriesTheAuxLevelForEachSwitchPosition() throws Exception {
        mockMvc.perform(get("/api/control-profiles/catalog"))
                .andExpect(jsonPath("$.positions[?(@.name=='LOW')].level").value(0))
                .andExpect(jsonPath("$.positions[?(@.name=='HIGH')].level").value(2))
                .andExpect(jsonPath("$.auxFunctions[?(@.number==46)].label").value("RC override enable"));
    }

    // --- create ---

    @Test
    void createCopiesTheBuiltInForTheNamedKindAndAnswers201() throws Exception {
        OwnedControlProfile created = savedRover("Bench rover", false);
        when(controlProfileService.create(ownerId, VehicleKind.ROVER, "Bench rover")).thenReturn(created);

        mockMvc.perform(post("/api/control-profiles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"rover\",\"name\":\"Bench rover\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.source").value("SAVED"))
                .andExpect(jsonPath("$.active").value(false));

        verify(controlProfileService).create(ownerId, VehicleKind.ROVER, "Bench rover");
    }

    @Test
    void createReturns400ForAnUnknownVehicleKindAndNeverTouchesTheService() throws Exception {
        mockMvc.perform(post("/api/control-profiles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"submarine\",\"name\":\"Bench\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));

        verify(controlProfileService, org.mockito.Mockito.never())
                .create(any(), any(), any());
    }

    // --- update ---

    @Test
    void updateSendsBothMapsThroughToTheService() throws Exception {
        OwnedControlProfile existing = savedRover("Bench rover", true);
        when(controlProfileService.update(eq(ownerId), eq(existing.id()), eq("Field rover"), any(), any(), any()))
                .thenReturn(existing);

        mockMvc.perform(put("/api/control-profiles/{id}", existing.id().value())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Field rover",
                                 "channelMap":[{"source":"AXIS","kind":"SWITCH_3","function":"AUX_1",
                                                "sourceIndex":4,"rcChannel":6,"minMicros":1000,
                                                "centerMicros":1500,"maxMicros":2000,"deadband":0.0,
                                                "reversed":false}],
                                 "actionMap":[{"source":"BUTTON","kind":"SWITCH_2","sourceIndex":1,
                                               "positions":[{"position":"LOW","action":"DISARM"},
                                                            {"position":"HIGH","action":"AUX_FUNCTION",
                                                             "parameter":"46"}]}]}"""))
                .andExpect(status().isOk());

        ArgumentCaptor<ChannelMap> channels = ArgumentCaptor.forClass(ChannelMap.class);
        ArgumentCaptor<ActionMap> actions = ArgumentCaptor.forClass(ActionMap.class);
        verify(controlProfileService).update(eq(ownerId), eq(existing.id()), eq("Field rover"),
                channels.capture(), actions.capture(), any());
        assertEquals(1, channels.getValue().bindings().size());
        assertEquals(6, channels.getValue().bindings().get(0).rcChannel());
        assertEquals(2, actions.getValue().bindings().get(0).positions().size());
        assertEquals(46, actions.getValue().bindings().get(0).positions().get(1).auxFunctionNumber());
    }

    @Test
    void updateCarriesHowTheOperatorsTransmitterIsArranged() throws Exception {
        OwnedControlProfile existing = savedRover("Bench rover", true);
        when(controlProfileService.update(eq(ownerId), eq(existing.id()), any(), any(), any(), any()))
                .thenReturn(existing);

        mockMvc.perform(put("/api/control-profiles/{id}", existing.id().value())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Bench rover","channelMap":[],"actionMap":[],
                                 "stickMode":1,"forwardIsUp":false}"""))
                .andExpect(status().isOk());

        ArgumentCaptor<TransmitterView> view = ArgumentCaptor.forClass(TransmitterView.class);
        verify(controlProfileService).update(eq(ownerId), eq(existing.id()), any(), any(), any(), view.capture());
        assertEquals(new TransmitterView(1, false), view.getValue());
    }

    /** An older client that has never drawn the picture sends no opinion, and must not be refused. */
    @Test
    void updateWithoutATransmitterViewKeepsThePlatformDefault() throws Exception {
        OwnedControlProfile existing = savedRover("Bench rover", true);
        when(controlProfileService.update(eq(ownerId), eq(existing.id()), any(), any(), any(), any()))
                .thenReturn(existing);

        mockMvc.perform(put("/api/control-profiles/{id}", existing.id().value())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Bench rover","channelMap":[],"actionMap":[]}"""))
                .andExpect(status().isOk());

        ArgumentCaptor<TransmitterView> view = ArgumentCaptor.forClass(TransmitterView.class);
        verify(controlProfileService).update(eq(ownerId), eq(existing.id()), any(), any(), any(), view.capture());
        assertEquals(TransmitterView.DEFAULT, view.getValue());
    }

    /**
     * The domain refuses a 3-position switch read from a button (one button cannot report three
     * positions) — and the edge must surface that as a 400 rather than a 500.
     */
    @Test
    void updateReturns400ForABindingTheDomainRefuses() throws Exception {
        mockMvc.perform(put("/api/control-profiles/{id}", ControlProfileId.random().value())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Impossible",
                                 "actionMap":[{"source":"BUTTON","kind":"SWITCH_3","sourceIndex":1,
                                               "positions":[{"position":"MIDDLE","action":"ARM"}]}]}"""))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(controlProfileService);
    }

    @Test
    void updateReturns404ForAProfileNobodySaved() throws Exception {
        ControlProfileId id = ControlProfileId.random();
        when(controlProfileService.update(eq(ownerId), eq(id), any(), any(), any(), any()))
                .thenThrow(new NoSuchElementException("No control profile " + id.value()));

        mockMvc.perform(put("/api/control-profiles/{id}", id.value())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Field rover\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }

    @Test
    void updateReturns403ForAnotherOperatorsProfile() throws Exception {
        ControlProfileId id = ControlProfileId.random();
        when(controlProfileService.update(eq(ownerId), eq(id), any(), any(), any(), any()))
                .thenThrow(new AccessDeniedException("belongs to another operator"));

        mockMvc.perform(put("/api/control-profiles/{id}", id.value())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Field rover\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void aBadProfileUuidIs400AndNeverReachesTheService() throws Exception {
        mockMvc.perform(post("/api/control-profiles/{id}/activate", "not-a-uuid"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(controlProfileService);
    }

    // --- activate / delete ---

    @Test
    void activateAnswers204AndDelegatesWithTheCallerAsOwner() throws Exception {
        ControlProfileId id = ControlProfileId.random();

        mockMvc.perform(post("/api/control-profiles/{id}/activate", id.value()))
                .andExpect(status().isNoContent());

        verify(controlProfileService).activate(ownerId, id);
    }

    @Test
    void deleteAnswers204() throws Exception {
        ControlProfileId id = ControlProfileId.random();

        mockMvc.perform(delete("/api/control-profiles/{id}", id.value()))
                .andExpect(status().isNoContent());

        verify(controlProfileService).delete(ownerId, id);
    }

    /** A built-in is not editable, and the service says so with an IllegalArgumentException (C7). */
    @Test
    void deletingABuiltInIs400() throws Exception {
        ControlProfileId builtIn = ControlProfileId.builtIn(VehicleKind.COPTER);
        org.mockito.Mockito.doThrow(new IllegalArgumentException("Built-in control profiles cannot be edited"))
                .when(controlProfileService).delete(ownerId, builtIn);

        mockMvc.perform(delete("/api/control-profiles/{id}", builtIn.value()))
                .andExpect(status().isBadRequest());
    }
}
