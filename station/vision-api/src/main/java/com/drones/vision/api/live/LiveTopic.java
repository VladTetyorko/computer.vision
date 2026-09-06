package com.drones.vision.api.live;

import com.drones.vision.kernel.AssetId;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * A subscribable {@code GET /api/live} topic (docs/plans/done/REALTIME-PLAN.md §4, item 2) — {@code fleet},
 * {@code event}, {@code devices}, {@code detection-events}, {@code map}, {@code discovery},
 * {@code zones}, and {@code system} are implicit and always-on (every connection gets all eight
 * regardless of the {@code topics} query parameter); {@code telemetry:<assetId>}/{@code
 * detections:<assetId>}/{@code geo:<assetId>} are opt-in, named explicitly by the caller.
 *
 * @param kind    which kind of topic
 * @param assetId the asset this topic is scoped to; {@code null} for {@link #FLEET}/{@link
 *                #EVENT}/{@link #DEVICES}/{@link #DETECTION_EVENTS}/{@link #MAP}/{@link
 *                #DISCOVERY}/{@link #ZONES}/{@link #SYSTEM}
 */
record LiveTopic(LiveTopicKind kind, AssetId assetId) {

    /** The always-on fleet-state topic (asset/device/stream lifecycle). */
    static final LiveTopic FLEET = new LiveTopic(LiveTopicKind.FLEET, null);

    /** The always-on domain-event topic (device online/offline, stream started/stopped, ...). */
    static final LiveTopic EVENT = new LiveTopic(LiveTopicKind.EVENT, null);

    /** The always-on device-list + active-stream-list topic (extends the R-c channel — see {@link LiveTopicKind#DEVICES}). */
    static final LiveTopic DEVICES = new LiveTopic(LiveTopicKind.DEVICES, null);

    /** The always-on debounced-detection-event topic (extends the R-c channel — see {@link LiveTopicKind#DETECTION_EVENTS}). */
    static final LiveTopic DETECTION_EVENTS = new LiveTopic(LiveTopicKind.DETECTION_EVENTS, null);

    /** The always-on common-operational-picture topic (docs/plans/done/MAP-REWORK-PLAN.md §4.3 — see {@link LiveTopicKind#MAP}); one of two topics (with {@link #FLEET}) whose delivery is filtered per connection. */
    static final LiveTopic MAP = new LiveTopic(LiveTopicKind.MAP, null);

    /** The always-on discovery-inbox delta topic (docs/plans/active/SOURCE-ONBOARDING-2-PLAN.md §3.2 C4 — see {@link LiveTopicKind#DISCOVERY}). */
    static final LiveTopic DISCOVERY = new LiveTopic(LiveTopicKind.DISCOVERY, null);

    /** The always-on geofence-zone delta topic (docs/plans/active/LIVE-POLL-RETIREMENT-PLAN.md §3 D2/§4.1 — see {@link LiveTopicKind#ZONES}). */
    static final LiveTopic ZONES = new LiveTopic(LiveTopicKind.ZONES, null);

    /** The always-on system-health topic (docs/plans/active/LIVE-POLL-RETIREMENT-PLAN.md §3 D3/§4.2 — see {@link LiveTopicKind#SYSTEM}). */
    static final LiveTopic SYSTEM = new LiveTopic(LiveTopicKind.SYSTEM, null);

    static LiveTopic telemetry(AssetId assetId) {
        return new LiveTopic(LiveTopicKind.TELEMETRY, assetId);
    }

    static LiveTopic detections(AssetId assetId) {
        return new LiveTopic(LiveTopicKind.DETECTIONS, assetId);
    }

    /** Visual geolocation's per-asset corrected-track topic (docs/plans/done/VISUAL-GEO-V2-PLAN.md §3.4, D11). */
    static LiveTopic geo(AssetId assetId) {
        return new LiveTopic(LiveTopicKind.GEO, assetId);
    }

    /**
     * @return the wire form, e.g. {@code "fleet"} or {@code "telemetry:<assetId>"}
     */
    String wire() {
        return assetId == null ? kind.wire() : kind.wire() + ":" + assetId.value();
    }

    /**
     * Parses one topic string, e.g. {@code "telemetry:3fa8..."}. {@code "fleet"}/{@code "event"}
     * (with no {@code ":..."} suffix) are accepted as harmless, redundant aliases for the
     * already-implicit topics of the same kind.
     *
     * @param raw the topic string
     * @return the parsed topic
     * @throws IllegalArgumentException if the kind prefix is unrecognized, a {@code telemetry}/
     *                                   {@code detections} topic is missing its asset id, or the
     *                                   asset id is not a valid UUID
     */
    static LiveTopic parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("live topic must not be blank");
        }
        String trimmed = raw.trim();
        int colon = trimmed.indexOf(':');
        String kindPart = colon < 0 ? trimmed : trimmed.substring(0, colon);
        String idPart = colon < 0 ? null : trimmed.substring(colon + 1);
        LiveTopicKind kind = LiveTopicKind.fromWire(kindPart);
        return switch (kind) {
            case FLEET -> FLEET;
            case EVENT -> EVENT;
            case DEVICES -> DEVICES;
            case DETECTION_EVENTS -> DETECTION_EVENTS;
            case MAP -> MAP;
            case DISCOVERY -> DISCOVERY;
            case ZONES -> ZONES;
            case SYSTEM -> SYSTEM;
            case TELEMETRY -> telemetry(requireAssetId(idPart, "telemetry"));
            case DETECTIONS -> detections(requireAssetId(idPart, "detections"));
            case GEO -> geo(requireAssetId(idPart, "geo"));
        };
    }

    private static AssetId requireAssetId(String idPart, String kindName) {
        if (idPart == null || idPart.isBlank()) {
            throw new IllegalArgumentException(
                    kindName + " topic requires an asset id, e.g. \"" + kindName + ":<assetId>\"");
        }
        return AssetId.of(idPart);
    }

    /**
     * Parses a comma-separated {@code topics} query parameter value. Blank entries are ignored;
     * {@code null}/blank input yields an empty set (the caller always adds the implicit {@link
     * #FLEET}/{@link #EVENT} topics on top of whatever this returns).
     *
     * @param raw the raw {@code topics} query parameter value
     * @return the parsed, explicitly-requested topics
     * @throws IllegalArgumentException per {@link #parse(String)}, for any malformed entry
     */
    static Set<LiveTopic> parseTopicsParam(String raw) {
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }
        Set<LiveTopic> topics = new LinkedHashSet<>();
        for (String part : raw.split(",")) {
            if (!part.isBlank()) {
                topics.add(parse(part));
            }
        }
        return topics;
    }
}
