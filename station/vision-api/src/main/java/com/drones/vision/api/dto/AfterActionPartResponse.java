package com.drones.vision.api.dto;

import com.drones.vision.api.support.afteraction.AfterActionPart;

/**
 * Wire representation of one {@code parts[]} row (docs/plans/active/AFTER-ACTION-PLAN.md
 * &sect;3.1's frozen wire contract). No {@code @JsonInclude(NON_NULL)} — {@code note: null} is
 * meaningful (a {@code PRESENT} part with nothing to qualify) and must appear on the wire, not be
 * omitted.
 *
 * @param part  the evidence category, lowercase (e.g. {@code "telemetry"})
 * @param state {@code PRESENT}, {@code ABSENT}, {@code TRUNCATED} or {@code FORBIDDEN}, verbatim
 * @param count how many items this part carries; {@code 0} for {@code ABSENT}/{@code FORBIDDEN}
 * @param note  human-readable qualifier, or {@code null} only when {@code state == PRESENT} and
 *              there is nothing to qualify
 */
public record AfterActionPartResponse(String part, String state, int count, String note) {

    /**
     * Maps a domain {@link AfterActionPart} to its wire representation.
     *
     * @param part the part to map
     * @return the response body for {@code part}
     */
    public static AfterActionPartResponse from(AfterActionPart part) {
        return new AfterActionPartResponse(part.part().wireName(), part.state().name(), part.count(), part.note());
    }
}
