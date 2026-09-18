package com.drones.vision.adapter.carrierserial;

import com.drones.mavlink.transport.CarrierKind;
import com.drones.mavlink.transport.LinkDescriptor;
import com.drones.mavlink.transport.LinkId;
import com.drones.mavlink.transport.LinkRegistry;
import com.drones.mavlink.transport.SerialLink;
import com.drones.mavlink.transport.SerialRole;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

/**
 * Hotplug-aware serial carrier (LINK-PAIRING-PLAN.md §3.2): a fixed-delay poll of {@link
 * SerialPortEnumerator#currentPorts()} that opens one {@link SerialLink} per newly-matched port
 * and registers it on {@link #linkRegistry}, and unregisters+closes one the instant it disappears
 * from that same poll. A ground radio registers above the UDP lobby's frozen priority 50 (see
 * {@code adapter-carrier-udp}'s {@code UdpCarrierConfiguration}); a bench cable always registers
 * at priority 0 — §3.2's frozen decision is that a bench link is <b>never</b> auto-elected
 * regardless of that number, which L3 election policy (not this class) is what actually enforces.
 *
 * <h2>Depends on mavlink-core (+Spring) only</h2>
 * Same seam as {@code adapter-carrier-udp}: {@link LinkRegistry} arrives as an injected bean this
 * module never implements or depends on the implementor of — {@code station/vision-app}'s {@code
 * CarrierWiring} is the one place that actually supplies the {@code MavlinkGateway}-backed one.
 *
 * <h2>Threading</h2>
 * {@link #pollPorts()} runs on Spring's shared scheduling thread pool, serialized with itself
 * (never two polls concurrently) but not with anything else — {@link LinkRegistry#register}/{@link
 * LinkRegistry#unregister} are documented thread-safe for exactly this reason.
 */
@Configuration
@EnableConfigurationProperties({CarrierSerialProperties.class, CarrierSerialBenchProperties.class})
public class SerialCarrierConfiguration {

    private static final System.Logger LOG = System.getLogger(SerialCarrierConfiguration.class.getName());
    /** Must exceed carrier-udp's frozen lobby priority (50) — LINK-PAIRING-PLAN.md §3.2. Not configurable, matching that section's "no new config surface" ruling. */
    private static final int GROUND_RADIO_PRIORITY = 100;
    private static final int BENCH_PRIORITY = 0;

    private final CarrierSerialProperties properties;
    private final LinkRegistry linkRegistry;
    private final SerialPortEnumerator enumerator;
    private final ConcurrentMap<String, RegisteredPort> byPortKey = new ConcurrentHashMap<>();

    public SerialCarrierConfiguration(CarrierSerialProperties properties, CarrierSerialBenchProperties benchProperties,
                                       LinkRegistry linkRegistry) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.linkRegistry = Objects.requireNonNull(linkRegistry, "linkRegistry must not be null");
        this.enumerator = new SerialPortEnumerator(properties, Objects.requireNonNull(benchProperties, "benchProperties must not be null"));
    }

    /**
     * One tick of the hotplug poll — a no-op while {@code vision.carrier.serial.enabled} is
     * {@code false} (the default; see {@link CarrierSerialProperties}). Idempotent per port: a
     * port already registered from a previous tick is left alone; a port this tick no longer sees
     * is unregistered and closed exactly once.
     */
    @Scheduled(fixedDelayString = "${vision.carrier.serial.poll-interval:2s}")
    void pollPorts() {
        if (!properties.enabled()) {
            return;
        }
        List<SerialPortEnumerator.PortInfo> seen = enumerator.currentPorts().stream()
                .filter(enumerator::matches)
                .toList();
        Set<String> seenKeys = seen.stream().map(SerialPortEnumerator.PortInfo::portKey).collect(Collectors.toSet());

        for (String key : Set.copyOf(byPortKey.keySet())) {
            if (!seenKeys.contains(key)) {
                RegisteredPort gone = byPortKey.remove(key);
                if (gone != null) {
                    linkRegistry.unregister(gone.linkId());
                    gone.link().close();
                    LOG.log(System.Logger.Level.INFO, () -> "Serial port " + key + " disappeared; unregistered and closed");
                }
            }
        }

        for (SerialPortEnumerator.PortInfo port : seen) {
            byPortKey.computeIfAbsent(port.portKey(), key -> openAndRegister(port));
        }
    }

    /** @return the new registration, or {@code null} on a failed open — {@link java.util.Map#computeIfAbsent} then stores nothing, so the next tick simply retries. */
    private RegisteredPort openAndRegister(SerialPortEnumerator.PortInfo port) {
        try {
            SerialLink link = SerialLink.open(port.portKey(), enumerator.baudRateFor(port));
            SerialRole role = enumerator.roleFor(port);
            int priority = role == SerialRole.BENCH ? BENCH_PRIORITY : GROUND_RADIO_PRIORITY;
            LinkId id = linkRegistry.register(link, new LinkDescriptor(CarrierKind.SERIAL, role, port.portKey(), priority));
            LOG.log(System.Logger.Level.INFO, () -> "Serial port " + port.portKey() + " appeared; registered as " + role);
            return new RegisteredPort(id, link);
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "Could not open serial port " + port.portKey() + "; will retry next poll", e);
            return null;
        }
    }

    private record RegisteredPort(LinkId linkId, SerialLink link) {
    }
}
