package com.drones.vision.api;

import com.drones.vision.api.dto.DetectionEventResponse;
import com.drones.vision.domain.model.StreamId;
import com.drones.vision.domain.port.out.DetectionEventRepositoryPort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Driving REST adapter for debounced {@code DetectionEvent}s (docs/MVP2-PLAN.md §E, E-a):
 * "a person was seen for a while", not the raw per-frame {@code DetectionResult} stream {@link
 * StreamController#detections} already serves.
 *
 * <p>Kept as its own controller, mirroring {@link UsageTimelineController}'s precedent — a
 * single, focused dependency ({@link DetectionEventRepositoryPort}, used read-only exactly like
 * {@link StreamController} already does for {@code DetectionRepositoryPort}) unrelated to any
 * existing controller's own concerns, rather than growing one of them for an unrelated read.
 *
 * <p><b>Polling, not SSE</b> (docs/MVP2-PLAN.md §E, E-a): the simplest thing consistent with the
 * SPA's existing {@code PollScheduler} pattern (vision-web already polls {@code /api/streams}/
 * detections on an interval rather than holding a server push connection open) — adding a second,
 * different delivery mechanism for one new feed was judged not worth the complexity a first cut
 * needs. {@code sinceMs} exists specifically to make that polling cheap: a caller remembers the
 * newest {@code lastSeen} it has already rendered and passes it back in, so a steady-state poll
 * only ever receives genuinely new activity.
 *
 * <p>Per the hexagonal dependency rule (ARCHITECTURE.md §2, enforced by ArchUnit), this module
 * depends only on {@code vision-domain} and {@code vision-application} — never on an adapter.
 */
@RestController
public class EventController {

    /** Default {@code limit} when the query parameter is absent, mirroring {@link
     *  StreamController}'s detections endpoint. */
    static final int DEFAULT_LIMIT = 50;

    private final DetectionEventRepositoryPort detectionEventRepositoryPort;

    public EventController(DetectionEventRepositoryPort detectionEventRepositoryPort) {
        this.detectionEventRepositoryPort =
                Objects.requireNonNull(detectionEventRepositoryPort, "detectionEventRepositoryPort must not be null");
    }

    /**
     * Lists recent detection events across every stream, newest-first by {@code lastSeen}.
     *
     * @param sinceMs epoch milliseconds; when present, only events whose {@code lastSeen} is at
     *                or after this instant are returned (a polling cursor); absent means no lower
     *                bound
     * @param limit   maximum number of events to return; must be positive (400 otherwise);
     *                defaults to {@value #DEFAULT_LIMIT}
     * @return matching events, newest-first
     */
    @GetMapping("/api/events")
    public List<DetectionEventResponse> recent(@RequestParam(required = false) Long sinceMs,
                                                @RequestParam(defaultValue = "" + DEFAULT_LIMIT) int limit) {
        requirePositiveLimit(limit);
        Instant since = sinceMs == null ? null : Instant.ofEpochMilli(sinceMs);
        return detectionEventRepositoryPort.findRecent(since, limit).stream()
                .map(DetectionEventResponse::from)
                .toList();
    }

    /**
     * Lists one stream's detection events, newest-first by {@code lastSeen}.
     *
     * <p>An unknown stream id behaves exactly as {@link DetectionEventRepositoryPort#findByStream}
     * does (an empty list), not a 404 — the same precedent {@link StreamController#detections}
     * already sets for the same reason: there is no service method here to layer "unknown stream"
     * validation onto.
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
        return detectionEventRepositoryPort.findByStream(StreamId.of(streamId), limit).stream()
                .map(DetectionEventResponse::from)
                .toList();
    }

    private static void requirePositiveLimit(int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive: " + limit);
        }
    }
}
