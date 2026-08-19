package com.drones.vision.api.dto;

import com.drones.vision.flight.domain.model.FlightPassport;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Response body for {@code GET /api/assets/{assetId}/usages/{usageId}/passport}
 * (docs/plans/active/DRONE-ONBOARDING-PLAN.md §8.1, frozen wire contract, O13).
 *
 * <p>{@code preflight}/{@code postflight} are omitted from the JSON entirely (not serialized as
 * {@code null}) when that snapshot was never captured — the plan's own comment on this shape reads
 * "a snapshot never captured is ABSENT, not null: 'we did not look' and 'we looked and found
 * nothing' are different claims, and only one of them is true here." Same {@code
 * @JsonInclude(NON_NULL)} idiom as {@link AssetUsageResponse}'s {@code endedAt}/positions — this is
 * deliberately the opposite convention from {@link VehicleProfileResponse} itself, which keeps its
 * own unanswered fields as literal {@code null}s (see that type's javadoc); a whole missing
 * snapshot is a different kind of absence than one unanswered field inside a captured one.
 *
 * @param usageId    the usage this passport belongs to, as a canonical UUID string
 * @param assetId    the asset that usage belongs to, as a canonical UUID string
 * @param preflight  the snapshot captured at {@code PREFLIGHT}, or absent if none has been captured yet
 * @param postflight the snapshot captured at {@code POSTFLIGHT}, or absent if the flight has not
 *                   reached that phase yet, or none was captured
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FlightPassportResponse(String usageId, String assetId, VehicleProfileResponse preflight,
                                      VehicleProfileResponse postflight) {

    /**
     * Maps a domain {@link FlightPassport} to its wire representation.
     *
     * @param passport the passport to map
     * @return the response body for {@code passport}
     */
    public static FlightPassportResponse from(FlightPassport passport) {
        return new FlightPassportResponse(
                passport.usageId().value().toString(),
                passport.assetId().value().toString(),
                passport.preflightProfile() == null ? null : VehicleProfileResponse.from(passport.preflightProfile()),
                passport.postflightProfile() == null ? null : VehicleProfileResponse.from(passport.postflightProfile()));
    }
}
