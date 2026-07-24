package com.drones.vision.api;

import com.drones.vision.api.dto.LiveSubscriptionResponse;
import com.drones.vision.api.dto.UpdateLiveTopicsRequest;
import com.drones.vision.api.live.LiveUpdateRegistry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Objects;

/**
 * Driving REST adapter for the server-push data plane (docs/REALTIME-PLAN.md §4) — one SSE
 * connection replacing the steady-state polling of fleet state, telemetry, detections, and
 * events.
 *
 * <p>Gated by {@code vision.live.enabled} (default {@code true}, {@code matchIfMissing}): when
 * {@code false}, neither this controller nor {@link LiveUpdateRegistry} is registered as a bean at
 * all, so {@code /api/live} 404s exactly like any other unmapped route (docs/REALTIME-PLAN.md §4,
 * item 4) — {@code vision-app}'s wiring still supplies a no-op {@code LiveUpdatePublisherPort} to
 * the application layer in that case (see that module's {@code MODULE.md}).
 *
 * <p>All actual connection/topic/replay/coalescing logic lives in {@link LiveUpdateRegistry} —
 * this class is a thin HTTP-shape translation only (query param/header parsing, path variable),
 * mirroring every other controller in this module.
 */
@RestController
@ConditionalOnProperty(prefix = "vision.live", name = "enabled", matchIfMissing = true)
public class LiveController {

    private final LiveUpdateRegistry registry;

    /**
     * {@code @Qualifier} disambiguates the component-scanned {@code liveUpdateRegistry} bean from
     * {@code WiringConfiguration#liveUpdatePublisherPort} — that {@code @Bean} method's declared
     * return type is {@code LiveUpdatePublisherPort}, but once instantiated its actual runtime
     * instance <em>is</em> this same {@link LiveUpdateRegistry} singleton (when {@code
     * vision.live.enabled=true}), so Spring's type-based autowiring sees two same-typed candidates
     * for a plain, unqualified {@code LiveUpdateRegistry} constructor parameter.
     */
    public LiveController(@Qualifier("liveUpdateRegistry") LiveUpdateRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
    }

    /**
     * Opens a new live-update connection.
     *
     * @param topics       comma-separated {@code telemetry:<assetId>}/{@code detections:<assetId>}
     *                     topics to opt into, in addition to the always-on fleet/event topics;
     *                     {@code null}/absent means fleet/event only
     * @param lastEventId  the {@code Last-Event-ID} header {@code EventSource} sends automatically
     *                     on reconnect (this connection's own {@code seq}); absent means a fresh
     *                     connect, not a resume
     * @return the SSE stream
     * @throws IllegalArgumentException if {@code topics} contains a malformed entry (unknown kind,
     *                                   missing/malformed asset id) — 400 via {@link
     *                                   ApiExceptionHandler}
     */
    @GetMapping(path = "/api/live", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter connect(@RequestParam(required = false) String topics,
                              @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
        Long since = (lastEventId == null || lastEventId.isBlank()) ? null : Long.parseLong(lastEventId);
        return registry.connect(topics, since);
    }

    /**
     * Adds/removes topics on an already-open connection.
     *
     * @param connectionId the connection to update (from the {@code connection} SSE event sent
     *                      right after connecting)
     * @param request      topics to add/remove; an absent body is treated as "change nothing"
     * @return the connection's full topic set afterward
     * @throws java.util.NoSuchElementException if {@code connectionId} is unknown (404 via {@link
     *                                            ApiExceptionHandler})
     * @throws IllegalArgumentException          if a topic entry is malformed (400)
     */
    @PatchMapping("/api/live/{connectionId}/topics")
    public LiveSubscriptionResponse updateTopics(@PathVariable String connectionId,
                                                  @RequestBody(required = false) UpdateLiveTopicsRequest request) {
        return registry.updateTopics(connectionId, request == null ? UpdateLiveTopicsRequest.EMPTY : request);
    }
}
