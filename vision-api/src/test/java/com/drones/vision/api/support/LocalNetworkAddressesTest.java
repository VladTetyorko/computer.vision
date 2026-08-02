package com.drones.vision.api.support;

import com.drones.vision.api.dto.NetworkAddressResponse;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link LocalNetworkAddresses}, feeding a fixed, fake {@link
 * NetworkInterface}/{@link InetAddress} list via its package-private test-seam constructor
 * (docs/DRONE-INFRA-PLAN.md I-g) rather than depending on whatever NICs the build machine
 * actually has. {@link InetAddress} instances are real, not mocked — {@link
 * InetAddress#getByName(String)} on a numeric literal (IPv4 or IPv6) resolves purely locally, no
 * DNS/network access, so a genuine {@code Inet4Address}/{@code Inet6Address} with correct {@code
 * isSiteLocalAddress()} behavior is trivial to construct. {@link NetworkInterface} itself is
 * {@code final}; it is mocked directly (Mockito's inline mock maker self-attaches for exactly
 * this case, verified empirically against this project's actual Mockito 5.23 setup) rather than
 * wrapped in a parallel hand-rolled type.
 */
class LocalNetworkAddressesTest {

    @Test
    void listReturnsASiteLocalIpv4AddressOfAnUpNonLoopbackInterface() throws Exception {
        NetworkInterface eth0 = interfaceNamed("eth0", true, false, ipv4("192.168.1.42"));

        List<NetworkAddressResponse> addresses = list(eth0);

        assertEquals(List.of(new NetworkAddressResponse("192.168.1.42", "eth0")), addresses);
    }

    @Test
    void listExcludesADownInterface() throws Exception {
        NetworkInterface downNic = interfaceNamed("eth1", false, false, ipv4("192.168.1.43"));

        assertTrue(list(downNic).isEmpty());
    }

    @Test
    void listExcludesALoopbackInterface() throws Exception {
        NetworkInterface loopback = interfaceNamed("lo", true, true, ipv4("127.0.0.1"));

        assertTrue(list(loopback).isEmpty());
    }

    @Test
    void listExcludesAPublicIpv4Address() throws Exception {
        NetworkInterface eth0 = interfaceNamed("eth0", true, false, ipv4("8.8.8.8"));

        assertTrue(list(eth0).isEmpty());
    }

    @Test
    void listExcludesALinkLocalIpv4Address() throws Exception {
        NetworkInterface eth0 = interfaceNamed("eth0", true, false, ipv4("169.254.1.5"));

        assertTrue(list(eth0).isEmpty());
    }

    @Test
    void listExcludesAnIpv6Address() throws Exception {
        NetworkInterface eth0 = interfaceNamed("eth0", true, false, InetAddress.getByName("fe80::1"));

        assertTrue(list(eth0).isEmpty());
    }

    @Test
    void listIncludesEveryRfc1918Range() throws Exception {
        NetworkInterface eth0 =
                interfaceNamed("eth0", true, false, ipv4("10.0.0.5"), ipv4("172.16.0.5"), ipv4("192.168.0.5"));

        List<NetworkAddressResponse> addresses = list(eth0);

        assertEquals(3, addresses.size());
    }

    @Test
    void listSortsByInterfaceNameThenAddress() throws Exception {
        NetworkInterface wlan0 = interfaceNamed("wlan0", true, false, ipv4("192.168.0.10"));
        NetworkInterface eth0 = interfaceNamed("eth0", true, false, ipv4("192.168.0.30"), ipv4("192.168.0.20"));

        List<NetworkAddressResponse> addresses = list(wlan0, eth0);

        assertEquals(List.of(
                new NetworkAddressResponse("192.168.0.20", "eth0"),
                new NetworkAddressResponse("192.168.0.30", "eth0"),
                new NetworkAddressResponse("192.168.0.10", "wlan0")), addresses);
    }

    @Test
    void listReturnsEmptyWhenEnumerationThrowsSocketException() {
        LocalNetworkAddresses localNetworkAddresses = new LocalNetworkAddresses(() -> {
            throw new SocketException("no network access in this sandbox");
        });

        assertTrue(localNetworkAddresses.list().isEmpty());
    }

    @Test
    void listSkipsAnInterfaceWhoseUpStatusCannotBeQueriedButKeepsTheOthers() throws Exception {
        NetworkInterface flaky = mock(NetworkInterface.class);
        when(flaky.getName()).thenReturn("flaky0");
        when(flaky.isUp()).thenThrow(new SocketException("device removed"));
        NetworkInterface eth0 = interfaceNamed("eth0", true, false, ipv4("192.168.1.1"));

        List<NetworkAddressResponse> addresses = list(flaky, eth0);

        assertEquals(List.of(new NetworkAddressResponse("192.168.1.1", "eth0")), addresses);
    }

    @Test
    void listReturnsEmptyWhenNoInterfacesArePresent() {
        assertTrue(list().isEmpty());
    }

    private static NetworkInterface interfaceNamed(String name, boolean up, boolean loopback,
            InetAddress... addresses) throws SocketException {
        NetworkInterface networkInterface = mock(NetworkInterface.class);
        when(networkInterface.getName()).thenReturn(name);
        when(networkInterface.isUp()).thenReturn(up);
        when(networkInterface.isLoopback()).thenReturn(loopback);
        when(networkInterface.getInetAddresses()).thenReturn(Collections.enumeration(List.of(addresses)));
        return networkInterface;
    }

    private static InetAddress ipv4(String literal) throws Exception {
        return InetAddress.getByName(literal);
    }

    private static List<NetworkAddressResponse> list(NetworkInterface... interfaces) {
        LocalNetworkAddresses localNetworkAddresses = new LocalNetworkAddresses(() -> List.of(interfaces));
        return localNetworkAddresses.list();
    }
}
