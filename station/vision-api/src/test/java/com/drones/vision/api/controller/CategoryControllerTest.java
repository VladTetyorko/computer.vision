package com.drones.vision.api.controller;

import com.drones.vision.api.exception.ApiExceptionHandler;
import com.drones.vision.api.security.CurrentUser;
import com.drones.vision.api.security.PrincipalResolver;
import com.drones.vision.map.application.MapAccessPolicy;
import com.drones.vision.platform.VisibilityScope;
import com.drones.vision.platform.Authority;
import com.drones.vision.platform.Capability;
import com.drones.vision.identity.domain.model.Role;
import com.drones.vision.warehouse.application.category.CategoryEdit;
import com.drones.vision.warehouse.application.category.CategoryService;
import com.drones.vision.warehouse.application.category.CategorySpec;
import com.drones.vision.kernel.CategoryId;
import com.drones.vision.kernel.GroupId;
import com.drones.vision.kernel.Ownership;
import com.drones.vision.kernel.UserId;
import com.drones.vision.warehouse.domain.model.DeviceCategory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CategoryControllerTest {

    private CategoryService categoryService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        categoryService = mock(CategoryService.class);
        CurrentUser currentUser = new CurrentUser(new Ownership(UserId.random(), GroupId.random()));
        mockMvc = MockMvcBuilders.standaloneSetup(new CategoryController(categoryService, currentUser))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void listReturns200WithTopLevelAndChildCategories() throws Exception {
        DeviceCategory drone = new DeviceCategory(new CategoryId("drone"), "Drone", null, List.of("weightKg"), true);
        DeviceCategory fpvDrone = new DeviceCategory(new CategoryId("fpv-drone"), "FPV drone",
                new CategoryId("drone"), List.of("weightKg", "vtxFrequency"), true);
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

    @Test
    void createReturns201WithTheCreatedCategory() throws Exception {
        DeviceCategory battery = new DeviceCategory(new CategoryId("battery"), "Battery", null,
                List.of("cycles"), false);
        when(categoryService.create(new CategorySpec(new CategoryId("battery"), "Battery", null, false,
                List.of("cycles")))).thenReturn(battery);

        mockMvc.perform(post("/api/categories").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"battery\",\"name\":\"Battery\",\"connected\":false,"
                                + "\"attributeHints\":[\"cycles\"]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.slug").value("battery"))
                .andExpect(jsonPath("$.connected").value(false));
    }

    @Test
    void createReturns403WhenCallerMayNotManageOrg() throws Exception {
        MockMvc restrictedMvc = mockMvcFor(currentUserWithScope(VisibilityScope.assignedAssets(Set.of())));

        restrictedMvc.perform(post("/api/categories").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"battery\",\"name\":\"Battery\",\"connected\":false}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void updateReturns200WithTheUpdatedCategory() throws Exception {
        CategoryId id = new CategoryId("drone");
        DeviceCategory updated = new DeviceCategory(id, "Renamed Drone", null, List.of("model"), true);
        when(categoryService.update(id, new CategoryEdit("Renamed Drone", null, true, List.of("model"))))
                .thenReturn(updated);

        mockMvc.perform(put("/api/categories/drone").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Renamed Drone\",\"connected\":true,\"attributeHints\":[\"model\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Renamed Drone"));
    }

    @Test
    void updateReturns403WhenCallerMayNotManageOrg() throws Exception {
        MockMvc restrictedMvc = mockMvcFor(currentUserWithScope(VisibilityScope.assignedAssets(Set.of())));

        restrictedMvc.perform(put("/api/categories/drone").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Drone\",\"connected\":true}"))
                .andExpect(status().isForbidden());
    }

    private CurrentUser currentUserWithScope(VisibilityScope scope) {
        return new CurrentUser(new PrincipalResolver() {
            @Override
            public UserId userId() {
                return UserId.random();
            }

            @Override
            public Ownership ownership() {
                return new Ownership(UserId.random(), GroupId.random());
            }

            @Override
            public VisibilityScope scope() {
                return scope;
            }

            @Override
            public MapAccessPolicy.Viewer viewer() {
                throw new UnsupportedOperationException("CategoryController never calls viewer()");
            }

            @Override
            public Role role() {
                throw new UnsupportedOperationException("CategoryController never calls role()");
            }

            @Override
            public Authority authority() {
                return new Authority(scope, EnumSet.allOf(Capability.class));
            }
        });
    }

    private MockMvc mockMvcFor(CurrentUser user) {
        return MockMvcBuilders.standaloneSetup(new CategoryController(categoryService, user))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }
}
