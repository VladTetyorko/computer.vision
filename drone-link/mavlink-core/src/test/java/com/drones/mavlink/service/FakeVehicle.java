package com.drones.mavlink.service;

import com.drones.mavlink.CompId;
import com.drones.mavlink.PeerId;
import com.drones.mavlink.SysId;
import com.drones.mavlink.codec.FrameReader;
import com.drones.mavlink.codec.FrameWriter;
import com.drones.mavlink.codec.MavFrame;
import com.drones.mavlink.transport.ByteChunk;
import com.drones.mavlink.transport.UdpTargetLink;

import io.dronefleet.mavlink.common.AutopilotVersion;
import io.dronefleet.mavlink.common.CommandAck;
import io.dronefleet.mavlink.common.MavCmd;
import io.dronefleet.mavlink.common.MavParamType;
import io.dronefleet.mavlink.common.MavResult;
import io.dronefleet.mavlink.common.ParamValue;
import io.dronefleet.mavlink.common.RcChannelsOverride;
import io.dronefleet.mavlink.util.EnumValue;
import io.dronefleet.mavlink.minimal.Heartbeat;
import io.dronefleet.mavlink.minimal.MavAutopilot;
import io.dronefleet.mavlink.minimal.MavModeFlag;
import io.dronefleet.mavlink.minimal.MavState;
import io.dronefleet.mavlink.minimal.MavType;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A minimal fake aircraft, built on this module's own {@code L1}/{@code L2} primitives
 * ({@link UdpTargetLink} + {@link FrameWriter} + {@link FrameReader}) rather than raw sockets — this
 * is what "port {@code adapter-mavlink}'s {@code FakeVehicle}" means at this layer: same continuous
 * ~5 Hz heartbeat (a one-shot heartbeat races a gateway's socket bind and can be silently lost on
 * plain UDP — documented in {@code adapter-mavlink}'s own MODULE.md {@code MavlinkFlightCommanderTest}
 * entry; not reintroduced here), same "drain everything, keep what matches" reader idiom, reworked to
 * speak through this module's own codec instead of a hand-rolled {@code MavlinkConnection}.
 *
 * <p>Public (not package-private) specifically so {@code com.drones.mavlink.api}'s own tests can
 * reuse it too ({@code DefaultCommandGatewayTest}) rather than duplicating a second fake vehicle --
 * both packages' tests need the identical "heartbeats, decodes COMMAND_LONG, acks it" double.
 */
public final class FakeVehicle implements AutoCloseable {

    private static final Duration HEARTBEAT_PERIOD = Duration.ofMillis(200);
    private static final Duration POLL_TIMEOUT = Duration.ofMillis(200);

    private final String gatewayHost;
    private final int gatewayPort;
    private final SysId sysid;
    private final CompId compid;
    private final MavAutopilot autopilot;
    private final MavType mavType;

    /** (link, writer, reader) always swapped together so a reader never sees a torn combination. */
    private record LinkBundle(UdpTargetLink link, FrameWriter writer, FrameReader reader) {
    }

    private volatile LinkBundle current;
    private final BlockingQueue<MavFrame> received = new LinkedBlockingQueue<>();
    private final AtomicBoolean stopped = new AtomicBoolean(false);
    private volatile Thread heartbeatThread;
    private volatile Thread readerThread;

    private FakeVehicle(String gatewayHost, int gatewayPort, int sysid, int compid, MavAutopilot autopilot, MavType mavType) {
        this.gatewayHost = gatewayHost;
        this.gatewayPort = gatewayPort;
        this.sysid = new SysId(sysid);
        this.compid = new CompId(compid);
        this.autopilot = autopilot;
        this.mavType = mavType;
    }

    public static FakeVehicle start(String gatewayHost, int gatewayPort, int sysid, int compid,
                              MavAutopilot autopilot, MavType mavType) throws IOException {
        FakeVehicle vehicle = new FakeVehicle(gatewayHost, gatewayPort, sysid, compid, autopilot, mavType);
        vehicle.openLink();
        vehicle.heartbeatThread = new Thread(vehicle::heartbeatLoop, "fake-vehicle-heartbeat-" + sysid);
        vehicle.heartbeatThread.setDaemon(true);
        vehicle.heartbeatThread.start();
        return vehicle;
    }

    public PeerId id() {
        return new PeerId(sysid, compid);
    }

    private void openLink() throws IOException {
        UdpTargetLink newLink = new UdpTargetLink(gatewayHost, gatewayPort);
        FrameWriter newWriter = new FrameWriter(sysid, compid);
        newWriter.addLink(newLink);
        LinkBundle bundle = new LinkBundle(newLink, newWriter, new FrameReader(newLink));
        this.current = bundle;
        Thread newReaderThread = new Thread(() -> readerLoop(bundle), "fake-vehicle-reader-" + sysid.value());
        newReaderThread.setDaemon(true);
        this.readerThread = newReaderThread;
        newReaderThread.start();
    }

    /**
     * Simulates this vehicle's transport address changing (a re-elected claim, a NAT rebind): closes
     * the current link and opens a fresh one to the same gateway, under the identical MAVLink
     * identity (sysid/compid unchanged). Used to prove a service re-resolves a peer's address on
     * every tick rather than caching it at engage-time.
     */
    public void relocate() throws IOException, InterruptedException {
        LinkBundle old = this.current;
        Thread oldReaderThread = this.readerThread;
        openLink();
        old.link().close();
        oldReaderThread.join(Duration.ofSeconds(2).toMillis());
    }

