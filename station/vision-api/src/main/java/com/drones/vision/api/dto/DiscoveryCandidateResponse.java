package com.drones.vision.api.dto;

import com.drones.vision.warehouse.domain.model.DiscoveredDevice;
import com.drones.vision.warehouse.domain.model.DiscoveryCandidate;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.Map;

/**
 * Response body element for {@code GET /api/discovery/inbox} (docs/plans/active/
 * ZERO-CONFIG-ONBOARDING-CONTEXT.md &sect;11, Z2c).
 *
 * <p>Unlike {@link DiscoveredDeviceResponse} (the transient {@code POST /api/discovery/scan}
 * shape), this DTO carries {@code suggestedStream}'s full {@code options()} map rather than the
 * lossy {@code details["sysid"]}-only {@code suggestedOptions} workaround that DTO's own javadoc
 * documents as a defect — nothing about the persisted candidate's {@link DiscoveredDevice} is
 * dropped on the wire. {@code suggestedCategory}/{@code suggestedStreamProtocol}/{@code
 * suggestedStreamUri}/{@code suggestedStreamOptions}/{@code registeredAssetId} are omitted from the
 * JSON entirely (rather than serialized as {@code null}) when absent.
 *
 * @param id                       candidate identity, as a canonical UUID string
 * @param method                   the discovery mechanism that found this candidate
 * @param name                     human-readable name or best-effort label for the candidate
 * @param address                  network or local address of the candidate, as a string
 * @param suggestedCategory        best-guess category slug, or absent if the mechanism cannot infer one
 * @param suggestedStreamProtocol  the suggested stream's protocol key, or absent if no stream was suggested
 * @param suggestedStreamUri       the suggested stream's resource locator, as a string, or absent if no stream was suggested
 * @param suggestedStreamOptions   the suggested stream's adapter-specific options, or absent if no stream was suggested
 * @param details                  mechanism-specific extra info (e.g. raw scopes, service name)
 * @param firstSeen                when this identity was first reported
 * @param lastSeen                 when this identity was most recently reported — the age a client derives "stale" from
 * @param status                   {@code NEW}, {@code DISMISSED}, or {@code REGISTERED}
 * @param registeredAssetId        the asset this candidate resolved to, as a canonical UUID string, or absent if none is known
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DiscoveryCandidateResponse(String id, String method, String name, String address,
                                          String suggestedCategory, String suggestedStreamProtocol,
                                          String suggestedStreamUri, Map<String, String> suggestedStreamOptions,
                                          Map<String, String> details, Instant firstSeen, Instant lastSeen,
                                          String status, String registeredAssetId) {

    /**
     * Maps a domain {@link DiscoveryCandidate} to its wire representation.
     *
     * @param candidate the candidate to map
     * @return the response body element for {@code candidate}
     */
    public static DiscoveryCandidateResponse from(DiscoveryCandidate candidate) {
        DiscoveredDevice discovered = candidate.discovered();
        var stream = discovered.suggestedStream();
        return new DiscoveryCandidateResponse(
                candidate.id().value().toString(),
                discovered.method(),
                discovered.name(),
                discovered.address().toString(),
                discovered.suggestedCategory() != null ? discovered.suggestedCategory().slug() : null,
                stream != null ? stream.protocol() : null,
                stream != null ? stream.uri().toString() : null,
                stream != null ? stream.options() : null,
                discovered.details(),
                candidate.firstSeen(),
                candidate.lastSeen(),
                candidate.status().name(),
                candidate.registeredAsset() != null ? candidate.registeredAsset().value().toString() : null);
    }
}
