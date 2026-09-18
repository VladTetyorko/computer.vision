package com.drones.vision.api.dto;

import com.drones.vision.flight.domain.model.LinkQuality;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * Response body for one {@link com.drones.vision.flight.domain.model.LinkView}'s radio-quality
 * reading (LINK-PAIRING-PLAN.md §3.4 frozen contract, {@code LinkQualityView} in
 * {@code station/vision-web}) — every field absent (never serialized {@code null}) until at least
 * one {@code RADIO_STATUS} frame has arrived for the link.
 *
 * @param lastRadioStatusAt when the last {@code RADIO_STATUS} frame for this link arrived
 * @param rssi              local receive signal strength, or absent
 * @param remoteRssi        remote-reported receive signal strength, or absent
 * @param noise             local noise floor, or absent
 * @param rxErrors          cumulative receive errors, or absent
 * @param fixed             whether the radio reports a fixed/locked link, or absent
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LinkQualityResponse(Instant lastRadioStatusAt, Integer rssi, Integer remoteRssi, Integer noise,
                                   Integer rxErrors, Boolean fixed) {

    /**
     * Maps a domain {@link LinkQuality} to its wire representation.
     *
     * @param quality the quality reading to map, or {@code null}
     * @return the response body, or {@code null} if {@code quality} is {@code null} (the caller
     *         omits the field entirely, matching {@link com.drones.vision.flight.domain.model.LinkView#quality()}'s
     *         own "absent until first reading" contract)
     */
    public static LinkQualityResponse from(LinkQuality quality) {
        if (quality == null) {
            return null;
        }
        return new LinkQualityResponse(quality.lastRadioStatusAt(), quality.rssi(), quality.remoteRssi(),
                quality.noise(), quality.rxErrors(), quality.fixed());
    }
}
