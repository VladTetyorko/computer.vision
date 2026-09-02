package com.drones.vision.app.config.wiring;

import com.drones.vision.api.dto.OpsThresholdsResponse;
import com.drones.vision.app.config.properties.VisionOpsProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pure unit coverage (no Spring context) for {@code vision.ops.*}'s mapping onto the {@link
 * OpsThresholdsResponse} bean {@code OpsThresholdsController} (vision-api) serves off {@code GET
 * /api/ops/thresholds} — mirrors {@link TrackingWiringTest}'s own no-context style for a plain
 * properties-to-bean mapping method.
 */
class OpsWiringConfigurationTest {

    @Test
    void mapsBatteryThresholdsVerbatimOntoTheResponse() {
        OpsThresholdsResponse response = new OpsWiringConfiguration().opsThresholds(new VisionOpsProperties(
                new VisionOpsProperties.Battery(30, 12), new VisionOpsProperties.Rc(8)));

        assertEquals(30, response.battery().warningPercent());
        assertEquals(12, response.battery().criticalPercent());
    }

    @Test
    void defaultPropertiesYieldTwentyFiveAndTen() {
        OpsThresholdsResponse response = new OpsWiringConfiguration().opsThresholds(new VisionOpsProperties(null, null));

        assertEquals(25, response.battery().warningPercent());
        assertEquals(10, response.battery().criticalPercent());
    }

    @Test
    void mapsNeutralTolerancePercentVerbatimOntoTheResponse() {
        OpsThresholdsResponse response = new OpsWiringConfiguration().opsThresholds(new VisionOpsProperties(
                new VisionOpsProperties.Battery(30, 12), new VisionOpsProperties.Rc(15)));

        assertEquals(15, response.rc().neutralTolerancePercent());
    }

    @Test
    void defaultPropertiesYieldFivePercentNeutralTolerance() {
        OpsThresholdsResponse response = new OpsWiringConfiguration().opsThresholds(new VisionOpsProperties(null, null));

        assertEquals(5, response.rc().neutralTolerancePercent());
    }
}
