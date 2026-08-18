package com.drones.vision.api.dto;

import com.drones.vision.flight.domain.model.MessageObservation;
import com.drones.vision.flight.domain.model.ParameterReading;
import com.drones.vision.flight.domain.model.VehicleProfile;

import java.time.Instant;
import java.util.List;

/**
 * Wire shape for {@code VehicleProfileResponse} (docs/plans/active/DRONE-ONBOARDING-PLAN.md §8.1,
 * frozen) — {@code GET/POST /api/assets/{assetId}/profile|probe} and {@code POST
 * /api/onboarding/probe} all answer this exact shape. Field order and names mirror the domain
 * {@link VehicleProfile} one-for-one; nothing is dropped or renamed.
 *
 * <p>Deliberately carries no {@code @JsonInclude(NON_NULL)}: §8.1's own example shows {@code
 * "incompleteReason": null} as a literal, always-present field (C7 — a probe that answered nothing
 * for a field must say so explicitly, not omit it), unlike {@link DiscoveredDeviceResponse}'s
 * omit-when-absent convention for a field the discovery mechanism never attempted to infer.
 */
public record VehicleProfileResponse(
        String linkKey,
        Instant observedAt,
        Integer sysid,
        String firmware,
        String firmwareVersion,
        String vehicleKind,
        Long capabilityBitmask,
        List<String> capabilityFlags,
        List<MessageObservationResponse> messages,
        List<ParameterReadingResponse> parameters,
        Long linkBytesPerSecond,
        boolean complete,
        String incompleteReason) {

    /** One {@link VehicleProfile#messages()} entry, verbatim. */
    public record MessageObservationResponse(int messageId, String name, double hz, long count) {

        public static MessageObservationResponse from(MessageObservation observation) {
            return new MessageObservationResponse(observation.messageId(), observation.name(), observation.hz(),
                    observation.count());
        }
    }

    /** One {@link VehicleProfile#parameters()} entry, verbatim. */
    public record ParameterReadingResponse(String name, double value, String type) {

        public static ParameterReadingResponse from(ParameterReading reading) {
            return new ParameterReadingResponse(reading.name(), reading.value(), reading.type());
        }
    }

    /**
     * Maps a domain {@link VehicleProfile} to its wire representation.
     *
     * @param profile the snapshot to map
     * @return the response body for {@code profile}
     */
    public static VehicleProfileResponse from(VehicleProfile profile) {
        return new VehicleProfileResponse(
                profile.linkKey(),
                profile.observedAt(),
                profile.sysid(),
                profile.firmware(),
                profile.firmwareVersion(),
                profile.vehicleKind(),
                profile.capabilityBitmask(),
                profile.capabilityFlags(),
                profile.messages().stream().map(MessageObservationResponse::from).toList(),
                profile.parameters().stream().map(ParameterReadingResponse::from).toList(),
                profile.linkBytesPerSecond(),
                profile.complete(),
                profile.incompleteReason());
    }
}
