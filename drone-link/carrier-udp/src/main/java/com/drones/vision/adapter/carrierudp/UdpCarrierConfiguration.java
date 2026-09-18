package com.drones.vision.adapter.carrierudp;

import com.drones.mavlink.transport.CarrierKind;
import com.drones.mavlink.transport.LinkDescriptor;
import com.drones.mavlink.transport.LinkId;
import com.drones.mavlink.transport.LinkRegistry;
import com.drones.mavlink.transport.SerialRole;
import com.drones.mavlink.transport.UdpListenLink;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Binds the well-known MAVLink "lobby" {@link UdpListenLink} and registers it on a {@link
 * LinkRegistry} — LINK-PAIRING-PLAN.md §3.2/§7: <b>this is the only place in the whole
 * application that opens the lobby's UDP socket</b> now that {@code MavlinkGateway}
 * (drone-link/mavlink) opens no socket of its own.
 *
 * <h2>Depends on mavlink-core (+Spring) only</h2>
 * This module never depends on {@code drone-link/mavlink} or {@code station/vision-app}
 * (ArchUnit-enforced, see {@code vision-app}'s {@code ArchitectureTest}). The {@link LinkRegistry}
 * this configuration registers onto is therefore injected as a plain Spring bean of that
 * interface's type — {@code station/vision-app}'s own {@code CarrierWiring} is the one place that
 * actually supplies it (backed by {@code MavlinkTelemetrySource.linkRegistry(port)}, itself backed
 * by a {@code MavlinkGateway}) — this class knows nothing about who implements it. Likewise the
 * bind host/port are read directly off the exact property keys {@code vision-app} already defines
 * for this address ({@code vision.mavlink.bind-host}, {@code vision.discovery.mavlink-port}) via
 * {@code @Value}, rather than importing those modules' {@code @ConfigurationProperties} types —
 * {@code @Value} needs only the key string, not a compile dependency on the class that binds it.
 *
 * <h2>Priority 50, label "lobby" — frozen (LINK-PAIRING-PLAN.md §3.2)</h2>
 * The registered {@link LinkDescriptor} is exactly {@code new LinkDescriptor(CarrierKind.UDP,
 * SerialRole.NONE, "lobby", 50)}; a serial ground radio registers above this, a serial bench cable
 * always registers at priority 0 (see {@code adapter-carrier-serial}).
 */
@Configuration
public class UdpCarrierConfiguration {

    private static final System.Logger LOG = System.getLogger(UdpCarrierConfiguration.class.getName());

    /** LINK-PAIRING-PLAN.md §3.2's frozen label for the lobby link's {@link LinkDescriptor}. */
    static final String LOBBY_LABEL = "lobby";
    /** LINK-PAIRING-PLAN.md §3.2's frozen priority: above nothing else by default; a ground radio outranks it. */
    static final int LOBBY_PRIORITY = 50;

    /**
     * Binds {@code bindHost:port} immediately (bean creation is eager, matching the pre-L1
     * behavior of binding the lobby at application boot) and registers the resulting {@link
     * UdpListenLink} on {@code linkRegistry}. The bean's value is the assigned {@link LinkId} —
     * exposed mainly so a caller/test can confirm registration happened, not because anything
     * downstream needs to look the link back up by id (the registry is the lookup seam).
     *
     * @throws UncheckedIOException if the address is already bound by something else — surfaces
     *                               synchronously as a bean-creation failure, exactly as a bind
     *                               conflict on this same address always has (this address has
     *                               been bound eagerly at boot since the zero-config-onboarding
     *                               standing lobby shipped, independent of this class)
     */
    @Bean
    public LinkId lobbyUdpLink(LinkRegistry linkRegistry,
                                @Value("${vision.mavlink.bind-host:0.0.0.0}") String bindHost,
                                @Value("${vision.discovery.mavlink-port:14550}") int port) {
        try {
            UdpListenLink link = new UdpListenLink(bindHost, port);
            LinkId id = linkRegistry.register(link,
                    new LinkDescriptor(CarrierKind.UDP, SerialRole.NONE, LOBBY_LABEL, LOBBY_PRIORITY));
            LOG.log(System.Logger.Level.INFO,
                    () -> "carrier-udp bound and registered the lobby UDP link on " + bindHost + ":" + port);
            return id;
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "carrier-udp could not bind the lobby UDP link on " + bindHost + ":" + port, e);
        }
    }
}
