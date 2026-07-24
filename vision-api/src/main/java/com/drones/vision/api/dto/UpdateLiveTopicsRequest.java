package com.drones.vision.api.dto;

import java.util.List;

/**
 * Request body of {@code PATCH /api/live/{connectionId}/topics} (docs/REALTIME-PLAN.md §4, item
 * 2) — adds/removes per-asset {@code telemetry:<assetId>}/{@code detections:<assetId>} topics on
 * an already-open connection, so a viewer's fan-out shrinks/grows as tiles enter/leave the screen
 * without reconnecting.
 *
 * @param add    topic strings to subscribe to, in addition to whatever is already subscribed;
 *               {@code null}/absent means none
 * @param remove topic strings to unsubscribe from; {@code null}/absent means none. The always-on
 *               {@code fleet} topic is never actually removed even if named here (see {@link
 *               com.drones.vision.api.live.LiveTopic#FLEET})
 */
public record UpdateLiveTopicsRequest(List<String> add, List<String> remove) {

    /** Used when the request body is entirely absent. */
    public static final UpdateLiveTopicsRequest EMPTY = new UpdateLiveTopicsRequest(List.of(), List.of());

    public UpdateLiveTopicsRequest {
        add = add == null ? List.of() : List.copyOf(add);
        remove = remove == null ? List.of() : List.copyOf(remove);
    }
}
