package com.drones.vision.api.dto;

import java.util.List;
import com.drones.vision.api.controller.SystemNetworkController;

/**
 * Body of {@code GET /api/system/network} (docs/plans/active/DRONE-INFRA-PLAN.md I-g's frozen wire contract)
 * — the guided drone-onboarding wizard's "what address/port should the drone-side config point
 * at" read.
 *
 * @param addresses   every site-local IPv4 address this host has on an up, non-loopback network
 *                    interface, sorted by {@link NetworkAddressResponse#interfaceName()} then
 *                    {@link NetworkAddressResponse#address()}; empty when none are detected —
 *                    never an error (see {@code com.drones.vision.api.controller.SystemNetworkController})
 * @param mavlinkPort the UDP port {@code MavlinkHeartbeatScanner} listens on for heartbeats —
 *                    the same port the wizard's generated FC/companion-computer snippets must
 *                    target, sourced from {@code vision-app}'s {@code
 *                    vision.discovery.mavlink-port} so the two can never disagree
 */
public record SystemNetworkResponse(List<NetworkAddressResponse> addresses, int mavlinkPort) {
}
