package com.drones.mavlink.codec;

import com.drones.mavlink.CompId;
import com.drones.mavlink.PeerId;
import com.drones.mavlink.SysId;
import com.drones.mavlink.transport.ByteChunk;
import com.drones.mavlink.transport.LinkPeer;

import io.dronefleet.mavlink.common.CommandLong;
import io.dronefleet.mavlink.common.MavCmd;
import io.dronefleet.mavlink.minimal.Heartbeat;
import io.dronefleet.mavlink.minimal.MavAutopilot;
import io.dronefleet.mavlink.minimal.MavModeFlag;
import io.dronefleet.mavlink.minimal.MavState;
import io.dronefleet.mavlink.minimal.MavType;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Golden-bytes acceptance gate (docs/plans/active/MAVLINK-CORE-PLAN.md W1 exit criteria): encode
 * through {@link FrameWriter}, decode through {@link FrameReader}, and prove header/payload
 * round-trip exactly. Also proves resync still finds a valid frame after a garbage prefix, and
 * that {@link FrameWriter}'s sequence allocation wraps at 255.
 */
class FrameWriterFrameReaderRoundTripTest {

    private static final LinkPeer TARGET = new LinkPeer("127.0.0.1", 14_550);
    private static final LinkPeer SOURCE = new LinkPeer("127.0.0.1", 55_555);

    private final RecordingLink link = new RecordingLink("golden-bytes-link", true, TARGET);
    private final FrameWriter writer = new FrameWriter(new SysId(1), new CompId(1));
    private final FrameReader reader = new FrameReader(link);

    FrameWriterFrameReaderRoundTripTest() {
        writer.addLink(link);
    }

    @Test
    void heartbeatRoundTripsExactly() {
        Heartbeat heartbeat = Heartbeat.builder()
                .type(MavType.MAV_TYPE_QUADROTOR)
                .autopilot(MavAutopilot.MAV_AUTOPILOT_ARDUPILOTMEGA)
                .baseMode(MavModeFlag.MAV_MODE_FLAG_SAFETY_ARMED, MavModeFlag.MAV_MODE_FLAG_CUSTOM_MODE_ENABLED)
                .customMode(5)
                .systemStatus(MavState.MAV_STATE_ACTIVE)
                .mavlinkVersion(3)
                .build();

        writer.send(heartbeat, new PeerId(new SysId(1), new CompId(1)));
        List<MavFrame> frames = decode(onlySentFrame());

        assertEquals(1, frames.size());
        MavFrame frame = frames.get(0);
        assertEquals(2, frame.header().version());
        assertEquals(0, frame.header().sequence());
        assertEquals(1, frame.header().system().value());
        assertEquals(1, frame.header().component().value());
        assertEquals(0, frame.header().messageId()); // HEARTBEAT wire id
        assertFalse(frame.header().signed());
        assertTrue(frame.is(Heartbeat.class));

        Heartbeat decoded = frame.as(Heartbeat.class);
        assertEquals(MavType.MAV_TYPE_QUADROTOR, decoded.type().entry());
        assertEquals(MavAutopilot.MAV_AUTOPILOT_ARDUPILOTMEGA, decoded.autopilot().entry());
        assertEquals(5L, decoded.customMode());
        assertEquals(MavState.MAV_STATE_ACTIVE, decoded.systemStatus().entry());
        assertEquals(3, decoded.mavlinkVersion());
    }

    @Test
    void commandLongRoundTripsExactly() {
        CommandLong command = CommandLong.builder()
                .targetSystem(7)
                .targetComponent(1)
                .command(MavCmd.MAV_CMD_DO_SET_MODE)
                .confirmation(1)
                .param1(1.0f)
                .param2(6.0f)
                .build();

        writer.send(command, new PeerId(new SysId(7), new CompId(1)));
        List<MavFrame> frames = decode(onlySentFrame());

        assertEquals(1, frames.size());
        MavFrame frame = frames.get(0);
        assertTrue(frame.is(CommandLong.class));
        CommandLong decoded = frame.as(CommandLong.class);
        assertEquals(7, decoded.targetSystem());
        assertEquals(1, decoded.targetComponent());
        assertEquals(MavCmd.MAV_CMD_DO_SET_MODE, decoded.command().entry());
        assertEquals(1, decoded.confirmation());
        assertEquals(1.0f, decoded.param1());
        assertEquals(6.0f, decoded.param2());
    }

    @Test
    void resyncFindsTheValidFrameAfterAGarbagePrefix() {
        Heartbeat heartbeat = Heartbeat.builder()
                .type(MavType.MAV_TYPE_QUADROTOR)
                .autopilot(MavAutopilot.MAV_AUTOPILOT_GENERIC)
                .baseMode(MavModeFlag.MAV_MODE_FLAG_MANUAL_INPUT_ENABLED)
                .customMode(0)
                .systemStatus(MavState.MAV_STATE_STANDBY)
                .mavlinkVersion(3)
                .build();
        writer.send(heartbeat, new PeerId(new SysId(1), new CompId(1)));
        byte[] validFrame = onlySentFrame();

        byte[] garbage = {0x00, 0x11, 0x22, (byte) 0xAB, (byte) 0xCD, 0x05, 0x00, 0x01};
        ByteArrayOutputStream withPrefix = new ByteArrayOutputStream();
        withPrefix.writeBytes(garbage);
        withPrefix.writeBytes(validFrame);

        List<MavFrame> frames = decode(withPrefix.toByteArray());

        assertEquals(1, frames.size());
        assertTrue(frames.get(0).is(Heartbeat.class));
        Heartbeat decoded = frames.get(0).as(Heartbeat.class);
        assertEquals(MavState.MAV_STATE_STANDBY, decoded.systemStatus().entry());
    }

    @Test
    void sequenceAllocationWrapsAtTwoFiftyFive() {
        int total = 300;
        ByteArrayOutputStream concatenated = new ByteArrayOutputStream();
        for (int i = 0; i < total; i++) {
            writer.send(simpleHeartbeat(), new PeerId(new SysId(1), new CompId(1)));
        }
        for (byte[] frame : link.sent()) {
            concatenated.writeBytes(frame);
        }

        List<MavFrame> frames = decode(concatenated.toByteArray());
        assertEquals(total, frames.size());
        for (int i = 0; i < total; i++) {
            assertEquals(i % 256, frames.get(i).header().sequence(), "frame " + i + " sequence");
        }
    }

    private static Heartbeat simpleHeartbeat() {
        return Heartbeat.builder()
                .type(MavType.MAV_TYPE_QUADROTOR)
                .autopilot(MavAutopilot.MAV_AUTOPILOT_GENERIC)
                .baseMode(MavModeFlag.MAV_MODE_FLAG_MANUAL_INPUT_ENABLED)
                .customMode(0)
                .systemStatus(MavState.MAV_STATE_STANDBY)
                .mavlinkVersion(3)
                .build();
    }

    private byte[] onlySentFrame() {
        assertEquals(1, link.sent().size());
        return link.sent().get(link.sent().size() - 1);
    }

    private List<MavFrame> decode(byte[] bytes) {
        List<MavFrame> frames = new ArrayList<>();
        ByteChunk chunk = new ByteChunk(bytes, bytes.length, SOURCE, Instant.now());
        reader.offer(chunk, frames::add);
        return frames;
    }
}
