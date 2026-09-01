package com.drones.vision.api.support;

import com.drones.vision.kernel.DeviceOrigin;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Shared conversion for the optional {@code origin} field on {@code RegisterDeviceRequest}, {@code
 * UpdateDeviceRequest}, and {@code CreateAssetRequest.DeviceSpec} (all {@code ...api.dto}).
 *
 * <p>Two entry points because "absent" means two different things depending on the request shape:
 * a new registration with no {@code origin} named is a real device ({@link #parse} defaults to
 * {@link DeviceOrigin#LIVE}), but a partial edit with no {@code origin} named means "leave it as it
 * is" ({@link #parseOptional} stays {@code null}, matching every other {@code UpdateDeviceRequest}
 * field).
 */
public final class DeviceOriginParsing {

    private DeviceOriginParsing() {
    }

    /**
     * Parses an origin name, defaulting to {@link DeviceOrigin#LIVE} when absent — registering a
     * device without naming one is overwhelmingly the common case (a real camera or autopilot).
     *
     * @param origin the origin name, matched against {@link DeviceOrigin#name()}
     *               case-insensitively; {@code null} defaults to {@link DeviceOrigin#LIVE}
     * @return the resolved origin, never {@code null}
     * @throws IllegalArgumentException if {@code origin} is present but names no known {@link DeviceOrigin}
     */
    public static DeviceOrigin parse(String origin) {
        return origin == null ? DeviceOrigin.LIVE : toOrigin(origin);
    }

    /**
     * Parses an optional origin name for a partial edit, where absent means "leave unchanged"
     * rather than "default to LIVE".
     *
     * @param origin the origin name, matched against {@link DeviceOrigin#name()}
     *               case-insensitively; {@code null} stays {@code null}
     * @return the resolved origin, or {@code null} if none was sent
     * @throws IllegalArgumentException if {@code origin} is present but names no known {@link DeviceOrigin}
     */
    public static DeviceOrigin parseOptional(String origin) {
        return origin == null ? null : toOrigin(origin);
    }

    private static DeviceOrigin toOrigin(String name) {
        for (DeviceOrigin value : DeviceOrigin.values()) {
            if (value.name().equalsIgnoreCase(name)) {
                return value;
            }
        }
        throw new IllegalArgumentException("Unknown origin: " + name + " (valid values: "
                + Arrays.stream(DeviceOrigin.values()).map(Enum::name).collect(Collectors.joining(", ")) + ")");
    }
}
