package com.drones.vision.api.controller;

import com.drones.vision.api.exception.ApiExceptionHandler;
import com.drones.vision.platform.Health;
import com.drones.vision.platform.SubsystemStatus;
import com.drones.vision.platform.SubsystemStatusPort;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * docs/plans/done/SYSTEM-STATUS-PLAN.md §4.3's frozen wire contract: {@code GET
 * /api/system/status} rolls many {@link SubsystemStatusPort}s into one response, computing {@code
 * overall} and never failing the whole request because one provider misbehaves.
 */
class SystemStatusControllerTest {

    private static MockMvc mockMvcFor(List<SubsystemStatusPort> providers) {
        return MockMvcBuilders.standaloneSetup(new SystemStatusController(providers))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private static SubsystemStatusPort fixed(SubsystemStatus status) {
        return () -> status;
    }

    @Test
    void overallIsTheWorstHealthAcrossMixedOkAndDegradedSubsystems() throws Exception {
        MockMvc mockMvc = mockMvcFor(List.of(
                fixed(new SubsystemStatus("cv-service", "CV inference", Health.OK, "cv-service is READY", null, null)),
                fixed(new SubsystemStatus("video-publish", "Video publish (mediamtx)", Health.DEGRADED,
                        "1/2 stream(s) in outage: abc", null, "Check mediamtx"))));

        mockMvc.perform(get("/api/system/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overall").value("DEGRADED"))
                .andExpect(jsonPath("$.checkedAt").exists())
                .andExpect(jsonPath("$.subsystems[0].id").value("cv-service"))
                .andExpect(jsonPath("$.subsystems[0].health").value("OK"))
                .andExpect(jsonPath("$.subsystems[1].id").value("video-publish"))
                .andExpect(jsonPath("$.subsystems[1].health").value("DEGRADED"))
                .andExpect(jsonPath("$.subsystems[1].hint").value("Check mediamtx"));
    }

    @Test
    void disabledSubsystemsAreExcludedFromTheOverallRollup() throws Exception {
        MockMvc mockMvc = mockMvcFor(List.of(
                fixed(new SubsystemStatus("cv-service", "CV inference", Health.DISABLED,
                        "CV detection is disabled", null, null)),
                fixed(new SubsystemStatus("live-updates", "Live updates (SSE)", Health.OK, "3 live connections open",
                        null, null))));

        mockMvc.perform(get("/api/system/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overall").value("OK"))
                .andExpect(jsonPath("$.subsystems[0].health").value("DISABLED"));
    }

    @Test
    void overallIsUnknownWhenNoProvidersAreRegistered() throws Exception {
        MockMvc mockMvc = mockMvcFor(List.of());

        mockMvc.perform(get("/api/system/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overall").value("UNKNOWN"))
                .andExpect(jsonPath("$.subsystems").isEmpty());
    }

    @Test
    void overallIsUnknownWhenEverySubsystemIsDisabled() throws Exception {
        MockMvc mockMvc = mockMvcFor(List.of(
                fixed(new SubsystemStatus("cv-service", "CV inference", Health.DISABLED, "off", null, null))));

        mockMvc.perform(get("/api/system/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overall").value("UNKNOWN"));
    }

    @Test
    void aThrowingProviderIsReportedAsUnknownWithTheExceptionMessageRatherThanFailingTheEndpoint() throws Exception {
        SubsystemStatusPort broken = () -> {
            throw new IllegalStateException("boom: cannot reach mavlink session");
        };
        MockMvc mockMvc = mockMvcFor(List.of(
                fixed(new SubsystemStatus("cv-service", "CV inference", Health.OK, "cv-service is READY", null, null)),
                broken));

        mockMvc.perform(get("/api/system/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subsystems", hasSize(2)))
                .andExpect(jsonPath("$.subsystems[1].health").value("UNKNOWN"))
                .andExpect(jsonPath("$.subsystems[1].detail").value("boom: cannot reach mavlink session"))
                .andExpect(jsonPath("$.overall").value("UNKNOWN"));
    }

    /**
     * Regression guard: {@code SubsystemStatus} rejects a <em>blank</em> {@code detail}, not merely a
     * null one, so building the fallback status from a blank exception message used to throw from
     * inside the catch block and 500 the whole endpoint — defeating the per-provider isolation the
     * catch exists to provide. A healthy sibling subsystem must still be reported.
     */
    @Test
    void aProviderThrowingWithABlankMessageStillDoesNotFailTheEndpoint() throws Exception {
        SubsystemStatusPort broken = () -> {
            throw new IllegalStateException("");
        };
        MockMvc mockMvc = mockMvcFor(List.of(
                fixed(new SubsystemStatus("cv-service", "CV inference", Health.OK, "cv-service is READY", null, null)),
                broken));

        mockMvc.perform(get("/api/system/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subsystems", hasSize(2)))
                .andExpect(jsonPath("$.subsystems[0].health").value("OK"))
                .andExpect(jsonPath("$.subsystems[1].health").value("UNKNOWN"))
                .andExpect(jsonPath("$.subsystems[1].detail").value(containsString("IllegalStateException")))
                .andExpect(jsonPath("$.overall").value("UNKNOWN"));
    }
}
