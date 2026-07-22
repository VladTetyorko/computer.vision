package com.drones.vision.api.dto;

import com.drones.vision.domain.model.Capability;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Shared conversion for the optional {@code capabilities} field on {@link
 * RegisterDeviceRequest} and {@link CreateAssetRequest.DeviceSpec}.
 *
 * <p>Package-private: this is de-duplicated request-parsing logic, not part
 * of the wire contract itself.
 */
final class CapabilityParsing {

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
    static Set<Capability> parse(List<String> names) {
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
