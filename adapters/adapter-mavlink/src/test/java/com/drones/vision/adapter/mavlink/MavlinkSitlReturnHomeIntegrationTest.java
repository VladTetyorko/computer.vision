package com.drones.vision.adapter.mavlink;

import com.drones.vision.domain.model.Capability;
import com.drones.vision.domain.model.CommandResult;
import com.drones.vision.domain.model.Device;
import com.drones.vision.domain.model.DeviceId;
import com.drones.vision.domain.model.StreamDescriptor;
import com.drones.vision.domain.model.Telemetry;

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
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * docs/DRONE-INFRA-PLAN.md I-e Stage 1 × I-c: proves {@link MavlinkFlightCommander} against a
 * <em>genuine, flying</em> ArduPilot SITL aircraft ({@code infra/sitl/}) — the one scenario the
 * loopback fakes in {@link MavlinkFlightCommanderTest} cannot cover: a real autopilot accepting
 * {@code DO_SET_MODE → RTL} mid-flight and actually switching modes.
 *
 * <p>Same docker-and-image gating as {@link MavlinkSitlSmokeIntegrationTest} (skip cleanly, never
 * build ArduPilot). Runs at {@code SITL_SPEEDUP=5} so boot → EKF → arm → takeoff compresses from
 * minutes to tens of seconds.
 *
 * <h2>Honest flakiness posture</h2>
 * A real autopilot refuses mode changes until EKF/arming preconditions hold, so the command is
 * <b>retried</b> until the aircraft answers {@code ACCEPTED} (an {@link IllegalStateException}
 * denial or a lost-datagram {@link CommandResult#NO_ACK} both just mean "not yet" here — retrying
 * is the test driving the aircraft to readiness, not papering over a transport bug; transport
 * correctness is owned by the loopback tests). The final assertion is the strongest available
 * truth: the aircraft's own telemetry reports {@code mode=RTL}.
 */
class MavlinkSitlReturnHomeIntegrationTest {

    /** Must match {@code infra/sitl/up.sh}'s own {@code IMAGE}. */
    private static final String SITL_IMAGE = "vision-sitl:4.7.0";

    @Test
    @Timeout(value = 330, unit = TimeUnit.SECONDS)
    void aFlyingArduPilotSitlAircraftAcceptsReturnToHomeAndSwitchesToRtl() throws Exception {
        assumeTrue(MavlinkSitlSmokeIntegrationTest.dockerAvailable(),
                "docker is not available in this environment -- skipping");
        assumeTrue(sitlImagePresent(), SITL_IMAGE + " is not built locally -- run infra/sitl/up.sh once; skipping");

        String containerName = "vision-sitl-rtl-" + UUID.randomUUID();
        int port = freePort();
        MavlinkTelemetrySource source = new MavlinkTelemetrySource();
        MavlinkFlightCommander commander = new MavlinkFlightCommander(source);
        DeviceId deviceId = DeviceId.random();
        boolean containerStarted = false;
        try {
            startContainer(containerName, port);
            containerStarted = true;

            Device device = new Device(deviceId, "sitl-rtl-test", Set.of(Capability.TELEMETRY),
                    new StreamDescriptor("mavlink", URI.create("udp://0.0.0.0:" + port), Map.of()));
            LatestSampleCollector collector = LatestSampleCollector.subscribeTo(source.open(device));

            // Phase 1: the autofly circuit arms and takes off on its own -- wait for a genuinely
            // armed aircraft (speedup 5: typically well under a minute of wall clock).
            collector.awaitFlightState(Duration.ofSeconds(150),
                    fs -> Boolean.TRUE.equals(fs.armed()),
                    "an armed FlightState from the autofly circuit");

            // Phase 2: command RTL, retrying until the autopilot answers ACCEPTED.
            long commandDeadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
            CommandResult result = null;
            String lastRefusal = null;
            while (System.nanoTime() < commandDeadline) {
                try {
                    result = commander.returnToHome(device);
                    if (result == CommandResult.ACCEPTED) {
                        break;
                    }
                    lastRefusal = "NO_ACK (datagram or ack lost)";
                } catch (IllegalStateException refused) {
                    lastRefusal = refused.getMessage();
                }
                Thread.sleep(3_000);
            }
            if (result != CommandResult.ACCEPTED) {
                fail("SITL never ACCEPTED RTL within the retry window; last refusal: " + lastRefusal);
            }

            // Phase 3: the aircraft's own telemetry must report the mode change.
            collector.awaitFlightState(Duration.ofSeconds(45),
                    fs -> "RTL".equals(fs.mode()),
                    "telemetry reporting mode=RTL after an ACCEPTED command");
        } finally {
            source.close(deviceId);
            if (containerStarted) {
                removeContainerQuietly(containerName);
            }
        }
    }

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
                "-e", "SITL_SPEEDUP=5",
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

    /** Tracks the freshest sample; callers await a {@code FlightState} predicate. */
    private static final class LatestSampleCollector implements Flow.Subscriber<Telemetry> {
        private final AtomicReference<Telemetry> latest = new AtomicReference<>();
        private final AtomicReference<Throwable> error = new AtomicReference<>();
        private final Object monitor = new Object();

        static LatestSampleCollector subscribeTo(Flow.Publisher<Telemetry> publisher) {
            LatestSampleCollector collector = new LatestSampleCollector();
            publisher.subscribe(collector);
            return collector;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(Telemetry item) {
            latest.set(item);
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

        void awaitFlightState(Duration timeout, Predicate<com.drones.vision.domain.model.FlightState> predicate,
                              String expectation) throws InterruptedException {
            long deadline = System.currentTimeMillis() + timeout.toMillis();
            synchronized (monitor) {
                while (System.currentTimeMillis() < deadline && error.get() == null) {
                    Telemetry sample = latest.get();
                    if (sample != null && sample.flightState() != null && predicate.test(sample.flightState())) {
                        return;
                    }
                    monitor.wait(500);
                }
            }
            if (error.get() != null) {
                fail("telemetry publisher errored while awaiting " + expectation + ": " + error.get());
            }
            Telemetry last = latest.get();
            fail("timed out (" + timeout + ") awaiting " + expectation + "; last flight state: "
                    + (last == null ? "no sample at all" : String.valueOf(last.flightState())));
        }
    }
}
