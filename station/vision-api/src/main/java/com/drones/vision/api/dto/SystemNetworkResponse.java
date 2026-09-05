package com.drones.vision.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import com.drones.vision.api.controller.SystemNetworkController;

/**
 * Body of {@code GET /api/system/network} (docs/plans/active/DRONE-INFRA-PLAN.md I-g's frozen wire contract,
 * extended by docs/plans/active/SOURCE-ONBOARDING-2-PLAN.md &sect;3.2 C3 with {@code videoPushPort}/
 * {@code videoPushPathPrefix}) — the guided drone-onboarding wizard's "what address/port should the
 * drone-side config point at" read. A client composes the full push URL itself: {@code
 * <selected address>:<videoPushPort>/<videoPushPathPrefix><name>}.
 *
 * @param addresses          every site-local IPv4 address this host has on an up, non-loopback
 *                           network interface, sorted by {@link NetworkAddressResponse#kind()}
 *                           (LAN first) then {@link NetworkAddressResponse#interfaceName()} then
 *                           {@link NetworkAddressResponse#address()}; empty when none are
 *                           detected — never an error (see {@code
 *                           com.drones.vision.api.controller.SystemNetworkController})
 * @param mavlinkPort        the UDP port {@code MavlinkHeartbeatScanner} listens on for heartbeats
 *                           — the same port the wizard's generated FC/companion-computer snippets
 *                           must target, sourced from {@code vision-app}'s {@code
 *                           vision.discovery.mavlink-port} so the two can never disagree
 * @param videoPushPort      the port a camera pushes RTSP to (mediamtx's publish port); absent
 *                           when mediamtx publish is unconfigured
 * @param videoPushPathPrefix mediamtx path-name prefix a device push must live under to be
 *                           reported as a discovery candidate; absent when mediamtx publish is
 *                           unconfigured
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SystemNetworkResponse(List<NetworkAddressResponse> addresses, int mavlinkPort, Integer videoPushPort,
                                     String videoPushPathPrefix) {
}
