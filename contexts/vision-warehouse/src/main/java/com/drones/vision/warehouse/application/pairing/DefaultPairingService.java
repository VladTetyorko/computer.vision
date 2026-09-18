package com.drones.vision.warehouse.application.pairing;

import com.drones.vision.kernel.DeviceId;
import com.drones.vision.kernel.StreamDescriptor;
import com.drones.vision.kernel.UserId;
import com.drones.vision.platform.AuditAction;
import com.drones.vision.platform.AuditEntry;
import com.drones.vision.platform.AuditTargetType;
import com.drones.vision.platform.AuditTrailPort;
import com.drones.vision.warehouse.application.device.DeviceEdit;
import com.drones.vision.warehouse.application.device.DeviceService;
import com.drones.vision.warehouse.domain.model.Device;
import com.drones.vision.warehouse.domain.model.Pairing;
import com.drones.vision.warehouse.domain.model.PairingId;
import com.drones.vision.warehouse.domain.model.RadioBind;
import com.drones.vision.warehouse.domain.model.VehicleKey;
import com.drones.vision.warehouse.domain.port.PairingRepositoryPort;

import java.math.BigInteger;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The one implementation of {@link PairingService}.
 */
public final class DefaultPairingService implements PairingService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int VEHICLE_KEY_LENGTH = 32;

    /** Mirrors {@code MavlinkTelemetrySource.OPTION_SYSID} — vision-warehouse cannot depend on the
     * adapter module that owns the runtime constant, so the key is duplicated here by contract. */
    private static final String OPTION_SYSID = "sysid";

    private final PairingRepositoryPort pairingRepository;
    private final DeviceService deviceService;
    private final AuditTrailPort auditTrail;
    private final PairingSettings settings;

    public DefaultPairingService(PairingRepositoryPort pairingRepository, DeviceService deviceService,
                                  AuditTrailPort auditTrail, PairingSettings settings) {
        this.pairingRepository = Objects.requireNonNull(pairingRepository, "pairingRepository must not be null");
        this.deviceService = Objects.requireNonNull(deviceService, "deviceService must not be null");
        this.auditTrail = Objects.requireNonNull(auditTrail, "auditTrail must not be null");
        this.settings = Objects.requireNonNull(settings, "settings must not be null");
    }

    @Override
    public Pairing pair(DeviceId deviceId, int heardSysid, BigInteger hardwareUid, UserId actor) {
        Objects.requireNonNull(deviceId, "deviceId must not be null");
        Objects.requireNonNull(actor, "actor must not be null");

        // Idempotent: adopt-is-one-motion (§7 ruling 3) calls this every time a mavlink device is
        // registered/attached, but a device is only ever paired once through this method.
        var existing = pairingRepository.findByDeviceId(deviceId);
        if (existing.isPresent()) {
            return existing.get();
        }

        // Bounded by the configured range, not just "any valid MAVLink sysid": this is what makes
        // "1 is factory default, 251-255 are GCS" (§7) an enforced rule rather than a comment --
        // a vehicle heard at 1 (virtually every freshly-flashed/reset autopilot) or in the GCS band
        // never gets "kept" at that number, it always gets pushed a real assignment instead.
        boolean heardSysidFree = heardSysid >= settings.sysidRangeMin() && heardSysid <= settings.sysidRangeMax()
                && pairingRepository.findBySysid(heardSysid).isEmpty();
        int sysid = heardSysidFree ? heardSysid : lowestFreeSysid();

        Pairing pairing = new Pairing(PairingId.random(), deviceId, sysid, mintVehicleKey(), hardwareUid,
                RadioBind.NONE, Instant.now(), null);
        Pairing saved = pairingRepository.save(pairing);
        audit(actor, AuditAction.CREATED, saved, "Paired device " + deviceId.value() + " as sysid " + sysid);

        // The pairing is the truth about identity; the device mirrors it. When the assigned sysid
        // differs from the one heard, the runtime (MavlinkTelemetrySource) still opens using the
        // stale options["sysid"] until this is updated -- a pairing the device does not answer to
        // is a dead pairing (docs/plans/active/LINK-PAIRING-PLAN.md §8 defect #4).
        if (sysid != heardSysid) {
            mirrorSysidOntoDevice(deviceId, sysid, actor);
        }
        return saved;
    }

    /**
     * No-op when the device cannot be found: in practice {@code deviceId} always names a
     * just-created device by the time {@link #pair} runs ("adopt is one motion", §7 ruling 3), so
     * this only guards against a caller pairing an id that was never registered.
     */
    private void mirrorSysidOntoDevice(DeviceId deviceId, int sysid, UserId actor) {
        deviceService.find(deviceId).ifPresent(device -> {
            StreamDescriptor current = device.stream();
            Map<String, String> options = new LinkedHashMap<>(current.options());
            options.put(OPTION_SYSID, String.valueOf(sysid));
            StreamDescriptor updated = new StreamDescriptor(current.protocol(), current.uri(), options);
            deviceService.update(deviceId, new DeviceEdit(null, null, updated, null), actor);
        });
    }

    @Override
    public Optional<Pairing> find(DeviceId deviceId) {
        Objects.requireNonNull(deviceId, "deviceId must not be null");
        return pairingRepository.findByDeviceId(deviceId);
    }

    @Override
    public Pairing replaceHardware(PairingId id, UserId actor) {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        Pairing existing = require(id);
        Pairing updated = new Pairing(existing.id(), existing.deviceId(), existing.sysid(), existing.vehicleKey(),
                null, existing.radioBind(), existing.createdAt(), Instant.now());
        Pairing saved = pairingRepository.save(updated);
        audit(actor, AuditAction.UPDATED, saved, "Replaced hardware for pairing " + id.value());
        return saved;
    }

    @Override
    public void forget(PairingId id, UserId actor) {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        Pairing existing = require(id);
        pairingRepository.deleteById(id);
        audit(actor, AuditAction.DELETED, existing, "Forgot pairing " + id.value()
                + " (sysid " + existing.sysid() + " released)");
    }

    @Override
    public List<DeviceId> listUnpaired() {
        Set<DeviceId> paired = pairingRepository.findAll().stream()
                .map(Pairing::deviceId)
                .collect(Collectors.toUnmodifiableSet());
        return deviceService.devices(false).stream()
                .map(Device::id)
                .filter(deviceId -> !paired.contains(deviceId))
                .toList();
    }

    private Pairing require(PairingId id) {
        return pairingRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Unknown pairing: " + id.value()));
    }

    /**
     * Lowest free sysid in {@link PairingSettings}' range — a small O(range) scan is fine, the
     * range tops out at a few hundred values and pairing is an operator-paced action, not a hot
     * path.
     *
     * @throws IllegalStateException if the whole range is already assigned
     */
    private int lowestFreeSysid() {
        for (int candidate = settings.sysidRangeMin(); candidate <= settings.sysidRangeMax(); candidate++) {
            if (pairingRepository.findBySysid(candidate).isEmpty()) {
                return candidate;
            }
        }
        throw new IllegalStateException(
                "No free sysid in range " + settings.sysidRangeMin() + "-" + settings.sysidRangeMax());
    }

    private static VehicleKey mintVehicleKey() {
        byte[] bytes = new byte[VEHICLE_KEY_LENGTH];
        RANDOM.nextBytes(bytes);
        return new VehicleKey(bytes);
    }

    private void audit(UserId actor, AuditAction action, Pairing pairing, String summary) {
        auditTrail.record(AuditEntry.of(actor, action, AuditTargetType.PAIRING, pairing.id().value().toString(),
                summary));
    }
}
