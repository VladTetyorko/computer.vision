package com.drones.vision.api.support;

import com.drones.vision.api.dto.NetworkAddressResponse;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import com.drones.vision.api.controller.SystemNetworkController;

/**
 * Enumerates this host's site-local IPv4 addresses for {@link SystemNetworkController}
 * (docs/plans/active/DRONE-INFRA-PLAN.md I-g's guided drone-onboarding wizard — the "Configure-your-drone"
 * step pre-fills copy-paste FC/companion-computer snippets with one of these).
 *
 * <p>Stateless, no interface ({@code .claude/skills/java-clean-code/SKILL.md} §1) — {@link
 * SystemNetworkController}'s one collaborator, with no second implementation ever plausible; the
 * same "small helper, not a domain/application port" shape {@code SnapshotJpegEncoder} already
 * establishes for this module's own byte-level infrastructure. Public only because it now lives in
 * {@code ...api.support}, a different package from {@link SystemNetworkController}
 * ({@code ...api.controller}) (docs/plans/active/LAYERING-REFACTOR-PLAN.md §3/§7 row B). The {@link
 * NetworkInterface} enumeration itself sits behind an injectable {@link NetworkInterfaceSource}
 * (the package-private second constructor) purely so a unit test can feed a fixed, fake interface
 * list — including one that throws {@link SocketException} — instead of depending on whatever
 * NICs happen to be present on the machine running the build.
 */
public final class LocalNetworkAddresses {

    private static final System.Logger LOG = System.getLogger(LocalNetworkAddresses.class.getName());

    private final NetworkInterfaceSource networkInterfaceSource;

    public LocalNetworkAddresses() {
        this(() -> Collections.list(NetworkInterface.getNetworkInterfaces()));
    }

    /** Package-private test seam — see class javadoc. */
    LocalNetworkAddresses(NetworkInterfaceSource networkInterfaceSource) {
        this.networkInterfaceSource =
                Objects.requireNonNull(networkInterfaceSource, "networkInterfaceSource must not be null");
    }

    /**
     * @return every site-local IPv4 address of an up, non-loopback interface on this host,
     *         sorted by {@link NetworkAddressResponse#interfaceName()} then {@link
     *         NetworkAddressResponse#address()}; empty — never throws — when nothing qualifies,
     *         enumeration itself fails, or a single interface's own up/loopback status can't be
     *         queried (per docs/plans/active/DRONE-INFRA-PLAN.md I-g's frozen contract: "no addresses" is a
     *         valid, non-error result)
     */
    public List<NetworkAddressResponse> list() {
        List<NetworkInterface> interfaces;
        try {
            interfaces = networkInterfaceSource.get();
        } catch (SocketException e) {
            LOG.log(System.Logger.Level.WARNING,
                    "Failed to enumerate network interfaces; reporting no local addresses", e);
            return List.of();
        }
        return interfaces.stream()
                .filter(LocalNetworkAddresses::isUpAndNotLoopback)
                .flatMap(LocalNetworkAddresses::siteLocalIpv4Addresses)
                .sorted(Comparator.comparing(NetworkAddressResponse::interfaceName)
                        .thenComparing(NetworkAddressResponse::address))
                .toList();
    }

    private static boolean isUpAndNotLoopback(NetworkInterface networkInterface) {
        try {
            return networkInterface.isUp() && !networkInterface.isLoopback();
        } catch (SocketException e) {
            // A NIC whose own up/loopback status can't be queried is not usable for the
            // wizard's purposes either -- skip just this one rather than fail the whole request.
            LOG.log(System.Logger.Level.WARNING,
                    "Failed to inspect network interface " + networkInterface.getName(), e);
            return false;
        }
    }

    private static Stream<NetworkAddressResponse> siteLocalIpv4Addresses(NetworkInterface networkInterface) {
        return Collections.list(networkInterface.getInetAddresses()).stream()
                .filter(Inet4Address.class::isInstance)
                .filter(InetAddress::isSiteLocalAddress)
                .map(address -> new NetworkAddressResponse(address.getHostAddress(), networkInterface.getName()));
    }

    /** Throwing supplier of the raw candidate interface list — see class javadoc. */
    @FunctionalInterface
    interface NetworkInterfaceSource {
        List<NetworkInterface> get() throws SocketException;
    }
}
