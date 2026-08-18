package com.drones.mavlink.session;

import com.drones.mavlink.CompId;
import com.drones.mavlink.SysId;
import com.drones.mavlink.codec.MavFrame;
import com.drones.mavlink.codec.MavHeader;
import com.drones.mavlink.transport.LinkId;
import com.drones.mavlink.transport.LinkPeer;

import io.dronefleet.mavlink.common.AutopilotVersion;
import io.dronefleet.mavlink.common.CommandAck;
import io.dronefleet.mavlink.common.MavCmd;
import io.dronefleet.mavlink.common.MavParamType;
import io.dronefleet.mavlink.common.MavResult;
import io.dronefleet.mavlink.common.MissionAck;
import io.dronefleet.mavlink.common.MissionCount;
import io.dronefleet.mavlink.common.MissionRequest;
import io.dronefleet.mavlink.common.MissionRequestInt;
import io.dronefleet.mavlink.common.ParamValue;
import io.dronefleet.mavlink.minimal.Heartbeat;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The D6 key table, tested directly rather than only through a live correlator — the whole risk this
 * table carries is that the request side and the reply side compute a different key for the same
 * exchange, which shows up as a silent hang, never as an assertion failure somewhere else. Every
 * test here therefore pairs a named factory against {@link CorrelationKeys#keyFor} on a frame.
 */
class CorrelationKeysTest {

    private static final SysId VEHICLE = new SysId(42);
    private static final SysId OTHER_VEHICLE = new SysId(43);

    @Test
    void commandAckKeyIsUnchangedFromTheSingleBranchItReplaced() {
        MavFrame frame = frameFrom(VEHICLE, CommandAck.builder()
                .command(MavCmd.MAV_CMD_COMPONENT_ARM_DISARM)
                .result(MavResult.MAV_RESULT_ACCEPTED)
                .build());

        assertEquals(CorrelationKeys.forCommandAck(VEHICLE, 400), CorrelationKeys.keyFor(frame));
        assertEquals(77, CorrelationKeys.keyFor(frame).messageId());
    }

    @Test
    void paramValueRoutesToTheSameKeyTheRequesterAwaitsUnder() {
        MavFrame frame = frameFrom(VEHICLE, paramValue("SR2_EXTRA2", 5f));

        assertEquals(CorrelationKeys.forParamValue(VEHICLE, "SR2_EXTRA2"), CorrelationKeys.keyFor(frame));
    }

    @Test
    void paramValueKeyIsPerVehicleAndPerName() {
        MavFrame frame = frameFrom(VEHICLE, paramValue("SR2_EXTRA2", 5f));

        assertNotEquals(CorrelationKeys.forParamValue(OTHER_VEHICLE, "SR2_EXTRA2"), CorrelationKeys.keyFor(frame));
        assertNotEquals(CorrelationKeys.forParamValue(VEHICLE, "SR2_EXTRA1"), CorrelationKeys.keyFor(frame));
    }

    /** The wire pads {@code param_id} to 16 chars with NULs; a name must survive the round trip. */
    @Test
    void aNulPaddedNameOffTheWireMatchesTheUnpaddedNameTheRequesterUsed() {
        MavFrame frame = frameFrom(VEHICLE, paramValue("BATT_CAPACITY\0\0\0", 5000f));

        assertEquals(CorrelationKeys.forParamValue(VEHICLE, "BATT_CAPACITY"), CorrelationKeys.keyFor(frame));
        assertEquals("BATT_CAPACITY", CorrelationKeys.normalizeParamId("BATT_CAPACITY\0\0\0"));
    }

    @Test
    void aNameLongerThanSixteenCharactersIsTruncatedTheWayTheWireWouldTruncateIt() {
        assertEquals("ABCDEFGHIJKLMNOP", CorrelationKeys.normalizeParamId("ABCDEFGHIJKLMNOPQRS"));
    }

    @Test
    void autopilotVersionNeedsNoDiscriminatorBecauseThereIsOnlyOnePerVehicle() {
        MavFrame frame = frameFrom(VEHICLE, AutopilotVersion.builder().flightSwVersion(0x04050700L).build());

        assertEquals(CorrelationKeys.forAutopilotVersion(VEHICLE), CorrelationKeys.keyFor(frame));
        assertEquals(CorrelationKeys.NO_DISCRIMINATOR, CorrelationKeys.keyFor(frame).discriminator());
    }

    /**
     * MISSIONS-PLAN D14: this table is landed once, in full, by whichever wave arrives first, and the
     * other consumes it unchanged. These four rows exist for `MissionService` and are asserted here
     * so that wave inherits a tested seam rather than a promise.
     */
    @Test
    void missionTransferTypesCarryTheItemSeqWhereTheProtocolMatchesOnIt() {
        MavFrame request = frameFrom(VEHICLE, MissionRequest.builder().seq(7).build());
        MavFrame requestInt = frameFrom(VEHICLE, MissionRequestInt.builder().seq(7).build());

        assertEquals(40, CorrelationKeys.keyFor(request).messageId());
        assertEquals(7, CorrelationKeys.keyFor(request).discriminator());
        assertEquals(51, CorrelationKeys.keyFor(requestInt).messageId());
        assertEquals(7, CorrelationKeys.keyFor(requestInt).discriminator());
        assertNotEquals(CorrelationKeys.keyFor(request), CorrelationKeys.keyFor(requestInt));
    }

    @Test
    void missionCountAndAckAreOncePerTransferSoTheyCarryNoDiscriminator() {
        MavFrame count = frameFrom(VEHICLE, MissionCount.builder().count(12).build());
        MavFrame ack = frameFrom(VEHICLE, MissionAck.builder().build());

        assertEquals(44, CorrelationKeys.keyFor(count).messageId());
        assertEquals(CorrelationKeys.NO_DISCRIMINATOR, CorrelationKeys.keyFor(count).discriminator());
        assertEquals(47, CorrelationKeys.keyFor(ack).messageId());
        assertEquals(CorrelationKeys.NO_DISCRIMINATOR, CorrelationKeys.keyFor(ack).discriminator());
    }

    /** Telemetry is not a reply: a stream must reach subscribers via `Dispatcher` and never occupy a waiter. */
    @Test
    void anUncorrelatedMessageTypeYieldsNoKeyAtAll() {
        assertNull(CorrelationKeys.keyFor(frameFrom(VEHICLE, Heartbeat.builder().build())));
    }

    private static ParamValue paramValue(String name, float value) {
        return ParamValue.builder()
                .paramId(name)
                .paramValue(value)
                .paramType(MavParamType.MAV_PARAM_TYPE_INT8)
                .paramIndex(1)
                .paramCount(2)
                .build();
    }

    private static MavFrame frameFrom(SysId system, Object payload) {
        return new MavFrame(new MavHeader(2, 0, system, new CompId(1), 0, 0, 0, false), payload,
                new LinkId("test"), new LinkPeer("127.0.0.1", 14550), Instant.EPOCH);
    }
}
