package com.drones.vision.api.controller;

import com.drones.vision.api.dto.CvTrackerResponse;
import com.drones.vision.api.exception.ApiExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * docs/TRACKING-PLAN.md §4.F's frozen wire contract: {@code GET /api/cv/trackers} just wraps
 * whatever roster it was constructed with — the roster's actual content is {@code vision-app}'s
 * wiring concern ({@code TrackingWiring#cvTrackerRoster}), so this class proves only the
 * controller's own shape, exactly as {@link CvModelsControllerTest} does for its sibling endpoint.
 */
class CvTrackersControllerTest {

    private MockMvc mockMvc;

    private static MockMvc mockMvcFor(List<CvTrackerResponse> roster) {
        return MockMvcBuilders.standaloneSetup(new CvTrackersController(roster))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @BeforeEach
    void setUp() {
        mockMvc = mockMvcFor(List.of(
                new CvTrackerResponse("bytetrack", "ByteTrack (multi-object)", List.of("ASSOCIATE"), false,
                        "~0.8 ms/frame"),
                new CvTrackerResponse("lk", "Optical flow (fast follow)", List.of("FOLLOW"), false, "~0.4 ms/frame"),
                new CvTrackerResponse("ncc", "Template match (robust follow)", List.of("FOLLOW"), false,
                        "~0.6 ms/frame")));
    }

    @Test
    void trackersReturnsTheRosterWrappedUnderTrackersWithModesPerEngine() throws Exception {
        mockMvc.perform(get("/api/cv/trackers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trackers", hasSize(3)))
                .andExpect(jsonPath("$.trackers[0].id").value("bytetrack"))
                .andExpect(jsonPath("$.trackers[0].displayName").value("ByteTrack (multi-object)"))
                .andExpect(jsonPath("$.trackers[0].modes", hasSize(1)))
                .andExpect(jsonPath("$.trackers[0].modes[0]").value("ASSOCIATE"))
                .andExpect(jsonPath("$.trackers[0].needsAssets").value(false))
                .andExpect(jsonPath("$.trackers[0].costHint").value("~0.8 ms/frame"))
                .andExpect(jsonPath("$.trackers[1].id").value("lk"))
                .andExpect(jsonPath("$.trackers[1].modes[0]").value("FOLLOW"))
                .andExpect(jsonPath("$.trackers[2].id").value("ncc"))
                .andExpect(jsonPath("$.trackers[2].modes[0]").value("FOLLOW"));
    }

    @Test
    void trackersNeverErrorsEvenWhenTheRosterIsEmpty() throws Exception {
        mockMvcFor(List.of()).perform(get("/api/cv/trackers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trackers", hasSize(0)));
    }
}
