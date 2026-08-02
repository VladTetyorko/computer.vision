package com.drones.vision.api.controller;

import com.drones.vision.api.dto.CvModelResponse;
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
 * Docs/CV-CONTROL-PLAN.md §4's frozen wire contract: {@code GET /api/cv/models} just wraps whatever
 * roster it was constructed with — the actual roster content/order is {@code vision-app}'s wiring
 * concern (see its own {@code WiringConfiguration#cvModelRoster} test coverage), this class only
 * proves the controller's own mapping/shape.
 */
class CvModelsControllerTest {

    private MockMvc mockMvc;

    private static MockMvc mockMvcFor(List<CvModelResponse> roster) {
        return MockMvcBuilders.standaloneSetup(new CvModelsController(roster))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @BeforeEach
    void setUp() {
        mockMvc = mockMvcFor(List.of(
                new CvModelResponse("yolo26n.pt", "General (people & vehicles, fast)", "general", false, List.of()),
                new CvModelResponse("orion12l.pt", "Military vehicles", "specialized", false, List.of()),
                new CvModelResponse("yoloe-26s-seg-pf.pt", "Everything (incl. buildings, slower)", "open-vocab",
                        true, List.of())));
    }

    @Test
    void modelsReturnsTheRosterWrappedUnderModelsWithYolo26nFirst() throws Exception {
        mockMvc.perform(get("/api/cv/models"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.models", hasSize(3)))
                .andExpect(jsonPath("$.models[0].id").value("yolo26n.pt"))
                .andExpect(jsonPath("$.models[0].displayName").value("General (people & vehicles, fast)"))
                .andExpect(jsonPath("$.models[0].kind").value("general"))
                .andExpect(jsonPath("$.models[0].openVocab").value(false))
                .andExpect(jsonPath("$.models[0].defaultLabelFilter", hasSize(0)))
                .andExpect(jsonPath("$.models[1].id").value("orion12l.pt"))
                .andExpect(jsonPath("$.models[2].id").value("yoloe-26s-seg-pf.pt"))
                .andExpect(jsonPath("$.models[2].kind").value("open-vocab"))
                .andExpect(jsonPath("$.models[2].openVocab").value(true))
                .andExpect(jsonPath("$.models[2].defaultLabelFilter", hasSize(0)));
    }

    @Test
    void modelsNeverErrorsEvenWhenTheRosterIsEmpty() throws Exception {
        MockMvc emptyRosterMockMvc = mockMvcFor(List.of());

        emptyRosterMockMvc.perform(get("/api/cv/models"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.models", hasSize(0)));
    }
}
