package com.drones.vision.app.config.wiring;

import com.drones.vision.api.dto.BatteryThresholdsResponse;
import com.drones.vision.api.dto.OpsThresholdsResponse;
import com.drones.vision.app.config.properties.VisionOpsProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires {@code vision.ops.*} (docs/plans/active/ASSET-FLOWS-PLAN.md §2 "Battery thresholds") onto the
 * one {@link OpsThresholdsResponse} bean {@code OpsThresholdsController} (vision-api) serves verbatim
 * off {@code GET /api/ops/thresholds}. Deploy-time config, so the response is built once here rather
 * than re-read per request — the same shape {@code TrackingWiring#cvTrackerRoster} already uses for a
 * config-backed vision-api DTO bean.
 */
@Configuration
@EnableConfigurationProperties(VisionOpsProperties.class)
public class OpsWiringConfiguration {

    /**
     * @param properties the bound {@code vision.ops.*} configuration
     * @return the frozen {@code GET /api/ops/thresholds} response, mirroring {@code properties}
     *         verbatim
     */
    @Bean
    public OpsThresholdsResponse opsThresholds(VisionOpsProperties properties) {
        VisionOpsProperties.Battery battery = properties.battery();
        return new OpsThresholdsResponse(
                new BatteryThresholdsResponse(battery.warningPercent(), battery.criticalPercent()));
    }
}
