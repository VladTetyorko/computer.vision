package com.drones.vision.api.controller;

import com.drones.vision.api.dto.PairDeviceRequest;
import com.drones.vision.api.dto.PairingResponse;
import com.drones.vision.api.dto.UnpairedDeviceResponse;
import com.drones.vision.api.security.CurrentUser;
import com.drones.vision.api.security.StreamAccess;
import com.drones.vision.kernel.DeviceId;
import com.drones.vision.platform.AccessDeniedException;
import com.drones.vision.warehouse.application.device.DeviceService;
import com.drones.vision.warehouse.application.pairing.PairingService;
import com.drones.vision.warehouse.domain.model.Pairing;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * Driving REST adapter for a vehicle's persisted identity (docs/plans/active/
 * LINK-PAIRING-PLAN.md §3.3) — the explicit pairing endpoints for a manually registered device.
 * Discovery's own "adopt is one motion" path (§7 ruling 3) does not come through here; it calls
 * {@link PairingService#pair} directly from {@code DiscoveryInboxController}.
 *
 * <p>Constructor-injected with {@link PairingService}, {@link DeviceService} (only to enrich
 * {@link #unpairedDevices} with a name/capabilities), {@link StreamAccess} and {@link CurrentUser}.
 *
 * <h2>Authority</h2>
 * {@link #pair}/{@link #forget}/{@link #replaceHardware} are device-keyed and gated the same way
 * {@link DeviceController#update}/{@link DeviceController#setState} are: {@link
 * StreamAccess#requireVisible(DeviceId)}, not {@code mayManageOrg()} — a pairing is a fact about a
 * specific device, and a PILOT correcting their own assigned aircraft's pairing is the same "may
 * still" case {@code StreamAccess}'s own javadoc documents for edits. {@link #unpairedDevices} has
 * no single device to check visibility against — like {@link DeviceController#register}/{@link
 * DeviceController#delete}, it is gated on {@link com.drones.vision.platform.Authority#mayManageOrg()
 * authority().mayManageOrg()} instead, the same org-level fleet-administration gate a device that
 * belongs to no asset yet already uses.
 */
@RestController
public class PairingController {

    private final PairingService pairingService;
    private final DeviceService deviceService;
    private final StreamAccess streamAccess;
    private final CurrentUser currentUser;

    public PairingController(PairingService pairingService, DeviceService deviceService, StreamAccess streamAccess,
                              CurrentUser currentUser) {
        this.pairingService = Objects.requireNonNull(pairingService, "pairingService must not be null");
        this.deviceService = Objects.requireNonNull(deviceService, "deviceService must not be null");
        this.streamAccess = Objects.requireNonNull(streamAccess, "streamAccess must not be null");
        this.currentUser = Objects.requireNonNull(currentUser, "currentUser must not be null");
    }

    /**
     * Pairs a manually registered device — the explicit twin of discovery's "adopt is one motion".
     *
     * @param id      the device to pair
     * @param request the heard sysid/hardware uid, if known
     * @return the pairing, new or (idempotently) pre-existing
     * @throws NoSuchElementException   if the caller's scope may not reach this device (404)
     * @throws IllegalArgumentException if {@code hardwareUid} is present but not a valid decimal
     *                                   integer (400)
     */
    @PostMapping("/api/devices/{id}/pairing")
    public PairingResponse pair(@PathVariable String id, @RequestBody(required = false) PairDeviceRequest request) {
        DeviceId deviceId = DeviceId.of(id);
        PairDeviceRequest body = request == null ? new PairDeviceRequest(null, null) : request;
        int heardSysid = body.effectiveHeardSysid();
        var hardwareUid = body.parsedHardwareUid(); // a malformed uid is a 400, before the scope 404
        streamAccess.requireVisible(deviceId);
        Pairing pairing = pairingService.pair(deviceId, heardSysid, hardwareUid, currentUser.userId());
        return PairingResponse.from(pairing, pairing.sysid() != heardSysid);
    }

    /**
     * Forgets a device's pairing (⚠ hard delete, §3.3) — its sysid and key stop being valid
     * immediately. The {@code Device}/{@code Asset} rows are untouched.
     *
     * @param id the device whose pairing to forget
     * @throws NoSuchElementException if the caller's scope may not reach this device, or the device
     *                                 has no pairing (404)
     */
    @DeleteMapping("/api/devices/{id}/pairing")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void forget(@PathVariable String id) {
        DeviceId deviceId = DeviceId.of(id);
        streamAccess.requireVisible(deviceId);
        Pairing pairing = require(deviceId);
        pairingService.forget(pairing.id(), currentUser.userId());
    }

    /**
     * Records a hardware swap on a paired device: clears the hardware uid and bumps {@code
     * replacedAt}, keeping the same sysid and key.
     *
     * @param id the device whose hardware was replaced
     * @return the updated pairing
     * @throws NoSuchElementException if the caller's scope may not reach this device, or the device
     *                                 has no pairing (404)
     */
    @PostMapping("/api/devices/{id}/pairing/replace-hardware")
    public PairingResponse replaceHardware(@PathVariable String id) {
        DeviceId deviceId = DeviceId.of(id);
        streamAccess.requireVisible(deviceId);
        Pairing pairing = require(deviceId);
        Pairing updated = pairingService.replaceHardware(pairing.id(), currentUser.userId());
        return PairingResponse.from(updated, null);
    }

    /**
     * Lists devices that have never been paired — the picklist a manual {@link #pair} call starts
     * from.
     *
     * @return the unpaired devices, enriched with name/capabilities
     * @throws AccessDeniedException if the caller's scope may not manage the organization (403)
     */
    @GetMapping("/api/pairings/unpaired-devices")
    public List<UnpairedDeviceResponse> unpairedDevices() {
        if (!currentUser.authority().mayManageOrg()) {
            throw new AccessDeniedException("Not permitted to view unpaired devices");
        }
        return pairingService.listUnpaired().stream()
                .flatMap(deviceId -> deviceService.find(deviceId).stream())
                .map(UnpairedDeviceResponse::from)
                .toList();
    }

    private Pairing require(DeviceId deviceId) {
        return pairingService.find(deviceId)
                .orElseThrow(() -> new NoSuchElementException("Device " + deviceId.value() + " is not paired"));
    }
}
