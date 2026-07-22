package com.drones.vision.application;

import com.drones.vision.domain.model.DeviceId;
import com.drones.vision.domain.model.PipelineConfig;
import com.drones.vision.domain.model.StreamId;

import java.util.List;
import java.util.Set;

/**
 * The lifecycle of live streams: start one for a device, stop it, list what is running.
 *
 * <p>One interface, one implementation ({@link DefaultStreamService}). Asset-level streaming
 * lives in {@link AssetService}, which resolves the device and then calls in here.
 *
 * <h2>Threading</h2>
 * Implementations must be safe for concurrent use — one pipeline per stream, started and stopped
 * from control-plane calls while others run.
 */
public interface StreamService {

    /**
     * Opens a stream for a device and starts its pipeline.
     *
     * @param deviceId the device to pull frames from
     * @param config   pipeline settings for this stream
     * @return the new stream's id
     * @throws java.util.NoSuchElementException if no device has that id
     * @throws IllegalStateException            if the device is not in service, or already streaming
     */
    StreamId start(DeviceId deviceId, PipelineConfig config);

    /**
     * Stops a stream and releases its source. A no-op for an unknown or already-stopped id.
     *
     * @param streamId the stream to stop
     */
    void stop(StreamId streamId);

    /**
     * Lists the streams running in this instance.
     *
     * @return an immutable snapshot
     */
    List<ActiveStream> streams();

    /**
     * The devices that currently have an active stream.
     *
     * @return an immutable snapshot, for deriving asset status
     */
    Set<DeviceId> activeDeviceIds();
}
