package com.drones.vision.api.dto;

/**
 * One site-local IPv4 address on one network interface — an entry of {@link
 * SystemNetworkResponse#addresses()} (docs/plans/active/DRONE-INFRA-PLAN.md I-g's frozen wire contract,
 * extended by docs/plans/active/SOURCE-ONBOARDING-2-PLAN.md &sect;3.2 C3 with {@code kind}).
 *
 * @param address       the dotted-decimal IPv4 address, e.g. {@code "192.168.0.104"}
 * @param interfaceName the owning interface's OS-reported name, e.g. {@code "wlp2s0"}
 * @param kind          {@code "LAN"} or {@code "VIRTUAL"}, classified purely from {@code
 *                      interfaceName}'s prefix (docker/br-/veth/virbr/tun/tap &rarr; {@code
 *                      VIRTUAL}, everything else &rarr; {@code LAN}) — {@code "UNKNOWN"} is a
 *                      reserved third value this classifier never actually assigns
 */
public record NetworkAddressResponse(String address, String interfaceName, String kind) {
}
