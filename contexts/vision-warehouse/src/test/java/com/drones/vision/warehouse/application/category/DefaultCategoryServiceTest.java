package com.drones.vision.warehouse.application.category;

import com.drones.vision.kernel.CategoryId;
import com.drones.vision.warehouse.domain.model.DeviceCategory;
import com.drones.vision.warehouse.domain.port.CategoryRepositoryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DefaultCategoryServiceTest {

    private CategoryRepositoryPort categoryRepository;
    private CategoryService service;

    @BeforeEach
    void setUp() {
        categoryRepository = mock(CategoryRepositoryPort.class);
        service = new DefaultCategoryService(categoryRepository);
    }

    @Test
    void categoriesDelegatesToRepositoryFindAllSortedBySlug() {
        DeviceCategory robot = new DeviceCategory(new CategoryId("robot"), "Robot", null, List.of(), true);
        DeviceCategory drone = new DeviceCategory(new CategoryId("drone"), "Drone", null, List.of("range-km"), true);
        DeviceCategory fpvDrone = new DeviceCategory(new CategoryId("fpv-drone"), "FPV Drone",
                new CategoryId("drone"), List.of(), true);
        when(categoryRepository.findAll()).thenReturn(List.of(robot, drone, fpvDrone));

        List<DeviceCategory> result = service.categories();

        assertEquals(List.of(drone, fpvDrone, robot), result);
    }

    @Test
    void categoriesReturnsEmptyListWhenRepositoryEmpty() {
        when(categoryRepository.findAll()).thenReturn(List.of());

        assertEquals(List.of(), service.categories());
    }

    @Test
    void constructorRejectsNullRepository() {
        assertThrows(NullPointerException.class, () -> new DefaultCategoryService(null));
    }
}
