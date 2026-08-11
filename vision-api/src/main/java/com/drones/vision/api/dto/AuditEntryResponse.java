package com.drones.vision.api.dto;

import com.drones.vision.platform.AuditEntry;

import java.time.Instant;
import java.util.Map;

/**
 * Response body element for the audit endpoints.
 *
 * <p>{@code summary} is written to be read on its own — an audit page should be legible without
 * the reader having to reconstruct meaning from ids — while {@code details} carries the
 * before/after specifics for anyone who needs them.
 *
 * @param id         entry identity, as a canonical UUID string
 * @param occurredAt when the change was applied
 * @param actor      the acting user's id, as a canonical UUID string
 * @param action     {@code CREATED}, {@code UPDATED}, {@code DEACTIVATED}, {@code ACTIVATED},
 *                   {@code DELETED} or {@code RESTORED}
 * @param targetType {@code ASSET} or {@code DEVICE}
 * @param targetId   the changed thing's id
 * @param summary    human-readable one-line description
 * @param details    free-form specifics, e.g. changed fields as {@code before → after}
 */
public record AuditEntryResponse(String id, Instant occurredAt, String actor, String action, String targetType,
                                  String targetId, String summary, Map<String, String> details) {

    /**
     * Maps a domain {@link AuditEntry} to its wire representation.
     *
     * @param entry the entry to map
     * @return the response body element for {@code entry}
     */
    public static AuditEntryResponse from(AuditEntry entry) {
        return new AuditEntryResponse(
                entry.id().value().toString(),
                entry.occurredAt(),
                entry.actor().value().toString(),
                entry.action().name(),
                entry.targetType().name(),
                entry.targetId(),
                entry.summary(),
                entry.details());
    }
}
