package com.drones.vision.app.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Configuration for the vehicle-onboarding pipeline's PROBE stage ({@code vision.onboarding.*}),
 * docs/plans/active/DRONE-ONBOARDING-PLAN.md §8.1/O5.
 *
 * <p>{@link #probe()} and {@link #remediate()} are bound here. The remaining key §8.1's configuration
 * table lists under {@code vision.onboarding} ({@code installer.enabled}, O10's served installer
 * token) belongs to a wave this one is not: it has no consumer in this module yet, and binding an
 * unread property is worse than not binding it (a future reader would reasonably assume something
 * already reads it). Add it as its own record when its owning wave actually wires a consumer.
 *
 * @param probe     the PROBE stage's guardrail and timeouts — see {@link Probe}
 * @param remediate the CONFIGURE stage's automatic half — see {@link Remediate}
 */
@ConfigurationProperties(prefix = "vision.onboarding")
public record VisionOnboardingProperties(@DefaultValue Probe probe, @DefaultValue Remediate remediate) {

    /**
     * The guardrail (D17): {@code enabled=false} (the default) means {@code VehicleConfigPort} has
     * no real implementation wired ({@code OnboardingWiringConfiguration} falls back to {@code
     * NoopVehicleConfigPort}) and every probe/remediate endpoint behaves exactly as it does today —
     * byte-identical, per the wave table's own guardrail statement.
     *
     * @param enabled         the guardrail; default {@code false}
     * @param inventoryWindow how long a PROBE listens to the link before summarizing the passive
     *                        message inventory — {@link com.drones.vision.flight.domain.port.VehicleConfigPort#probe}'s
     *                        {@code window} argument; default {@value #DEFAULT_INVENTORY_WINDOW}
     * @param requestTimeout  per-request timeout for one {@code REQUEST_MESSAGE}/parameter read
     *                        during a probe; not yet threaded into any constructor call in this
     *                        module (O4's {@code MavlinkVehicleConfigurator} is the expected reader
     *                        once it exists) but bound here now so §8.1's full property surface has
     *                        somewhere to land; default {@value #DEFAULT_REQUEST_TIMEOUT}
     */
    public record Probe(@DefaultValue("false") boolean enabled,
                         @DefaultValue(VisionOnboardingProperties.DEFAULT_INVENTORY_WINDOW) Duration inventoryWindow,
                         @DefaultValue(VisionOnboardingProperties.DEFAULT_REQUEST_TIMEOUT) Duration requestTimeout) {

        public Probe {
            if (inventoryWindow == null || inventoryWindow.isNegative() || inventoryWindow.isZero()) {
                throw new IllegalArgumentException(
                        "vision.onboarding.probe.inventory-window must be positive: " + inventoryWindow);
            }
            if (requestTimeout == null || requestTimeout.isNegative() || requestTimeout.isZero()) {
                throw new IllegalArgumentException(
                        "vision.onboarding.probe.request-timeout must be positive: " + requestTimeout);
            }
        }
    }

    /**
     * The CONFIGURE stage's one automatic action (O8). Separate from {@link Probe#enabled()} on
     * purpose: probing only <em>reads</em>, while this <em>sends</em> a command to an aircraft
     * nobody asked about, which the plan calls its single deliberate exception to "nothing automatic
     * from discovery" (§6.2 rule 3). An operator who wants to look should not thereby be made to
     * transmit, so the two guardrails are independent.
     *
     * @param messageInterval Mechanism A on connect — see {@link MessageInterval}
     */
    public record Remediate(@DefaultValue MessageInterval messageInterval) {

        /**
         * @param enabled when {@code true}, every gateway asks each aircraft it learns for the
         *                configured message set via {@code MAV_CMD_SET_MESSAGE_INTERVAL}. Nothing is
         *                written and nothing persists — the request is session-scoped on the wire.
         *                Default {@code false}, and with it {@code false} no remediator is
         *                constructed at all, so no command can be sent.
         */
        public record MessageInterval(@DefaultValue("false") boolean enabled) {
        }
    }

    static final String DEFAULT_INVENTORY_WINDOW = "10s";
    static final String DEFAULT_REQUEST_TIMEOUT = "3s";
}
