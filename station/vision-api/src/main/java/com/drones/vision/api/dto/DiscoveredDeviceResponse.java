package com.drones.vision.api.dto;

import com.drones.vision.warehouse.domain.model.DiscoveredDevice;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/**
 * Response body element for {@code POST /api/discovery/scan}'s {@code
 * devices} list.
 *
 * <p>{@code suggestedCategory}, {@code protocol}, and {@code uri} are
 * omitted from the JSON entirely (rather than serialized as {@code null})
 * when the discovery mechanism could not infer them. {@code protocol}/{@code
 * uri} are flattened from {@link DiscoveredDevice#suggestedStream()} when
 * present, so clients don't have to unwrap a nested stream object.
 *
 * @param method            the discovery mechanism that found this candidate (see {@code DeviceDiscoveryPort#method()})
 * @param name              human-readable name or best-effort label for the candidate
 * @param address           network or local address of the candidate, as a string
 * @param suggestedCategory best-guess category slug, or absent if the mechanism cannot infer one
 * @param protocol          the suggested stream's protocol key, or absent if no stream was suggested
 * @param uri               the suggested stream's resource locator, as a string, or absent if no stream was suggested
 * @param details           mechanism-specific extra info (e.g. raw scopes, service name); kept for one
 *                          release after {@code suggestedOptions} was added (D16) — its removal is
 *                          deferred, named here, not yet scheduled
 * @param suggestedOptions  docs/plans/active/DRONE-ONBOARDING-PLAN.md §8.1/D16 — the same entries a
 *                          probe/register call would want under {@code options} (e.g. {@code
 *                          {"sysid":"7"}}), ending the lossy {@code details["sysid"]}-only workaround;
 *                          synthesized here from {@code details} rather than a new {@link
 *                          DiscoveredDevice} domain field, since every value it carries already
 *                          exists on {@code details} today — a real typed domain field is deferred to
 *                          whoever next owns {@code vision-warehouse}'s discovery model
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DiscoveredDeviceResponse(String method, String name, String address, String suggestedCategory,
                                        String protocol, String uri, Map<String, String> details,
                                        Map<String, String> suggestedOptions) {

    /**
     * Maps a domain {@link DiscoveredDevice} to its wire representation.
     *
     * @param device the candidate to map
     * @return the response body element for {@code device}
     */
    public static DiscoveredDeviceResponse from(DiscoveredDevice device) {
        String protocol = device.suggestedStream() != null ? device.suggestedStream().protocol() : null;
        String uri = device.suggestedStream() != null ? device.suggestedStream().uri().toString() : null;
        String sysid = device.details() == null ? null : device.details().get("sysid");
        Map<String, String> suggestedOptions = sysid == null || sysid.isBlank() ? Map.of() : Map.of("sysid", sysid);
        return new DiscoveredDeviceResponse(
                device.method(),
                device.name(),
                device.address().toString(),
                device.suggestedCategory() != null ? device.suggestedCategory().slug() : null,
                protocol,
                uri,
                device.details(),
                suggestedOptions);
    }
}
