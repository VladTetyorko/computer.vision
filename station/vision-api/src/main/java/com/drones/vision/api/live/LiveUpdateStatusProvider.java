package com.drones.vision.api.live;

import com.drones.vision.platform.Health;
import com.drones.vision.platform.SubsystemStatus;
import com.drones.vision.platform.SubsystemStatusPort;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * {@code live-updates}'s {@link SubsystemStatusPort} (docs/plans/done/SYSTEM-STATUS-PLAN.md §4.2):
 * reports whether the SSE data plane ({@link LiveUpdateRegistry}) is dispatching. Active whenever
 * {@link LiveUpdateRegistry} itself is (same {@code vision.live.enabled} condition, repeated here
 * rather than read via {@code @ConditionalOnBean} — see {@code CvWiring} in vision-app,
 * docs/plans/active/CV-RECONNECT-PLAN.md §3.3, for why that pattern is avoided repo-wide: it is
 * sensitive to bean-definition-order, which a component-scanned peer in the same package cannot rely
 * on any more safely than a cross-module {@code @Bean} method can). {@link
 * LiveUpdateStatusDisabledProvider} is the companion bean for the opposite condition.
 *
 * <p>Unlike {@code cv-service}/{@code mavlink-link}/{@code video-publish}, this subsystem has no
 * external process to be unreachable from — it dispatches purely in-process — so there is no
 * {@link Health#DOWN} case to detect here: this bean's mere presence and ability to answer already
 * proves dispatch is alive. It can still be honestly {@link Health#DEGRADED} though, self-reported:
 * if any {@link LiveUpdateRegistry} ring buffer has ever overflowed ({@code everDropped}), a slow
 * consumer missed at least one update and the plan's "honest status" doctrine (UX-DESIGN §7.2) says
 * that must surface rather than be silently absorbed.
 */
@Component
@ConditionalOnProperty(prefix = "vision.live", name = "enabled", matchIfMissing = true)
public class LiveUpdateStatusProvider implements SubsystemStatusPort {

    /**
     * This subsystem's id, shared with {@link LiveUpdateStatusDisabledProvider} (the {@code
     * vision.live.enabled=false} companion) and with {@link SystemStatusSampler}, which excludes
     * exactly this id from its change-detection comparison to avoid the self-feedback hazard of
     * broadcasting on the very registry it samples through (docs/plans/active/
     * LIVE-POLL-RETIREMENT-PLAN.md &sect;3 D3/&sect;4.2, wave L4).
     */
    static final String SUBSYSTEM_ID = "live-updates";

    private final LiveUpdateRegistry registry;

    /**
     * {@code @Qualifier("liveUpdateRegistry")} disambiguates from the port-typed alias beans
     * {@code vision-app}'s wiring exposes for the same singleton ({@code FleetLiveUpdatePort}/{@code
     * TelemetryLiveUpdatePort}/{@code DetectionLiveUpdatePort}/{@code MapLiveUpdatePort}/{@code
     * EventLiveUpdatePort} — each an {@code @Bean} method that hands back this exact instance under a
     * narrower declared type). Once those are constructed, Spring resolves their <em>runtime</em>
     * type as {@code LiveUpdateRegistry} too, so a plain by-type autowire of the concrete class here
     * finds six candidates, not one — this constructor needs the concrete class specifically (for
     * {@link #status()}'s {@code connectionCount()}/{@code anyBufferEverDropped()} calls, neither on
     * any of those five port interfaces), so the qualifier pins it to the one bean actually named
     * {@code liveUpdateRegistry} (this class's own {@code @Component} default name).
     */
    public LiveUpdateStatusProvider(@Qualifier("liveUpdateRegistry") LiveUpdateRegistry registry) {
        this.registry = registry;
    }

    @Override
    public SubsystemStatus status() {
        int connections = registry.connectionCount();
        String connectionsPhrase = connections + (connections == 1 ? " live connection open" : " live connections open");
        if (registry.anyBufferEverDropped()) {
            String detail = connectionsPhrase + "; a slow consumer has missed at least one update";
            return new SubsystemStatus(SUBSYSTEM_ID, "Live updates (SSE)", Health.DEGRADED, detail, null,
                    "A client reconnect (or page refresh) resubscribes to a fresh buffer");
        }
        return new SubsystemStatus(SUBSYSTEM_ID, "Live updates (SSE)", Health.OK, connectionsPhrase, null, null);
    }
}
