package com.drones.vision.warehouse.application.category;

import com.drones.vision.kernel.CategoryId;
import com.drones.vision.warehouse.domain.model.DeviceCategory;
import com.drones.vision.warehouse.domain.port.CategoryRepositoryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
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

    @Test
    void createSavesANewCategory() {
        CategorySpec spec = new CategorySpec(new CategoryId("battery"), "Battery", null, false, List.of("cycles"));
        when(categoryRepository.findById(spec.id())).thenReturn(Optional.empty());
        when(categoryRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        DeviceCategory created = service.create(spec);

        assertEquals(new DeviceCategory(spec.id(), "Battery", null, List.of("cycles"), false), created);
        verify(categoryRepository).save(created);
    }

    @Test
    void createRejectsAnAlreadyExistingId() {
        CategoryId id = new CategoryId("drone");
        CategorySpec spec = new CategorySpec(id, "Drone", null, true, List.of());
        when(categoryRepository.findById(id))
                .thenReturn(Optional.of(new DeviceCategory(id, "Drone", null, List.of(), true)));

        assertThrows(IllegalStateException.class, () -> service.create(spec));
    }

    @Test
    void updateReplacesAnExistingCategorysMutableFields() {
        CategoryId id = new CategoryId("drone");
        DeviceCategory existing = new DeviceCategory(id, "Drone", null, List.of("model"), true);
        when(categoryRepository.findById(id)).thenReturn(Optional.of(existing));
        when(categoryRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        CategoryEdit edit = new CategoryEdit("Renamed Drone", null, true, List.of("model", "range-km"));
        DeviceCategory updated = service.update(id, edit);

        assertEquals(new DeviceCategory(id, "Renamed Drone", null, List.of("model", "range-km"), true), updated);
    }

    @Test
    void updateRejectsAnUnknownId() {
        CategoryId id = new CategoryId("no-such-category");
        when(categoryRepository.findById(id)).thenReturn(Optional.empty());

        assertThrows(NoSuchElementException.class,
                () -> service.update(id, new CategoryEdit("Name", null, true, List.of())));
    }
}
