package com.drones.vision.adapter.mavlink;

import com.drones.mavlink.session.LinkHealth;
import com.drones.vision.kernel.DeviceId;
import com.drones.vision.platform.Health;
import com.drones.vision.platform.SubsystemStatus;
import com.drones.vision.platform.SubsystemStatusPort;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * {@code mavlink-link}'s {@link SubsystemStatusPort} (docs/plans/done/SYSTEM-STATUS-PLAN.md §4.2):
 * reports whether currently-claimed MAVLink vehicles are actually heard from, using {@link
 * LinkHealth.Health} per claimed vehicle — this class is {@link LinkHealth}'s first production
 * consumer; every prior call site was test-only (docs/plans/active/MAVLINK-CORE-PLAN.md).
 *
 * <p>Takes a {@code Supplier<Map<DeviceId, LinkHealth.Health>>} rather than {@link
 * MavlinkTelemetrySource} itself so this class's rollup logic is testable with a plain lambda, the
 * same shape {@code CvStatusProvider} (cv/grpc) uses for its own supervisor lookup. {@code
 * vision-app} wires {@code mavlinkTelemetrySource::claimedVehicleHealth}.
 *
 * <h2>Per-vehicle identity, not a fleet average (FLEET-RADIO-PLAN.md D4/R4)</h2>
 * Before R4, {@link LinkHealth.Health} carried no vehicle identity, so this class could only ever
 * roll every claimed vehicle's drop rate into one average and report "at least one vehicle" is
 * degraded — an operator flying three aircraft learned that <i>something</i> was wrong, never
 * <i>which</i> radio to go check. {@code Map<DeviceId, Health>} keeps that identity all the way
 * from {@link com.drones.mavlink.session.DefaultLinkHealth#of} through {@link
 * MavlinkGateway#claimedVehicleHealth()} to here, so {@link #status()}'s {@code detail} names the
 * one worst-off device rather than an anonymous count.
 *
 * <h2>Per-vehicle verdict, then worst-case rollup</h2>
 * Each vehicle is independently classified using {@code thresholds}:
 * <ul>
 *   <li>never heard from ({@code lastHeard == null}) → DOWN;</li>
 *   <li>connected, but {@code dropRate * 100 >= thresholds.dropRateAlarmPercent()} → DOWN — the
 *       socket is technically still receiving, but losing that much of the expected stream is
 *       operationally no better than silence;</li>
 *   <li>heard before but now stale ({@code connected == false}, {@code lastHeard != null}) →
 *       DEGRADED;</li>
 *   <li>connected, but {@code dropRate * 100 >= thresholds.dropRateWarnPercent()} → DEGRADED;</li>
 *   <li>otherwise → OK.</li>
 * </ul>
 * The reported {@link Health} is the worst verdict across every claimed vehicle (DOWN beats
 * DEGRADED beats OK, matching this class's pre-D4 "worst case wins" philosophy); {@code detail}
 * names one device achieving that worst verdict (picked deterministically — the lowest {@link
 * DeviceId} — when more than one ties) plus the fleet's connected count and average drop rate for
 * context. {@code since} is that device's own {@code lastHeard} (may be {@code null}).
 *
 * <p>No claimed vehicle at all is reported as {@link Health#UNKNOWN}, not {@link Health#DISABLED} —
 * see {@link MavlinkTelemetrySource#claimedVehicleHealth()}'s javadoc: this subsystem has no off
 * switch, so an empty claim list means "nothing configured yet", not "deliberately turned off".
 */
public final class MavlinkLinkStatusProvider implements SubsystemStatusPort {

    private final Supplier<Map<DeviceId, LinkHealth.Health>> claimedVehicleHealth;
    private final MavlinkSettings.LinkStatus thresholds;

    public MavlinkLinkStatusProvider(Supplier<Map<DeviceId, LinkHealth.Health>> claimedVehicleHealth,
                                      MavlinkSettings.LinkStatus thresholds) {
        this.claimedVehicleHealth = Objects.requireNonNull(claimedVehicleHealth, "claimedVehicleHealth");
        this.thresholds = Objects.requireNonNull(thresholds, "thresholds");
    }

    @Override
    public SubsystemStatus status() {
        Map<DeviceId, LinkHealth.Health> vehicles = claimedVehicleHealth.get();
        if (vehicles.isEmpty()) {
            return new SubsystemStatus("mavlink-link", "MAVLink link", Health.UNKNOWN,
                    "No MAVLink vehicle is currently claimed", null, null);
        }

        long connected = vehicles.values().stream().filter(LinkHealth.Health::connected).count();
        int total = vehicles.size();
        double averageDropRatePercent = 100.0 * vehicles.values().stream()
                .mapToDouble(LinkHealth.Health::dropRate).average().orElse(0.0);
        String averageDropRate = String.format(Locale.ROOT, "%.1f", averageDropRatePercent);

        Map.Entry<DeviceId, LinkHealth.Health> worstDown = worst(vehicles, this::isDown);
        if (worstDown != null) {
            return new SubsystemStatus("mavlink-link", "MAVLink link", Health.DOWN,
                    connected + "/" + total + " claimed MAVLink vehicle(s) connected; device "
                            + worstDown.getKey().value() + " " + reasonFor(worstDown.getValue())
                            + "; average drop rate " + averageDropRate + "%",
                    worstDown.getValue().lastHeard(), "Check the MAVLink radio/link and vehicle power");
        }

        Map.Entry<DeviceId, LinkHealth.Health> worstDegraded = worst(vehicles, this::isDegraded);
        if (worstDegraded != null) {
            return new SubsystemStatus("mavlink-link", "MAVLink link", Health.DEGRADED,
                    connected + "/" + total + " claimed MAVLink vehicle(s) connected; device "
                            + worstDegraded.getKey().value() + " " + reasonFor(worstDegraded.getValue())
                            + "; average drop rate " + averageDropRate + "%",
                    worstDegraded.getValue().lastHeard(), "Check the MAVLink radio/link and vehicle power");
        }

        return new SubsystemStatus("mavlink-link", "MAVLink link", Health.OK,
                connected + "/" + total + " claimed MAVLink vehicle(s) connected; average drop rate "
                        + averageDropRate + "%",
                null, null);
    }

    private boolean isDown(LinkHealth.Health health) {
        return health.lastHeard() == null
                || (health.connected() && dropRatePercent(health) >= thresholds.dropRateAlarmPercent());
    }

    private boolean isDegraded(LinkHealth.Health health) {
        if (health.lastHeard() == null) {
            return false; // already reported DOWN by isDown
        }
        if (!health.connected()) {
            return true; // heard before, now stale
        }
        return dropRatePercent(health) >= thresholds.dropRateWarnPercent();
    }

    private String reasonFor(LinkHealth.Health health) {
        if (health.lastHeard() == null) {
            return "has never been heard from";
        }
        if (health.connected()) {
            double threshold = dropRatePercent(health) >= thresholds.dropRateAlarmPercent()
                    ? thresholds.dropRateAlarmPercent() : thresholds.dropRateWarnPercent();
            String severity = dropRatePercent(health) >= thresholds.dropRateAlarmPercent() ? "alarm" : "warn";
            return String.format(Locale.ROOT, "drop rate %.1f%% exceeds the %s threshold (%.1f%%)",
                    dropRatePercent(health), severity, threshold);
        }
        long ageSeconds = Duration.between(health.lastHeard(), Instant.now()).toSeconds();
        return "has gone silent (last heard " + ageSeconds + "s ago)";
    }

    private static double dropRatePercent(LinkHealth.Health health) {
        return 100.0 * health.dropRate();
    }

    /** The worst (by {@link DeviceId} value, for a deterministic pick among ties) vehicle matching {@code verdict}. */
    private static Map.Entry<DeviceId, LinkHealth.Health> worst(
            Map<DeviceId, LinkHealth.Health> vehicles, Predicate<LinkHealth.Health> verdict) {
        return vehicles.entrySet().stream()
                .filter(entry -> verdict.test(entry.getValue()))
                .min(Comparator.comparing(entry -> entry.getKey().value()))
                .orElse(null);
    }
}