    private void heartbeatLoop() {
        while (!stopped.get()) {
            try {
                sendHeartbeat(this.current);
                Thread.sleep(HEARTBEAT_PERIOD.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                // A stale link mid-relocate, or the link closing -- skip this one heartbeat and
                // keep the thread alive; the next iteration reads a fresh `current` bundle.
            }
        }
    }

    private void sendHeartbeat(LinkBundle bundle) {
        Heartbeat heartbeat = Heartbeat.builder()
                .type(mavType)
                .autopilot(autopilot)
                .baseMode(MavModeFlag.MAV_MODE_FLAG_SAFETY_ARMED, MavModeFlag.MAV_MODE_FLAG_CUSTOM_MODE_ENABLED)
                .customMode(0)
                .systemStatus(MavState.MAV_STATE_ACTIVE)
                .mavlinkVersion(3)
                .build();
        bundle.writer().broadcast(heartbeat, bundle.link().id());
    }

    private void readerLoop(LinkBundle bundle) {
        while (!stopped.get() && this.current == bundle) {
            try {
                ByteChunk chunk = bundle.link().poll(POLL_TIMEOUT);
                if (chunk == null) {
                    continue;
                }
                bundle.reader().offer(chunk, received::add);
            } catch (IOException e) {
                return; // link closing -- stop quietly
            }
        }
    }

    /** Blocks (bounded by {@code timeout}) until a frame whose payload is a {@code T} arrives, skipping anything else. */
    public <T> T awaitFrame(Class<T> type, Duration timeout) throws InterruptedException {
        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        while (true) {
            long remainingMillis = (deadlineNanos - System.nanoTime()) / 1_000_000L;
            if (remainingMillis <= 0) {
                break;
            }
            MavFrame frame = received.poll(remainingMillis, TimeUnit.MILLISECONDS);
            if (frame != null && frame.is(type)) {
                return frame.as(type);
            }
        }
        throw new AssertionError("expected a " + type.getSimpleName() + " within " + timeout);
    }

    /** Like {@link #awaitFrame} but keeps polling until {@code chan1Raw} matches -- skips stale/earlier ticks. */
    public RcChannelsOverride awaitOverrideWhereChan1Is(int expectedChan1, Duration timeout) throws InterruptedException {
        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        while (true) {
            long remainingMillis = (deadlineNanos - System.nanoTime()) / 1_000_000L;
            if (remainingMillis <= 0) {
                break;
            }
            MavFrame frame = received.poll(remainingMillis, TimeUnit.MILLISECONDS);
            if (frame != null && frame.is(RcChannelsOverride.class)) {
                RcChannelsOverride override = frame.as(RcChannelsOverride.class);
                if (override.chan1Raw() == expectedChan1) {
                    return override;
                }
            }
        }
        throw new AssertionError("expected an RC_CHANNELS_OVERRIDE with chan1Raw=" + expectedChan1 + " within " + timeout);
    }

    /** Non-blocking-ish poll (bounded by {@code timeout}) for the next queued frame of any kind, or {@code null}. */
    public MavFrame pollAny(Duration timeout) throws InterruptedException {
        return received.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    public void clearReceived() {
        received.clear();
    }

    public void replyAck(MavCmd command, MavResult result) {
        LinkBundle bundle = this.current;
        CommandAck ack = CommandAck.builder().command(command).result(result).build();
        bundle.writer().broadcast(ack, bundle.link().id());
    }

    public void replyAckInProgress(MavCmd command, int progress) {
        LinkBundle bundle = this.current;
        CommandAck ack = CommandAck.builder().command(command).result(MavResult.MAV_RESULT_IN_PROGRESS).progress(progress).build();
        bundle.writer().broadcast(ack, bundle.link().id());
    }

    /**
     * Answers with a {@code PARAM_VALUE}. Takes the name verbatim rather than echoing whatever was
     * requested, so a test can make this vehicle answer a <i>different</i> parameter than the one
     * asked for — the case {@code ParameterService}'s exact-name verification exists for.
     */
    public void replyParamValue(String paramId, float value, MavParamType type, int index, int count) {
        LinkBundle bundle = this.current;
        ParamValue paramValue = ParamValue.builder()
                .paramId(paramId)
                .paramValue(value)
                .paramType(type)
                .paramIndex(index)
                .paramCount(count)
                .build();
        bundle.writer().broadcast(paramValue, bundle.link().id());
    }

    /** Answers with an {@code AUTOPILOT_VERSION}; {@code capabilityBits} is the raw bitmask, as the wire carries it. */
    public void replyAutopilotVersion(long flightSwVersion, int capabilityBits, long boardVersion,
                                      int vendorId, int productId) {
        LinkBundle bundle = this.current;
        AutopilotVersion version = AutopilotVersion.builder()
                .capabilities(EnumValue.create(capabilityBits))
                .flightSwVersion(flightSwVersion)
                .boardVersion(boardVersion)
                .vendorId(vendorId)
                .productId(productId)
                .build();
        bundle.writer().broadcast(version, bundle.link().id());
    }

    @Override
    public void close() {
        stopped.set(true);
        current.link().close();
        try {
            if (heartbeatThread != null) {
                heartbeatThread.join(Duration.ofSeconds(2).toMillis());
            }
            if (readerThread != null) {
                readerThread.join(Duration.ofSeconds(2).toMillis());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
