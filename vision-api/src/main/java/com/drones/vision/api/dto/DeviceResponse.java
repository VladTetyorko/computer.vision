package com.drones.vision.api.dto;

import com.drones.vision.domain.model.Device;

import java.util.List;
import java.util.Map;

/**
 * Response body describing a registered device, returned by {@code POST
 * /api/devices} and {@code GET /api/devices}.
 *
 * <p>There is no {@code type} field: the {@code DeviceType} enum was removed
 * in favor of the data-driven category model, which applies to {@code
 * Asset}s (see {@code AssetSummaryResponse}/{@code AssetDetailsResponse}),
 * not to raw devices.
 *
 * @param id           device identity, as a canonical UUID string
 * @param name         human-readable device name
 * @param capabilities capability names this device exposes
 * @param protocol     ingest protocol key
 * @param uri          the stream's resource locator, as a string
 * @param options      adapter-specific stream parameters
 * @param state        {@code ACTIVE} or {@code DEACTIVATED}; a deactivated device refuses to stream
 */
public record DeviceResponse(String id, String name, List<String> capabilities, String protocol,
                              String uri, Map<String, String> options, String state) {

    /**
     * Maps a domain {@link Device} to its wire representation.
     *
     * @param device the device to map
     * @return the response body for {@code device}
     */
    public static DeviceResponse from(Device device) {
        return new DeviceResponse(
                device.id().value().toString(),
                device.name(),
                device.capabilities().stream().map(Enum::name).toList(),
                device.stream().protocol(),
                device.stream().uri().toString(),
                device.stream().options(),
                device.state().name());
    }
}
