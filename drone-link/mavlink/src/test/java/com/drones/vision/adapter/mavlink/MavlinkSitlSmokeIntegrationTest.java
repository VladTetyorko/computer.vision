package com.drones.vision.adapter.mavlink;

import com.drones.vision.kernel.Capability;
import com.drones.vision.warehouse.domain.model.Device;
import com.drones.vision.kernel.DeviceId;
import com.drones.vision.kernel.StreamDescriptor;
import com.drones.vision.kernel.Telemetry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.Future;
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
 * <h2>Gating (docker-gated AND image-gated, skip cleanly, never build ArduPilot)</h2>
 * This test only runs when both are true: the {@code docker} CLI can talk to a daemon, and the
 * {@code infra/sitl} image ({@value #SITL_IMAGE}, see {@code infra/sitl/up.sh}'s own {@code
 * IMAGE}) is <b>already built locally</b> (checked via {@code docker image inspect}). Neither
 * check builds anything — {@code infra/sitl/Dockerfile} downloads a prebuilt ArduPilot binary and
 * this test brief explicitly rules out compiling ArduPilot as part of a test run. Either
 * condition failing skips via a JUnit {@link org.junit.jupiter.api.Assumptions#assumeTrue}
 * (reported as skipped, never a failure) rather than an {@code @EnabledIf} class-level condition,
 * so the two independent reasons ("no docker" vs "image not built") get distinct messages.
 *
 * <h2>How the container is wired</h2>
 * One instance, {@code --network host} (so the container's {@code 127.0.0.1} <em>is</em> the
 * host's, letting SITL push straight at an ephemeral localhost port with no {@code
 * host.docker.internal} indirection), {@code MAVLINK_TARGET_HOST=127.0.0.1}/{@code
 * MAVLINK_TARGET_PORT=<ephemeral>} in place of {@code up.sh}'s own bridge-network defaults — the
 * same env vars {@code infra/sitl/entrypoint.sh} already reads, just pointed at a test-local port
 * instead of the well-known 14550. Always removed in a {@code finally} block.
 *
 * <p>Deliberately does <b>not</b> attempt command TX against this SITL instance — arming/flight-
 * state preconditions make that flaky; {@link MavlinkFlightCommanderTest}'s loopback fakes own TX
 * correctness. This test only proves the RX/decode path against real firmware.
 */
class MavlinkSitlSmokeIntegrationTest {

    /** Must match {@code infra/sitl/up.sh}'s own {@code IMAGE}. */
    private static final String SITL_IMAGE = "vision-sitl:4.7.0";

    @Test
    @Timeout(value = 150, unit = TimeUnit.SECONDS)
    void aRealArduPilotSitlInstanceDecodesIntoATelemetrySampleWithFlightState() throws Exception {
        assumeTrue(dockerAvailable(), "docker is not available in this environment -- skipping");
        assumeTrue(sitlImagePresent(), SITL_IMAGE + " is not built locally (docker image inspect failed) -- "
                + "run `docker build -t " + SITL_IMAGE + " infra/sitl` or `infra/sitl/up.sh` once to enable "
                + "this test; skipping rather than building ArduPilot as part of a test run");

        String containerName = "vision-sitl-smoke-" + UUID.randomUUID();
        int port = freePort();
        MavlinkTelemetrySource source = new MavlinkTelemetrySource();
        DeviceId deviceId = DeviceId.random();
        boolean containerStarted = false;
        try {
            startContainer(containerName, port);
            containerStarted = true;

            Device device = new Device(deviceId, "sitl-smoke-test", Set.of(Capability.TELEMETRY),
                    new StreamDescriptor("mavlink", URI.create("udp://0.0.0.0:" + port), Map.of()));
            Collector collector = Collector.subscribeTo(source.open(device));

            Telemetry sample = collector.awaitSampleWithFlightState(Duration.ofSeconds(90));
            assertNotNull(sample.flightState(), "expected a real ArduPilot HEARTBEAT to have merged a FlightState");
        } finally {
            source.close(deviceId);
            if (containerStarted) {
                removeContainerQuietly(containerName);
            }
        }
    }

    /** JUnit assumption helper: true iff the {@code docker} CLI can talk to a daemon. */
    static boolean dockerAvailable() {
        try {
            return run(Duration.ofSeconds(5), "docker", "info").exitCode() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** JUnit assumption helper: true iff {@value #SITL_IMAGE} is already built locally -- never builds it. */
    private static boolean sitlImagePresent() {
        try {
            return run(Duration.ofSeconds(10), "docker", "image", "inspect", SITL_IMAGE).exitCode() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static void startContainer(String name, int port) throws IOException, InterruptedException {
        ProcessResult result = run(Duration.ofSeconds(30),
                "docker", "run", "-d", "--rm", "--name", name,
                "--network", "host",
                "-e", "SYSID_THISMAV=1",
                "-e", "INSTANCE=0",
                "-e", "MAVLINK_TARGET_HOST=127.0.0.1",
                "-e", "MAVLINK_TARGET_PORT=" + port,
                SITL_IMAGE);
        if (result.exitCode() != 0) {
            fail("failed to start SITL container: " + result.output());
        }
    }

    private static void removeContainerQuietly(String name) {
        try {
            run(Duration.ofSeconds(15), "docker", "rm", "-f", name);
        } catch (Exception ignored) {
            // best-effort cleanup only
        }
    }

    private static int freePort() throws Exception {
        try (DatagramSocket socket = new DatagramSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static ProcessResult run(Duration timeout, String... command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "docker-cli-output-reader");
            t.setDaemon(true);
            return t;
        });
        Future<String> outputFuture = executor.submit(
                () -> new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        try {
            boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new IOException("Command timed out after " + timeout + ": " + String.join(" ", command));
            }
            String output;
            try {
                output = outputFuture.get(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                output = "";
            }
            return new ProcessResult(process.exitValue(), output);
        } finally {
            executor.shutdownNow();
        }
    }

    private record ProcessResult(int exitCode, String output) {
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
