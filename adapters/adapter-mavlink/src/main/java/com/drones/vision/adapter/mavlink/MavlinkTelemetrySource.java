package com.drones.vision.adapter.mavlink;

import com.drones.vision.domain.model.Capability;
import com.drones.vision.domain.model.Device;
import com.drones.vision.domain.model.DeviceId;
import com.drones.vision.domain.model.StreamDescriptor;
import com.drones.vision.domain.model.Telemetry;
import com.drones.vision.domain.port.out.TelemetrySourcePort;

import io.dronefleet.mavlink.MavlinkConnection;
import io.dronefleet.mavlink.MavlinkMessage;

import java.io.IOException;
import java.io.OutputStream;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@link TelemetrySourcePort} implementation that ingests MAVLink 2 telemetry over UDP — the
 * de-facto transport for telemetry radios and ArduPilot/PX4 SITL (docs/MVP2-PLAN.md X-a).
 * Supports {@link StreamDescriptor#protocol()} {@code "mavlink"} with a {@code udp://host:port}
 * {@link StreamDescriptor#uri()}.
 *
 * <h2>{@code udp://host:port} means <b>listen</b>, not connect</h2>
 * A telemetry radio or SITL instance <b>pushes</b> datagrams to this app; this adapter does not
 * dial out. {@code host} is the local address to <b>bind</b> the listening socket to (blank/absent
 * falls back to the wildcard {@value #DEFAULT_BIND_HOST}, i.e. all interfaces); {@code port} is
 * the local UDP port to bind and listen on. The sender's own address is irrelevant and never
 * validated — any datagram arriving on the bound port is read.
 *
 * <p>{@link #supports(Device)} requires {@link Capability#TELEMETRY} and a {@code "mavlink"}/{@code
 * "udp"} descriptor with a positive port. Each {@link #open(Device)} call binds a fresh {@link
 * DatagramSocket} and starts one dedicated platform thread ({@code mavlink-telemetry-<id>})
 * running a blocking read loop: {@link MavlinkUdpInputStream} bridges the socket to the byte
 * stream {@link MavlinkConnection} expects, and {@link MavlinkTelemetryDecoder} merges each
 * decoded message into a {@link Telemetry} sample, submitted to a per-device {@link
 * SubmissionPublisher} (mirroring {@code adapter-simulation}'s {@code SimulatedTelemetrySource},
 * this module's closest reference implementation).
 *
 * <h2>Robustness</h2>
 * Unparseable/garbage datagrams are never fatal — {@code MavlinkConnection#next()} itself scans
 * for the next valid frame-start marker and silently drops anything that fails to parse or fails
 * CRC (see {@link MavlinkUdpInputStream}'s javadoc for the full reasoning), so this adapter adds
 * no extra try/catch around individual reads for that case. The only exception path that reaches
 * this class's own {@code catch} is a genuine {@link IOException} from the socket itself — most
 * commonly {@link #close(DeviceId)} closing it to unblock the read thread, which is treated as a
 * graceful shutdown, not an error.
 *
 * <p>Plain class with no framework dependency — instantiated directly by {@code vision-app}'s
 * wiring configuration.
 */
public final class MavlinkTelemetrySource implements TelemetrySourcePort {

    private static final String PROTOCOL = "mavlink";
    private static final String SCHEME_UDP = "udp";
    static final String DEFAULT_BIND_HOST = "0.0.0.0";

    private static final long CLOSE_JOIN_TIMEOUT_MILLIS = 5_000L;

    private final Map<DeviceId, DeviceRuntime> runtimes = new ConcurrentHashMap<>();

    @Override
    public boolean supports(Device device) {
        if (device == null || !device.capabilities().contains(Capability.TELEMETRY)) {
            return false;
        }
        StreamDescriptor stream = device.stream();
        if (!PROTOCOL.equals(stream.protocol())) {
            return false;
        }
        URI uri = stream.uri();
        return uri != null && SCHEME_UDP.equalsIgnoreCase(uri.getScheme()) && uri.getPort() > 0;
    }

    @Override
    public Flow.Publisher<Telemetry> open(Device device) {
        if (!supports(device)) {
            throw new IllegalArgumentException("MavlinkTelemetrySource does not support device: " + device);
        }
        URI uri = device.stream().uri();
        DeviceRuntime runtime = new DeviceRuntime(device.id(), bindHost(uri), uri.getPort());
        DeviceRuntime previous = runtimes.put(device.id(), runtime);
        if (previous != null) {
            previous.close(); // defensive: a device id must not have two live runtimes
        }
        runtime.start();
        return runtime.publisher;
    }

    @Override
    public void close(DeviceId id) {
        DeviceRuntime runtime = runtimes.remove(id);
        if (runtime != null) {
            runtime.close();
        }
    }

    private static String bindHost(URI uri) {
        String host = uri.getHost();
        return host == null || host.isBlank() ? DEFAULT_BIND_HOST : host;
    }

    /** Per-open runtime: a dedicated UDP read thread feeding a {@link SubmissionPublisher}. */
    private static final class DeviceRuntime {
        private final DeviceId deviceId;
        private final String bindHost;
        private final int port;
        private final SubmissionPublisher<Telemetry> publisher = new SubmissionPublisher<>();
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private volatile Thread readThread;
        private volatile DatagramSocket socket;

        DeviceRuntime(DeviceId deviceId, String bindHost, int port) {
            this.deviceId = deviceId;
            this.bindHost = bindHost;
            this.port = port;
        }

        void start() {
            readThread = new Thread(this::runReadLoop, "mavlink-telemetry-" + deviceId.value());
            readThread.setDaemon(true);
            readThread.start();
        }

        private void runReadLoop() {
            boolean errored = false;
            DatagramSocket sock = null;
            try {
                sock = new DatagramSocket(null);
                sock.setReuseAddress(true);
                sock.bind(new InetSocketAddress(bindHost, port));
                socket = sock;

                MavlinkConnection connection = MavlinkConnection.create(
                        new MavlinkUdpInputStream(sock), OutputStream.nullOutputStream());
                MavlinkTelemetryDecoder decoder = new MavlinkTelemetryDecoder(deviceId);

                while (!closed.get()) {
                    // Blocks; malformed/garbage datagrams are resynced past internally by
                    // MavlinkConnection/MavlinkFrameReader -- see class javadoc.
                    MavlinkMessage<?> message = connection.next();
                    Telemetry sample = decoder.accept(message);
                    if (sample != null) {
                        publisher.submit(sample);
                    }
                }
            } catch (Exception e) {
                errored = true;
                if (!closed.get()) {
                    // Unrecoverable failure (not a malformed datagram -- those never reach here):
                    // signal onError, per TelemetrySourcePort's contract.
                    publisher.closeExceptionally(e);
                }
            } finally {
                closeQuietly(sock);
                if (!errored) {
                    publisher.close();
                }
            }
        }

        void close() {
            if (closed.compareAndSet(false, true)) {
                closeQuietly(socket); // unblocks a pending receive()
                Thread thread = readThread;
                if (thread != null && thread != Thread.currentThread()) {
                    thread.interrupt(); // best-effort; a blocked receive() may not respond to this alone
                    try {
                        thread.join(CLOSE_JOIN_TIMEOUT_MILLIS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                publisher.close();
            }
        }

        private static void closeQuietly(DatagramSocket socket) {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        }
    }
}
