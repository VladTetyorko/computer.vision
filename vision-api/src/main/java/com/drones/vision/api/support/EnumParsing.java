package com.drones.vision.api.support;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Case-insensitive request-body enum parsing with an actionable error message — the one copy of the
 * idiom {@code CapabilityParsing#toCapability} (and, before docs/MAP-REWORK-PLAN.md Wave C,
 * {@code CreateMarkRequest#toKind}/{@code PatchMarkRequest#toStatus}) each spelled out by hand.
 *
 * <p>Extracted because the map wire contract (docs/MAP-REWORK-PLAN.md §4.2) parses <em>eight</em>
 * distinct enums off request bodies ({@code MarkKind}, {@code Affiliation}, {@code MarkStatus},
 * {@code LayerKind}, {@code AccessLevel}, {@code LayerGrant.SubjectType}, {@code DrawKind},
 * {@code Verification.VerificationState}); eight hand-copied loops would be eight places for the
 * message format to drift.
 *
 * <p>Every failure is an {@link IllegalArgumentException}, which {@code ApiExceptionHandler} maps to
 * {@code 400 BAD_REQUEST} — the same status the hand-written loops produced.
 */
public final class EnumParsing {

    private EnumParsing() {
    }

    /**
     * Parses {@code raw} as a constant of {@code type}, ignoring case.
     *
     * @param type  the enum to parse into
     * @param field the request field's name, used in the error message
     * @param raw   the raw wire value; {@code null}/blank is an error
     * @param <E>   the enum type
     * @return the matching constant
     * @throws IllegalArgumentException if {@code raw} is {@code null}, blank, or not a constant of
     *                                   {@code type} — message lists every valid value (→ 400)
     */
    public static <E extends Enum<E>> E require(Class<E> type, String field, String raw) {
        if (raw != null) {
            for (E candidate : type.getEnumConstants()) {
                if (candidate.name().equalsIgnoreCase(raw.trim())) {
                    return candidate;
                }
            }
        }
        throw new IllegalArgumentException("Unknown " + field + ": " + raw + " (valid values: "
                + Arrays.stream(type.getEnumConstants()).map(Enum::name).collect(Collectors.joining(", ")) + ")");
    }

    /**
     * The optional counterpart of {@link #require}: an absent (null/blank) value is {@code null}
     * rather than an error, so a partial-patch field can mean "unchanged".
     *
     * @param type  the enum to parse into
     * @param field the request field's name, used in the error message
     * @param raw   the raw wire value, or {@code null}/blank for "absent"
     * @param <E>   the enum type
     * @return the matching constant, or {@code null} if {@code raw} was absent
     * @throws IllegalArgumentException if {@code raw} is present but not a constant of {@code type}
     */
    public static <E extends Enum<E>> E optional(Class<E> type, String field, String raw) {
        return raw == null || raw.isBlank() ? null : require(type, field, raw);
    }
}
