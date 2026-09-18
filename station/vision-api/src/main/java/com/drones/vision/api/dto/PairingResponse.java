package com.drones.vision.api.dto;

import com.drones.vision.warehouse.domain.model.Pairing;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * Response body for every {@code PairingController} endpoint that returns a pairing
 * (docs/plans/active/LINK-PAIRING-PLAN.md §3.3). Never carries {@link Pairing#vehicleKey()} —
 * key material is never put on the wire, the same reasoning {@code VehicleKey#toString()}'s own
 * redaction already applies.
 *
 * @param pairingId         pairing identity, as a canonical UUID string
 * @param deviceId          the device this pairing answers for, as a canonical UUID string
 * @param sysid             the assigned MAVLink system id
 * @param hardwareUid       the flight-controller hardware uid, as a decimal string, or absent if
 *                          not yet read from a capability probe
 * @param createdAt         when this pairing was first created
 * @param replacedAt        when {@code replaceHardware} last ran, or absent if never
 * @param sysidPushRequired {@code true} when {@code sysid} differs from the sysid the caller
 *                          supplied/heard, so {@code MAV_SYSID} must be pushed to the vehicle;
 *                          absent when not applicable (e.g. {@code replace-hardware}, which never
 *                          changes the sysid)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PairingResponse(String pairingId, String deviceId, int sysid, String hardwareUid, Instant createdAt,
                               Instant replacedAt, Boolean sysidPushRequired) {

    /**
     * Maps a domain {@link Pairing} to its wire representation.
     *
     * @param pairing           the pairing to map
     * @param sysidPushRequired see {@link #sysidPushRequired()}; {@code null} when not applicable
     * @return the response body for {@code pairing}
     */
    public static PairingResponse from(Pairing pairing, Boolean sysidPushRequired) {
        return new PairingResponse(
                pairing.id().value().toString(),
                pairing.deviceId().value().toString(),
                pairing.sysid(),
                pairing.hardwareUid() != null ? pairing.hardwareUid().toString() : null,
                pairing.createdAt(),
                pairing.replacedAt(),
                sysidPushRequired);
    }
}
