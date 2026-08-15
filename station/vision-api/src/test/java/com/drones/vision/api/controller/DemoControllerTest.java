package com.drones.vision.api.controller;

import com.drones.vision.api.demo.DemoPlan;
import com.drones.vision.api.demo.DemoScenario;
import com.drones.vision.api.demo.DemoSeedReport;
import com.drones.vision.api.demo.DemoVideoLibrary;
import com.drones.vision.api.exception.ApiExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DemoControllerTest {

    private DemoScenario scenario;
    private DemoVideoLibrary videos;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        scenario = mock(DemoScenario.class);
        videos = mock(DemoVideoLibrary.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new DemoController(scenario, videos))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void statusReportsTheVideoFolderAndItsFiles() throws Exception {
        when(videos.directory()).thenReturn(Path.of("/home/pilot/Videos"));
        when(videos.videos()).thenReturn(List.of(Path.of("/home/pilot/Videos/drone.mp4"),
                Path.of("/home/pilot/Videos/tank_fight.MP4")));

        mockMvc.perform(get("/api/demo"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.videosDirectory").value("/home/pilot/Videos"))
                .andExpect(jsonPath("$.videos[0]").value("drone.mp4"))
                .andExpect(jsonPath("$.videos[1]").value("tank_fight.MP4"));
    }

    @Test
    void seedWithNoBodyUsesTheDefaultPlanAndReturns201() throws Exception {
        when(scenario.seed(any())).thenReturn(report());

        mockMvc.perform(post("/api/demo/seed"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.assets").value(2))
                .andExpect(jsonPath("$.users").value(1))
                .andExpect(jsonPath("$.assignments").value(3))
                .andExpect(jsonPath("$.streamsStarted").value(1))
                .andExpect(jsonPath("$.assetNames[0]").value("FPV Pis-UN"))
                .andExpect(jsonPath("$.usernames[0]").value("demo.falcon"))
                .andExpect(jsonPath("$.password").value("demo"))
                .andExpect(jsonPath("$.videosUsed[0]").value("drone.mp4"))
                .andExpect(jsonPath("$.problems").isEmpty());

        ArgumentCaptor<DemoPlan> plan = ArgumentCaptor.forClass(DemoPlan.class);
        verify(scenario).seed(plan.capture());
        assertEquals(DemoPlan.DEFAULT, plan.getValue());
    }

    @Test
    void seedHonoursExplicitCountsAndClampsThemToTheCeiling() throws Exception {
        when(scenario.seed(any())).thenReturn(report());

        mockMvc.perform(post("/api/demo/seed")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assets\":999,\"users\":2,\"startStreams\":7}"))
                .andExpect(status().isCreated());

        ArgumentCaptor<DemoPlan> plan = ArgumentCaptor.forClass(DemoPlan.class);
        verify(scenario).seed(plan.capture());
        assertEquals(new DemoPlan(DemoPlan.MAX, 2, 7), plan.getValue());
    }

    @Test
    void anAbsentFieldFallsBackToItsDefault() throws Exception {
        when(scenario.seed(any())).thenReturn(report());

        mockMvc.perform(post("/api/demo/seed")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assets\":4}"))
                .andExpect(status().isCreated());

        ArgumentCaptor<DemoPlan> plan = ArgumentCaptor.forClass(DemoPlan.class);
        verify(scenario).seed(plan.capture());
        assertEquals(new DemoPlan(4, DemoPlan.DEFAULT.users(), DemoPlan.DEFAULT.startStreams()), plan.getValue());
    }

    @Test
    void problemsFromAPartiallyFailedSeedTravelOnTheResponseNotAsAnError() throws Exception {
        when(scenario.seed(any())).thenReturn(new DemoSeedReport(List.of("FPV Pis-UN"), List.of(), 0, 0, 0, 0,
                List.of(), List.of("stream FPV Pis-UN: publisher unreachable")));

        mockMvc.perform(post("/api/demo/seed"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.problems[0]").value("stream FPV Pis-UN: publisher unreachable"));
    }

    private static DemoSeedReport report() {
        return new DemoSeedReport(List.of("FPV Pis-UN", "FPV Vyriy"), List.of("demo.falcon"), 3, 2, 5, 1,
                List.of("drone.mp4"), List.of());
    }
}
