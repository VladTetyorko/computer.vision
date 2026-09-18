package com.drones.vision.adapter.mavlink;

import com.drones.mavlink.transport.LinkDescriptor;
import com.drones.mavlink.transport.LinkId;

import com.drones.vision.adapter.mavlink.election.LinkGroupSnapshot;
import com.drones.vision.adapter.mavlink.election.LinkSnapshot;
import com.drones.vision.flight.domain.model.CarrierKind;
import com.drones.vision.flight.domain.model.CarrierView;
import com.drones.vision.flight.domain.model.LinkGroupView;
import com.drones.vision.flight.domain.model.LinkQuality;
import com.drones.vision.flight.domain.model.LinkView;
import com.drones.vision.flight.domain.model.SerialRole;
import com.drones.vision.flight.domain.port.CarrierDirectoryPort;
import com.drones.vision.flight.domain.port.VehicleLinkPort;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.DeviceId;
import com.drones.vision.warehouse.application.asset.AssetService;
import com.drones.vision.warehouse.domain.model.Device;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * {@link VehicleLinkPort} implementation (LINK-PAIRING-PLAN.md §3.4 frozen contract): resolves
 * {@code AssetId} &rarr; {@code DeviceId} via {@link AssetService#details(AssetId)}, filtered to
 * this adapter's own MAVLink-protocol {@code TELEMETRY} device(s) — reusing the existing {@code
 * vision-flight -> vision-warehouse} edge this adapter module already depends on, not a new
 * cross-context port (LINK-PAIRING-PLAN.md §3.4's own QA note). The unscoped {@link
 * AssetService#details(AssetId)} overload is safe here because the only caller of this port,
 * {@code LinkStateService}, is itself only ever reached through a controller that has already
 * checked the acting user's visibility scope.
 *
 * <p>Translates this module's adapter-internal {@link LinkGroupSnapshot}/{@link LinkSnapshot} into
 * {@code vision-flight}'s own local mirror types ({@link LinkGroupView}/{@link LinkView}) — see
 * {@code vision-flight}'s {@code LinkId}'s own javadoc for why a direct import from that module's
 * domain layer is architecturally impossible (ArchUnit's domain/application layer rules forbid a
 * {@code ..domain..}/{@code ..application..} class from depending on {@code com.drones.mavlink..}).
 *
 * <h2>Multi-device assets</h2>
 * {@link #linksFor} iterates over every one of the asset's MAVLink-protocol devices, tagging each
 * resulting {@link LinkView} with its own {@link LinkView#deviceId()} — additive beyond the frozen
 * contract, per this wave's own task brief: "the web assumed one paired telemetry device per asset
 * and needs it for multi-device assets." The group-level {@code activeLinkId}/{@code pinned}/{@code
 * lastFailoverAt} come from the first device that has ever been heard from, a documented
 * simplification for the multi-device case — collapsing several devices' independent election
 * states into one asset-level "active" triple would need a real priority rule across devices, not
 * just across links, which is out of this wave's scope.
 *
 * <h2>{@link CarrierDirectoryPort} — station-wide, not per-asset</h2>
 * {@link #carriers()} answers a different question than {@link #linksFor}: not "what does this
 * asset currently see", but "what carriers exist on this station at all" (LINK-PAIRING-PLAN.md
 * §3.4/§7 ruling 5) — every carrier registered on every open {@link MavlinkGateway}, merged via
 * {@link MavlinkTelemetrySource#registeredCarriers()}, independent of any sysid's election state.
 * Implemented on this same class (rather than a second small adapter class) because both ports
 * share the exact same {@code toFlightCarrier}/{@code toFlightSerialRole} translation helpers and
 * the same one collaborator ({@link #telemetrySource}) — mirroring {@code LiveUpdateRegistry}'s own
 * "one adapter class, several related ports" precedent.
 */
public final class MavlinkVehicleLinkPort implements VehicleLinkPort, CarrierDirectoryPort {

    private final MavlinkTelemetrySource telemetrySource;
    private final AssetService assetService;

    public MavlinkVehicleLinkPort(MavlinkTelemetrySource telemetrySource, AssetService assetService) {
        this.telemetrySource = Objects.requireNonNull(telemetrySource, "telemetrySource must not be null");
        this.assetService = Objects.requireNonNull(assetService, "assetService must not be null");
    }

    @Override
    public LinkGroupView linksFor(AssetId assetId) {
        Objects.requireNonNull(assetId, "assetId must not be null");
        List<LinkView> links = new ArrayList<>();
        com.drones.vision.flight.domain.model.LinkId activeLinkId = null;
        boolean pinned = false;
        Instant lastFailoverAt = null;
        boolean groupFound = false;
        for (DeviceId deviceId : mavlinkDeviceIds(assetId)) {
            LinkGroupSnapshot snapshot = telemetrySource.linkGroupSnapshot(deviceId).orElse(null);
            if (snapshot == null) {
                continue;
            }
            for (LinkSnapshot link : snapshot.links()) {
                links.add(toLinkView(link, deviceId));
            }
            if (!groupFound) {
                activeLinkId = toFlightLinkId(snapshot.activeLinkId());
                pinned = snapshot.pinned();
                lastFailoverAt = snapshot.lastFailoverAt();
                groupFound = true;
            }
        }
        return new LinkGroupView(assetId, links, activeLinkId, pinned, lastFailoverAt);
    }

    @Override
    public void pin(AssetId assetId, com.drones.vision.flight.domain.model.LinkId linkId) {
        Objects.requireNonNull(assetId, "assetId must not be null");
        Objects.requireNonNull(linkId, "linkId must not be null");
        DeviceId deviceId = primaryMavlinkDeviceId(assetId);
        telemetrySource.pinLink(deviceId, new LinkId(linkId.value()));
    }

    @Override
    public void release(AssetId assetId) {
        Objects.requireNonNull(assetId, "assetId must not be null");
        for (DeviceId deviceId : mavlinkDeviceIds(assetId)) {
            telemetrySource.releasePin(deviceId);
        }
    }

    @Override
    public List<CarrierView> carriers() {
        return telemetrySource.registeredCarriers().stream().map(MavlinkVehicleLinkPort::toCarrierView).toList();
    }

    private static CarrierView toCarrierView(MavlinkGateway.RegisteredCarrier carrier) {
        LinkDescriptor descriptor = carrier.descriptor();
        return new CarrierView(toFlightLinkId(carrier.id()), toFlightCarrier(descriptor.carrier()),
                toFlightSerialRole(descriptor.serialRole()), descriptor.label(), descriptor.priority());
    }

    private DeviceId primaryMavlinkDeviceId(AssetId assetId) {
        return mavlinkDeviceIds(assetId).stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "asset " + assetId + " has no MAVLink telemetry device"));
    }

    private List<DeviceId> mavlinkDeviceIds(AssetId assetId) {
        return assetService.details(assetId).devices().stream()
                .filter(telemetrySource::supports)
                .map(Device::id)
                .toList();
    }

    private LinkView toLinkView(LinkSnapshot link, DeviceId deviceId) {
        return new LinkView(toFlightLinkId(link.id()), toFlightCarrier(link.carrier()),
                toFlightSerialRole(link.serialRole()), link.label(), link.active(), link.receiving(),
                link.heartbeatAge(), toFlightQuality(link.quality()), deviceId);
    }

    private static com.drones.vision.flight.domain.model.LinkId toFlightLinkId(LinkId id) {
        return id == null ? null : new com.drones.vision.flight.domain.model.LinkId(id.value());
    }

    private static CarrierKind toFlightCarrier(com.drones.mavlink.transport.CarrierKind carrier) {
        return CarrierKind.valueOf(carrier.name());
    }

    private static SerialRole toFlightSerialRole(com.drones.mavlink.transport.SerialRole role) {
        return SerialRole.valueOf(role.name());
    }

    private static LinkQuality toFlightQuality(com.drones.mavlink.session.LinkQuality.Quality quality) {
        if (quality == null) {
            return null;
        }
        return new LinkQuality(quality.lastRadioStatusAt(), quality.rssi(), quality.remoteRssi(), quality.noise(),
                quality.rxErrors(), quality.fixed());
    }
}
