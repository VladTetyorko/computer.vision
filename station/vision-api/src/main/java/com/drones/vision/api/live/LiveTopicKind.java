package com.drones.vision.api.live;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * The ten kinds of {@link LiveTopic} (docs/plans/done/REALTIME-PLAN.md §4; {@link #DEVICES}/{@link
 * #DETECTION_EVENTS} extend the channel for the fleet/warehouse and events UIs; {@link #MAP}
 * extends it again for the common operational picture, docs/plans/done/MAP-REWORK-PLAN.md
 * §4.3; {@link #GEO} extends it again for visual geolocation's corrected track,
 * docs/plans/done/VISUAL-GEO-V2-PLAN.md §3.4/D11; {@link #ZONES}/{@link #SYSTEM} extend it again
 * for geofence-zone deltas and a server-side system-health sampler, docs/plans/active/
 * LIVE-POLL-RETIREMENT-PLAN.md §3 D2/D3 &amp; §4.1/§4.2, waves L3/L4) — {@link #wire()}
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
    /**
     * Asset-centric fleet snapshot (docs/plans/done/REALTIME-PLAN.md §4) — {@code
     * List<AssetSummaryResponse>}. Always-on, like {@link #EVENT}/{@link #DEVICES}/{@link
     * #DETECTION_EVENTS}/{@link #MAP}/{@link #DISCOVERY} — but its payload is filtered per
     * connection, down to the assets that connection's viewer may currently see, the same policy
     * {@code GET /api/assets} itself applies (a viewer with nothing visible still receives an empty
     * list, never a dropped envelope) — see {@link LiveConnection#project}.
     */
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
    DETECTION_EVENTS("detection-events"),
    /**
     * The common operational picture (docs/plans/done/MAP-REWORK-PLAN.md §4.3) — marks, drawings and layers,
     * always-on. <strong>Replaces the {@code marks} topic outright</strong> (removed, not
     * deprecated: the SPA is the only client and migrates in Wave E).
     *
     * <p>All three entity types and all four lifecycle actions ride as {@code entity}/{@code action}
     * fields inside {@code MapEventPayload} rather than as twelve topic kinds, mirroring how {@link
     * #DETECTION_EVENTS} carries OPEN/CLOSED in one topic instead of two.
     *
     * <p><strong>Unlike most topics here, this one is not broadcast to everyone unchanged.</strong>
     * Delivery is filtered per connection against the viewer captured at connect, by the event's
     * {@code layerId} — see {@link LiveConnection#project} and {@link MapVisibility}. Visibility
     * is a property of the data, resolved server-side; a client never filters the map itself.
     * {@link #FLEET} gets the same per-connection treatment for its own reason — see that constant's
     * own javadoc and {@link LiveConnection#project}.
     */
    MAP("map"),
    /**
     * Per-asset visual-geolocation corrected track, opt-in (docs/plans/done/VISUAL-GEO-V2-PLAN.md
     * §3.4, D11) — exactly {@link #TELEMETRY}'s shape and scoping: unfiltered, a client must name
     * {@code geo:<assetId>} in {@code ?topics=} to receive it. Ring-buffer capacity 1: the freshest
     * {@code CorrectionResponse} is the only one that matters (CLAUDE.md rule 9); replay after
     * reconnect comes from {@code GET /api/geo/corrections}, not this buffer.
     */
    GEO("geo"),
    /**
     * Discovery-inbox deltas — an operator's found-devices list changing (docs/plans/active/
     * SOURCE-ONBOARDING-2-PLAN.md &sect;3.2 C4). Always-on, like {@link #FLEET}/{@link #EVENT}/{@link
     * #DEVICES}/{@link #DETECTION_EVENTS}/{@link #MAP}. Delta-only: published only when a sweep's
     * {@code ReportOutcome.changed()} is {@code true}, or on an operator verb (attach/register/
     * dismiss/restore) — never once per sweep regardless of content.
     */
    DISCOVERY("discovery"),
    /**
     * Geofence-zone deltas — created/updated/deleted (docs/plans/active/LIVE-POLL-RETIREMENT-PLAN.md
     * &sect;3 D2/&sect;4.1, wave L3). Always-on, like {@link #FLEET}/{@link #EVENT}/{@link
     * #DEVICES}/{@link #DETECTION_EVENTS}/{@link #MAP}/{@link #DISCOVERY}. Deliberately its own
     * topic rather than riding {@link #MAP}: a geofence zone is {@code vision-flight} domain, and
     * {@code vision-map} has no declared dependency edge on {@code vision-flight} (see {@code
     * ContextArchitectureTest}) — carrying it as a {@code MapEvent} entity kind would require one.
     * Not filtered per connection: every zone is visible to every caller, matching {@code
     * GeofenceController#list}'s {@code @OpenByDesign} REST read.
     */
    ZONES("zones"),
    /**
     * Server-sampled subsystem health, broadcast only when it changes (docs/plans/active/
     * LIVE-POLL-RETIREMENT-PLAN.md &sect;3 D3/&sect;4.2, wave L4) — the same {@code
     * com.drones.vision.api.dto.SystemStatusResponse} shape {@code GET /api/system/status} already
     * returns. Always-on. Ring-buffer capacity 1: only the latest sample matters (CLAUDE.md rule 9).
     */
    SYSTEM("system");

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
