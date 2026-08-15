package com.drones.vision.api.dto;

/**
 * One site-local IPv4 address on one network interface — an entry of {@link
 * SystemNetworkResponse#addresses()} (docs/plans/active/DRONE-INFRA-PLAN.md I-g's frozen wire contract).
 *
 * @param address       the dotted-decimal IPv4 address, e.g. {@code "192.168.0.104"}
 * @param interfaceName the owning interface's OS-reported name, e.g. {@code "wlp2s0"}
 */
public record NetworkAddressResponse(String address, String interfaceName) {
}
