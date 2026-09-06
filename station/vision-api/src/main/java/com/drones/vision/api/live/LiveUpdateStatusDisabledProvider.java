package com.drones.vision.api.live;

import com.drones.vision.platform.Health;
import com.drones.vision.platform.SubsystemStatus;
import com.drones.vision.platform.SubsystemStatusPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * The {@code vision.live.enabled=false} companion to {@link LiveUpdateStatusProvider} — see that
 * class's javadoc. Reports {@link Health#DISABLED}, excluded from {@code SystemStatusController}'s
 * overall rollup, rather than being silently absent from {@code GET /api/system/status} the way
 * {@code /api/live} itself 404s when this flag is off.
 */
@Component
@ConditionalOnProperty(prefix = "vision.live", name = "enabled", havingValue = "false")
public class LiveUpdateStatusDisabledProvider implements SubsystemStatusPort {

    @Override
    public SubsystemStatus status() {
        return new SubsystemStatus(LiveUpdateStatusProvider.SUBSYSTEM_ID, "Live updates (SSE)", Health.DISABLED,
                "Live updates are disabled (vision.live.enabled=false)", null,
                "Set vision.live.enabled=true to enable /api/live");
    }
}
