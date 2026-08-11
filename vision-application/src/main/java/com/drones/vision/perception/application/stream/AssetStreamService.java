package com.drones.vision.perception.application.stream;

import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.DeviceId;
import com.drones.vision.kernel.StreamId;
import com.drones.vision.perception.domain.model.PipelineConfig;

/**
 * Asset-level streaming: resolves which of an asset's devices to use, then delegates the mechanics
 * to {@link StreamService}.
 *
 * <p>Split out of {@code com.drones.vision.warehouse.application.asset.AssetService}
 * (docs/plans/active/DOMAIN-SEPARATION-W1.md &sect;15, W1.6e): starting a stream resolves a device
 * and hands it, together with {@link PipelineConfig}/{@link TrackingConfigPatch}, to the runtime —
 * that is perception's job, not inventory's. {@code perception -> warehouse} is the legal direction
 * (a stream must resolve its device), so this class reaches into warehouse to resolve the asset
 * itself; the reverse — warehouse importing perception's configuration types into its own published
 * interface to drive the runtime — is exactly the coupling this split removes. Stopping a stream
 * stays on {@code AssetService}: unlike starting one, it names no perception type at all, and is
 * expressible entirely through {@code AssetLiveStatePort} (the inverted read/stop port
 * warehouse declares — see Part 2 of the same wave).
 *
 * <p>One interface, one implementation ({@link DefaultAssetStreamService}).
 *
 * <h2>Threading</h2>
 * Implementations must be safe for concurrent use; all shared state lives behind driven ports.
 */
public interface AssetStreamService {

    /**
     * Starts streaming from one of the asset's devices, with the caller stating nothing about
     * tracking — exactly {@code startStream(id, device, config, TrackingConfigPatch.NOTHING)}.
     *
     * @param id     the asset to stream
     * @param device which device to use, or {@code null} to resolve the asset's only active
     *               video-capable source
     * @param config pipeline settings for this stream
     * @return the new stream's id
     * @throws java.util.NoSuchElementException if no asset has that id
     * @throws IllegalArgumentException         if the device does not belong to the asset, or which
     *                                          device to use is ambiguous
     * @throws IllegalStateException            if the asset is not in service
     */
    StreamId startStream(AssetId id, DeviceId device, PipelineConfig config);

    /**
     * Starts streaming from one of the asset's devices, stating the caller's tracking wishes
     * separately — the asset-level twin of {@link StreamService#start(DeviceId, PipelineConfig,
     * TrackingConfigPatch)}, whose javadoc describes how the three configuration layers fold.
     *
     * @param id       the asset to stream
     * @param device   which device to use, or {@code null} to resolve the asset's only active
     *                 video-capable source
     * @param config   pipeline settings for this stream
     * @param tracking what this request states about tracking, per field; never {@code null}
     * @return the new stream's id
     * @throws java.util.NoSuchElementException if no asset has that id
     * @throws IllegalArgumentException         if the device does not belong to the asset, which
     *                                          device to use is ambiguous, or the composed tracking
     *                                          configuration is invalid
     * @throws IllegalStateException            if the asset is not in service
     */
    StreamId startStream(AssetId id, DeviceId device, PipelineConfig config, TrackingConfigPatch tracking);
}
