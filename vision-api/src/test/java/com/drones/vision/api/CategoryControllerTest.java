package com.drones.vision.api;

import com.drones.vision.api.exceptions.ApiExceptionHandler;
import com.drones.vision.application.CategoryService;
import com.drones.vision.domain.model.CategoryId;
import com.drones.vision.domain.model.DeviceCategory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CategoryControllerTest {

    private CategoryService categoryService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        categoryService = mock(CategoryService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new CategoryController(categoryService))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void listReturns200WithTopLevelAndChildCategories() throws Exception {
        DeviceCategory drone = new DeviceCategory(new CategoryId("drone"), "Drone", null, List.of("weightKg"));
        DeviceCategory fpvDrone = new DeviceCategory(new CategoryId("fpv-drone"), "FPV drone",
                new CategoryId("drone"), List.of("weightKg", "vtxFrequency"));
        when(categoryService.categories()).thenReturn(List.of(drone, fpvDrone));

        mockMvc.perform(get("/api/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].slug").value("drone"))
                .andExpect(jsonPath("$[0].name").value("Drone"))
                .andExpect(jsonPath("$[0].parent").doesNotExist())
                .andExpect(jsonPath("$[0].attributeHints", hasSize(1)))
                .andExpect(jsonPath("$[1].slug").value("fpv-drone"))
                .andExpect(jsonPath("$[1].parent").value("drone"))
                .andExpect(jsonPath("$[1].attributeHints", hasSize(2)));
    }

    @Test
    void listReturns200WithEmptyListWhenNoCategories() throws Exception {
        when(categoryService.categories()).thenReturn(List.of());

        mockMvc.perform(get("/api/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }
}
