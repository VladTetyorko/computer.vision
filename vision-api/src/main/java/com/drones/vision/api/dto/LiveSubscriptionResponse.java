package com.drones.vision.api.dto;

import java.util.List;

/**
 * Response body of {@code PATCH /api/live/{connectionId}/topics} — the connection's full topic
 * set after applying the requested add/remove.
 *
 * @param connectionId the connection that was updated
 * @param topics       every topic this connection is now subscribed to (wire form), including the
 *                     implicit, always-on {@code fleet} topic
 */
public record LiveSubscriptionResponse(String connectionId, List<String> topics) {
}
