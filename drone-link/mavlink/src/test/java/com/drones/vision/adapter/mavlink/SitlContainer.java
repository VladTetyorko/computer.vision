package com.drones.vision.adapter.mavlink;

import java.io.IOException;
import java.net.DatagramSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * One running ArduPilot SITL container ({@code infra/sitl/}), shared by every test in this module
 * that needs real firmware rather than this module's own {@link MavlinkFeedTransmitter} simulator.
 *
 * <h2>Gating: docker-gated AND image-gated, skips cleanly, never builds ArduPilot</h2>
 * {@link #dockerAvailable()} and {@link #imagePresent()} are kept separate so a test's two
 * {@code assumeTrue} calls can give distinct reasons ("no docker" vs "image not built"). Neither
 * builds anything — {@code infra/sitl/Dockerfile} downloads a prebuilt ArduPilot binary, and
 * compiling ArduPilot inside a test run is explicitly out of scope.
 *
 * <h2>Wiring</h2>
 * {@code --network host}, so the container's {@code 127.0.0.1} <i>is</i> the host's and SITL can push
 * straight at an ephemeral localhost port with no {@code host.docker.internal} indirection.
 * {@code MAVLINK_TARGET_HOST}/{@code MAVLINK_TARGET_PORT} replace {@code up.sh}'s bridge-network
 * defaults — the same env vars {@code infra/sitl/entrypoint.sh} already reads, pointed at a
 * test-local port instead of the well-known 14550.
 *
 * <p>Extracted in wave O4 (docs/plans/active/DRONE-ONBOARDING-PLAN.md) because a third copy of this
 * plumbing was about to be written: {@link MavlinkSitlSmokeIntegrationTest} and
 * {@link MavlinkSitlReturnHomeIntegrationTest} each carried their own.
 */
final class SitlContainer implements AutoCloseable {

    /** Must match {@code infra/sitl/up.sh}'s own {@code IMAGE}. */
    static final String IMAGE = "vision-sitl:4.7.0";

    static final String NO_DOCKER = "docker is not available in this environment -- skipping";

    static final String NO_IMAGE = IMAGE + " is not built locally (docker image inspect failed) -- run "
            + "`docker build -t " + IMAGE + " infra/sitl` or `infra/sitl/up.sh` once to enable this test; "
            + "skipping rather than building ArduPilot as part of a test run";

    private final String name;

    private SitlContainer(String name) {
        this.name = name;
    }

    /** True iff the {@code docker} CLI can talk to a daemon. */
    static boolean dockerAvailable() {
        try {
            return run(Duration.ofSeconds(5), "docker", "info").exitCode() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** True iff {@value #IMAGE} is already built locally — never builds it. */
    static boolean imagePresent() {
        try {
            return run(Duration.ofSeconds(10), "docker", "image", "inspect", IMAGE).exitCode() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Starts one instance pushing telemetry at {@code 127.0.0.1:targetPort}.
     *
     * @throws IllegalStateException if the container will not start — a failure, never a skip, since
     *                               the gating above already established that it could
     */
    static SitlContainer start(String purpose, int targetPort, int sysid) throws IOException, InterruptedException {
        return start(purpose, targetPort, sysid, null);
    }

    /**
     * @param speedup {@code SITL_SPEEDUP}, or {@code null} for the image's own real-time default —
     *                a test that has to fly the aircraft somewhere wants this, one that only reads
     *                telemetry does not
     */
    static SitlContainer start(String purpose, int targetPort, int sysid, Integer speedup)
            throws IOException, InterruptedException {
        String name = "vision-sitl-" + purpose + "-" + UUID.randomUUID();
        List<String> command = new ArrayList<>(List.of(
                "docker", "run", "-d", "--rm", "--name", name,
                "--network", "host",
                "-e", "SYSID_THISMAV=" + sysid,
                "-e", "INSTANCE=0",
                "-e", "MAVLINK_TARGET_HOST=127.0.0.1",
                "-e", "MAVLINK_TARGET_PORT=" + targetPort));
        if (speedup != null) {
            command.addAll(List.of("-e", "SITL_SPEEDUP=" + speedup));
        }
        command.add(IMAGE);

        ProcessResult result = run(Duration.ofSeconds(30), command.toArray(String[]::new));
        if (result.exitCode() != 0) {
            throw new IllegalStateException("failed to start SITL container: " + result.output());
        }
        return new SitlContainer(name);
    }

    @Override
    public void close() {
        try {
            run(Duration.ofSeconds(15), "docker", "rm", "-f", name);
        } catch (Exception ignored) {
            // best-effort cleanup only -- `--rm` already reaps it when the process exits
        }
    }

    static int freePort() throws IOException {
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
}
