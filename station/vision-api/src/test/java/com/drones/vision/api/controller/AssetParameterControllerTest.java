package com.drones.vision.api.controller;

import com.drones.vision.api.exception.ApiExceptionHandler;
import com.drones.vision.api.security.CurrentUser;
import com.drones.vision.flight.application.RemediationService;
import com.drones.vision.flight.application.VehicleProfileService;
import com.drones.vision.flight.domain.model.ParameterReading;
import com.drones.vision.flight.domain.model.ParameterWriteOutcome;
import com.drones.vision.flight.domain.model.RemediationResultCode;
import com.drones.vision.flight.domain.model.VehicleProfile;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.GroupId;
import com.drones.vision.kernel.Ownership;
import com.drones.vision.kernel.UserId;
import com.drones.vision.platform.AccessDeniedException;
import com.drones.vision.platform.Authority;
import com.drones.vision.platform.VisibilityScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Covers {@link AssetParameterController} (docs/plans/active/FLEET-RADIO-PLAN.md R5). {@link
 * RemediationService}/{@link VehicleProfileService} are mocked directly — this class proves the
 * controller's own translation and gates (consent, spelling resolution, exception mapping), not
 * {@code DefaultRemediationService}'s tier/authority/disarmed logic, which is {@code
 * DefaultRemediationServiceTest}'s (contexts/vision-flight) job.
 */
class AssetParameterControllerTest {

    private RemediationService remediationService;
    private VehicleProfileService vehicleProfileService;
    private MockMvc mockMvc;

    private final UserId ownerId = UserId.random();
    private final Ownership ownership = new Ownership(ownerId, GroupId.random());
    private final CurrentUser currentUser = new CurrentUser(ownership);
    private final AssetId assetId = AssetId.random();

    @BeforeEach
    void setUp() {
        remediationService = mock(RemediationService.class);
        vehicleProfileService = mock(VehicleProfileService.class);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new AssetParameterController(remediationService, vehicleProfileService, currentUser))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private static VehicleProfile profileWithParameters(List<ParameterReading> parameters) {
        return new VehicleProfile("udp://0.0.0.0:14550#7", Instant.parse("2026-08-27T09:00:00Z"), 7, "ardupilot",
                "4.7.0", "quadcopter", null, List.of(), List.of(), parameters, null, true, null);
    }

    // --- happy path -------------------------------------------------------------------------------

    @Test
    void writeParameterReturns200WithTheOutcomeOnSuccess() throws Exception {
        ParameterWriteOutcome outcome = new ParameterWriteOutcome("SR0_EXTRA1", RemediationResultCode.ACCEPTED,
                0.0, 10.0, "written and read back");
        when(remediationService.writeParameter(eq(assetId), eq("SR0_EXTRA1"), eq(10.0), eq(true), eq(ownerId),
                any(Authority.class))).thenReturn(outcome);

        mockMvc.perform(post("/api/assets/{id}/parameters", assetId.value())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"SR0_EXTRA1\",\"value\":10.0,\"consent\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.parameterName").value("SR0_EXTRA1"))
                .andExpect(jsonPath("$.outcome").value("ACCEPTED"))
                .andExpect(jsonPath("$.previousValue").value(0.0))
                .andExpect(jsonPath("$.newValue").value(10.0));

