package com.drones.vision.app.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Operational severity thresholds ({@code vision.ops.*}), docs/plans/active/ASSET-FLOWS-PLAN.md §2
 * "Battery thresholds" — the ONE severity source both the Fly cockpit's OSD and the fleet
 * attention-logic now consume (replacing OSD's own previously hardcoded 20/45 and fleet's own
 * previously hardcoded 20/10), mirrored to {@code vision-api}'s {@code
 * com.drones.vision.api.dto.OpsThresholdsResponse} verbatim by {@code
 * com.drones.vision.app.config.wiring.OpsWiringConfiguration#opsThresholds} and served unchanged off
 * {@code GET /api/ops/thresholds}. {@code rc} joined this record for
 * docs/plans/active/FLY-CONTROL-UX-PLAN.md §2 "Frozen contract — neutral gate", exactly mirroring the
 * {@code battery} pattern.
 *
 * @param battery battery urgency thresholds; defaulted as a whole when the {@code vision.ops.battery}
 *                block is entirely absent (Spring's relaxed binder leaves an absent nested record
 *                {@code null} rather than applying its own field-level {@code @DefaultValue}s — same
 *                shape as {@link VisionMavlinkProperties}'s {@code scan}/{@code transmit})
 * @param rc      RC stick neutral-tolerance threshold; defaulted as a whole under the same
 *                entirely-absent-block rule as {@code battery}
 */
@ConfigurationProperties(prefix = "vision.ops")
public record VisionOpsProperties(Battery battery, Rc rc) {

    public VisionOpsProperties {
        if (battery == null) {
            battery = Battery.defaults();
        }
        if (rc == null) {
            rc = Rc.defaults();
        }
    }

    /**
     * @param warningPercent  battery percent at/below which a vehicle reports "battery low"; default
     *                        {@value #DEFAULT_WARNING_PERCENT}
     * @param criticalPercent battery percent at/below which a vehicle reports "battery critical";
     *                        must be strictly less than {@code warningPercent}; default {@value
     *                        #DEFAULT_CRITICAL_PERCENT}
     */
    public record Battery(@DefaultValue(Battery.DEFAULT_WARNING_PERCENT) int warningPercent,
                           @DefaultValue(Battery.DEFAULT_CRITICAL_PERCENT) int criticalPercent) {

        static final String DEFAULT_WARNING_PERCENT = "25";
        static final String DEFAULT_CRITICAL_PERCENT = "10";
        static final int DEFAULT_WARNING_PERCENT_INT = 25;
        static final int DEFAULT_CRITICAL_PERCENT_INT = 10;

        public Battery {
            if (warningPercent < 0 || warningPercent > 100) {
                throw new IllegalArgumentException(
                        "vision.ops.battery.warning-percent must be in [0,100]: " + warningPercent);
            }
            if (criticalPercent < 0 || criticalPercent > 100) {
                throw new IllegalArgumentException(
                        "vision.ops.battery.critical-percent must be in [0,100]: " + criticalPercent);
            }
            if (criticalPercent >= warningPercent) {
                throw new IllegalArgumentException(
                        "vision.ops.battery.critical-percent must be < warning-percent: " + criticalPercent
                                + " >= " + warningPercent);
            }
        }

        static Battery defaults() {
            return new Battery(DEFAULT_WARNING_PERCENT_INT, DEFAULT_CRITICAL_PERCENT_INT);
        }
    }

    /**
     * @param neutralTolerancePercent how far (in percent) a live stick axis may sit from its rest
     *                                position and still count as "neutral" for the web-side arm gate
     *                                (docs/plans/active/FLY-CONTROL-UX-PLAN.md §2); must be in [1,25];
     *                                default {@value #DEFAULT_NEUTRAL_TOLERANCE_PERCENT}
     */
    public record Rc(@DefaultValue(Rc.DEFAULT_NEUTRAL_TOLERANCE_PERCENT) int neutralTolerancePercent) {

        static final String DEFAULT_NEUTRAL_TOLERANCE_PERCENT = "5";
        static final int DEFAULT_NEUTRAL_TOLERANCE_PERCENT_INT = 5;

        public Rc {
            if (neutralTolerancePercent < 1 || neutralTolerancePercent > 25) {
                throw new IllegalArgumentException(
                        "vision.ops.rc.neutral-tolerance-percent must be in [1,25]: " + neutralTolerancePercent);
            }
        }

        static Rc defaults() {
            return new Rc(DEFAULT_NEUTRAL_TOLERANCE_PERCENT_INT);
        }
    }
}
