package com.drones.vision.application.stream;

import com.drones.vision.domain.model.DeviceId;
import com.drones.vision.domain.model.StreamId;

import java.time.Instant;

/**
 * A stream that is running right now.
 *
 * @param streamId  the stream's identity
 * @param deviceId  the device it is pulling frames from
 * @param startedAt when it started
 */
public record ActiveStream(StreamId streamId, DeviceId deviceId, Instant startedAt) {

    public ActiveStream {
        if (streamId == null) {
            throw new IllegalArgumentException("ActiveStream streamId must not be null");
        }
        if (deviceId == null) {
            throw new IllegalArgumentException("ActiveStream deviceId must not be null");
        }
        if (startedAt == null) {
            throw new IllegalArgumentException("ActiveStream startedAt must not be null");
        }
    }
}
