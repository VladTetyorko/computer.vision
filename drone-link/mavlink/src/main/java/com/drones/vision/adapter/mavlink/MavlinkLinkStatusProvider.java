package com.drones.vision.adapter.mavlink;

import com.drones.mavlink.session.LinkHealth;
import com.drones.vision.platform.Health;
import com.drones.vision.platform.SubsystemStatus;
import com.drones.vision.platform.SubsystemStatusPort;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * {@code mavlink-link}'s {@link SubsystemStatusPort} (docs/plans/active/SYSTEM-STATUS-PLAN.md §4.2):
 * reports whether currently-claimed MAVLink vehicles are actually heard from, using {@link
 * LinkHealth.Health} per claimed vehicle — this class is {@link LinkHealth}'s first production
 * consumer; every prior call site was test-only (docs/plans/active/MAVLINK-CORE-PLAN.md).
 *
 * <p>Takes a {@code Supplier<List<LinkHealth.Health>>} rather than {@link MavlinkTelemetrySource}
 * itself so this class's rollup logic is testable with a plain lambda, the same shape {@code
 * CvStatusProvider} (cv/grpc) uses for its own supervisor lookup. {@code vision-app} wires {@code
 * mavlinkTelemetrySource::claimedVehicleHealth}.
 *
 * <h2>Per-vehicle mapping (SYSTEM-STATUS-PLAN.md §4.2's table)</h2>
 * {@code connected} → OK; heard before but now stale ({@code lastHeard} present, {@code connected}
 * false) → DEGRADED; never heard ({@code lastHeard} null) → DOWN.
 *
 * <h2>Rolling many claimed vehicles into one status</h2>
 * The gateway (docs/plans/active/DRONE-INFRA-PLAN.md I-a) supports N claimed vehicles at once, but
 * {@link LinkHealth.Health} carries no vehicle identity to name in {@code detail} — so this reports
 * one aggregate: all connected → OK; any vehicle never heard from → DOWN (worst case); otherwise (all
 * heard from at some point, at least one now stale) → DEGRADED. {@code detail} always includes the
 * connected count and the aggregate drop rate per the plan; {@code since} is the stalest
 * still-known {@code lastHeard} when not OK.
 *
 * <p>No claimed vehicle at all is reported as {@link Health#UNKNOWN}, not {@link Health#DISABLED} —
 * see {@link MavlinkTelemetrySource#claimedVehicleHealth()}'s javadoc: this subsystem has no off
 * switch, so an empty claim list means "nothing configured yet", not "deliberately turned off".
 */
public final class MavlinkLinkStatusProvider implements SubsystemStatusPort {

    private final Supplier<List<LinkHealth.Health>> claimedVehicleHealth;

    public MavlinkLinkStatusProvider(Supplier<List<LinkHealth.Health>> claimedVehicleHealth) {
        this.claimedVehicleHealth = claimedVehicleHealth;
    }

    @Override
    public SubsystemStatus status() {
        List<LinkHealth.Health> vehicles = claimedVehicleHealth.get();
        if (vehicles.isEmpty()) {
            return new SubsystemStatus("mavlink-link", "MAVLink link", Health.UNKNOWN,
                    "No MAVLink vehicle is currently claimed", null, null);
        }

        long connected = vehicles.stream().filter(LinkHealth.Health::connected).count();
        int total = vehicles.size();
        double dropRatePercent = 100.0 * vehicles.stream().mapToDouble(LinkHealth.Health::dropRate).average().orElse(0.0);
        String dropRate = String.format(Locale.ROOT, "%.1f", dropRatePercent);

        if (connected == total) {
            return new SubsystemStatus("mavlink-link", "MAVLink link", Health.OK,
                    connected + "/" + total + " claimed MAVLink vehicle(s) connected; drop rate " + dropRate + "%",
                    null, null);
        }

        boolean anyNeverHeard = vehicles.stream().anyMatch(v -> v.lastHeard() == null);
        Instant stalestLastHeard = vehicles.stream()
                .filter(v -> !v.connected() && v.lastHeard() != null)
                .min(Comparator.comparing(LinkHealth.Health::lastHeard))
                .map(LinkHealth.Health::lastHeard)
                .orElse(null);

        if (anyNeverHeard) {
            return new SubsystemStatus("mavlink-link", "MAVLink link", Health.DOWN,
                    connected + "/" + total + " claimed MAVLink vehicle(s) connected; at least one has "
                            + "never been heard from; drop rate " + dropRate + "%",
                    stalestLastHeard, "Check the MAVLink radio/link and vehicle power");
        }

        String age = stalestLastHeard == null ? "unknown"
                : Duration.between(stalestLastHeard, Instant.now()).toSeconds() + "s";
        return new SubsystemStatus("mavlink-link", "MAVLink link", Health.DEGRADED,
                connected + "/" + total + " claimed MAVLink vehicle(s) connected; oldest stale " + age
                        + " ago; drop rate " + dropRate + "%",
                stalestLastHeard, "Check the MAVLink radio/link and vehicle power");
    }
}
