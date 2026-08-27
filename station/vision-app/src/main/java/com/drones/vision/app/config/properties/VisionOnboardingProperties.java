package com.drones.vision.app.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.List;

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
 * @param passport  the flight passport's automatic capture — see {@link Passport}
 */
@ConfigurationProperties(prefix = "vision.onboarding")
public record VisionOnboardingProperties(@DefaultValue Probe probe, @DefaultValue Remediate remediate,
                                          @DefaultValue Passport passport) {

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
     * @param parameters      the vehicle parameters a PROBE reads, overriding the adapter's
     *                        firmware-verified default list. <b>Empty (the default) keeps that
     *                        list</b> — this exists because parameter names are firmware-version and
     *                        vehicle-type state ({@code FENCE_ALT_MAX} exists on Copter, not on
     *                        Rover; the system id is spelled differently either side of ArduPilot
     *                        4.7), not because the default is expected to be wrong
     *                        (docs/plans/active/FLEET-RADIO-PLAN.md F12). A stale override degrades
     *                        a probe silently rather than failing, since MAVLink cannot report an
     *                        unknown parameter name — so override deliberately or not at all
     */
    public record Probe(@DefaultValue("false") boolean enabled,
                         @DefaultValue(VisionOnboardingProperties.DEFAULT_INVENTORY_WINDOW) Duration inventoryWindow,
                         @DefaultValue(VisionOnboardingProperties.DEFAULT_REQUEST_TIMEOUT) Duration requestTimeout,
                         List<String> parameters) {

        public Probe {
            parameters = parameters == null ? List.of() : List.copyOf(parameters);
            if (parameters.stream().anyMatch(name -> name == null || name.isBlank())) {
                throw new IllegalArgumentException(
                        "vision.onboarding.probe.parameters must not contain a blank name: " + parameters);
            }
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

    /**
     * The flight passport's automatic capture (docs/plans/active/DRONE-ONBOARDING-PLAN.md §2.4,
     * Wave O11): whether a usage's PREFLIGHT/POSTFLIGHT phase transition (folded by {@code
     * UsageTracker}, vision-perception) triggers {@code
     * VehicleProfileService#captureSnapshot} and attaches the resulting {@code VehicleProfile}
     * snapshot to the {@code AssetUsage}.
     *
     * @param enabled the guardrail; default {@code false} — with it off, {@code
     *                OnboardingWiringConfiguration} declares no {@code UsagePhaseObserver} bean at
     *                all, and {@code ApplicationServiceWiring#usageTracker} falls back to the
     *                no-op observer, so a usage's phase folding is byte-identical to before O11
     *                wired anything up. {@code true} alone is not sufficient to capture anything
     *                real — see {@link Probe#enabled()}'s own javadoc and {@code
     *                OnboardingWiringConfiguration}'s startup-warning javadoc for what happens when
     *                this is on while probing is off.
     */
    public record Passport(@DefaultValue("false") boolean enabled) {
    }

    static final String DEFAULT_INVENTORY_WINDOW = "10s";
    static final String DEFAULT_REQUEST_TIMEOUT = "3s";
}
