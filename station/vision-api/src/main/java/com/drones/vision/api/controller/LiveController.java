package com.drones.vision.api.controller;

import com.drones.vision.api.dto.LiveSubscriptionResponse;
import com.drones.vision.api.dto.UpdateLiveTopicsRequest;
import com.drones.vision.api.exception.ApiExceptionHandler;
import com.drones.vision.api.live.LiveAssetAccess;
import com.drones.vision.api.live.LiveUpdateRegistry;
import com.drones.vision.api.live.MapVisibility;
import com.drones.vision.api.security.CurrentUser;
import com.drones.vision.kernel.UserId;
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
 * Driving REST adapter for the server-push data plane (docs/plans/done/REALTIME-PLAN.md §4) — one SSE
 * connection replacing the steady-state polling of fleet state, telemetry, detections, and
 * events.
 *
 * <p>Gated by {@code vision.live.enabled} (default {@code true}, {@code matchIfMissing}): when
 * {@code false}, neither this controller nor {@link LiveUpdateRegistry} is registered as a bean at
 * all, so {@code /api/live} 404s exactly like any other unmapped route (docs/plans/done/REALTIME-PLAN.md §4,
 * item 4) — {@code vision-app}'s wiring still supplies a no-op implementation of whichever
 * live-update port each application-layer collaborator needs (see that module's {@code MODULE.md}).
 *
 * <p>All actual connection/topic/replay/coalescing logic lives in {@link LiveUpdateRegistry} —
 * this class is a thin HTTP-shape translation only (query param/header parsing, path variable),
 * mirroring every other controller in this module.
 *
 * <h2>Who the connection is</h2>
 * Two things beyond HTTP shape happen here, and only here: the {@code map} topic's delivery is
 * scoped per viewer (docs/plans/done/MAP-REWORK-PLAN.md §4.3), and every per-asset topic
 * (telemetry/detections/geo) is scoped per caller (docs/plans/active/LIVE-SCOPE-PLAN.md §2, W3) —
 * identity is resolved at the API edge in both cases, never inside the registry. So {@link #connect}
 * reads {@link CurrentUser#userId()}/{@link CurrentUser#viewer()} once, at connect time: {@code
 * userId} both filters the requested {@code topics} down to ones the caller may see ({@link
 * LiveAssetAccess#filterTopicsParam}) and becomes the per-delivery predicate ({@link
 * LiveAssetAccess#deliveryPredicate}); {@code viewer()} becomes the {@code map} predicate ({@link
 * MapVisibility#deliveryPredicate}). Both predicates, plus {@code userId} itself, are handed to
 * {@link LiveUpdateRegistry#connect} — which stores them on the connection and consults them on
 * every broadcast and every resume replay without ever knowing whose they are. {@link
 * #updateTopics} does the mirror-image filtering on a {@code PATCH}'s {@code add} list ({@link
 * LiveAssetAccess#filterAdditions}) and threads {@code userId} through so the registry can refuse a
 * caller who does not own the target connection.
 */
@RestController
@ConditionalOnProperty(prefix = "vision.live", name = "enabled", matchIfMissing = true)
public class LiveController {

    private final LiveUpdateRegistry registry;
    private final MapVisibility mapVisibility;
    private final LiveAssetAccess assetAccess;
    private final CurrentUser currentUser;

    /**
     * {@code @Qualifier} disambiguates the component-scanned {@code liveUpdateRegistry} bean from
     * {@code ApplicationServiceWiring}'s five {@code *LiveUpdatePort} {@code @Bean} methods — each
     * one's declared return type is a single-method port interface, but once instantiated its
     * actual runtime instance <em>is</em> this same {@link LiveUpdateRegistry} singleton (when
     * {@code vision.live.enabled=true}), so Spring's type-based autowiring sees two same-typed
     * candidates for a plain, unqualified {@code LiveUpdateRegistry} constructor parameter.
     */
    public LiveController(@Qualifier("liveUpdateRegistry") LiveUpdateRegistry registry,
                           MapVisibility mapVisibility, LiveAssetAccess assetAccess, CurrentUser currentUser) {
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        this.mapVisibility = Objects.requireNonNull(mapVisibility, "mapVisibility must not be null");
        this.assetAccess = Objects.requireNonNull(assetAccess, "assetAccess must not be null");
        this.currentUser = Objects.requireNonNull(currentUser, "currentUser must not be null");
    }

    /**
     * Opens a new live-update connection.
     *
     * @param topics       comma-separated {@code telemetry:<assetId>}/{@code detections:<assetId>}
     *                     topics to opt into, in addition to the always-on fleet/event topics;
     *                     {@code null}/absent means fleet/event only
     * @param lastEventId  the {@code Last-Event-ID} header {@code EventSource} sends automatically
     *                     on reconnect (this connection's own {@code seq}); absent means a fresh
     *                     connect, not a resume. The replayed burst is re-filtered against the
     *                     viewer resolved <em>on this request</em>, not the one that first opened
     *                     the stream, so a revoked grant is not replayed back.
     * @return the SSE stream
     * @throws IllegalArgumentException if {@code topics} contains a malformed entry (unknown kind,
     *                                   missing/malformed asset id) — 400 via {@link
     *                                   ApiExceptionHandler}
     */
    @GetMapping(path = "/api/live", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter connect(@RequestParam(required = false) String topics,
                              @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
        Long since = (lastEventId == null || lastEventId.isBlank()) ? null : Long.parseLong(lastEventId);
        UserId userId = currentUser.userId();
        String visibleTopics = assetAccess.filterTopicsParam(userId, topics);
        return registry.connect(visibleTopics, since, userId, mapVisibility.deliveryPredicate(currentUser.viewer()),
                assetAccess.deliveryPredicate(userId));
    }

    /**
     * Adds/removes topics on an already-open connection.
     *
     * @param connectionId the connection to update (from the {@code connection} SSE event sent
     *                      right after connecting)
     * @param request      topics to add/remove; an absent body is treated as "change nothing"; any
     *                      {@code add} entry naming an asset outside the caller's current scope is
     *                      silently dropped ({@link LiveAssetAccess#filterAdditions}) rather than
     *                      failing the whole request
     * @return the connection's full topic set afterward
     * @throws java.util.NoSuchElementException if {@code connectionId} is unknown, <em>or belongs to
     *                                            a different caller</em> (docs/plans/active/LIVE-SCOPE-PLAN.md
     *                                            §2, W3) — both collapse to the same 404 via {@link
     *                                            ApiExceptionHandler} so a caller cannot distinguish
     *                                            "no such connection" from "not yours"
     * @throws IllegalArgumentException          if a topic entry is malformed (400)
     */
    @PatchMapping("/api/live/{connectionId}/topics")
    public LiveSubscriptionResponse updateTopics(@PathVariable String connectionId,
                                                  @RequestBody(required = false) UpdateLiveTopicsRequest request) {
        UserId userId = currentUser.userId();
        UpdateLiveTopicsRequest body = request == null ? UpdateLiveTopicsRequest.EMPTY : request;
        return registry.updateTopics(connectionId, assetAccess.filterAdditions(userId, body), userId);
    }
}
