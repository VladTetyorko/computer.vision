package com.drones.vision.warehouse.domain.model;

import com.drones.vision.kernel.DeviceId;

import java.math.BigInteger;
import java.time.Instant;
import java.util.Objects;

/**
 * A vehicle's persisted identity, distinct from the {@link Device} row it answers through
 * (docs/plans/active/LINK-PAIRING-PLAN.md §3.3).
 *
 * <p>🔒 Keys on {@link DeviceId}, not {@code AssetId}: a pairing is a fact about the physical board
 * that answers on the wire, which is replaced whole on {@code replaceHardware} — not the
 * categorized/owned inventory row, which survives a hardware swap unchanged. {@code
 * PairingRepositoryPort#findByDeviceId} is the primary lookup.
 *
 * @param id          typed pairing identity
 * @param deviceId    the device this pairing answers for
 * @param sysid       the MAVLink system id assigned to this vehicle (1-250; see
 *                    {@code PairingSettings})
 * @param vehicleKey  the 32-byte key minted once, at first pairing
 * @param hardwareUid the flight-controller hardware uid read from a capability probe ({@code
 *                    AUTOPILOT_VERSION.uid}, §3.6), or {@code null} if not yet read — plain
 *                    nullable, same convention as {@code DiscoveryCandidate#registeredAsset}; not
 *                    every optional value needs a {@code NONE} sentinel, only compound value
 *                    objects do (see {@link RadioBind})
 * @param radioBind   radio-layer binding facts, or {@link RadioBind#NONE}
 * @param createdAt   when this pairing was first created
 * @param replacedAt  when {@code replaceHardware} last ran, or {@code null} if never
 */
public record Pairing(PairingId id, DeviceId deviceId, int sysid, VehicleKey vehicleKey, BigInteger hardwareUid,
                       RadioBind radioBind, Instant createdAt, Instant replacedAt) {

    public Pairing {
        Objects.requireNonNull(id, "Pairing id must not be null");
        Objects.requireNonNull(deviceId, "Pairing deviceId must not be null");
        if (sysid < 1 || sysid > 255) {
            throw new IllegalArgumentException("Pairing sysid must be 1-255: " + sysid);
        }
        Objects.requireNonNull(vehicleKey, "Pairing vehicleKey must not be null");
        Objects.requireNonNull(radioBind, "Pairing radioBind must not be null");
        Objects.requireNonNull(createdAt, "Pairing createdAt must not be null");
        if (replacedAt != null && replacedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("Pairing replacedAt must not precede createdAt");
        }
    }
}
