package com.drones.vision.api.dto;

import com.drones.vision.flight.domain.model.ControlBinding;
import com.drones.vision.flight.domain.model.ControlFunction;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import com.drones.vision.api.ws.ManualControlWebSocketHandler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * (De)serialization of the {@code /ws/manual-control} frame DTOs against the exact JSON shapes
 * docs/plans/done/RC-CONTROL-PHASE1-PLAN.md §4 freezes. {@link com.drones.vision.api.ws.ManualControlWebSocketHandler}
 * itself parses inbound frames via a raw {@code JsonNode} tree rather than these typed request
 * records (a discriminated union needs the {@code "type"} field read before the concrete shape is
 * known — see that class's own javadoc), so {@link ManualControlEngageRequest}/{@link
 * ManualControlChannelsRequest} are exercised directly here instead.
 */
class ManualControlFrameDtoTest {

    private static final JsonMapper JSON = new JsonMapper();

    @Test
    void engageRequestDeserializesFromTheFrozenShape() {
        ManualControlEngageRequest request = JSON.readValue(
                "{\"type\":\"engage\",\"assetId\":\"11111111-1111-1111-1111-111111111111\"}",
                ManualControlEngageRequest.class);

        assertEquals("engage", request.type());
        assertEquals("11111111-1111-1111-1111-111111111111", request.assetId());
    }

    @Test
    void channelsRequestDeserializesFromTheFrozenShapeIncludingSeqAndTSent() {
        ManualControlChannelsRequest request = JSON.readValue(
                "{\"type\":\"channels\",\"axes\":[0.0,-0.12,1.0,0.0],\"buttons\":[0.0,1.0],"
                        + "\"seq\":42,\"tSent\":1738300000123}",
                ManualControlChannelsRequest.class);

        assertEquals("channels", request.type());
        assertEquals(List.of(0.0, -0.12, 1.0, 0.0), request.axes());
        assertEquals(List.of(0.0, 1.0), request.buttons());
        assertEquals(42L, request.seq());
        assertEquals(1738300000123L, request.tSent());
    }

    @Test
    void channelsRequestDefaultsMissingAxesAndButtonsToEmptyLists() {
        ManualControlChannelsRequest request =
                JSON.readValue("{\"type\":\"channels\",\"seq\":1,\"tSent\":1}", ManualControlChannelsRequest.class);

        assertTrue(request.axes().isEmpty());
        assertTrue(request.buttons().isEmpty());
    }

    @Test
    void engagedFrameSerializesWithTheFixedTypeLiteralAndItsChannelMap() {
        ManualControlEngagedFrame frame = new ManualControlEngagedFrame("11111111-1111-1111-1111-111111111111",
                33, "COPTER", "AETR", "Multirotor",
                List.of(ManualControlChannelBindingResponse.from(
                        ControlBinding.centeredAxis(ControlFunction.ROLL, 0, 1))));

        String json = JSON.writeValueAsString(frame);

        assertTrue(json.contains("\"type\":\"engaged\""));
        assertTrue(json.contains("\"rateHz\":33"));
        assertTrue(json.contains("\"label\":\"Roll\""));
        assertTrue(json.contains("\"vehicleKind\":\"COPTER\""));
        assertTrue(json.contains("\"profileCode\":\"AETR\""));
    }

    /** The one field a client cannot render an honest throttle without (VEHICLE-CONTROL-PROFILES §2 P3). */
    @Test
    void aChannelMapEntryCarriesItsFunctionAndItsTravel() {
        String copterThrottle = JSON.writeValueAsString(ManualControlChannelBindingResponse.from(
                ControlBinding.unidirectionalAxis(ControlFunction.THROTTLE, 2, 3)));
        String roverThrottle = JSON.writeValueAsString(ManualControlChannelBindingResponse.from(
                ControlBinding.centeredAxis(ControlFunction.THROTTLE, 2, 3)));

        assertTrue(copterThrottle.contains("\"function\":\"THROTTLE\""));
        assertTrue(copterThrottle.contains("\"travel\":\"UNIDIRECTIONAL\""));
        assertTrue(copterThrottle.contains("\"centerMicros\":1000"));

        assertTrue(roverThrottle.contains("\"travel\":\"CENTERED\""));
        assertTrue(roverThrottle.contains("\"centerMicros\":1500"));
    }

    @Test
    void deniedFrameSerializesWithTheFixedTypeLiteral() {
        String json = JSON.writeValueAsString(new ManualControlDeniedFrame("OUT_OF_SCOPE", "nope"));

        assertTrue(json.contains("\"type\":\"denied\""));
        assertTrue(json.contains("\"code\":\"OUT_OF_SCOPE\""));
    }

    @Test
    void ackFrameSerializesWithTheFixedTypeLiteral() {
        String json = JSON.writeValueAsString(new ManualControlAckFrame(1L, 2L, 3L));

        assertTrue(json.contains("\"type\":\"ack\""));
    }

    @Test
    void releasedFrameSerializesWithTheFixedTypeLiteralAndBothFrozenReasons() {
        assertTrue(JSON.writeValueAsString(new ManualControlReleasedFrame(ManualControlReleasedFrame.REASON_EXPLICIT))
                .contains("\"reason\":\"EXPLICIT\""));
        assertTrue(
                JSON.writeValueAsString(new ManualControlReleasedFrame(ManualControlReleasedFrame.REASON_SOCKET_CLOSE))
                        .contains("\"reason\":\"SOCKET_CLOSE\""));
    }

    @Test
    void watchdogFrameSerializesWithTheFixedTypeLiteral() {
        String json = JSON.writeValueAsString(new ManualControlWatchdogFrame(300L));

        assertTrue(json.contains("\"type\":\"watchdog\""));
        assertTrue(json.contains("\"timeoutMs\":300"));
    }
}
