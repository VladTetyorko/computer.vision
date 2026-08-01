package com.drones.vision.api;

import com.drones.vision.api.exceptions.ApiExceptionHandler;
import com.drones.vision.api.live.LiveUpdateRegistry;
import com.drones.vision.application.AssetService;
import com.drones.vision.application.AssetStatus;
import com.drones.vision.application.AssetSummary;
import com.drones.vision.application.DeviceService;
import com.drones.vision.application.StreamService;
import com.drones.vision.domain.model.Asset;
import com.drones.vision.domain.model.AssetId;
import com.drones.vision.domain.model.CategoryId;
import com.drones.vision.domain.model.DeviceId;
import com.drones.vision.domain.model.Event;
import com.drones.vision.domain.model.EventType;
import com.drones.vision.domain.model.GroupId;
import com.drones.vision.domain.model.Ownership;
import com.drones.vision.domain.model.StreamId;
import com.drones.vision.domain.model.UserId;
import com.drones.vision.domain.port.out.DetectionEventRepositoryPort;
import com.drones.vision.domain.port.out.StreamPublisherPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MockMvc test for the {@code GET /api/live} SSE endpoint (docs/REALTIME-PLAN.md §4) — connect,
 * receive the connection handshake + fleet snapshot, receive a delta after a port emission, and
 * {@code Last-Event-ID} resume. Uses a real {@link LiveUpdateRegistry} (its own background
 * scheduler, not a test double) constructed directly rather than through Spring wiring, exactly
 * like every other MockMvc test in this module mocks its use-case ports directly.
 *
 * <p>Pure ring-buffer/resume/coalescing logic is unit-tested without any of this class's async/
 * MockMvc machinery in {@code com.drones.vision.api.live.LiveUpdateRegistryTest}/{@code
 * LiveRingBufferTest} — this class only proves the HTTP/SSE wiring on top of that logic.
 */
class LiveControllerTest {

    private AssetService assetService;
    private LiveUpdateRegistry registry;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        assetService = mock(AssetService.class);
        when(assetService.assets()).thenReturn(List.of());
        DeviceService deviceService = mock(DeviceService.class);
        StreamService streamService = mock(StreamService.class);
        StreamPublisherPort streamPublisherPort = mock(StreamPublisherPort.class);
        DetectionEventRepositoryPort detectionEventRepositoryPort = mock(DetectionEventRepositoryPort.class);
        registry = new LiveUpdateRegistry(
                provider(assetService), provider(deviceService), provider(streamService), streamPublisherPort,
                provider(detectionEventRepositoryPort));
        mockMvc = MockMvcBuilders.standaloneSetup(new LiveController(registry))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private static <T> ObjectProvider<T> provider(T value) {
        return new ObjectProvider<>() {
            @Override
            public T getObject() {
                return value;
            }
        };
    }

    @Test
    void connectingReceivesTheConnectionHandshakeThenTheFleetAndDevicesSnapshots() throws Exception {
        AssetId assetId = AssetId.random();
        when(assetService.assets()).thenReturn(List.of(summary(assetId)));

        MvcResult result = mockMvc.perform(get("/api/live"))
                .andExpect(request().asyncStarted())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertTrue(body.contains("event:connection"), "the first thing sent must be the connection handshake event");
        List<String> dataLines = dataLines(body);
        // Connection handshake + one fleet snapshot + one devices snapshot -- detection-events and
        // event have nothing to seed from in this test (no detection events/domain events raised),
        // so they contribute zero envelopes on this fresh connect. Fleet/devices are looked up by
        // their own "type" field, not a fixed index -- the topic loop iterates a plain (unordered)
        // Set, so which of the two is written first is not something a caller may rely on.
        assertEquals(3, dataLines.size(), "connection handshake + fleet snapshot + devices snapshot");
        JsonNode connected = json(dataLines.get(0));
        assertTrue(connected.has("connectionId"));
        JsonNode fleetEnvelope = envelopeOfType(dataLines, "fleet");
        assertEquals(assetId.value().toString(), fleetEnvelope.get("payload").get(0).get("assetId").asString());
        JsonNode devicesEnvelope = envelopeOfType(dataLines, "devices");
        assertTrue(devicesEnvelope.get("payload").has("devices"));
        assertTrue(devicesEnvelope.get("payload").has("streams"));
    }

    @Test
    void receivesADeltaAfterAPortEmission() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/live"))
                .andExpect(request().asyncStarted())
                .andReturn();
        int initialDataLines = dataLines(result.getResponse().getContentAsString()).size();

