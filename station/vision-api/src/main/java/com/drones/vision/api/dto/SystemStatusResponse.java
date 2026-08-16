package com.drones.vision.api.dto;

import com.drones.vision.platform.Health;

import java.time.Instant;
import java.util.List;

/**
 * Response body for {@code GET /api/system/status} (docs/plans/active/SYSTEM-STATUS-PLAN.md §4.3's
 * frozen wire contract) — UX-DESIGN §7.2's "honest status over optimistic status" doctrine made into
 * an endpoint: whether each subsystem this deployment depends on is currently working.
 *
 * <p>Readable by any authenticated user, not {@code managerOnly} — a deliberate call
 * (docs/plans/active/SYSTEM-STATUS-PLAN.md §4.3): an operator whose CV has died must be able to see
 * why, and this exposes no secrets, only hostnames and states.
 *
 * @param overall     the worst {@link Health} across {@code subsystems}, ignoring {@link
 *                     Health#DISABLED} (a deliberately switched-off subsystem must never read as a
 *                     fault); {@link Health#UNKNOWN} when {@code subsystems} is empty
 * @param checkedAt   when this snapshot was assembled
 * @param subsystems  one entry per registered {@code SubsystemStatusPort}
 */
public record SystemStatusResponse(Health overall, Instant checkedAt, List<SubsystemStatusResponse> subsystems) {

    public SystemStatusResponse {
        subsystems = List.copyOf(subsystems);
    }
}
