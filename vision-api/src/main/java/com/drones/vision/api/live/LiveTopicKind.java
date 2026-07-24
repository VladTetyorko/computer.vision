package com.drones.vision.api.live;

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * The four kinds of {@link LiveTopic} (docs/REALTIME-PLAN.md §4) — {@link #wire()} is both the
 * topic-string prefix (e.g. {@code "telemetry:<assetId>"}) and the {@code
 * com.drones.vision.api.dto.LiveEnvelopeResponse#type()} value for envelopes of that kind, since
 * the two are deliberately the same vocabulary.
 */
enum LiveTopicKind {
    FLEET,
    EVENT,
    TELEMETRY,
    DETECTIONS;

    /**
     * @return the lower-case wire form (e.g. {@code "telemetry"})
     */
    String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * @param wire a lower-case wire form
     * @return the matching kind
     * @throws IllegalArgumentException if {@code wire} matches none, listing the valid values
     */
    static LiveTopicKind fromWire(String wire) {
        for (LiveTopicKind kind : values()) {
            if (kind.wire().equals(wire)) {
                return kind;
            }
        }
        throw new IllegalArgumentException("Unknown live topic kind: " + wire + " (expected one of "
                + Arrays.stream(values()).map(LiveTopicKind::wire).collect(Collectors.joining(", ")) + ")");
    }
}
