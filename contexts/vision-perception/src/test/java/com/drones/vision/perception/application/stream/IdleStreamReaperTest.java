package com.drones.vision.perception.application.stream;

import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.DeviceId;
import com.drones.vision.kernel.StreamId;
import com.drones.vision.perception.domain.model.StopReason;
import com.drones.vision.perception.domain.model.StreamState;
import com.drones.vision.perception.domain.port.VideoDemandPort;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Drives {@link IdleStreamReaper#sweep(Instant)} directly with an explicit clock — no scheduler, no
 * sleeping, no real {@code StreamService}. Every case here is a fail-safe question: the class only
 * earns its place if it never stops a stream someone is using.
 */
class IdleStreamReaperTest {

    private static final Duration TIMEOUT = Duration.ofMinutes(10);
    private static final IdleStreamPolicy POLICY =
            new IdleStreamPolicy(true, TIMEOUT, Duration.ofSeconds(30));

    private final Instant t0 = Instant.parse("2026-08-19T12:00:00Z");
    private final StreamId streamId = StreamId.random();
    private final DeviceId deviceId = DeviceId.random();
    private final AssetId assetId = AssetId.random();

    /** Records what was stopped and why; every other {@link StreamService} method is unused here. */
    private static final class RecordingStreams {
        private final List<Map.Entry<StreamId, StopReason>> stopped = new ArrayList<>();
        private final List<ActiveStream> running = new ArrayList<>();
        private final StreamService service = mock(StreamService.class);

        RecordingStreams() {
            when(service.streams()).thenAnswer(invocation -> List.copyOf(running));
            org.mockito.Mockito.doAnswer(invocation -> {
                StreamId id = invocation.getArgument(0);
                stopped.add(Map.entry(id, invocation.getArgument(1)));
                running.removeIf(stream -> stream.streamId().equals(id));
                return null;
            }).when(service).stop(any(StreamId.class), any(StopReason.class));
        }
    }

    private ActiveStream stream() {
        return new ActiveStream(streamId, deviceId, t0, true, StreamState.LIVE, false);
    }

    private IdleStreamReaper reaper(RecordingStreams streams, VideoDemandPort demand) {
        return reaper(streams, demand, POLICY);
    }

    private IdleStreamReaper reaper(RecordingStreams streams, VideoDemandPort demand, IdleStreamPolicy policy) {
        Function<DeviceId, AssetId> resolver = device -> device.equals(deviceId) ? assetId : null;
        return new IdleStreamReaper(streams.service, demand, policy, resolver);
    }

    @Test
    void neverStopsAStreamOnTheSweepThatDiscoveredIt() {
        RecordingStreams streams = new RecordingStreams();
        streams.running.add(stream());
        // Nobody has ever watched it, and the reaper has only just started -- it still gets the
        // full timeout, because "how long was it idle before I existed" is not something it knows.
        IdleStreamReaper reaper = reaper(streams, (id, asset) -> false);

        reaper.sweep(t0.plus(Duration.ofHours(3)));

        assertTrue(streams.stopped.isEmpty());
    }

    @Test
    void stopsAStreamThatHasBeenUnwantedForTheWholeTimeout() {
        RecordingStreams streams = new RecordingStreams();
        streams.running.add(stream());
        IdleStreamReaper reaper = reaper(streams, (id, asset) -> false);

        reaper.sweep(t0);
        reaper.sweep(t0.plus(TIMEOUT).minusSeconds(1));
        assertTrue(streams.stopped.isEmpty(), "one second short of the timeout is still within it");

        reaper.sweep(t0.plus(TIMEOUT));

        assertEquals(List.of(Map.entry(streamId, StopReason.IDLE_NO_VIEWERS)), streams.stopped);
    }

    @Test
    void demandRestartsTheClock() {
        RecordingStreams streams = new RecordingStreams();
        streams.running.add(stream());
        boolean[] wanted = {false};
        IdleStreamReaper reaper = reaper(streams, (id, asset) -> wanted[0]);

        reaper.sweep(t0);
        wanted[0] = true;
        reaper.sweep(t0.plus(TIMEOUT).minusSeconds(1)); // someone tuned in just before the deadline
        wanted[0] = false;
        reaper.sweep(t0.plus(TIMEOUT).plusSeconds(1));

        assertTrue(streams.stopped.isEmpty(), "the clock restarts at the last observed demand, not at start");
    }

    @Test
    void aDemandPortThatThrowsLeavesTheStreamRunning() {
        RecordingStreams streams = new RecordingStreams();
        streams.running.add(stream());
        IdleStreamReaper reaper = reaper(streams, (id, asset) -> {
            throw new IllegalStateException("mediamtx unreachable");
        });

        reaper.sweep(t0);
        reaper.sweep(t0.plus(TIMEOUT).plusSeconds(1));

        // The port's own contract says fail open; this asserts the reaper does not undo that when
        // an implementation breaks the contract and throws instead.
        assertTrue(streams.stopped.isEmpty());
    }

    @Test
    void oneFailingStreamDoesNotStopTheSweepEvaluatingTheOthers() {
        StreamId healthy = StreamId.random();
        DeviceId healthyDevice = DeviceId.random();
        RecordingStreams streams = new RecordingStreams();
        streams.running.add(stream());
        streams.running.add(new ActiveStream(healthy, healthyDevice, t0, true, StreamState.LIVE, false));
        VideoDemandPort demand = (id, asset) -> {
            if (id.equals(streamId)) {
                throw new IllegalStateException("boom");
            }
            return false;
        };
        IdleStreamReaper reaper = reaper(streams, demand);

        reaper.sweep(t0);
        reaper.sweep(t0.plus(TIMEOUT));

        assertEquals(List.of(Map.entry(healthy, StopReason.IDLE_NO_VIEWERS)), streams.stopped);
    }

    @Test
    void passesTheResolvedAssetToTheDemandPort() {
        RecordingStreams streams = new RecordingStreams();
        streams.running.add(stream());
        ConcurrentHashMap<StreamId, AssetId> seen = new ConcurrentHashMap<>();
        IdleStreamReaper reaper = reaper(streams, (id, asset) -> {
            if (asset != null) {
                seen.put(id, asset);
            }
            return true;
        });

        reaper.sweep(t0);

        assertEquals(assetId, seen.get(streamId));
    }

    @Test
    void aStreamStoppedByAnyoneElseStopsBeingTracked() {
        RecordingStreams streams = new RecordingStreams();
        streams.running.add(stream());
        IdleStreamReaper reaper = reaper(streams, (id, asset) -> false);

        reaper.sweep(t0);
        streams.running.clear();          // an operator pressed Stop
        reaper.sweep(t0.plus(TIMEOUT));
        streams.running.add(stream());    // the same id starts again later
        reaper.sweep(t0.plus(TIMEOUT).plusSeconds(1));
        reaper.sweep(t0.plus(TIMEOUT).plusSeconds(2));

        // Had the old entry survived, this restart would have been reaped on its first sweep.
        assertTrue(streams.stopped.isEmpty());
    }

    @Test
    void aDisabledPolicyNeverSchedulesASweep() {
        RecordingStreams streams = new RecordingStreams();
        streams.running.add(stream());
        try (IdleStreamReaper reaper = reaper(streams, (id, asset) -> false, IdleStreamPolicy.disabled())) {
            reaper.start();
        }

        assertTrue(streams.stopped.isEmpty());
    }
}
