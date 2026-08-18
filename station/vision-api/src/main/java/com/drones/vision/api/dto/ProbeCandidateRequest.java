package com.drones.vision.api.dto;

import java.util.Map;

/**
 * Request body for {@code POST /api/onboarding/probe} (docs/plans/active/DRONE-ONBOARDING-PLAN.md
 * §8.1, §3.1 stage 3) — deliberately mirrors {@link ProbeDeviceRequest}'s {@code
 * {protocol, uri, options}} shape (the plan's own "mirrors {@code POST /api/devices/probe}'s
 * existing shape deliberately"), since this is the same pre-registration test-before-save gesture,
 * just against a vehicle link instead of a video stream.
 *
 * @param protocol the link protocol (e.g. {@code "mavlink"}); not consumed by {@link #toLinkKey()}
 *                 today — only one {@code VehicleConfigPort} implementation model exists, so there
 *                 is nothing yet to dispatch on — but validated for non-blank so a malformed request
 *                 fails fast (400) rather than silently probing with no protocol recorded
 * @param uri      the candidate's bind address (e.g. {@code "udp://0.0.0.0:14550"})
 * @param options  adapter-specific parameters; {@code "sysid"} is folded into the derived link key
 *                 when present, mirroring {@code DiscoveredDeviceResponse}'s own {@code
 *                 details["sysid"]} convention; may be {@code null} (treated as empty)
 */
public record ProbeCandidateRequest(String protocol, String uri, Map<String, String> options) {

    /**
     * @throws IllegalArgumentException if {@code protocol} is missing/blank (400)
     */
    public String requireProtocol() {
        if (protocol == null || protocol.isBlank()) {
            throw new IllegalArgumentException("protocol must not be blank");
        }
        return protocol;
    }

    /**
     * Derives {@link com.drones.vision.flight.domain.model.VehicleProfile#linkKey()}'s candidate
     * form ({@code "udp://host:port#sysid"}), the identity {@code VehicleProfileService#probeCandidate}
     * uses before any {@code DeviceId} exists (D7).
     *
     * @throws IllegalArgumentException if {@code uri} is missing/blank (400)
     */
    public String toLinkKey() {
        if (uri == null || uri.isBlank()) {
            throw new IllegalArgumentException("uri must not be blank");
        }
        String sysid = options == null ? null : options.get("sysid");
        return sysid == null || sysid.isBlank() ? uri : uri + "#" + sysid;
    }
}
