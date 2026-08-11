package com.drones.vision.application.device;

import com.drones.vision.identity.domain.model.AuditAction;
import com.drones.vision.identity.domain.model.AuditEntry;
import com.drones.vision.identity.domain.model.AuditTargetType;
import com.drones.vision.kernel.Capability;
import com.drones.vision.warehouse.domain.model.Device;
import com.drones.vision.kernel.DeviceId;
import com.drones.vision.events.domain.model.Event;
import com.drones.vision.events.domain.model.EventType;
import com.drones.vision.kernel.LifecycleState;
import com.drones.vision.kernel.StreamDescriptor;
import com.drones.vision.kernel.UserId;
import com.drones.vision.identity.domain.port.AuditTrailPort;
import com.drones.vision.warehouse.domain.port.DeviceRepositoryPort;
import com.drones.vision.events.domain.port.EventPublisherPort;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import com.drones.vision.application.stream.ActiveStream;
import com.drones.vision.application.stream.StreamService;

/**
 * The one implementation of {@link DeviceService}.
 *
 * <p>Deletion is soft — the device is marked {@link LifecycleState#DELETED} and hidden, never
 * removed. That is what keeps this class free of any dependency on assets: because the row
 * survives and stays a member of its asset, deleting an asset's <em>last</em> source cannot break
 * the asset's "at least one device" invariant, so there is no cross-aggregate rule to enforce and
 * no refusal to explain. Usages and telemetry the device produced stay attributable for the same
 * reason.
 *
 * <p>Two records are written, for two different questions. {@link EventPublisherPort} answers
 * "what is happening" (operational, transient); {@link AuditTrailPort} answers "who changed this,
 * and when" (permanent).
 *
 * <h2>Threading</h2>
 * Holds no mutable state — all shared state is reached through the injected ports.
 */
public final class DefaultDeviceService implements DeviceService {

    private final DeviceRepositoryPort deviceRepository;
    private final StreamService streamService;
    private final AuditTrailPort auditTrail;
    private final EventPublisherPort eventPublisher;

    public DefaultDeviceService(DeviceRepositoryPort deviceRepository, StreamService streamService,
                                 AuditTrailPort auditTrail, EventPublisherPort eventPublisher) {
        this.deviceRepository = Objects.requireNonNull(deviceRepository, "deviceRepository must not be null");
        this.streamService = Objects.requireNonNull(streamService, "streamService must not be null");
        this.auditTrail = Objects.requireNonNull(auditTrail, "auditTrail must not be null");
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher must not be null");
    }

    @Override
    public Device register(DeviceRegistration registration, UserId actor) {
        Objects.requireNonNull(registration, "registration must not be null");
        Objects.requireNonNull(actor, "actor must not be null");

        Device device = new Device(DeviceId.random(), registration.name(), registration.capabilities(),
                registration.stream());
        Device saved = deviceRepository.save(device);
        eventPublisher.publish(Event.of(null, EventType.DEVICE_ONLINE, "Device registered: " + saved.name()));
        audit(actor, AuditAction.CREATED, saved,
                "Registered source " + saved.name() + " (" + saved.stream().protocol() + ")", Map.of());
        return saved;
    }

    @Override
    public List<Device> devices(boolean includeDeleted) {
        return deviceRepository.findAll().stream()
                .filter(device -> includeDeleted || !device.isDeleted())
                .toList();
    }

    @Override
    public Optional<Device> find(DeviceId id) {
        Objects.requireNonNull(id, "id must not be null");
        return deviceRepository.findById(id);
    }

    @Override
    public Device update(DeviceId id, DeviceEdit edit, UserId actor) {
        Objects.requireNonNull(edit, "edit must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        Device device = require(id);

        String name = edit.name() != null ? edit.name() : device.name();
        Set<Capability> capabilities = edit.capabilities() != null ? edit.capabilities() : device.capabilities();
        StreamDescriptor stream = edit.stream() != null ? edit.stream() : device.stream();

        Device saved = deviceRepository.save(device.withDetails(name, capabilities, stream));
        Map<String, String> changes = changes(device, saved);
        if (!changes.isEmpty()) {
            // A no-op edit writes no audit line: a trail padded with "changed nothing" entries is
            // harder to read after the fact, which defeats the point of keeping it.
            audit(actor, AuditAction.UPDATED, saved, "Edited source " + saved.name(), changes);
        }
        return saved;
    }

    @Override
    public Device setState(DeviceId id, LifecycleState state, UserId actor) {
        Objects.requireNonNull(state, "state must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        Device device = require(id);

        if (device.state() == state) {
            return device; // idempotent: no stream disturbed, nothing written
        }
        if (device.isDeleted() && state == LifecycleState.ACTIVE) {
            throw new IllegalStateException("Source " + device.name()
                    + " is deleted; restore it before putting it back into service");
        }
        if (state != LifecycleState.ACTIVE) {
            stopStreamOf(id);
        }

        Device saved = deviceRepository.save(device.withState(state));
        String what = describe(device.state(), state);
        eventPublisher.publish(Event.of(null,
                state == LifecycleState.ACTIVE ? EventType.DEVICE_ONLINE : EventType.DEVICE_OFFLINE,
                "Device " + saved.name() + " " + what));
        audit(actor, actionFor(device.state(), state), saved, "Source " + saved.name() + " " + what, Map.of());
        return saved;
    }

    @Override
    public Device delete(DeviceId id, UserId actor) {
        Device device = require(id);
        if (device.isDeleted()) {
            return device; // idempotent
        }
        return setState(id, LifecycleState.DELETED, actor);
    }

    /** Stops the device's stream if one is running; a no-op otherwise. */
    private void stopStreamOf(DeviceId id) {
        for (ActiveStream active : streamService.streams()) {
            if (active.deviceId().equals(id)) {
                streamService.stop(active.streamId());
            }
        }
    }

    private Device require(DeviceId id) {
        Objects.requireNonNull(id, "id must not be null");
        return deviceRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Unknown device: " + id.value()));
    }

    /** Maps a state transition to the audit action that describes it. */
    static AuditAction actionFor(LifecycleState from, LifecycleState to) {
        if (from == LifecycleState.DELETED) {
            return AuditAction.RESTORED;
        }
        return switch (to) {
            case ACTIVE -> AuditAction.ACTIVATED;
            case DEACTIVATED -> AuditAction.DEACTIVATED;
            case DELETED -> AuditAction.DELETED;
        };
    }

    static String describe(LifecycleState from, LifecycleState to) {
        if (from == LifecycleState.DELETED) {
            return "restored";
        }
        return switch (to) {
            case ACTIVE -> "activated";
            case DEACTIVATED -> "deactivated";
            case DELETED -> "deleted";
        };
    }

    /** The fields an edit actually changed, as {@code before → after} strings. */
    private static Map<String, String> changes(Device before, Device after) {
        Map<String, String> changes = new LinkedHashMap<>();
        if (!before.name().equals(after.name())) {
            changes.put("name", before.name() + " → " + after.name());
        }
        if (!before.capabilities().equals(after.capabilities())) {
            changes.put("capabilities", before.capabilities() + " → " + after.capabilities());
        }
        if (!before.stream().equals(after.stream())) {
            changes.put("stream", before.stream().protocol() + " " + before.stream().uri()
                    + " → " + after.stream().protocol() + " " + after.stream().uri());
        }
        return changes;
    }

    private void audit(UserId actor, AuditAction action, Device device, String summary,
                        Map<String, String> details) {
        auditTrail.record(AuditEntry.of(actor, action, AuditTargetType.DEVICE,
                device.id().value().toString(), summary, details));
    }
}
