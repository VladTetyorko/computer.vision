package com.drones.vision.warehouse.domain.port;

import com.drones.vision.kernel.DeviceId;
import com.drones.vision.warehouse.domain.model.Pairing;
import com.drones.vision.warehouse.domain.model.PairingId;

import java.util.List;
import java.util.Optional;

/**
 * Driven port for {@link Pairing} persistence (docs/plans/active/LINK-PAIRING-PLAN.md §3.3).
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>{@link #findByDeviceId} is the primary lookup — a pairing is a fact about the device, and
 *       callers almost always start from a {@link DeviceId}, not a {@link PairingId}.</li>
 *   <li>{@link #findBySysid} backs sysid-collision checks: a MAVLink sysid is a scarce, globally
 *       shared resource across every paired vehicle, never scoped per device.</li>
 *   <li>{@link #deleteById} hard-deletes the row (⚠ accepted deviation from soft-delete,
 *       docs/plans/active/LINK-PAIRING-PLAN.md §3.3 — a forgotten pairing's sysid and key must stop
 *       being valid immediately, not linger as a soft-deleted graveyard row). Idempotent: deleting
 *       an id that does not exist is not an error.</li>
 * </ul>
 *
 * <h2>Threading</h2>
 * Implementations must be safe for concurrent use.
 */
public interface PairingRepositoryPort {

    /**
     * Finds one pairing by its own id.
     *
     * @param id the pairing id
     * @return the pairing, or {@link Optional#empty()} if none exists
     */
    Optional<Pairing> findById(PairingId id);

    /**
     * Finds the pairing for a device, if it has been paired.
     *
     * @param deviceId the device id
     * @return the pairing, or {@link Optional#empty()} if the device is unpaired or unknown
     */
    Optional<Pairing> findByDeviceId(DeviceId deviceId);

    /**
     * Finds whichever pairing currently holds a sysid.
     *
     * @param sysid the MAVLink system id
     * @return the pairing holding it, or {@link Optional#empty()} if the sysid is free
     */
    Optional<Pairing> findBySysid(int sysid);

    /**
     * Lists every pairing.
     *
     * @return an immutable snapshot
     */
    List<Pairing> findAll();

    /**
     * Creates or replaces a pairing.
     *
     * @param pairing the pairing to persist
     * @return the persisted pairing
     */
    Pairing save(Pairing pairing);

    /**
     * Hard-deletes a pairing row. Idempotent.
     *
     * @param id the pairing to delete
     */
    void deleteById(PairingId id);
}
