package com.drones.vision.api.support;

import com.drones.vision.api.dto.TelemetryIntakeResponse;
import com.drones.vision.api.dto.VideoIntakeResponse;

import java.time.Instant;
import java.util.Objects;

/**
 * The facts {@code GET /api/discovery/status} (docs/plans/active/SOURCE-ONBOARDING-2-PLAN.md
 * &sect;3.2 C2) needs that this module cannot compute itself — {@code vision-api} may not depend on
 * {@code adapter-mavlink} (the standing MAVLink lobby's own module) or on {@code vision-app}'s
 * {@code @ConfigurationProperties}/wiring machinery, the same dependency-rule boundary {@code
 * SystemNetworkController}'s own {@code int mavlinkPort} plain-value bean already crosses.
 *
 * <p>A plain {@code Supplier<DiscoveryStatusFacts>} bean, not a new named port interface — this is
 * a one-shot read with a single caller ({@code DiscoveryStatusController}), so a JDK functional
 * type earns its place over inventing a type for it (.claude/skills/java-clean-code/SKILL.md §1).
 * {@code vision-app}'s {@code DiscoveryWiringConfiguration} supplies the bean, reading {@code
 * MavlinkTelemetrySource#intakeStatus(int)}, {@code DiscoveryInboxRunner#lastSweepAt()}, and (when
 * mediamtx publish is configured) {@code MediamtxPathScanner#scan}/{@code lastStatus()} fresh on
 * every call — this record itself holds no state and does no I/O.
 *
 * @param sweepSeconds    the discovery-inbox sweep's configured interval
 * @param lastSweepAt     when the sweep runner last completed a sweep; {@code null} before the
 *                        first one
 * @param telemetryIntake the standing MAVLink lobby's own reachability/decode counters; never
 *                        {@code null} — {@code MavlinkTelemetrySource#intakeStatus} never returns
 *                        {@code null} either, always answering {@code unbound(...)} at worst
 * @param videoIntake     mediamtx publish reachability; {@code null} when mediamtx publish is
 *                        unconfigured
 */
public record DiscoveryStatusFacts(int sweepSeconds, Instant lastSweepAt, TelemetryIntakeResponse telemetryIntake,
                                    VideoIntakeResponse videoIntake) {

    public DiscoveryStatusFacts {
        Objects.requireNonNull(telemetryIntake, "telemetryIntake must not be null");
    }
}
