package com.drones.vision.adapter.mavlink;

import com.drones.vision.kernel.Capability;
import com.drones.vision.warehouse.domain.model.Device;
import com.drones.vision.kernel.DeviceId;
import com.drones.vision.kernel.StreamDescriptor;
import com.drones.vision.kernel.Telemetry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * docs/plans/active/DRONE-INFRA-PLAN.md I-c: proves {@link MavlinkTelemetrySource} against genuine ArduPilot
 * SITL firmware ({@code infra/sitl/}), not this module's own {@link MavlinkFeedTransmitter}
 * simulator — real {@code HEARTBEAT}/GPS/PreArm behavior our own TX side can't fake.
 *
 * <p>Gating and container wiring both live in {@link SitlContainer}, which every SITL test in this
 * module shares; the two {@code assumeTrue} calls below skip cleanly (never fail) with distinct
 * reasons for "no docker" and "image not built".
 *
 * <p>Deliberately does <b>not</b> attempt command TX against this SITL instance — arming/flight-
 * state preconditions make that flaky; {@link MavlinkFlightCommanderTest}'s loopback fakes own TX
 * correctness. This test only proves the RX/decode path against real firmware.
 */
class MavlinkSitlSmokeIntegrationTest {

    @Test
    @Timeout(value = 150, unit = TimeUnit.SECONDS)
    void aRealArduPilotSitlInstanceDecodesIntoATelemetrySampleWithFlightState() throws Exception {
        assumeTrue(SitlContainer.dockerAvailable(), SitlContainer.NO_DOCKER);
        assumeTrue(SitlContainer.imagePresent(), SitlContainer.NO_IMAGE);

        int port = SitlContainer.freePort();
        MavlinkTelemetrySource source = new MavlinkTelemetrySource();
        DeviceId deviceId = DeviceId.random();
        try (SitlContainer ignored = SitlContainer.start("smoke", port, 1)) {
            Device device = new Device(deviceId, "sitl-smoke-test", Set.of(Capability.TELEMETRY),
                    new StreamDescriptor("mavlink", URI.create("udp://0.0.0.0:" + port), Map.of()));
            Collector collector = Collector.subscribeTo(source.open(device));

            Telemetry sample = collector.awaitSampleWithFlightState(Duration.ofSeconds(90));
            assertNotNull(sample.flightState(), "expected a real ArduPilot HEARTBEAT to have merged a FlightState");
        } finally {
            source.close(deviceId);
        }
    }


    /** Collects samples, exposing the first one carrying a non-null {@code FlightState}. */
    private static final class Collector implements Flow.Subscriber<Telemetry> {
        private final AtomicReference<Telemetry> sampleWithFlightState = new AtomicReference<>();
        private final AtomicReference<Throwable> error = new AtomicReference<>();
        private final Object monitor = new Object();

        static Collector subscribeTo(Flow.Publisher<Telemetry> publisher) {
            Collector collector = new Collector();
            publisher.subscribe(collector);
            return collector;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(Telemetry item) {
            if (item.flightState() != null) {
                sampleWithFlightState.compareAndSet(null, item);
            }
            synchronized (monitor) {
                monitor.notifyAll();
            }
        }

        @Override
        public void onError(Throwable throwable) {
            error.set(throwable);
            synchronized (monitor) {
                monitor.notifyAll();
            }
        }

        @Override
        public void onComplete() {
        }

        Telemetry awaitSampleWithFlightState(Duration timeout) throws InterruptedException {
            long deadline = System.currentTimeMillis() + timeout.toMillis();
            synchronized (monitor) {
                while (sampleWithFlightState.get() == null && error.get() == null
                        && System.currentTimeMillis() < deadline) {
                    monitor.wait(500);
                }
            }
            if (error.get() != null) {
                fail("telemetry publisher errored while awaiting a sample: " + error.get());
            }
            Telemetry sample = sampleWithFlightState.get();
            if (sample == null) {
                fail("expected a Telemetry sample with a non-null FlightState from real ArduPilot SITL within "
                        + timeout);
            }
            return sample;
        }
    }
}
