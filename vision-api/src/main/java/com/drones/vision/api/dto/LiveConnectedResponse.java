package com.drones.vision.api.dto;

import java.util.List;

/**
 * Payload of the {@code connection} SSE event — the very first thing sent on every new {@code GET
 * /api/live} connection (docs/REALTIME-PLAN.md §4, item 2: "connection id returned in the first
 * SSE event"). Sent as its own named SSE event ({@code event: connection}), distinct from the
 * {@code message}-named events carrying {@link LiveEnvelopeResponse}s, since it is connection
 * handshake metadata, not a live update — it has no {@code seq} of its own and isn't replayed on
 * resume.
 *
 * @param connectionId the id to use in {@code PATCH /api/live/{connectionId}/topics}
 * @param topics       the wire form of every topic this connection is (already) subscribed to,
 *                     right after connecting (always includes the implicit, always-on {@code
 *                     fleet} topic)
 */
public record LiveConnectedResponse(String connectionId, List<String> topics) {
}
