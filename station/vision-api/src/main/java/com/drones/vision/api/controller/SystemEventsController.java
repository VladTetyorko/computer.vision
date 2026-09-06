package com.drones.vision.api.controller;

import com.drones.vision.api.dto.EventResponse;
import com.drones.vision.api.security.OpenByDesign;
import com.drones.vision.platform.Event;
import com.drones.vision.platform.EventHistoryPort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Driving REST adapter for durable platform {@link Event} history (docs/plans/active/
 * ALWAYS-ON-FLOW-PLAN.md wave B3) — the fix for the notification bell and {@code /manage/system}
 * being a pure {@code computed} over the live {@code event} SSE topic's in-memory log, with nothing
 * behind it: both started empty on every page load and lost everything on a reconnect. This
 * controller is read-only; recording happens off this request path entirely, via {@code
 * PersistingEventPublisher} (vision-app) decorating {@code EventPublisherPort}.
 *
 * <p>Distinct from {@link EventController}, which serves an unrelated domain type ({@code
 * DetectionEvent}, a debounced per-track sighting) at {@code /api/events} — the path here is {@code
 * /api/system/events}, alongside {@link SystemStatusController}/{@link SystemNetworkController}'s
 * existing {@code /api/system/*} family and the frontend's own pre-existing "system events"
 * vocabulary ({@code core/system-events/}).
 *
 * <h2>Authorization — deliberately open (see {@link OpenByDesign})</h2>
 * This endpoint durably replays exactly what the live {@code event} SSE topic already broadcasts to
 * any connected caller, unfiltered by {@link com.drones.vision.platform.VisibilityScope} (vision-api's
 * own MODULE.md documents that topic as "always-on, no auth needed beyond the connection itself").
 * Gating the durable replay more tightly than the live feed it replays would mean 403ing a caller
 * for re-requesting exactly what they were already shown live moments earlier — worse than useless,
 * since it would silently defeat the fix for every non-admin role the moment they reconnect. The
 * frontend's own {@code /manage/system} route documents itself as "the one deliberate ungated
 * diagnostics entry" for the same reason {@link SystemStatusController} is open: an operator whose
 * pipeline just died needs to see why, regardless of role.
 *
 * <p>Per-event {@link com.drones.vision.platform.VisibilityScope} narrowing was considered and
 * rejected as impractical, not merely skipped: most persisted {@link
 * com.drones.vision.platform.EventType}s (device/battery/link/geofence/divergence) carry no {@code
 * streamId} at all — asset identity lives only inside the free-form {@code attributes} map, which
 * this port never queries into (see {@code EventHistoryPort}'s own javadoc) — and the two
 * stream-scoped types ({@code STREAM_STARTED}/{@code STREAM_STOPPED}/{@code PIPELINE_ERROR}) can
 * outlive the stream itself, leaving no live registry entry to resolve an owning asset from. Adding
 * a new cross-context lookup purely to narrow a read the live path never narrowed either would be
 * scope creep this wave does not need.
 */
@RestController
public class SystemEventsController {

    /** Default {@code limit} when the query parameter is absent, mirroring {@link EventController}. */
    static final int DEFAULT_LIMIT = 50;

    private final EventHistoryPort eventHistoryPort;

    public SystemEventsController(EventHistoryPort eventHistoryPort) {
        this.eventHistoryPort = Objects.requireNonNull(eventHistoryPort, "eventHistoryPort must not be null");
    }

    /**
     * Lists recorded platform events, newest-first — the durable backfill a reconnecting live-feed
     * client (or a fresh page load) uses instead of starting from an empty in-memory log.
     *
     * <p>Content depends on {@code vision.events.history.enabled} (vision-app): disabled (the
     * default) means nothing was ever recorded, so this always returns an empty list rather than an
     * error — the endpoint exists either way, exactly like every other flag-gated port in this
     * codebase resolves to a real bean regardless of whether the flag lets it do anything (see
     * vision-app's Conventions: "a flag gates the port... never the controller").
     *
     * @param sinceMs epoch milliseconds; when present, only events whose {@code at} is at or after
     *                this instant are returned (a reconnect cursor); absent means no lower bound
     * @param limit   maximum number of events to return; must be positive (400 otherwise); defaults
     *                to {@value #DEFAULT_LIMIT}
     * @return matching recorded events, newest-first
     */
    @GetMapping("/api/system/events")
    @OpenByDesign(reason = "durably replays exactly what the always-on `event` SSE topic already "
            + "broadcasts unfiltered to any connected caller -- gating the replay tighter than the "
            + "live feed it backfills would 403 a caller for re-requesting what they were already "
            + "shown live, defeating the fix for every non-admin role on reconnect. See class javadoc.")
    public List<EventResponse> recent(@RequestParam(required = false) Long sinceMs,
                                       @RequestParam(defaultValue = "" + DEFAULT_LIMIT) int limit) {
        requirePositiveLimit(limit);
        Instant since = sinceMs == null ? null : Instant.ofEpochMilli(sinceMs);
        return eventHistoryPort.findSince(since, limit).stream().map(EventResponse::from).toList();
    }

    private static void requirePositiveLimit(int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive: " + limit);
        }
    }
}
