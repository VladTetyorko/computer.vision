package com.drones.vision.warehouse.domain.port;

import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.DeviceId;
import com.drones.vision.kernel.StreamId;
import com.drones.vision.kernel.Telemetry;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/**
 * Driven port: the live, runtime facts warehouse needs but does not itself hold
 * (docs/plans/active/DOMAIN-SEPARATION-W1.md &sect;15, W1.6e).
 *
 * <p>Warehouse is inventory — assets, devices, categories — and inventory is the stable layer:
 * runtime reads inventory, inventory never reads runtime (the rule this whole sub-wave enforces).
 * But a few warehouse reads are honestly about live state ("is this asset streaming right now",
 * "what was its last battery reading"), not inventory, so they cannot simply be dropped. This port
 * is the inversion: warehouse declares the shape of what it needs, in its own kernel-typed
 * vocabulary, and perception — which actually holds the running streams, the telemetry tracker and
 * the detection-event store — implements it. Warehouse never depends on {@code StreamService},
 * {@code UsageTracker}, {@code ActiveStream} or {@code DetectionEvent} again.
 *
 * <p>{@link #stopStreamsForDevices} is a synchronous call here deliberately, not yet an event —
 * warehouse still decides, in the same request, that a device's stream must stop before the device
 * (or the asset that owns it) is retired or deleted. W2 is where this becomes an event warehouse
 * publishes and perception reacts to asynchronously instead; this port is a staging post on the way
 * there, not the destination.
 *
 * <h2>Threading</h2>
 * Implementations must be safe for concurrent use, mirroring the perception-side collaborators they
 * compose.
 */
public interface AssetLiveStatePort {

    /**
     * The device backing every stream running in this instance right now.
     *
     * @return an immutable snapshot, keyed by device id
     */
    Map<DeviceId, StreamId> activeStreamsByDevice();

    /**
     * Stops every currently running stream whose device is in {@code deviceIds} — the runtime side
     * of retiring or deleting an asset/device, called synchronously in the same request.
     *
     * @param deviceIds the devices to stop streaming, if they are
     * @return how many streams were actually stopped
     */
    int stopStreamsForDevices(Collection<DeviceId> deviceIds);

    /**
     * The most recent telemetry sample ever received for an asset, for a battery/staleness read —
     * see {@code UsageTracker#latestTelemetry}'s own javadoc for exactly what "most recent" means
     * (not scoped to a currently open usage).
     *
     * @param assetId the asset to inspect
     * @return the freshest sample, or {@link Optional#empty()} if the asset has never reported
     *         telemetry
     */
    Optional<Telemetry> latestTelemetry(AssetId assetId);

    /**
     * How many currently {@code OPEN} detection events each asset has, scanned across the fleet's
     * {@code scanLimit} most-recently-updated events.
     *
     * @param scanLimit how many of the most-recently-updated events to scan; the tuning constant
     *                  stays warehouse's, threaded through as a parameter rather than baked into the
     *                  port
     * @return an immutable snapshot; an asset with no open events is absent, not mapped to zero
     */
    Map<AssetId, Integer> openDetectionEventCounts(int scanLimit);
}
