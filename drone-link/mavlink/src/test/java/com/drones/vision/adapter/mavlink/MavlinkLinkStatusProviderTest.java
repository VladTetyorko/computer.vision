package com.drones.vision.adapter.mavlink;

import com.drones.mavlink.CompId;
import com.drones.mavlink.PeerId;
import com.drones.mavlink.SysId;
import com.drones.mavlink.session.LinkHealth;
import com.drones.vision.kernel.DeviceId;
import com.drones.vision.platform.Health;
import com.drones.vision.platform.SubsystemStatus;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FLEET-RADIO-PLAN.md D4/R4: {@link LinkHealth.Health} now carries a {@link PeerId}, and this
 * provider's whole rollup is keyed by {@link DeviceId} — these tests pin the payoff of that change,
 * not just the pre-D4 fleet-average behavior it replaces.
 */
class MavlinkLinkStatusProviderTest {

    /** {@link MavlinkSettings.LinkStatus#defaults()}: warn at 5%, alarm at 20% drop rate. */
    private static final MavlinkSettings.LinkStatus DEFAULT_THRESHOLDS = MavlinkSettings.LinkStatus.defaults();

    @Test
    void reportsUnknownWhenNoVehicleIsClaimed() {
        MavlinkLinkStatusProvider provider = new MavlinkLinkStatusProvider(Map::of, DEFAULT_THRESHOLDS);

        assertEquals(Health.UNKNOWN, provider.status().health());
    }

    @Test
    void reportsOkWhenEveryClaimedVehicleIsHealthy() {
        Map<DeviceId, LinkHealth.Health> vehicles = Map.of(DeviceId.random(), connected(0.0));
        MavlinkLinkStatusProvider provider = new MavlinkLinkStatusProvider(() -> vehicles, DEFAULT_THRESHOLDS);

        assertEquals(Health.OK, provider.status().health());
    }

    /**
     * The exact scenario FLEET-RADIO-PLAN.md R4's expected result names: two claimed vehicles,
     * only one degraded. Before D4, {@link LinkHealth.Health} carried no identity, so the old
     * provider could only ever average the two together and report "at least one vehicle" is bad.
     */
    @Test
    void namesTheOneBadDeviceAmongTwoWithoutAveragingItAway() {
        DeviceId healthyDevice = DeviceId.random();
        DeviceId badDevice = DeviceId.random();
        Map<DeviceId, LinkHealth.Health> vehicles = Map.of(
                healthyDevice, connected(0.0),
                badDevice, connected(0.30)); // 30% drop rate -- above the 20% alarm default
        MavlinkLinkStatusProvider provider = new MavlinkLinkStatusProvider(() -> vehicles, DEFAULT_THRESHOLDS);

        SubsystemStatus status = provider.status();
        assertEquals(Health.DOWN, status.health());
        assertTrue(status.detail().contains(badDevice.value().toString()),
                "detail must name the specific bad device, not just report a fleet average: " + status.detail());
        // Both vehicles are "connected" (socket/heartbeat liveness) -- the bad one is connected but
        // its drop rate breaches the alarm threshold, a genuinely different fact from never having
        // been heard at all. "2/2 connected" is correct here; only the drop-rate reason names it.
        assertTrue(status.detail().contains("2/2"), "must still report the fleet connected count: " + status.detail());
    }

    @Test
    void reportsDownWhenAClaimedVehicleHasNeverBeenHeardFrom() {
        Map<DeviceId, LinkHealth.Health> vehicles = Map.of(DeviceId.random(), neverHeard());
        MavlinkLinkStatusProvider provider = new MavlinkLinkStatusProvider(() -> vehicles, DEFAULT_THRESHOLDS);

        SubsystemStatus status = provider.status();
        assertEquals(Health.DOWN, status.health());
        assertTrue(status.detail().contains("never been heard"), status.detail());
    }

    @Test
    void reportsDegradedWhenAConnectedVehicleHasGoneStale() {
        Map<DeviceId, LinkHealth.Health> vehicles = Map.of(DeviceId.random(), stale());
        MavlinkLinkStatusProvider provider = new MavlinkLinkStatusProvider(() -> vehicles, DEFAULT_THRESHOLDS);

        assertEquals(Health.DEGRADED, provider.status().health());
    }

    @Test
    void dropRateThresholdsAreConfiguredNotHardcoded() {
        Map<DeviceId, LinkHealth.Health> vehicles = Map.of(DeviceId.random(), connected(0.10)); // 10% drop

        MavlinkLinkStatusProvider lenient = new MavlinkLinkStatusProvider(() -> vehicles,
                new MavlinkSettings.LinkStatus(50.0, 90.0, Duration.ofSeconds(2)));
        assertEquals(Health.OK, lenient.status().health(), "10% drop must pass a 50%/90% threshold pair");

        MavlinkLinkStatusProvider strict = new MavlinkLinkStatusProvider(() -> vehicles,
                new MavlinkSettings.LinkStatus(1.0, 5.0, Duration.ofSeconds(2)));
        assertEquals(Health.DOWN, strict.status().health(), "the same 10% drop must alarm a 1%/5% threshold pair");
    }

    private static LinkHealth.Health connected(double dropRate) {
        return new LinkHealth.Health(new PeerId(new SysId(1), new CompId(1)), true, Instant.now(), 100, 0, dropRate);
    }

    private static LinkHealth.Health neverHeard() {
        return new LinkHealth.Health(new PeerId(new SysId(2), new CompId(1)), false, null, 0, 0, 0.0);
    }

    private static LinkHealth.Health stale() {
        return new LinkHealth.Health(new PeerId(new SysId(3), new CompId(1)), false,
                Instant.now().minus(Duration.ofMinutes(5)), 50, 0, 0.0);
    }
}
