package com.drones.vision.api.support;

import com.drones.vision.domain.model.Capability;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Shared conversion for the optional {@code capabilities} field on {@code RegisterDeviceRequest}
 * and {@code CreateAssetRequest.DeviceSpec} (both {@code ...api.dto}).
 *
 * <p>Public only because it now lives in {@code ...api.support}, a different package from its
 * {@code ...api.dto} callers (docs/LAYERING-REFACTOR-PLAN.md §3/§7 row B) — this is still
 * de-duplicated request-parsing logic, not part of the wire contract itself, and still has no
 * second implementation ({@code .claude/skills/java-clean-code/SKILL.md} §1).
 */
public final class CapabilityParsing {

    private CapabilityParsing() {
    }

    /**
     * Parses capability names into a {@link Capability} set.
     *
     * @param names capability names matched against {@link Capability#name()}
     *              case-insensitively; {@code null} or empty defaults to
     *              {@code Set.of(Capability.VIDEO)} (backward-compatible with
     *              the Phase-1 VIDEO-only default)
     * @return the resolved, non-empty capability set
     * @throws IllegalArgumentException if any name doesn't match a known
     *                                   {@link Capability}, listing the valid values
     */
    public static Set<Capability> parse(List<String> names) {
        if (names == null || names.isEmpty()) {
            return Set.of(Capability.VIDEO);
        }
        Set<Capability> capabilities = new LinkedHashSet<>();
        for (String name : names) {
            capabilities.add(toCapability(name));
        }
        return Set.copyOf(capabilities);
    }

    private static Capability toCapability(String name) {
        if (name != null) {
            for (Capability capability : Capability.values()) {
                if (capability.name().equalsIgnoreCase(name)) {
                    return capability;
                }
            }
        }
        throw new IllegalArgumentException("Unknown capability: " + name + " (valid values: "
                + Arrays.stream(Capability.values()).map(Enum::name).collect(Collectors.joining(", ")) + ")");
    }
}
