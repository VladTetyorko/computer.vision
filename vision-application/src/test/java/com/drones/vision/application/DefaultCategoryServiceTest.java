package com.drones.vision.application;

import com.drones.vision.domain.model.CategoryId;
import com.drones.vision.domain.model.DeviceCategory;
import com.drones.vision.domain.port.out.CategoryRepositoryPort;
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
        DeviceCategory robot = new DeviceCategory(new CategoryId("robot"), "Robot", null, List.of());
        DeviceCategory drone = new DeviceCategory(new CategoryId("drone"), "Drone", null, List.of("range-km"));
        DeviceCategory fpvDrone =
                new DeviceCategory(new CategoryId("fpv-drone"), "FPV Drone", new CategoryId("drone"), List.of());
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
