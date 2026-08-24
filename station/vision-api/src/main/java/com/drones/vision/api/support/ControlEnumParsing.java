package com.drones.vision.api.support;

import com.drones.vision.flight.domain.model.ControlBinding;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Shared case-insensitive enum parsing for the controller-setup request bodies ({@code
 * ...api.dto.ControlBindingPayload}, {@code ActionBindingPayload}, {@code CreateControlProfileRequest}).
 *
 * <p>The same idiom {@link CapabilityParsing} and {@code GeofenceZoneRequest#toKind} already use,
 * hoisted here because four DTOs need it across three enums: an unrecognized name is an {@link
 * IllegalArgumentException} that lists the valid values, so a client that sends {@code "switch3"}
 * learns what to send instead of receiving a bare 400.
 *
 * <p>Not part of the wire contract — request parsing only, and it has no second implementation
 * ({@code .claude/skills/java-clean-code/SKILL.md} §1).
 */
public final class ControlEnumParsing {

    private ControlEnumParsing() {
    }

    /**
     * Resolves one enum constant by name, ignoring case.
     *
     * @param type  the enum to resolve against
     * @param value the client-supplied name; {@code null} or blank is rejected
     * @param field the request field's name, for the error message
     * @param <E>   the enum type
     * @return the matching constant
     * @throws IllegalArgumentException if {@code value} is missing or matches nothing
     */
    public static <E extends Enum<E>> E parse(Class<E> type, String value, String field) {
        if (value != null && !value.isBlank()) {
            for (E candidate : type.getEnumConstants()) {
                if (candidate.name().equalsIgnoreCase(value.trim())) {
                    return candidate;
                }
            }
        }
        throw new IllegalArgumentException("Unknown " + field + ": " + value + " (valid values: "
                + Arrays.stream(type.getEnumConstants()).map(Enum::name).collect(Collectors.joining(", ")) + ")");
    }

    /**
     * Resolves a {@code source} field, whose enum is nested inside {@link ControlBinding} and so
     * reads badly at every call site.
     *
     * @param value {@code "AXIS"} or {@code "BUTTON"}, case-insensitive
     * @return the matching source
     * @throws IllegalArgumentException if it matches neither
     */
    public static ControlBinding.Source source(String value) {
        return parse(ControlBinding.Source.class, value, "source");
    }
}
