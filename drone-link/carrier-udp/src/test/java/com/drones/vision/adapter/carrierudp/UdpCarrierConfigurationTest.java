package com.drones.vision.adapter.carrierudp;

import com.drones.mavlink.transport.CarrierKind;
import com.drones.mavlink.transport.LinkDescriptor;
import com.drones.mavlink.transport.LinkId;
import com.drones.mavlink.transport.LinkRegistry;
import com.drones.mavlink.transport.MavlinkLink;
import com.drones.mavlink.transport.SerialRole;
import com.drones.mavlink.transport.UdpListenLink;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.DatagramSocket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link UdpCarrierConfiguration}'s {@code @Bean} method exercised as a plain method (no Spring
 * context needed to prove its own logic) against a real, loopback-bound {@link UdpListenLink} —
 * no mocks, matching this codebase's adapter-test convention. A hand-written recording {@link
 * LinkRegistry} stands in for the one {@code station/vision-app}'s {@code CarrierWiring} actually
 * supplies in production, since this module must not depend on anything that implements it.
 */
class UdpCarrierConfigurationTest {

    private final UdpCarrierConfiguration configuration = new UdpCarrierConfiguration();

    @Test
    void bindsALoopbackLinkAndRegistersItWithTheFrozenLobbyDescriptor() throws IOException {
        RecordingLinkRegistry registry = new RecordingLinkRegistry();

        LinkId id = configuration.lobbyUdpLink(registry, "127.0.0.1", 0);
        try {
            assertInstanceOf(UdpListenLink.class, registry.lastLink,
                    "carrier-udp must register a real UdpListenLink, not a stand-in");
            assertEquals(registry.lastLink.id(), id, "the returned LinkId must be the link's own id, never a minted one");
            assertEquals(new LinkDescriptor(CarrierKind.UDP, SerialRole.NONE, "lobby", 50), registry.lastDescriptor,
                    "LINK-PAIRING-PLAN.md §3.2's lobby descriptor is frozen: UDP/NONE/\"lobby\"/50");
        } finally {
            registry.lastLink.close();
        }
    }

    @Test
    void aBindConflictSurfacesSynchronouslyAsAnUncheckedIOException() throws IOException {
        // Hold a real socket open on an ephemeral port first, then ask the configuration to bind
        // that exact port -- a genuine OS-level bind conflict, not a simulated one.
        try (DatagramSocket holder = new DatagramSocket(null)) {
            holder.setReuseAddress(false);
            holder.bind(new java.net.InetSocketAddress("127.0.0.1", 0));
            int busyPort = holder.getLocalPort();
            RecordingLinkRegistry registry = new RecordingLinkRegistry();

            UncheckedIOException thrown = assertThrows(UncheckedIOException.class,
                    () -> configuration.lobbyUdpLink(registry, "127.0.0.1", busyPort),
                    "a bind conflict on the lobby address must fail bean creation synchronously, not silently");
            assertInstanceOf(IOException.class, thrown.getCause());
        }
    }

    private static final class RecordingLinkRegistry implements LinkRegistry {
        private MavlinkLink lastLink;
        private LinkDescriptor lastDescriptor;

        @Override
        public LinkId register(MavlinkLink link, LinkDescriptor descriptor) {
            this.lastLink = link;
            this.lastDescriptor = descriptor;
            return link.id();
        }

        @Override
        public void unregister(LinkId id) {
            // not exercised by this test
        }
    }
}
