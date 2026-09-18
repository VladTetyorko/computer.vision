package com.drones.vision.api.dto;

import com.drones.vision.flight.domain.model.LinkGroupView;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * Response body for {@code GET /api/assets/{id}/links} and the {@code links:<assetId>} live topic
 * (LINK-PAIRING-PLAN.md §3.4 frozen contract, {@code LinkGroupResponse} in {@code
 * station/vision-web}) — one full-state snapshot per publish, never a delta (the same "one topic,
 * one full-state payload" rule {@code tracks}/{@code geo} already follow).
 *
 * @param assetId       the asset this snapshot belongs to
 * @param links         every link this asset's paired device(s) have ever been heard on
 * @param activeLinkId  the group's current ACTIVE link, or {@code null} if none (degrades honestly
 *                      rather than omitting the field, matching {@code LinkGroupView#activeLinkId()})
 * @param pinned        whether an operator has pinned {@code activeLinkId}, overriding automatic election
 * @param lastFailoverAt when the active link last changed, or absent if it never has
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LinkGroupResponse(String assetId, List<LinkViewResponse> links, String activeLinkId, boolean pinned,
                                 Instant lastFailoverAt) {

    /**
     * Maps a domain {@link LinkGroupView} to its wire representation.
     *
     * @param group the snapshot to map
     * @return the response body for {@code group}
     */
    public static LinkGroupResponse from(LinkGroupView group) {
        return new LinkGroupResponse(group.assetId().value().toString(),
                group.links().stream().map(LinkViewResponse::from).toList(),
                group.activeLinkId() == null ? null : group.activeLinkId().value(), group.pinned(),
                group.lastFailoverAt());
    }
}
