package com.drones.vision.warehouse.application.pairing;

import com.drones.vision.kernel.DeviceId;
import com.drones.vision.kernel.UserId;
import com.drones.vision.warehouse.domain.model.Pairing;
import com.drones.vision.warehouse.domain.model.PairingId;

import java.math.BigInteger;
import java.util.List;
import java.util.Optional;

/**
 * Everything the application does with a vehicle's persisted identity — the sysid, key and
 * hardware uid a device answers with, kept separate from the {@code Asset}/{@code Device} rows
 * that name and categorize it (docs/plans/active/LINK-PAIRING-PLAN.md §3.3).
 *
 * <p>One interface, one implementation ({@link DefaultPairingService}).
 *
 * <p>The acting user is a method parameter, never a constructor dependency, same reasoning as
 * every other service in this module.
 *
 * <h2>Threading</h2>
 * Implementations must be safe for concurrent use; all shared state lives behind driven ports.
 */
public interface PairingService {

    /**
     * Adopts a device as a paired vehicle identity — "adopt is one motion"
     * (docs/plans/active/LINK-PAIRING-PLAN.md §7 ruling 3): called in the same transaction as the
     * discovery register/attach that created {@code deviceId}, for a {@code mavlink} TELEMETRY
     * device.
     *
     * <p>Idempotent: a device that is already paired is returned unchanged — {@code heardSysid}
     * and {@code hardwareUid} are ignored on a repeat call, since re-pairing an already-paired
     * device is not a thing this method does (see {@link #replaceHardware}, {@link #forget}).
     *
     * <p>Otherwise, keeps {@code heardSysid} when it is a valid vehicle sysid (1-250) not already
     * held by another pairing — an existing fleet keeps its numbers, no parameter push needed.
     * Only on collision (or an out-of-range/reserved heard value) does it assign the lowest free
     * number in {@code PairingSettings}' range. A caller can tell which branch ran by comparing the
     * returned {@link Pairing#sysid()} to the {@code heardSysid} it passed in — when they differ,
     * {@code MAV_SYSID} must be pushed to the vehicle (ArduPilot applies it after reboot).
     *
     * <p><b>Side effect:</b> when the assigned sysid differs from {@code heardSysid}, this also
     * updates the device's persisted stream {@code options["sysid"]} to match — the runtime claims
     * that option to select which vehicle it opens, so a pairing the device does not answer to
     * would otherwise be a dead pairing.
     *
     * @param deviceId    the device to pair
     * @param heardSysid  the sysid the vehicle was heard announcing itself with
     * @param hardwareUid the hardware uid read from a capability probe, or {@code null} if not yet
     *                    known — pairing must not block on a probe round-trip
     * @param actor       the user performing the pairing
     * @return the pairing, new or pre-existing
     */
    Pairing pair(DeviceId deviceId, int heardSysid, BigInteger hardwareUid, UserId actor);

    /**
     * Finds a device's pairing, if it has one — the lookup {@code vision-api}'s {@code
     * PairingController} needs to resolve a {@link PairingId} from the {@link DeviceId} every
     * device-keyed pairing endpoint is addressed by.
     *
     * @param deviceId the device to look up
     * @return the pairing, or {@link Optional#empty()} if the device is unpaired or unknown
     */
    Optional<Pairing> find(DeviceId deviceId);

    /**
     * Records a hardware swap: clears {@link Pairing#hardwareUid()} (the new board has not been
     * probed yet) and bumps {@link Pairing#replacedAt()}, keeping the same sysid and key — the
     * vehicle's identity survives the board underneath it being replaced.
     *
     * @param id    the pairing whose hardware was replaced
     * @param actor the user performing the replacement
     * @return the updated pairing
     * @throws java.util.NoSuchElementException if no pairing has that id
     */
    Pairing replaceHardware(PairingId id, UserId actor);

    /**
     * Forgets a pairing: hard-deletes the row (⚠ accepted deviation from soft-delete, §3.3 — its
     * sysid must become assignable again immediately and its key must stop being valid at once).
     * The {@code Device}/{@code Asset} rows are untouched and keep their own independent
     * soft-delete lifecycle. Re-pairing the same device later creates a brand-new {@link Pairing}
     * (new id, new sysid, new key).
     *
     * @param id    the pairing to forget
     * @param actor the user performing the removal
     */
    void forget(PairingId id, UserId actor);

    /**
     * Lists devices that have never been paired.
     *
     * @return an immutable snapshot of unpaired device ids
     */
    List<DeviceId> listUnpaired();
}
