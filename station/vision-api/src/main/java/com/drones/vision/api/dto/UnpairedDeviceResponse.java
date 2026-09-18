package com.drones.vision.api.dto;

import com.drones.vision.warehouse.domain.model.Device;

import java.util.List;

/**
 * Response body element for {@code GET /api/pairings/unpaired-devices} (docs/plans/active/
 * LINK-PAIRING-PLAN.md §3.3) — enough of the device to let an operator pick which one to pair,
 * without pulling in the full {@link DeviceResponse} stream/options detail this list has no use
 * for.
 *
 * @param deviceId     device identity, as a canonical UUID string
 * @param name         human-readable device name
 * @param capabilities capability names this device exposes
 */
public record UnpairedDeviceResponse(String deviceId, String name, List<String> capabilities) {

    /**
     * Maps a domain {@link Device} to its wire representation.
     *
     * @param device the unpaired device to map
     * @return the response body element for {@code device}
     */
    public static UnpairedDeviceResponse from(Device device) {
        return new UnpairedDeviceResponse(device.id().value().toString(), device.name(),
                device.capabilities().stream().map(Enum::name).toList());
    }
}
