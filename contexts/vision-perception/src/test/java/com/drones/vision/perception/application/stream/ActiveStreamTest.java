package com.drones.vision.perception.application.stream;

import com.drones.vision.kernel.DeviceId;
import com.drones.vision.kernel.StreamId;
import com.drones.vision.perception.domain.model.StreamState;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ActiveStreamTest {

    @Test
    void rejectsAnyMissingComponent() {
        Instant now = Instant.parse("2026-07-22T12:00:00Z");

        assertThrows(IllegalArgumentException.class,
                () -> new ActiveStream(null, DeviceId.random(), now));
        assertThrows(IllegalArgumentException.class,
                () -> new ActiveStream(StreamId.random(), null, now));
        assertThrows(IllegalArgumentException.class,
                () -> new ActiveStream(StreamId.random(), DeviceId.random(), null));
        assertThrows(IllegalArgumentException.class,
                () -> new ActiveStream(StreamId.random(), DeviceId.random(), now, null, false));
    }

    @Test
    void threeArgConstructorDefaultsToUnobservedAndDetectionDisabled() {
        // docs/plans/done/STREAM-STATE-PLAN.md §2.3: the "we were not told" answers, deliberately,
        // not optimistic ones -- a caller that omits state has not established video is flowing.
        StreamId streamId = StreamId.random();
        DeviceId deviceId = DeviceId.random();
        Instant now = Instant.parse("2026-07-22T12:00:00Z");

        ActiveStream viaConvenience = new ActiveStream(streamId, deviceId, now);
        ActiveStream viaCanonical = new ActiveStream(streamId, deviceId, now, StreamState.UNOBSERVED, false);

        assertEquals(viaCanonical, viaConvenience);
        assertEquals(StreamState.UNOBSERVED, viaConvenience.state());
        assertFalse(viaConvenience.detectionEnabled());
    }

    @Test
    void canonicalConstructorAcceptsExplicitStateAndDetectionEnabled() {
        ActiveStream live = new ActiveStream(StreamId.random(), DeviceId.random(),
                Instant.parse("2026-07-22T12:00:00Z"), StreamState.LIVE, true);

        assertEquals(StreamState.LIVE, live.state());
        assertEquals(true, live.detectionEnabled());
    }
}
