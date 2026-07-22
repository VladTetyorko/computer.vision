package com.drones.vision.app.devsupport;

import com.drones.vision.domain.model.CategoryId;
import com.drones.vision.domain.model.DeviceCategory;
import com.drones.vision.domain.port.out.CategoryRepositoryPort;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory {@link CategoryRepositoryPort}: dev/Phase-0 fallback, seeded at
 * construction with the default category set from
 * docs/ASSET-MODEL-PLAN.md §4 (drone/fpv-drone/ip-camera/esp32-cam/
 * usb-camera/robot/simulated), no durability across restarts.
 *
 * <p>Replaced by {@code adapter-persistence} (JPA/Postgres), planned for
 * Phase 2.
 */
public final class InMemoryCategoryRepository implements CategoryRepositoryPort {

    private final Map<CategoryId, DeviceCategory> categories = new ConcurrentHashMap<>();

    public InMemoryCategoryRepository() {
        seed(new DeviceCategory(new CategoryId("drone"), "Drone", null,
                List.of("model", "range-km", "max-altitude-m", "weight-kg")));
        seed(new DeviceCategory(new CategoryId("fpv-drone"), "FPV Drone", new CategoryId("drone"),
                List.of("frame-size-in", "vtx-power-mw", "flight-controller")));
        seed(new DeviceCategory(new CategoryId("ip-camera"), "IP Camera", null,
                List.of("ip-address", "resolution", "onvif-profile")));
        seed(new DeviceCategory(new CategoryId("esp32-cam"), "ESP32-CAM", new CategoryId("ip-camera"),
                List.of("firmware-version", "wifi-ssid")));
        seed(new DeviceCategory(new CategoryId("usb-camera"), "USB Camera", null,
                List.of("device-path", "resolution")));
        seed(new DeviceCategory(new CategoryId("robot"), "Robot", null,
                List.of("wheelbase-m", "max-speed-mps", "payload-kg")));
        seed(new DeviceCategory(new CategoryId("simulated"), "Simulated", null,
                List.of("scenario", "seed")));
    }

    private void seed(DeviceCategory category) {
        categories.put(category.id(), category);
    }

    @Override
    public DeviceCategory save(DeviceCategory category) {
        categories.put(category.id(), category);
        return category;
    }

    @Override
    public Optional<DeviceCategory> findById(CategoryId id) {
        return Optional.ofNullable(categories.get(id));
    }

    @Override
    public List<DeviceCategory> findAll() {
        return List.copyOf(categories.values());
    }
}
