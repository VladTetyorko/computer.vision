package com.drones.vision.api.controller;

import com.drones.vision.api.dto.DetectionEventResponse;
import com.drones.vision.api.security.CurrentUser;
import com.drones.vision.api.security.StreamAccess;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.StreamId;
import com.drones.vision.perception.domain.model.DetectionEvent;
import com.drones.vision.perception.domain.port.DetectionEventRepositoryPort;
import com.drones.vision.platform.VisibilityScope;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Driving REST adapter for debounced {@code DetectionEvent}s (docs/plans/done/MVP2-PLAN.md §E, E-a):
 * "a person was seen for a while", not the raw per-frame {@code DetectionResult} stream {@link
 * StreamController#detections} already serves.
 *
 * <p>Kept as its own controller, mirroring {@link UsageTimelineController}'s precedent — a
 * single, focused dependency ({@link DetectionEventRepositoryPort}, used read-only exactly like
 * {@link StreamController} already does for {@code DetectionRepositoryPort}) unrelated to any
 * existing controller's own concerns, rather than growing one of them for an unrelated read.
 *
 * <p><b>Polling, not SSE</b> (docs/plans/done/MVP2-PLAN.md §E, E-a): the simplest thing consistent with the
 * SPA's existing {@code PollScheduler} pattern (vision-web already polls {@code /api/streams}/
 * detections on an interval rather than holding a server push connection open) — adding a second,
 * different delivery mechanism for one new feed was judged not worth the complexity a first cut
 * needs. {@code sinceMs} exists specifically to make that polling cheap: a caller remembers the
 * newest {@code lastSeen} it has already rendered and passes it back in, so a steady-state poll
 * only ever receives genuinely new activity.
 *
 * <p>Per the hexagonal dependency rule (ARCHITECTURE.md §2, enforced by ArchUnit), this module
 * depends only on {@code vision-domain} and {@code vision-application} — never on an adapter.
 *
 * <h2>Authorization (docs/plans/active/ARCHITECTURE-AUDIT-2026-08-26.md R7, finding A2)</h2>
 * A detection event is fleet-operational data (a label, a confidence, a best-effort {@code
 * GeoPosition}) tied to one asset, not deployment-wide reference data — so both handlers are gated
 * by {@link CurrentUser#scope()}, the same {@link VisibilityScope} every other scoped read in this
 * module filters by. {@link #forStream} mirrors {@link StreamController#detections} exactly: {@link
 * StreamAccess#requireVisible(StreamId)} 404s a currently-running stream the caller's scope may not
 * reach, and stays a no-op (so the pre-existing "unknown stream = empty list" contract below is
 * unchanged) for a stream id that is not currently running. {@link #recent} has no single stream to
 * gate on — it filters the fleet-wide list down to visible events instead, mirroring {@code
 * StreamController#list}'s {@code filterVisible} posture rather than 403ing the whole request; an
 * event whose {@code assetId} is {@code null} (device not yet attached to any asset) is visible only
 * to a caller whose scope {@link VisibilityScope#canAdminister()}, the same "unowned device" fallback
 * {@link StreamAccess#visibleAsset} already applies elsewhere.
 */
@RestController
public class EventController {

    /** Default {@code limit} when the query parameter is absent, mirroring {@link
     *  StreamController}'s detections endpoint. */
    static final int DEFAULT_LIMIT = 50;

    private final DetectionEventRepositoryPort detectionEventRepositoryPort;
    private final StreamAccess streamAccess;
    private final CurrentUser currentUser;

    public EventController(DetectionEventRepositoryPort detectionEventRepositoryPort, StreamAccess streamAccess,
                            CurrentUser currentUser) {
        this.detectionEventRepositoryPort =
                Objects.requireNonNull(detectionEventRepositoryPort, "detectionEventRepositoryPort must not be null");
        this.streamAccess = Objects.requireNonNull(streamAccess, "streamAccess must not be null");
        this.currentUser = Objects.requireNonNull(currentUser, "currentUser must not be null");
    }

    /**
     * Lists recent detection events across every stream the caller's scope may see, newest-first by
     * {@code lastSeen}.
     *
     * <p>Filtered, not all-or-nothing 403'd (see class javadoc): {@code limit} still bounds the
     * underlying query, so a caller whose scope excludes some of the newest events may see fewer
     * than {@code limit} results even when more visible ones exist further back — the same trade-off
     * {@code StreamController#list}'s {@code filterVisible} already accepts, since the port has no
     * scope-aware query to push the filter into.
     *
     * @param sinceMs epoch milliseconds; when present, only events whose {@code lastSeen} is at
     *                or after this instant are returned (a polling cursor); absent means no lower
     *                bound
     * @param limit   maximum number of events to return; must be positive (400 otherwise);
     *                defaults to {@value #DEFAULT_LIMIT}
     * @return matching events visible to the caller, newest-first
     */
    @GetMapping("/api/events")
    public List<DetectionEventResponse> recent(@RequestParam(required = false) Long sinceMs,
                                                @RequestParam(defaultValue = "" + DEFAULT_LIMIT) int limit) {
        requirePositiveLimit(limit);
        Instant since = sinceMs == null ? null : Instant.ofEpochMilli(sinceMs);
        VisibilityScope scope = currentUser.scope();
        return detectionEventRepositoryPort.findRecent(since, limit).stream()
                .filter(event -> visible(event, scope))
                .map(DetectionEventResponse::from)
                .toList();
    }

    /**
     * Lists one stream's detection events, newest-first by {@code lastSeen}.
     *
     * <p>An unknown or already-stopped stream id behaves exactly as {@link
     * DetectionEventRepositoryPort#findByStream} does (an empty list), not a 404 — the same
     * precedent {@link StreamController#detections} already sets for the same reason: there is no
     * service method here to layer "unknown stream" validation onto, and {@link
     * StreamAccess#requireVisible(StreamId)} is a no-op for exactly that case (see class javadoc). A
     * stream that <em>is</em> currently running, on a device the caller's scope may not reach, 404s
     * instead — existence is never revealed.
     *
     * @param streamId the stream to inspect, as a canonical UUID string
     * @param limit    maximum number of events to return; must be positive (400 otherwise);
     *                 defaults to {@value #DEFAULT_LIMIT}
     * @return the stream's events, newest-first
     */
    @GetMapping("/api/streams/{streamId}/events")
    public List<DetectionEventResponse> forStream(@PathVariable String streamId,
                                                   @RequestParam(defaultValue = "" + DEFAULT_LIMIT) int limit) {
        requirePositiveLimit(limit);
        StreamId id = StreamId.of(streamId);
        streamAccess.requireVisible(id);
        return detectionEventRepositoryPort.findByStream(id, limit).stream()
                .map(DetectionEventResponse::from)
                .toList();
    }

    private boolean visible(DetectionEvent event, VisibilityScope scope) {
        AssetId assetId = event.assetId();
        return assetId == null ? scope.canAdminister() : streamAccess.visibleAsset(assetId, scope);
    }

    private static void requirePositiveLimit(int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive: " + limit);
        }
    }
}
