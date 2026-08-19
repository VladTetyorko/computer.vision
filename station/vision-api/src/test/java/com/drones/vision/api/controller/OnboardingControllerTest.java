package com.drones.vision.api.controller;

import com.drones.vision.api.exception.ApiExceptionHandler;
import com.drones.vision.api.security.CurrentUser;
import com.drones.vision.api.support.OnboardingProperties;
import com.drones.vision.api.support.RemediationOrchestrator;
import com.drones.vision.flight.application.VehicleProfileService;
import com.drones.vision.flight.domain.model.FlightPassport;
import com.drones.vision.flight.domain.model.ParameterDrift;
import com.drones.vision.flight.domain.model.VehicleProfile;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.GroupId;
import com.drones.vision.kernel.Ownership;
import com.drones.vision.kernel.UsageId;
import com.drones.vision.kernel.UserId;
import com.drones.vision.platform.VisibilityScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Covers {@link OnboardingController#passport}/{@link OnboardingController#drift} (O13 --
 * docs/plans/active/DRONE-ONBOARDING-PLAN.md §8.1's frozen wire contract). The controller's other
 * endpoints (probe/profile/remediate) predate this wave and are unchanged; this class does not
 * re-test them.
 */
class OnboardingControllerTest {

    private VehicleProfileService vehicleProfileService;
    private CurrentUser currentUser;
    private MockMvc mockMvc;

    private final AssetId assetId = AssetId.random();
    private final UsageId usageId = UsageId.random();

    @BeforeEach
    void setUp() {
        vehicleProfileService = mock(VehicleProfileService.class);
        RemediationOrchestrator remediationOrchestrator = mock(RemediationOrchestrator.class);
        currentUser = new CurrentUser(new Ownership(UserId.random(), GroupId.random()));
        OnboardingProperties properties = OnboardingProperties.defaults();
        mockMvc = MockMvcBuilders
                .standaloneSetup(new OnboardingController(vehicleProfileService, remediationOrchestrator, currentUser,
                        properties))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private static VehicleProfile minimalProfile(Instant observedAt) {
        return new VehicleProfile("udp://0.0.0.0:14550#7", observedAt, 7, "ardupilot", "4.5.7", "quadcopter", null,
                List.of(), List.of(), List.of(), null, true, null);
    }

    // ---- passport ----

    @Test
    void passportReturns200WithBothSnapshotsWhenBothAreCaptured() throws Exception {
        VehicleProfile preflight = minimalProfile(Instant.parse("2026-08-18T09:10:00Z"));
        VehicleProfile postflight = minimalProfile(Instant.parse("2026-08-18T09:40:00Z"));
        FlightPassport passport = new FlightPassport(usageId, assetId, preflight, postflight);
        when(vehicleProfileService.passport(eq(assetId), eq(usageId), eq(VisibilityScope.unbounded())))
                .thenReturn(passport);

        mockMvc.perform(get("/api/assets/{assetId}/usages/{usageId}/passport", assetId.value(), usageId.value()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usageId").value(usageId.value().toString()))
                .andExpect(jsonPath("$.assetId").value(assetId.value().toString()))
                .andExpect(jsonPath("$.preflight.observedAt").value("2026-08-18T09:10:00Z"))
                .andExpect(jsonPath("$.postflight.observedAt").value("2026-08-18T09:40:00Z"));
    }

    @Test
    void passportOmitsAnUncapturedSnapshotFromTheResponse() throws Exception {
        VehicleProfile preflight = minimalProfile(Instant.parse("2026-08-18T09:10:00Z"));
        FlightPassport passport = new FlightPassport(usageId, assetId, preflight, null);
        when(vehicleProfileService.passport(eq(assetId), eq(usageId), eq(VisibilityScope.unbounded())))
                .thenReturn(passport);

        mockMvc.perform(get("/api/assets/{assetId}/usages/{usageId}/passport", assetId.value(), usageId.value()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.preflight").exists())
                .andExpect(jsonPath("$.postflight").doesNotExist());
    }

    @Test
    void passportReturns404ForAnUnknownOrOutOfScopeAsset() throws Exception {
        when(vehicleProfileService.passport(eq(assetId), eq(usageId), eq(VisibilityScope.unbounded())))
                .thenThrow(new NoSuchElementException("No asset with id " + assetId));

        mockMvc.perform(get("/api/assets/{assetId}/usages/{usageId}/passport", assetId.value(), usageId.value()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }

    @Test
    void passportReturns404WhenTheUsageDoesNotBelongToTheAsset() throws Exception {
        when(vehicleProfileService.passport(eq(assetId), eq(usageId), eq(VisibilityScope.unbounded())))
                .thenThrow(new NoSuchElementException("Usage " + usageId + " does not belong to asset " + assetId));

        mockMvc.perform(get("/api/assets/{assetId}/usages/{usageId}/passport", assetId.value(), usageId.value()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }

    @Test
    void passportReturns400ForAMalformedAssetId() throws Exception {
        mockMvc.perform(get("/api/assets/{assetId}/usages/{usageId}/passport", "not-a-uuid", usageId.value()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));
    }

    @Test
    void passportReturns400ForAMalformedUsageId() throws Exception {
        mockMvc.perform(get("/api/assets/{assetId}/usages/{usageId}/passport", assetId.value(), "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));
    }

    // ---- drift ----

    @Test
    void driftReturns200WithOneRowNamingTheParameterBothValuesAndBothTimestamps() throws Exception {
        ParameterDrift drift = new ParameterDrift("FENCE_ALT_MAX", 100.0, 120.0,
                Instant.parse("2026-08-18T09:10:00Z"), Instant.parse("2026-08-19T07:02:00Z"));
        when(vehicleProfileService.driftFromPreviousFlight(eq(assetId), eq(usageId), eq(VisibilityScope.unbounded())))
                .thenReturn(List.of(drift));

        mockMvc.perform(get("/api/assets/{assetId}/usages/{usageId}/drift", assetId.value(), usageId.value()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.drift", hasSize(1)))
                .andExpect(jsonPath("$.drift[0].parameterName").value("FENCE_ALT_MAX"))
                .andExpect(jsonPath("$.drift[0].previousValue").value(100.0))
                .andExpect(jsonPath("$.drift[0].currentValue").value(120.0))
                .andExpect(jsonPath("$.drift[0].previousObservedAt").value("2026-08-18T09:10:00Z"))
                .andExpect(jsonPath("$.drift[0].currentObservedAt").value("2026-08-19T07:02:00Z"));
    }

    @Test
    void driftReturns200WithAnEmptyArrayWhenThereIsNothingToCompareNeverA404() throws Exception {
        when(vehicleProfileService.driftFromPreviousFlight(eq(assetId), eq(usageId), eq(VisibilityScope.unbounded())))
                .thenReturn(List.of());

        mockMvc.perform(get("/api/assets/{assetId}/usages/{usageId}/drift", assetId.value(), usageId.value()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.drift", hasSize(0)));
    }

    @Test
    void driftReturns404ForAnUnknownOrOutOfScopeAsset() throws Exception {
        when(vehicleProfileService.driftFromPreviousFlight(eq(assetId), eq(usageId), eq(VisibilityScope.unbounded())))
                .thenThrow(new NoSuchElementException("No asset with id " + assetId));

        mockMvc.perform(get("/api/assets/{assetId}/usages/{usageId}/drift", assetId.value(), usageId.value()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }

    @Test
    void driftReturns400ForAMalformedUsageId() throws Exception {
        mockMvc.perform(get("/api/assets/{assetId}/usages/{usageId}/drift", assetId.value(), "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));
    }
}
