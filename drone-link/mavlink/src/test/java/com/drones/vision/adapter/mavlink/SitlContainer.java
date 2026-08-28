package com.drones.vision.adapter.mavlink;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
 * <h2>Why {@code INSTANCE} is allocated rather than fixed at 0</h2>
 * {@code --network host} is what makes the telemetry wiring simple, and it is also what makes SITL's
 * <i>own</i> listeners host-global: {@code arducopter -I N} binds TCP {@value #SERIAL1_BASE_PORT} and
 * {@value #SERIAL2_BASE_PORT} and UDP {@value #SIMULATION_BASE_PORT}, each offset by
 * {@value #INSTANCE_PORT_STRIDE}·N, on the host. Two containers at the same instance therefore cannot
 * coexist — the second dies at startup with {@code bind port 5762 for SERIAL1} and its test fails far
 * downstream, looking like lost telemetry rather than a port clash. That is not hypothetical: it was
 * reproduced by running two builds of this module at once, which is ordinary here (one agent verifying
 * while another builds). {@link #claimInstance()} hands out a free instance instead, so concurrent
 * builds and concurrent tests in one JVM stay out of each other's way.
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

    /**
     * {@code infra/sitl/entrypoint.sh}'s own {@code VEHICLE=rover} value (docs/plans/active/FLEET-RADIO-PLAN.md
     * R7) -- pass to {@link #start(String, int, int, Integer, String)} to boot ArduRover instead of
     * the image's own {@code copter} default. ArduRover arms in MANUAL and holds rather than flying
     * a circuit (see {@code infra/sitl/README.md}), specifically so it can be *driven*.
     */
    static final String VEHICLE_ROVER = "rover";

    /** {@code arducopter -I N} offsets every one of its own listeners by this much. */
    private static final int INSTANCE_PORT_STRIDE = 10;

    static final int SERIAL1_BASE_PORT = 5762;

    static final int SERIAL2_BASE_PORT = 5763;

    static final int SIMULATION_BASE_PORT = 5501;

    /**
     * Enough for ten concurrent SITLs; past that the ports would run into other services' territory,
     * and a machine running ten of these is out of CPU long before it is out of instances.
     */
    private static final int MAX_INSTANCES = 10;

    /**
     * Instances this JVM has handed out and not yet released. Port probing alone cannot serialise two
     * {@link #start} calls racing for the same instance — the loser would only find out at
     * {@code docker run} — so the claim is taken here first, before any port is touched.
     */
    private static final Set<Integer> CLAIMED_INSTANCES = ConcurrentHashMap.newKeySet();

    private final String name;

    private final int instance;

    private SitlContainer(String name, int instance) {
        this.name = name;
        this.instance = instance;
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
        return start(purpose, targetPort, sysid, null, null);
    }

    /**
     * @param speedup {@code SITL_SPEEDUP}, or {@code null} for the image's own real-time default —
     *                a test that has to fly the aircraft somewhere wants this, one that only reads
     *                telemetry does not
     */
    static SitlContainer start(String purpose, int targetPort, int sysid, Integer speedup)
            throws IOException, InterruptedException {
        return start(purpose, targetPort, sysid, speedup, null);
    }

    /**
     * @param vehicle {@code infra/sitl/entrypoint.sh}'s {@code VEHICLE} value -- {@link #VEHICLE_ROVER}
     *                to boot ArduRover, or {@code null} to leave {@code VEHICLE} unset (the image's
     *                own {@code copter} default, byte-identical to every call site that pre-dates
     *                FLEET-RADIO R7)
     */
    static SitlContainer start(String purpose, int targetPort, int sysid, Integer speedup, String vehicle)
            throws IOException, InterruptedException {
        String name = "vision-sitl-" + purpose + "-" + UUID.randomUUID();
        int instance = claimInstance();
        List<String> command = new ArrayList<>(List.of(
                "docker", "run", "-d", "--rm", "--name", name,
                "--network", "host",
                "-e", "SYSID_THISMAV=" + sysid,
                "-e", "INSTANCE=" + instance,
                "-e", "MAVLINK_TARGET_HOST=127.0.0.1",
                "-e", "MAVLINK_TARGET_PORT=" + targetPort));
        if (speedup != null) {
            command.addAll(List.of("-e", "SITL_SPEEDUP=" + speedup));
        }
        if (vehicle != null) {
            command.addAll(List.of("-e", "VEHICLE=" + vehicle));
        }
        command.add(IMAGE);

        ProcessResult result = run(Duration.ofSeconds(30), command.toArray(String[]::new));
        if (result.exitCode() != 0) {
            CLAIMED_INSTANCES.remove(instance);
            throw new IllegalStateException("failed to start SITL container: " + result.output());
        }
        SitlContainer container = new SitlContainer(name, instance);
        try {
            container.requireAlive();
        } catch (RuntimeException | IOException | InterruptedException e) {
            container.close();
            throw e;
        }
        return container;
    }

    /**
     * {@code docker run -d} reports success the moment the container is created, so a SITL that dies
     * one second later — the shape every port clash takes — would otherwise surface minutes on as an
     * unexplained telemetry timeout. Checking here turns that into a failure that names its own cause.
     */
    private void requireAlive() throws IOException, InterruptedException {
        ProcessResult running = run(Duration.ofSeconds(10), "docker", "inspect", "-f", "{{.State.Running}}", name);
        if (running.exitCode() == 0 && running.output().strip().equals("true")) {
            return;
        }
        // `--rm` has usually reaped it by now, taking the logs with it; say what is knowable.
        throw new IllegalStateException("SITL container " + name + " (instance " + instance
                + ") exited immediately after starting -- it did not survive to serve telemetry. "
                + "Its instance claims host ports TCP " + (SERIAL1_BASE_PORT + instance * INSTANCE_PORT_STRIDE)
                + "/" + (SERIAL2_BASE_PORT + instance * INSTANCE_PORT_STRIDE) + " and UDP "
                + (SIMULATION_BASE_PORT + instance * INSTANCE_PORT_STRIDE)
                + "; docker said: " + running.output().strip());
    }

    /**
     * @throws IllegalStateException if every instance is taken — a real failure, since a test that
     *                               silently shared an instance would fail more confusingly later
     */
    private static int claimInstance() {
        for (int instance = 0; instance < MAX_INSTANCES; instance++) {
            if (!CLAIMED_INSTANCES.add(instance)) {
                continue;
            }
            if (portsFree(instance)) {
                return instance;
            }
            CLAIMED_INSTANCES.remove(instance);
        }
        throw new IllegalStateException("no free ArduPilot SITL instance below " + MAX_INSTANCES
                + " -- every candidate's host ports are already bound, most likely by SITL containers "
                + "left behind by an interrupted run (`docker ps --filter name=vision-sitl`)");
    }

    /** Probes what {@code arducopter -I instance} will itself try to bind, on the host it will bind it on. */
    private static boolean portsFree(int instance) {
        int offset = instance * INSTANCE_PORT_STRIDE;
        return tcpPortFree(SERIAL1_BASE_PORT + offset)
                && tcpPortFree(SERIAL2_BASE_PORT + offset)
                && udpPortFree(SIMULATION_BASE_PORT + offset);
    }

    private static boolean tcpPortFree(int port) {
        try (ServerSocket socket = new ServerSocket()) {
            socket.setReuseAddress(false);
            socket.bind(new InetSocketAddress("0.0.0.0", port));
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean udpPortFree(int port) {
        try (DatagramSocket socket = new DatagramSocket(null)) {
            // Without this a second binder is welcomed rather than refused, and the probe always says "free".
            socket.setReuseAddress(false);
            socket.bind(new InetSocketAddress("0.0.0.0", port));
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    @Override
    public void close() {
        try {
            run(Duration.ofSeconds(15), "docker", "rm", "-f", name);
        } catch (Exception ignored) {
            // best-effort cleanup only -- `--rm` already reaps it when the process exits
        } finally {
            // Released even when removal fails: holding the claim would starve later tests of an
            // instance whose ports the dying container is about to give back anyway.
            CLAIMED_INSTANCES.remove(instance);
        }
    }

    /**
     * SITL's own default local TCP port for {@code serial2} (see the {@value #SERIAL2_BASE_PORT}
     * class constant), offset by this container's own claimed {@link #instance}. {@code
     * entrypoint.sh} does not point this at anything -- it is ArduPilot's own out-of-the-box
     * default, exposed here so a test can open <b>a second, independent</b> raw MAVLink connection
     * straight to the vehicle (exactly {@code autofly.py}'s own pattern against {@code serial1},
     * just read-only and test-side) without touching this platform's own ingest channel at all.
     * FLEET-RADIO R7's own RC-override assertion needs precisely this: proof that a channel this
     * platform sent reached the aircraft, read back from the aircraft's own telemetry over a link
     * nothing in {@code adapter-mavlink} production code ever opens or reads.
     */
    int serial2Port() {
        return SERIAL2_BASE_PORT + instance * INSTANCE_PORT_STRIDE;
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