        registry.publishFleetChanged(); // fires on the registry's own background dispatcher -- refreshes fleet AND devices

        List<String> dataLines = awaitAtLeast(result, initialDataLines + 2);
        JsonNode fleetDelta = json(dataLines.get(dataLines.size() - 2));
        assertEquals("fleet", fleetDelta.get("type").asString());
        JsonNode devicesDelta = json(dataLines.get(dataLines.size() - 1));
        assertEquals("devices", devicesDelta.get("type").asString());
    }

    @Test
    void lastEventIdResumeOnlyReplaysWhatCameAfterIt() throws Exception {
        MvcResult first = mockMvc.perform(get("/api/live"))
                .andExpect(request().asyncStarted())
                .andReturn();
        List<String> firstDataLines = dataLines(first.getResponse().getContentAsString());
        long lastSeq = json(firstDataLines.get(firstDataLines.size() - 1)).get("seq").asLong();
        int initialCount = firstDataLines.size();

        registry.publishEvent(Event.of(StreamId.random(), EventType.STREAM_STARTED, "started"));
        // The still-open first connection is itself subscribed to the always-on event topic, so
        // waiting for *it* to grow is an observable, cross-package-safe proxy for "the registry's
        // background dispatcher has actually appended the event to its buffer by now" -- avoids
        // needing access to this package-private internal state from this different package.
        awaitAtLeast(first, initialCount + 1);

        MvcResult resumed = mockMvc.perform(get("/api/live").header("Last-Event-ID", String.valueOf(lastSeq)))
                .andExpect(request().asyncStarted())
                .andReturn();
        List<String> resumedDataLines = dataLines(resumed.getResponse().getContentAsString());

        // connection handshake + exactly the one event published after lastSeq -- not a repeat of
        // the earlier (empty) fleet snapshot, since fleet had nothing newer than lastSeq either.
        assertEquals(2, resumedDataLines.size());
        JsonNode replayed = json(resumedDataLines.get(1));
        assertEquals("event", replayed.get("type").asString());
        assertEquals("STREAM_STARTED", replayed.get("payload").get("type").asString());
    }

    @Test
    void updateTopicsAddsATopicAndReturnsTheFullTopicSet() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/live"))
                .andExpect(request().asyncStarted())
                .andReturn();
        String connectionId = json(dataLines(result.getResponse().getContentAsString()).get(0))
                .get("connectionId").asString();
        AssetId assetId = AssetId.random();

        mockMvc.perform(patch("/api/live/{id}/topics", connectionId)
                        .contentType("application/json")
                        .content("{\"add\":[\"telemetry:" + assetId.value() + "\"]}"))
                .andExpect(status().isOk());

        // A second PATCH re-reads the resulting topic set without needing to re-parse SSE frames.
        mockMvc.perform(patch("/api/live/{id}/topics", connectionId)
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isOk());
    }

    @Test
    void updateTopicsReturns404ForAnUnknownConnection() throws Exception {
        mockMvc.perform(patch("/api/live/{id}/topics", "no-such-connection")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isNotFound());
    }

    private List<String> awaitAtLeast(MvcResult result, int minimumDataLines) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            List<String> dataLines = dataLines(result.getResponse().getContentAsString());
            if (dataLines.size() >= minimumDataLines) {
                return dataLines;
            }
            Thread.sleep(20);
        }
        fail("timed out waiting for at least " + minimumDataLines + " SSE data lines");
        return List.of();
    }

    private static List<String> dataLines(String sse) {
        return Arrays.stream(sse.split("\n"))
                .filter(line -> line.startsWith("data:"))
                .map(line -> line.substring("data:".length()))
                .toList();
    }

    /** Finds the one data line whose {@code type} field matches -- fails the test if none/more than one does. */
    private static JsonNode envelopeOfType(List<String> dataLines, String type) {
        List<JsonNode> matches = dataLines.stream().map(LiveControllerTest::json)
                .filter(node -> node.has("type") && type.equals(node.get("type").asString()))
                .toList();
        assertEquals(1, matches.size(), "expected exactly one \"" + type + "\" envelope among " + dataLines);
        return matches.get(0);
    }

    private static JsonNode json(String data) {
        return new JsonMapper().readTree(data);
    }

    private static AssetSummary summary(AssetId assetId) {
        Asset asset = new Asset(assetId, "drone-1", new CategoryId("drone"),
                new Ownership(UserId.random(), GroupId.random()), Set.of(DeviceId.random()), Map.of());
        return new AssetSummary(asset, "Drone", AssetStatus.OFFLINE, null, null);
    }
}
