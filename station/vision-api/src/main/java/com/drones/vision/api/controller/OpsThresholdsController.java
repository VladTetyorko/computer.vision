package com.drones.vision.api.controller;

import com.drones.vision.api.dto.OpsThresholdsResponse;
import com.drones.vision.api.security.OpenByDesign;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

/**
 * Driving REST adapter for operational severity thresholds (docs/plans/active/ASSET-FLOWS-PLAN.md §2
 * "Battery thresholds") — today just the battery warning/critical percents, the ONE severity source
 * the Fly cockpit's OSD and the fleet attention list both consume, replacing the two previously
 * hardcoded, disagreeing thresholds (OSD's own 20/45, fleet's own 20/10).
 *
 * <p>Constructor-injected with the response itself, a plain {@link OpsThresholdsResponse} bean
 * rather than a use-case port — deploy-time config, not fleet data, the same shape {@link
 * CvTrackersController}/{@link CvModelsController} already use for a config-backed roster. {@code
 * vision-app}'s {@code com.drones.vision.app.config.wiring.OpsWiringConfiguration#opsThresholds}
 * builds the one instance this endpoint ever serves, straight off {@code VisionOpsProperties}.
 */
@RestController
public class OpsThresholdsController {

    private final OpsThresholdsResponse thresholds;

    public OpsThresholdsController(OpsThresholdsResponse thresholds) {
        this.thresholds = Objects.requireNonNull(thresholds, "thresholds must not be null");
    }

    /**
     * The battery urgency thresholds every severity-coloring surface should use.
     *
     * @return {@code {"battery":{"warningPercent":...,"criticalPercent":...}}}; never throws
     */
    @OpenByDesign(reason = "Display config (battery severity thresholds), not fleet or per-user data "
            + "-- any signed-in caller may read it.")
    @GetMapping("/api/ops/thresholds")
    public OpsThresholdsResponse thresholds() {
        return thresholds;
    }
}
