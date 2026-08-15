package com.drones.mavlink.codec;

import com.drones.mavlink.CompId;
import com.drones.mavlink.PeerId;
import com.drones.mavlink.SysId;
import com.drones.mavlink.transport.ByteChunk;
import com.drones.mavlink.transport.LinkPeer;

import io.dronefleet.mavlink.ardupilotmega.Wind;
import io.dronefleet.mavlink.minimal.Heartbeat;
import io.dronefleet.mavlink.minimal.MavAutopilot;
import io.dronefleet.mavlink.minimal.MavModeFlag;
import io.dronefleet.mavlink.minimal.MavState;
import io.dronefleet.mavlink.minimal.MavType;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A vehicle's MAVLink dialect belongs to the <b>vehicle</b>, not to the socket address its datagrams
 * happen to arrive from — the same {@code (sysid, compid)}-not-transport-address identity rule the
 * rest of this module follows.
 *
 * <p>Regression guard for a real defect found in W4: {@link ResyncBuffer} originally remembered the
 * learned dialect per resync buffer, i.e. per source address. An ArduPilot vehicle's own
 * {@code HEARTBEAT} therefore unlocked ardupilotmega-only messages ({@code WIND},
 * {@code EKF_STATUS_REPORT}, {@code RANGEFINDER}) for that one source address only — so the same
 * vehicle relayed through a second address, which is what a companion computer, a router or a plain
 * NAT rebind produces, silently fell back to {@code CommonDialect} and could no longer decode them.
 * The pre-W4 adapter never had this bug, because its single long-lived {@code MavlinkConnection}
 * per socket carried the library's own per-system dialect cache.
 */
class DialectIsLearnedPerSystemNotPerSourceTest {

    private static final LinkPeer TARGET = new LinkPeer("127.0.0.1", 14_550);
    private static final LinkPeer VEHICLE_DIRECT = new LinkPeer("10.0.0.1", 14_550);
    private static final LinkPeer VIA_RELAY = new LinkPeer("10.0.0.9", 14_550);
    private static final SysId VEHICLE = new SysId(7);

    @Test
    void anArdupilotmegaOnlyMessageDecodesFromASecondSourceAddressOfTheSameSystem() {
        RecordingLink listenLink = new RecordingLink("udp-listen:0.0.0.0:14550", true, TARGET);
        FrameReader reader = new FrameReader(listenLink);
        List<MavFrame> frames = new ArrayList<>();

        // The vehicle's own ArduPilot HEARTBEAT arrives directly — this is what teaches the dialect.
        reader.offer(chunk(encodeArdupilotHeartbeat(), VEHICLE_DIRECT), frames::add);
        assertEquals(1, frames.size(), "the heartbeat itself must decode");

        // The SAME system id, but relayed through a different source address. Before the fix this
        // fell back to CommonDialect and WIND (an ardupilotmega-only message) never resolved.
        reader.offer(chunk(encodeWind(), VIA_RELAY), frames::add);

        assertEquals(2, frames.size(),
                "an ardupilotmega-only message from the same system must still decode when it "
                        + "arrives from a different source address");
        MavFrame windFrame = frames.get(1);
        assertTrue(windFrame.is(Wind.class),
                "expected a WIND payload, got " + windFrame.payload().getClass().getName());
        assertEquals(VEHICLE.value(), windFrame.header().system().value());
        assertEquals(12.5f, windFrame.as(Wind.class).speed(), 0.001f);
        assertEquals(VIA_RELAY, windFrame.source());
    }

    private static ByteChunk chunk(byte[] data, LinkPeer source) {
        return new ByteChunk(data, data.length, source, Instant.now());
    }

    private static byte[] encodeArdupilotHeartbeat() {
        return encode(Heartbeat.builder()
                .type(MavType.MAV_TYPE_QUADROTOR)
                .autopilot(MavAutopilot.MAV_AUTOPILOT_ARDUPILOTMEGA)
                .baseMode(MavModeFlag.MAV_MODE_FLAG_MANUAL_INPUT_ENABLED)
                .customMode(0)
                .systemStatus(MavState.MAV_STATE_ACTIVE)
                .mavlinkVersion(3)
                .build());
    }

    private static byte[] encodeWind() {
        return encode(Wind.builder().direction(180.0f).speed(12.5f).speedZ(0.5f).build());
    }

    private static byte[] encode(Object payload) {
        RecordingLink encoderLink = new RecordingLink("encoder-link", true, TARGET);
        FrameWriter writer = new FrameWriter(VEHICLE, new CompId(1));
        writer.addLink(encoderLink);
        writer.send(payload, new PeerId(VEHICLE, new CompId(1)));
        return encoderLink.sent().get(0);
    }
}
