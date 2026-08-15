package com.drones.mavlink.codec;

import com.drones.mavlink.CompId;
import com.drones.mavlink.PeerId;
import com.drones.mavlink.SysId;
import com.drones.mavlink.transport.ByteChunk;
import com.drones.mavlink.transport.LinkPeer;

import io.dronefleet.mavlink.minimal.Heartbeat;
import io.dronefleet.mavlink.minimal.MavAutopilot;
import io.dronefleet.mavlink.minimal.MavModeFlag;
import io.dronefleet.mavlink.minimal.MavState;
import io.dronefleet.mavlink.minimal.MavType;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The LSP guard named in docs/plans/active/MAVLINK-CORE-PLAN.md's W1 exit criteria:
 * {@link MavlinkLink#preservesMessageBoundaries() MavlinkLink.preservesMessageBoundaries()}
 * {@code == false} must mean "a single {@link com.drones.mavlink.transport.ByteChunk} may hold
 * part of a frame, all of a frame, or several frames" — never "one poll equals one message." A
 * decoder that only knows how to treat one datagram as one frame (a "datagram-shaped decoder")
 * would either drop a frame split across chunks or fail to find the second frame packed into one
 * chunk; {@link FrameReader}, driven with {@code preservesMessageBoundaries() == false}, must do
 * neither.
 */
class TcpFramingLspGuardTest {

    private static final LinkPeer TARGET = new LinkPeer("127.0.0.1", 5760);
    private static final LinkPeer REMOTE = new LinkPeer("127.0.0.1", 44_444);

    @Test
    void aFrameSplitAcrossThreeChunksStillProducesExactlyOneFrame() {
        byte[] frame = encodeOneHeartbeat(5);
        int third = frame.length / 3;
        byte[] part1 = Arrays.copyOfRange(frame, 0, third);
        byte[] part2 = Arrays.copyOfRange(frame, third, 2 * third);
        byte[] part3 = Arrays.copyOfRange(frame, 2 * third, frame.length);

        RecordingLink streamLink = new RecordingLink("tcp-client:127.0.0.1:5760", false, TARGET);
        FrameReader reader = new FrameReader(streamLink);
        List<MavFrame> frames = new ArrayList<>();

        reader.offer(chunk(part1), frames::add);
        assertEquals(0, frames.size(), "no frame should be decodable from only the first third");
        reader.offer(chunk(part2), frames::add);
        assertEquals(0, frames.size(), "no frame should be decodable from two thirds");
        reader.offer(chunk(part3), frames::add);

        assertEquals(1, frames.size(), "the frame should complete the instant the final bytes arrive");
        assertTrue(frames.get(0).is(Heartbeat.class));
        assertEquals(5L, frames.get(0).as(Heartbeat.class).customMode());
    }

    @Test
    void twoFramesInOneChunkBothDecode() {
        byte[] frameA = encodeOneHeartbeat(1);
        byte[] frameB = encodeOneHeartbeat(2);
        ByteArrayOutputStream both = new ByteArrayOutputStream();
        both.writeBytes(frameA);
        both.writeBytes(frameB);

        RecordingLink streamLink = new RecordingLink("tcp-client:127.0.0.1:5760", false, TARGET);
        FrameReader reader = new FrameReader(streamLink);
        List<MavFrame> frames = new ArrayList<>();

        reader.offer(chunk(both.toByteArray()), frames::add);

        assertEquals(2, frames.size());
        assertEquals(1L, frames.get(0).as(Heartbeat.class).customMode());
        assertEquals(2L, frames.get(1).as(Heartbeat.class).customMode());
    }

    private static ByteChunk chunk(byte[] data) {
        return new ByteChunk(data, data.length, REMOTE, Instant.now());
    }

    /** Produces one real, wire-correct encoded HEARTBEAT frame via {@link FrameWriter} itself. */
    private static byte[] encodeOneHeartbeat(long customMode) {
        RecordingLink encoderLink = new RecordingLink("encoder-link", true, TARGET);
        FrameWriter writer = new FrameWriter(new SysId(1), new CompId(1));
        writer.addLink(encoderLink);
        Heartbeat heartbeat = Heartbeat.builder()
                .type(MavType.MAV_TYPE_QUADROTOR)
                .autopilot(MavAutopilot.MAV_AUTOPILOT_GENERIC)
                .baseMode(MavModeFlag.MAV_MODE_FLAG_MANUAL_INPUT_ENABLED)
                .customMode(customMode)
                .systemStatus(MavState.MAV_STATE_STANDBY)
                .mavlinkVersion(3)
                .build();
        writer.send(heartbeat, new PeerId(new SysId(1), new CompId(1)));
        return encoderLink.sent().get(0);
    }
}
