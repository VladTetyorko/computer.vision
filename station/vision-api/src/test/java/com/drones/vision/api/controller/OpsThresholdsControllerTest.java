package com.drones.vision.api.controller;

import com.drones.vision.api.dto.BatteryThresholdsResponse;
import com.drones.vision.api.dto.OpsThresholdsResponse;
import com.drones.vision.api.dto.RcThresholdsResponse;
import com.drones.vision.api.exception.ApiExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * docs/plans/active/ASSET-FLOWS-PLAN.md §2 "Battery thresholds" frozen wire contract: {@code GET
 * /api/ops/thresholds} just wraps whatever {@link OpsThresholdsResponse} it was constructed with —
 * the actual threshold values are {@code vision-app}'s wiring concern ({@code
 * OpsWiringConfiguration#opsThresholds}), so this class proves only the controller's own shape,
 * exactly as {@link CvTrackersControllerTest} does for its sibling config-backed endpoint.
 */
class OpsThresholdsControllerTest {

    private MockMvc mockMvc;

    private static MockMvc mockMvcFor(OpsThresholdsResponse response) {
        return MockMvcBuilders.standaloneSetup(new OpsThresholdsController(response))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @BeforeEach
    void setUp() {
        mockMvc = mockMvcFor(new OpsThresholdsResponse(
                new BatteryThresholdsResponse(25, 10), new RcThresholdsResponse(5)));
    }

    @Test
    void thresholdsReturnsExactlyTheFrozenWireShape() throws Exception {
        mockMvc.perform(get("/api/ops/thresholds"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.battery.warningPercent").value(25))
                .andExpect(jsonPath("$.battery.criticalPercent").value(10))
                .andExpect(jsonPath("$.rc.neutralTolerancePercent").value(5));
    }

    @Test
    void thresholdsReflectsWhateverConfigItWasBuiltFrom() throws Exception {
        mockMvcFor(new OpsThresholdsResponse(
                        new BatteryThresholdsResponse(30, 15), new RcThresholdsResponse(15)))
                .perform(get("/api/ops/thresholds"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.battery.warningPercent").value(30))
                .andExpect(jsonPath("$.battery.criticalPercent").value(15))
                .andExpect(jsonPath("$.rc.neutralTolerancePercent").value(15));
    }
}
