package com.drones.vision.adapter.mavlink.election;

import com.drones.mavlink.transport.CarrierKind;
import com.drones.mavlink.transport.LinkDescriptor;
import com.drones.mavlink.transport.LinkId;
import com.drones.mavlink.transport.SerialRole;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LINK-PAIRING-PLAN.md §4 row L3's own required cases for {@link LinkGroup}'s election state
 * machine: reclaim needs a full dwell window, one missed heartbeat never flaps, {@link
 * SerialRole#BENCH} is never auto-elected, and an operator pin/release overrides then restores
 * automatic election. Every timestamp below is a synthetic, hand-advanced {@link Instant} — no real
 * sleep, so the test asserts the state machine's logic rather than wall-clock timing.
 */
class LinkGroupElectionTest {

    private static final int SYSID = 1;
    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    private static final LinkId UDP_LINK = new LinkId("udp-listen:0.0.0.0:14550");
    private static final LinkId SERIAL_LINK = new LinkId("serial:/dev/ttyUSB0");
    private static final LinkId BENCH_LINK = new LinkId("serial:/dev/ttyUSB1");

    private static final LinkDescriptor UDP_DESCRIPTOR = new LinkDescriptor(CarrierKind.UDP, SerialRole.NONE, "lobby", 1);
    private static final LinkDescriptor SERIAL_DESCRIPTOR =
            new LinkDescriptor(CarrierKind.SERIAL, SerialRole.GROUND_RADIO, "ground-radio", 5);
    private static final LinkDescriptor BENCH_DESCRIPTOR = new LinkDescriptor(CarrierKind.SERIAL, SerialRole.BENCH, "bench", 0);

    private static final LinkElectionSettings SETTINGS =
            new LinkElectionSettings(Duration.ofSeconds(3), Duration.ofSeconds(10), Duration.ofSeconds(5));

    @Test
    void higherPriorityLinkReclaimsControlOnlyAfterAFullDwellWindow() {
        LinkGroup group = new LinkGroup(SYSID, SETTINGS);

        group.sight(UDP_LINK, UDP_DESCRIPTOR, T0);
        assertEquals(UDP_LINK, group.activeLinkId(), "the only known link is elected immediately");

        // The higher-priority serial radio recovers -- UDP is still healthy, so this must not flap
        // control over on the very first sighting. Both links are re-sighted on every subsequent
        // second so neither ever goes soft-stale mid-test -- this test is about the dwell gate on a
        // *healthy* active link, not about a soft-timeout demotion.
        Instant serialFirstHeard = T0.plusSeconds(1);
        group.sight(SERIAL_LINK, SERIAL_DESCRIPTOR, serialFirstHeard);
        assertEquals(UDP_LINK, group.activeLinkId(),
                "a recovered higher-priority link must not reclaim before the dwell window elapses");

        // Still inside the dwell window (started at serialFirstHeard, 5s dwellWindow): keep both
        // links freshly healthy each second up to (but not past) the dwell bound -- no reclaim yet.
        for (int second = 2; second <= 5; second++) {
            Instant tick = T0.plusSeconds(second);
            group.sight(SERIAL_LINK, SERIAL_DESCRIPTOR, tick);
            group.sight(UDP_LINK, UDP_DESCRIPTOR, tick);
        }
        assertEquals(UDP_LINK, group.activeLinkId(), "still inside the dwell window -- no reclaim yet");

        // Once the higher-priority link has stayed the best candidate for a full dwell window, it wins.
        Instant afterDwellElapses = serialFirstHeard.plusSeconds(5); // >= 5s dwellWindow since serialFirstHeard
        group.sight(SERIAL_LINK, SERIAL_DESCRIPTOR, afterDwellElapses);
        assertEquals(SERIAL_LINK, group.activeLinkId(),
                "a higher-priority link that stayed best for a full dwell window reclaims control");
        assertEquals(afterDwellElapses, group.lastFailoverAt(), "the reclaim is an automatic failover");
    }

    @Test
    void oneMissedHeartbeatNeverFlapsTheActiveLink() {
        LinkGroup group = new LinkGroup(SYSID, SETTINGS);
        Duration heartbeatPeriod = Duration.ofSeconds(1);

        group.sight(UDP_LINK, UDP_DESCRIPTOR, T0);
        assertEquals(UDP_LINK, group.activeLinkId());
        Instant firstElection = group.lastFailoverAt();

        // A single missed heartbeat: the next sighting arrives two periods later (one period silently
        // dropped), but still well within the 3s soft timeout -- must not touch the active link or
        // fire a second failover.
        Instant afterOneMissedHeartbeat = T0.plus(heartbeatPeriod.multipliedBy(2));
        group.tick(afterOneMissedHeartbeat);
        assertEquals(UDP_LINK, group.activeLinkId(), "one missed heartbeat must not demote the active link");
        assertEquals(firstElection, group.lastFailoverAt(), "no second failover fires for a single missed heartbeat");

        Instant resumed = T0.plus(heartbeatPeriod.multipliedBy(3));
        group.sight(UDP_LINK, UDP_DESCRIPTOR, resumed);
        assertEquals(UDP_LINK, group.activeLinkId());
        assertEquals(firstElection, group.lastFailoverAt(), "resuming heartbeats does not itself count as a failover");
    }

    @Test
    void benchLinkIsNeverAutoElectedEvenAsTheOnlyMember() {
        LinkGroup group = new LinkGroup(SYSID, SETTINGS);

        group.sight(BENCH_LINK, BENCH_DESCRIPTOR, T0);
        assertNull(group.activeLinkId(), "a BENCH link must never be auto-elected, even alone");

        group.sight(BENCH_LINK, BENCH_DESCRIPTOR, T0.plusSeconds(1));
        assertNull(group.activeLinkId(), "repeated sightings of a BENCH-only group still elect nothing");
    }

    @Test
    void pinOverridesElectionIncludingForABenchLink() {
        LinkGroup group = new LinkGroup(SYSID, SETTINGS);
        group.sight(UDP_LINK, UDP_DESCRIPTOR, T0);
        group.sight(BENCH_LINK, BENCH_DESCRIPTOR, T0);
        assertEquals(UDP_LINK, group.activeLinkId(), "UDP wins automatic election over the BENCH link");
        Instant autoFailoverAt = group.lastFailoverAt();

        group.pin(BENCH_LINK);
        assertEquals(BENCH_LINK, group.activeLinkId(), "an operator pin forces the BENCH link active");
        assertTrue(group.pinned());
        assertEquals(autoFailoverAt, group.lastFailoverAt(),
                "a pin is an operator action, not an automatic failover -- lastFailoverAt must not move");
    }

    @Test
    void pinningAnUnknownLinkIsRejected() {
        LinkGroup group = new LinkGroup(SYSID, SETTINGS);
        group.sight(UDP_LINK, UDP_DESCRIPTOR, T0);

        assertThrows(IllegalArgumentException.class, () -> group.pin(SERIAL_LINK));
    }

    @Test
    void releaseReturnsControlToAutomaticElection() {
        LinkGroup group = new LinkGroup(SYSID, SETTINGS);
        group.sight(UDP_LINK, UDP_DESCRIPTOR, T0);
        group.sight(SERIAL_LINK, SERIAL_DESCRIPTOR, T0);

        group.pin(UDP_LINK); // pin the lower-priority link over the higher-priority one
        assertEquals(UDP_LINK, group.activeLinkId());
        assertTrue(group.pinned());

        Instant releaseAt = T0.plusSeconds(1);
        group.release(releaseAt);
        assertFalse(group.pinned(), "release must clear the pin");
        assertEquals(SERIAL_LINK, group.activeLinkId(),
                "release must re-run automatic election immediately -- the higher-priority link wins");
        assertEquals(releaseAt, group.lastFailoverAt(), "the automatic re-election on release is a real failover");
    }

    // --- sight/forget report whether they actually changed anything (§8 defect #5) --------------

    @Test
    void sightReturnsTrueOnlyWhenObservableStateActuallyChanges() {
        LinkGroup group = new LinkGroup(SYSID, SETTINGS);

        assertTrue(group.sight(UDP_LINK, UDP_DESCRIPTOR, T0), "a brand-new member becoming ACTIVE is a real change");
        assertFalse(group.sight(UDP_LINK, UDP_DESCRIPTOR, T0.plusSeconds(1)),
                "re-sighting the same, still-active, still-sole link moves nothing observable");
    }

    @Test
    void forgetReturnsTrueOnlyWhenObservableStateActuallyChanges() {
        LinkGroup group = new LinkGroup(SYSID, SETTINGS);
        group.sight(UDP_LINK, UDP_DESCRIPTOR, T0);
        group.sight(SERIAL_LINK, SERIAL_DESCRIPTOR, T0); // higher priority, but still dwell-gated -- UDP stays active

        assertTrue(group.forget(UDP_LINK, T0.plusSeconds(1)),
                "losing the ACTIVE link (and failing over to SERIAL_LINK) is a real change");
        assertFalse(group.forget(UDP_LINK, T0.plusSeconds(2)), "forgetting an already-absent link changes nothing");
    }
}
