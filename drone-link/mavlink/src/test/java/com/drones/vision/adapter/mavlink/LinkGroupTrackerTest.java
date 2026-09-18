package com.drones.vision.adapter.mavlink;

import com.drones.mavlink.CompId;
import com.drones.mavlink.SysId;
import com.drones.mavlink.codec.MavFrame;
import com.drones.mavlink.codec.MavHeader;
import com.drones.mavlink.session.Dispatcher;
import com.drones.mavlink.session.MessageFilter;
import com.drones.mavlink.session.Subscription;
import com.drones.mavlink.transport.CarrierKind;
import com.drones.mavlink.transport.LinkDescriptor;
import com.drones.mavlink.transport.LinkId;
import com.drones.mavlink.transport.LinkPeer;
import com.drones.mavlink.transport.SerialRole;

import com.drones.vision.adapter.mavlink.election.LinkElectionSettings;

import io.dronefleet.mavlink.minimal.Heartbeat;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link LinkGroupTracker#onChanged} (docs/plans/active/LINK-PAIRING-PLAN.md §8 defect #5): the
 * mechanism a spontaneous election change — a sighting or a forget, discovered off any request
 * thread — uses to reach {@code MavlinkGateway#onGroupChanged} -&gt; {@code
 * MavlinkTelemetrySource#onGroupChanged} -&gt; {@code MavlinkVehicleLinkPort#onGroupChanged} -&gt;
 * {@code DefaultLinkStateService}. Fed frames directly through a hand-fake {@link Dispatcher}, the
 * same no-socket pattern {@code MavlinkMessageInventoryTest} already established for this package.
 */
class LinkGroupTrackerTest {

    private static final LinkId LINK_A = new LinkId("udp-listen:0.0.0.0:14550");
    private static final LinkId LINK_B = new LinkId("serial:/dev/ttyUSB0");
    private static final LinkDescriptor DESCRIPTOR_A = new LinkDescriptor(CarrierKind.UDP, SerialRole.NONE, "lobby", 1);
    private static final LinkElectionSettings SETTINGS =
            new LinkElectionSettings(Duration.ofSeconds(3), Duration.ofSeconds(10), Duration.ofSeconds(5));

    private final FakeDispatcher dispatcher = new FakeDispatcher();
    private final LinkGroupTracker tracker = newTracker(dispatcher, LINK_A, DESCRIPTOR_A);

    @Test
    void aFirstSightingFiresTheListenerWithTheSysid() {
        List<Integer> fired = new CopyOnWriteArrayList<>();
        tracker.onChanged(fired::add);

        sight(42, LINK_A, Instant.now());

        assertEquals(List.of(42), fired, "the only known link is elected immediately -- that is a real change");
    }

    @Test
    void reSightingTheSameSoleLinkWithNoElectionMovementDoesNotFireAgain() {
        Instant t0 = Instant.now();
        sight(42, LINK_A, t0);
        List<Integer> fired = new CopyOnWriteArrayList<>();
        tracker.onChanged(fired::add);

        sight(42, LINK_A, t0.plusSeconds(1)); // same link, still active -- no movement

        assertTrue(fired.isEmpty(), "a re-sighting that changes nothing observable must not fire again");
    }

    @Test
    void forgettingTheOnlyLinkFiresTheListener() {
        sight(42, LINK_A, Instant.now());
        List<Integer> fired = new CopyOnWriteArrayList<>();
        tracker.onChanged(fired::add);

        tracker.forgetLink(LINK_A);

        assertEquals(List.of(42), fired, "the group's only member disappearing, and its ACTIVE link going "
                + "null with it, is exactly the kind of change a live subscriber must not miss");
    }

    @Test
    void forgettingALinkNeverSightedNeverFires() {
        sight(42, LINK_A, Instant.now());
        List<Integer> fired = new CopyOnWriteArrayList<>();
        tracker.onChanged(fired::add);

        tracker.forgetLink(LINK_B); // never a member of any group this tracker knows about

        assertTrue(fired.isEmpty());
    }

    @Test
    void noListenerRegisteredIsSilentlyTolerated() {
        assertDoesNotThrow(() -> sight(42, LINK_A, Instant.now()));
    }

    private void sight(int sysid, LinkId link, Instant receivedAt) {
        dispatcher.feed(heartbeat(sysid, link, receivedAt));
    }

    private static LinkGroupTracker newTracker(Dispatcher dispatcher, LinkId link, LinkDescriptor descriptor) {
        Map<LinkId, LinkDescriptor> descriptors = new ConcurrentHashMap<>(Map.of(link, descriptor));
        return new LinkGroupTracker(dispatcher, id -> null, SETTINGS, descriptors::get);
    }

    private static MavFrame heartbeat(int sysid, LinkId link, Instant receivedAt) {
        MavHeader header = new MavHeader(2, 0, new SysId(sysid), new CompId(1), 0, 0, 0, false);
        return new MavFrame(header, Heartbeat.builder().build(), link, new LinkPeer("127.0.0.1", 14550), receivedAt);
    }

    /** Captures the one handler {@link LinkGroupTracker} registers, so a test can feed frames without a real socket. */
    private static final class FakeDispatcher implements Dispatcher {
        private Consumer<MavFrame> handler;

        @Override
        public Subscription subscribe(MessageFilter filter, Consumer<MavFrame> handler) {
            this.handler = handler;
            return () -> { };
        }

        void feed(MavFrame frame) {
            handler.accept(frame);
        }
    }
}
