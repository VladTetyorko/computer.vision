package com.drones.vision.api.controller;

import com.drones.vision.api.dto.SystemNetworkResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;
import com.drones.vision.api.proxy.HlsProxyController;
import com.drones.vision.api.support.LocalNetworkAddresses;

/**
 * Driving REST adapter for host-environment introspection the guided drone-onboarding wizard
 * needs (docs/plans/active/DRONE-INFRA-PLAN.md I-g, extended by docs/plans/active/
 * SOURCE-ONBOARDING-2-PLAN.md &sect;3.2 C3 with mediamtx push facts).
 *
 * <p><b>Why this lives in vision-api, not vision-application</b>: {@code GET
 * /api/system/network} answers "what network interfaces does the machine this JVM happens to be
 * running on have right now" — a fact about the host process's own environment, with zero domain
 * meaning (no asset, device, or business decision involved; two calls a second apart from the
 * same request could legitimately answer differently if a NIC comes up or down). Every other
 * environment-shaped concern in this codebase that isn't a genuine business rule is likewise kept
 * out of the domain/application layers (e.g. {@link HlsProxyController}'s raw byte-proxying needs
 * no use-case port either); inventing a driving port for a single, stateless network-interface
 * enumeration would be indirection with no second implementation ever plausible ({@code
 * .claude/skills/java-clean-code/SKILL.md} §1). The enumeration itself lives in the
 * package-private {@link LocalNetworkAddresses} helper, this controller's one collaborator, so it
 * can be unit-tested without a Spring context and without depending on the test host's real NICs
 * — see that class's own javadoc.
 *
 * <p><b>No gate, no CORS/security config needed</b>: like {@link CategoryController} or {@link
 * com.drones.vision.api.dto} reference-data reads, this is an always-available plain read with no
 * feature flag — nothing in this module restricts cross-origin access to any controller today
 * (see {@code SpaResourceConfiguration}, the only {@code WebMvcConfigurer} here, which concerns
 * static-resource serving, not CORS), so this endpoint is exposed exactly like every other one.
 */
@RestController
public class SystemNetworkController {

    private final LocalNetworkAddresses localNetworkAddresses;
    private final int mavlinkPort;
    private final Integer videoPushPort;
    private final String videoPushPathPrefix;

    /**
     * @param mavlinkPort         the MAVLink heartbeat port, supplied as a raw {@code int} bean by
     *                            {@code vision-app}'s {@code DiscoveryWiringConfiguration#mavlinkPort}
     *                            — {@code vision-api} may not depend on {@code vision-app} to read
     *                            the backing property itself (the dependency rule runs the other
     *                            way), so the value crosses the module boundary as a plain
     *                            constructor argument, mirroring how {@link HlsProxyController}
     *                            receives its {@code URI} collaborator. See {@link #network()}'s
     *                            own javadoc for why this is always the same value {@code
     *                            MavlinkHeartbeatScanner} listens on.
     * @param videoPushPort       provides the mediamtx RTSP publish port, from {@code
     *                            DiscoveryWiringConfiguration#videoPushPort} — an {@link
     *                            ObjectProvider}, not a plain {@code Integer}, because that bean is
     *                            conditionally absent (not merely null-valued) when mediamtx publish
     *                            is unconfigured, and a required constructor parameter cannot accept
     *                            a genuinely missing bean
     * @param videoPushPathPrefix provides the mediamtx ingest path-name prefix, from {@code
     *                            DiscoveryWiringConfiguration#videoPushPathPrefix} — see {@code
     *                            videoPushPort} for why this is an {@link ObjectProvider}
     */
    @Autowired
    public SystemNetworkController(int mavlinkPort, ObjectProvider<Integer> videoPushPort,
                                    ObjectProvider<String> videoPushPathPrefix) {
        this(mavlinkPort, videoPushPort.getIfAvailable(), videoPushPathPrefix.getIfAvailable(),
                new LocalNetworkAddresses());
    }

    /** Package-private test seam — see class javadoc. */
    SystemNetworkController(int mavlinkPort, Integer videoPushPort, String videoPushPathPrefix,
                             LocalNetworkAddresses localNetworkAddresses) {
        this.mavlinkPort = mavlinkPort;
        this.videoPushPort = videoPushPort;
        this.videoPushPathPrefix = videoPushPathPrefix;
        this.localNetworkAddresses =
                Objects.requireNonNull(localNetworkAddresses, "localNetworkAddresses must not be null");
    }

    /**
     * Reports this host's site-local IPv4 addresses plus the MAVLink heartbeat port and mediamtx
     * push facts, so the onboarding wizard can pre-fill copy-paste FC/companion-computer
     * configuration — and a client can compose a camera's push URL — with this app's own reachable
     * address and ports instead of asking the operator to type them in (docs/plans/active/
     * DRONE-INFRA-PLAN.md I-g's frozen wire contract; docs/plans/active/SOURCE-ONBOARDING-2-PLAN.md
     * &sect;3.2 C3).
     *
     * <p>{@code mavlinkPort} is always the exact value {@code MavlinkHeartbeatScanner} (wired in
     * {@code vision-app}'s {@code DiscoveryWiringConfiguration}) listens on — both this
     * controller and that scanner are handed the value from the very same {@code
     * vision.discovery.mavlink-port} property (via {@code VisionDiscoveryProperties}), so a
     * generated onboarding snippet and the running scanner can never target different ports.
     *
     * @return every site-local, up, non-loopback IPv4 address this host has (possibly empty —
     *         never an error, per the frozen contract), plus the configured MAVLink port and
     *         mediamtx push facts (absent when mediamtx publish is unconfigured)
     */
    @GetMapping("/api/system/network")
    public SystemNetworkResponse network() {
        return new SystemNetworkResponse(localNetworkAddresses.list(), mavlinkPort, videoPushPort,
                videoPushPathPrefix);
    }
}
