package com.drones.vision.api.dto;

import com.drones.vision.domain.model.LifecycleState;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Request body for {@code POST /api/devices/{id}/state} and {@code POST /api/assets/{id}/state}
 * (docs/main/CYCLES-PLAN.md §8's pinned contract) — shared by both controllers, since the rule is
 * identical either way.
 *
 * <p>Only {@link LifecycleState#ACTIVE} and {@link LifecycleState#DEACTIVATED} may be requested
 * directly: {@link LifecycleState#DELETED} is reached only through the dedicated delete (archive)
 * endpoints. {@code DEACTIVATED} on an already-{@code DELETED} thing is how this contract spells
 * "restore" — the services already allow that transition; only a direct {@code DELETED} →
 * {@code ACTIVE} request is refused (409, via {@link IllegalStateException}).
 *
 * @param state the requested lifecycle state, matched case-insensitively against {@code ACTIVE}/
 *              {@code DEACTIVATED}
 */
public record SetLifecycleStateRequest(String state) {

    private static final LifecycleState[] SETTABLE = {LifecycleState.ACTIVE, LifecycleState.DEACTIVATED};

    /**
     * Resolves {@link #state()} to one of the two directly-settable {@link LifecycleState}
     * values.
     *
     * @return {@link LifecycleState#ACTIVE} or {@link LifecycleState#DEACTIVATED}
     * @throws IllegalArgumentException if {@link #state()} is missing or not one of those two
     *                                   values, listing the valid ones
     */
    public LifecycleState toLifecycleState() {
        if (state != null) {
            for (LifecycleState candidate : SETTABLE) {
                if (candidate.name().equalsIgnoreCase(state)) {
                    return candidate;
                }
            }
        }
        throw new IllegalArgumentException("Unknown state: " + state + " (valid values: "
                + Arrays.stream(SETTABLE).map(Enum::name).collect(Collectors.joining(", ")) + ")");
    }
}
