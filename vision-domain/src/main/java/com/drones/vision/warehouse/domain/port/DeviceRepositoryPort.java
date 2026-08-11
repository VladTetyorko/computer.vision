package com.drones.vision.warehouse.domain.port;

import com.drones.vision.warehouse.domain.model.Device;
import com.drones.vision.kernel.DeviceId;

import java.util.List;
import java.util.Optional;

/**
 * Driven port: persist and retrieve registered devices.
 *
 * <p>Shared device state is reachable only through this port — no pipeline
 * or use-case code holds device state directly — so the backing store can
 * be externalized (e.g. from an in-memory map to Postgres) without any
 * change to core code, and so device state is naturally shareable across
 * JVM instances once a networked implementation is used.
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>{@link #save(Device)} inserts or updates (upsert by {@link
 *       DeviceId}) and returns the persisted device.</li>
 *   <li>{@link #findById(DeviceId)} returns {@link Optional#empty()}, never
 *       {@code null}, when no device with that id exists.</li>
 *   <li>{@link #findAll()} returns a snapshot; the returned list is not a
 *       live view of the store.</li>
 *   <li>{@link #deleteById(DeviceId)} is idempotent: deleting a
 *       non-existent id is a no-op, not an error.</li>
 * </ul>
 *
 * <h2>Threading</h2>
 * Implementations must be safe for concurrent use — multiple stream
 * pipelines and control-plane operations may read/write devices
 * concurrently, and no caller assumes exclusive access.
 */
public interface DeviceRepositoryPort {

    /**
     * Inserts or updates a device.
     *
     * @param device the device to persist
     * @return the persisted device
     */
    Device save(Device device);

    /**
     * Finds a device by id.
     *
     * @param id the device id
     * @return the device, or {@link Optional#empty()} if none exists
     */
    Optional<Device> findById(DeviceId id);

    /**
     * Lists all devices.
     *
     * @return an immutable snapshot of all devices
     */
    List<Device> findAll();

    /**
     * Deletes a device by id. Idempotent.
     *
     * @param id the device id to delete
     */
    void deleteById(DeviceId id);
}
