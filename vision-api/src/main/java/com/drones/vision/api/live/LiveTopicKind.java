package com.drones.vision.api.live;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * The six kinds of {@link LiveTopic} (docs/REALTIME-PLAN.md §4; {@link #DEVICES}/{@link
 * #DETECTION_EVENTS} extend the channel for the fleet/warehouse and events UIs) — {@link #wire()}
 * is both the topic-string prefix (e.g. {@code "telemetry:<assetId>"}) and the {@code
 * com.drones.vision.api.dto.LiveEnvelopeResponse#type()} value for envelopes of that kind, since
 * the two are deliberately the same vocabulary.
 *
 * <p>Each constant carries its own wire string explicitly (rather than deriving it from {@link
 * #name()}) so a multi-word kind like {@link #DETECTION_EVENTS} can use the hyphenated {@code
 * "detection-events"} the frontend/REST convention already uses (matching {@code GET /api/events}),
 * not the underscore a lower-cased enum name would produce.
 */
enum LiveTopicKind {
    /** Asset-centric fleet snapshot (docs/REALTIME-PLAN.md §4) — {@code List<AssetSummaryResponse>}. */
    FLEET("fleet"),
    /** Generic domain {@code Event}s (device online/offline, stream started/stopped, pipeline errors, ...). */
    EVENT("event"),
    /** Per-asset telemetry samples, opt-in. */
    TELEMETRY("telemetry"),
    /** Per-asset latest detection result, opt-in. */
    DETECTIONS("detections"),
    /**
     * Device-list + active-stream-list snapshot — the domain {@code FleetStore} (vision-web) polls
     * via {@code GET /api/devices}+{@code GET /api/streams} today; deliberately a separate topic
     * from {@link #FLEET}, which is asset-centric ({@code AssetSummaryResponse}) and shares nothing
     * with raw {@code Device}/{@code ActiveStream} shapes or that store's domain.
     */
    DEVICES("devices"),
    /**
     * Debounced {@code DetectionEvent} occurrences (open/advance/close) — the same shape {@code GET
     * /api/events} serves, carried here instead of the unrelated generic {@link #EVENT} topic.
     */
    DETECTION_EVENTS("detection-events");

    private final String wire;

    LiveTopicKind(String wire) {
        this.wire = wire;
    }

    /**
     * @return the wire form (e.g. {@code "telemetry"}, {@code "detection-events"})
     */
    String wire() {
        return wire;
    }

    /**
     * @param wire a wire form
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