        verify(remediationService).writeParameter(eq(assetId), eq("SR0_EXTRA1"), eq(10.0), eq(true), eq(ownerId),
                any(Authority.class));
        verifyNoInteractions(vehicleProfileService); // unaliased name: no profile lookup needed
    }

    // --- consent (expected result 2) ---------------------------------------------------------------

    @Test
    void writeParameterReturns400AndNeverDispatchesWhenConsentIsFalse() throws Exception {
        mockMvc.perform(post("/api/assets/{id}/parameters", assetId.value())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"SR0_EXTRA1\",\"value\":10.0,\"consent\":false}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));

        verifyNoInteractions(remediationService);
    }

    @Test
    void writeParameterReturns400AndNeverDispatchesWhenConsentIsAbsent() throws Exception {
        mockMvc.perform(post("/api/assets/{id}/parameters", assetId.value())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"SR0_EXTRA1\",\"value\":10.0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));

        verifyNoInteractions(remediationService);
    }

    @Test
    void writeParameterReturns400ForABlankName() throws Exception {
        mockMvc.perform(post("/api/assets/{id}/parameters", assetId.value())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"  \",\"value\":10.0,\"consent\":true}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(remediationService);
    }

    @Test
    void writeParameterReturns400ForAMissingValue() throws Exception {
        mockMvc.perform(post("/api/assets/{id}/parameters", assetId.value())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"SR0_EXTRA1\",\"consent\":true}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(remediationService);
    }

    // --- authorization (expected result 1) ----------------------------------------------------------

    @Test
    void writeParameterReturns403WhenTheAssetIsOutsideManagementScope() throws Exception {
        when(remediationService.writeParameter(eq(assetId), eq("SR0_EXTRA1"), eq(10.0), eq(true), eq(ownerId),
                any(Authority.class)))
                .thenThrow(new AccessDeniedException("Asset " + assetId.value() + " is outside your management scope"));

        mockMvc.perform(post("/api/assets/{id}/parameters", assetId.value())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"SR0_EXTRA1\",\"value\":10.0,\"consent\":true}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));
    }

    @Test
    void writeParameterReturns404ForAnUnknownAsset() throws Exception {
        when(remediationService.writeParameter(eq(assetId), eq("SR0_EXTRA1"), eq(10.0), eq(true), eq(ownerId),
                any(Authority.class)))
                .thenThrow(new NoSuchElementException("Unknown asset: " + assetId.value()));

        mockMvc.perform(post("/api/assets/{id}/parameters", assetId.value())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"SR0_EXTRA1\",\"value\":10.0,\"consent\":true}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }

    @Test
    void writeParameterReturns400ForABadUuidAndNeverTouchesTheService() throws Exception {
        mockMvc.perform(post("/api/assets/{id}/parameters", "not-a-uuid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"SR0_EXTRA1\",\"value\":10.0,\"consent\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));

        verifyNoInteractions(remediationService);
    }

    @Test
    void writeParameterReturns409WhenTheAircraftIsArmedOrHasNoConfigurableDevice() throws Exception {
        when(remediationService.writeParameter(eq(assetId), eq("SR0_EXTRA1"), eq(10.0), eq(true), eq(ownerId),
                any(Authority.class)))
                .thenThrow(new IllegalStateException("Asset " + assetId.value() + " is armed; refusing to write"));

        mockMvc.perform(post("/api/assets/{id}/parameters", assetId.value())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"SR0_EXTRA1\",\"value\":10.0,\"consent\":true}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("CONFLICT"));
    }

    @Test
    void writeParameterReturns400WhenTheServiceRejectsATierCOrUnclassifiedName() throws Exception {
        when(remediationService.writeParameter(eq(assetId), eq("ARMING_CHECK"), eq(1.0), eq(true), eq(ownerId),
                any(Authority.class)))
                .thenThrow(new IllegalArgumentException("Parameter 'ARMING_CHECK' is not on the writable allowlist"));

        mockMvc.perform(post("/api/assets/{id}/parameters", assetId.value())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"ARMING_CHECK\",\"value\":1.0,\"consent\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));
    }

    // --- spelling resolution (expected result 3, F0) ------------------------------------------------

    @Test
    void writeParameterTargetsTheModernSpellingWhenTheVehicleAnsweredUnderIt() throws Exception {
        when(vehicleProfileService.latestProfile(eq(assetId), any(VisibilityScope.class)))
                .thenReturn(profileWithParameters(List.of(new ParameterReading("MAV_SYSID", 1.0, "UINT8"))));
        ParameterWriteOutcome outcome =
                new ParameterWriteOutcome("MAV_SYSID", RemediationResultCode.ACCEPTED, 1.0, 2.0, "ok");
        when(remediationService.writeParameter(eq(assetId), eq("MAV_SYSID"), eq(2.0), eq(true), eq(ownerId),
                any(Authority.class))).thenReturn(outcome);

        mockMvc.perform(post("/api/assets/{id}/parameters", assetId.value())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"SYSID_THISMAV\",\"value\":2.0,\"consent\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.parameterName").value("MAV_SYSID"));

        verify(remediationService).writeParameter(eq(assetId), eq("MAV_SYSID"), eq(2.0), eq(true), eq(ownerId),
                any(Authority.class));
        verify(remediationService, never()).writeParameter(eq(assetId), eq("SYSID_THISMAV"), any(Double.class),
                eq(true), eq(ownerId), any(Authority.class));
    }

    @Test
    void writeParameterTargetsTheLegacySpellingWhenTheVehicleAnsweredUnderIt() throws Exception {
        when(vehicleProfileService.latestProfile(eq(assetId), any(VisibilityScope.class)))
                .thenReturn(profileWithParameters(List.of(new ParameterReading("SYSID_THISMAV", 1.0, "UINT8"))));
        ParameterWriteOutcome outcome =
                new ParameterWriteOutcome("SYSID_THISMAV", RemediationResultCode.ACCEPTED, 1.0, 3.0, "ok");
        when(remediationService.writeParameter(eq(assetId), eq("SYSID_THISMAV"), eq(3.0), eq(true), eq(ownerId),
                any(Authority.class))).thenReturn(outcome);

        mockMvc.perform(post("/api/assets/{id}/parameters", assetId.value())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"MAV_SYSID\",\"value\":3.0,\"consent\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.parameterName").value("SYSID_THISMAV"));

        verify(remediationService).writeParameter(eq(assetId), eq("SYSID_THISMAV"), eq(3.0), eq(true), eq(ownerId),
                any(Authority.class));
    }

    @Test
    void writeParameterFallsBackToTheRequestedSpellingWhenTheAssetWasNeverProbed() throws Exception {
        when(vehicleProfileService.latestProfile(eq(assetId), any(VisibilityScope.class)))
                .thenThrow(new NoSuchElementException("never probed"));
        ParameterWriteOutcome outcome =
                new ParameterWriteOutcome("SYSID_THISMAV", RemediationResultCode.NO_ACK, null, null, "no ack");
        when(remediationService.writeParameter(eq(assetId), eq("SYSID_THISMAV"), eq(4.0), eq(true), eq(ownerId),
                any(Authority.class))).thenReturn(outcome);

        mockMvc.perform(post("/api/assets/{id}/parameters", assetId.value())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"SYSID_THISMAV\",\"value\":4.0,\"consent\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("NO_ACK"));

        verify(remediationService).writeParameter(eq(assetId), eq("SYSID_THISMAV"), eq(4.0), eq(true), eq(ownerId),
                any(Authority.class));
    }
}
