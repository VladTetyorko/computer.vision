package com.drones.vision.api.controller;

import com.drones.vision.api.exception.ApiExceptionHandler;
import com.drones.vision.api.live.LiveUpdateRegistry;
import com.drones.vision.api.live.MapVisibility;
import com.drones.vision.api.security.CurrentUser;
import com.drones.vision.application.asset.AssetService;
import com.drones.vision.application.device.DeviceService;
import com.drones.vision.application.map.LayerSpec;
import com.drones.vision.application.map.LayerView;
import com.drones.vision.application.map.MapAccessPolicy.Viewer;
import com.drones.vision.application.map.MapLayerService;
import com.drones.vision.application.stream.StreamService;
import com.drones.vision.domain.model.AccessLevel;
import com.drones.vision.domain.model.Affiliation;
import com.drones.vision.domain.model.GeoPosition;
import com.drones.vision.domain.model.GroupId;
import com.drones.vision.domain.model.LayerGrant;
import com.drones.vision.domain.model.LayerId;
import com.drones.vision.domain.model.LayerKind;
import com.drones.vision.domain.model.MapEvent;
import com.drones.vision.domain.model.MapLayer;
import com.drones.vision.domain.model.Mark;
import com.drones.vision.domain.model.MarkId;
import com.drones.vision.domain.model.MarkKind;
import com.drones.vision.domain.model.MarkSource;
import com.drones.vision.domain.model.MarkStatus;
import com.drones.vision.domain.model.Ownership;
import com.drones.vision.domain.model.UserId;
import com.drones.vision.domain.model.Verification;
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

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;

/**
 * The security-critical half of docs/plans/done/MAP-REWORK-PLAN.md §4.3, end to end over real SSE frames: two
 * open {@code /api/live} connections belonging to <em>different</em> viewers must receive different
 * {@code map} events, decided server-side from each connection's own viewer.
 *
 * <p>Deliberately a separate class from {@link LiveControllerTest}: that one proves the SSE
 * transport (handshake, snapshot, resume, topic PATCH) with a single connection, and this one proves
 * the per-connection filter on top of it — two concerns, two failure stories. Both use the real
 * {@link LiveUpdateRegistry} (real ring buffers, real background dispatcher) rather than a double,
 * since "the buffer is shared but delivery is not" is exactly the property under test.
 */
class LiveMapScopingTest {

    /** Alpha's team layer — visible to alpha only. */
    private final LayerId alphaLayer = LayerId.random();

    /** Bravo's team layer — visible to bravo only. */
    private final LayerId bravoLayer = LayerId.random();

    /** The COP layer — visible to everyone. */
    private final LayerId copLayer = LayerId.random();

    private LiveUpdateRegistry registry;
    private MockMvc alphaMvc;
    private MockMvc bravoMvc;

    private final CurrentUser alpha = new CurrentUser(new Ownership(UserId.random(), GroupId.random()));
    private final CurrentUser bravo = new CurrentUser(new Ownership(UserId.random(), GroupId.random()));

    @BeforeEach
    void setUp() {
        AssetService assetService = mock(AssetService.class);
        when(assetService.assets()).thenReturn(List.of());
        DeviceService deviceService = mock(DeviceService.class);
        when(deviceService.devices()).thenReturn(List.of());
        StreamService streamService = mock(StreamService.class);
        when(streamService.streams()).thenReturn(List.of());
        StreamPublisherPort streamPublisherPort = mock(StreamPublisherPort.class);
        DetectionEventRepositoryPort detectionEvents = mock(DetectionEventRepositoryPort.class);
        when(detectionEvents.findRecent(null, 300)).thenReturn(List.of());

        registry = new LiveUpdateRegistry(provider(assetService), provider(deviceService),
                provider(streamService), streamPublisherPort, provider(detectionEvents));

        Map<UserId, List<LayerId>> visibleByUser = new HashMap<>();
        visibleByUser.put(alpha.userId(), List.of(copLayer, alphaLayer));
        visibleByUser.put(bravo.userId(), List.of(copLayer, bravoLayer));
        MapVisibility visibility = new MapVisibility(new PerViewerLayerService(visibleByUser));

        alphaMvc = mvcFor(visibility, alpha);
        bravoMvc = mvcFor(visibility, bravo);
    }

    private MockMvc mvcFor(MapVisibility visibility, CurrentUser user) {
        return MockMvcBuilders.standaloneSetup(new LiveController(registry, visibility, user))
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

    private Mark markOn(LayerId layerId, String label) {
        return new Mark(MarkId.random(), layerId, new GeoPosition(50.45, 30.52, null), MarkKind.TARGET,
                Affiliation.HOSTILE, label, null, new Ownership(UserId.random(), GroupId.random()),
                Instant.now(), MarkStatus.ACTIVE, MarkSource.MANUAL, Verification.unverified());
    }

    @Test
    void aMarkOnOneTeamsLayerReachesThatTeamsConnectionOnly() throws Exception {
        MvcResult alphaStream = connect(alphaMvc);
        MvcResult bravoStream = connect(bravoMvc);
        int alphaBefore = dataLines(alphaStream).size();
        int bravoBefore = dataLines(bravoStream).size();

        Mark secret = markOn(alphaLayer, "Alpha only");
        registry.publishMapEvent(
                new MapEvent(MapEvent.EntityType.MARK, MapEvent.Action.CREATED, alphaLayer, secret));

        JsonNode delivered = awaitMapMark(alphaStream, "Alpha only");
        assertEquals("mark", delivered.get("payload").get("entity").asString());
        assertEquals("created", delivered.get("payload").get("action").asString());
        assertEquals(alphaLayer.value().toString(), delivered.get("payload").get("layerId").asString());

        // Bravo's stream must not have grown at all -- the event was filtered out on the way out,
        // not merely rendered differently.
        assertEquals(bravoBefore, dataLines(bravoStream).size(),
                "a mark on another team's layer must never reach this connection");
        assertFalse(bodyOf(bravoStream).contains("Alpha only"),
                "the label must not appear anywhere in the other viewer's stream");
        assertTrue(alphaBefore < dataLines(alphaStream).size(), "alpha's own stream did grow");
    }

    @Test
    void aMarkOnTheCopLayerReachesBothConnections() throws Exception {
        MvcResult alphaStream = connect(alphaMvc);
        MvcResult bravoStream = connect(bravoMvc);
        int alphaBefore = dataLines(alphaStream).size();
        int bravoBefore = dataLines(bravoStream).size();

        registry.publishMapEvent(new MapEvent(MapEvent.EntityType.MARK, MapEvent.Action.CREATED,
                copLayer, markOn(copLayer, "Shared picture")));

        // Selected by content, not by position: a connect burst writes one envelope per subscribed
        // topic and the topic set is unordered, so "the last data line" is not a stable handle.
        assertEquals(copLayer.value().toString(),
                awaitMapMark(alphaStream, "Shared picture").get("payload").get("layerId").asString());
        assertEquals(copLayer.value().toString(),
                awaitMapMark(bravoStream, "Shared picture").get("payload").get("layerId").asString());
        assertTrue(alphaBefore < dataLines(alphaStream).size());
        assertTrue(bravoBefore < dataLines(bravoStream).size());
    }

    @Test
    void everyConnectionStillReceivesTheUnscopedTopics() throws Exception {
        MvcResult alphaStream = connect(alphaMvc);
        MvcResult bravoStream = connect(bravoMvc);
        int alphaBefore = dataLines(alphaStream).size();
        int bravoBefore = dataLines(bravoStream).size();

        // fleet/devices are broadcast, not scoped -- the map filter must not leak onto them.
        registry.publishFleetChanged();

        assertTrue(awaitAtLeast(alphaStream, alphaBefore + 2).size() >= alphaBefore + 2);
        assertTrue(awaitAtLeast(bravoStream, bravoBefore + 2).size() >= bravoBefore + 2);
        assertTrue(bodyOf(alphaStream).contains("\"type\":\"fleet\""));
        assertTrue(bodyOf(bravoStream).contains("\"type\":\"fleet\""));
    }

    @Test
    void aLastEventIdResumeIsReFilteredForTheResumingViewer() throws Exception {
        MvcResult alphaStream = connect(alphaMvc);
        int alphaBefore = dataLines(alphaStream).size();

        registry.publishMapEvent(new MapEvent(MapEvent.EntityType.MARK, MapEvent.Action.CREATED,
                alphaLayer, markOn(alphaLayer, "Alpha only")));
        registry.publishMapEvent(new MapEvent(MapEvent.EntityType.MARK, MapEvent.Action.CREATED,
                copLayer, markOn(copLayer, "Shared picture")));
        // Wait until alpha (who may see both) has actually received them, which also proves the
        // registry's background dispatcher has finished appending them to the shared buffer.
        awaitMapMark(alphaStream, "Alpha only");
        awaitMapMark(alphaStream, "Shared picture");
        awaitAtLeast(alphaStream, alphaBefore + 2);

        // Bravo now connects fresh, resuming from before either event. The shared buffer holds both,
        // but bravo may only see the COP one -- the replay is re-filtered, not replayed verbatim.
        MvcResult bravoResume = bravoMvc.perform(get("/api/live").header("Last-Event-ID", "0"))
                .andExpect(request().asyncStarted())
                .andReturn();

        String body = bravoResume.getResponse().getContentAsString();
        assertTrue(body.contains("Shared picture"), "the COP-layer event must be replayed to bravo");
        assertFalse(body.contains("Alpha only"),
                "a resume must not replay events the resuming viewer may not see");
    }

    private MvcResult connect(MockMvc mvc) throws Exception {
        return mvc.perform(get("/api/live"))
                .andExpect(request().asyncStarted())
                .andReturn();
    }

    private static String bodyOf(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString();
    }

    private static List<String> dataLines(MvcResult result) throws Exception {
        return dataLines(bodyOf(result));
    }

    private static List<String> dataLines(String sse) {
        return Arrays.stream(sse.split("\n"))
                .filter(line -> line.startsWith("data:"))
                .map(line -> line.substring("data:".length()))
                .toList();
    }

    /**
     * Polls until a {@code map} envelope carrying a mark with {@code label} appears on this stream.
     * Selecting by content rather than by index keeps the assertion independent of the (unordered)
     * order a connect burst writes its per-topic envelopes in.
     */
    private static JsonNode awaitMapMark(MvcResult result, String label) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            for (String line : dataLines(result)) {
                JsonNode node = json(line);
                if (node.has("type") && "map".equals(node.get("type").asString())
                        && node.get("payload").has("mark")
                        && label.equals(node.get("payload").get("mark").get("label").asString())) {
                    return node;
                }
            }
            Thread.sleep(20);
        }
        fail("timed out waiting for a map envelope carrying mark \"" + label + "\"");
        return null;
    }

    private static List<String> awaitAtLeast(MvcResult result, int minimumDataLines) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            List<String> lines = dataLines(result);
            if (lines.size() >= minimumDataLines) {
                return lines;
            }
            Thread.sleep(20);
        }
        fail("timed out waiting for at least " + minimumDataLines + " SSE data lines");
        return List.of();
    }

    private static JsonNode json(String data) {
        return new JsonMapper().readTree(data);
    }

    /** A {@link MapLayerService} that answers "which layers can you see" per viewer id. */
    private static final class PerViewerLayerService implements MapLayerService {

        private final Map<UserId, List<LayerId>> visibleByUser;

        PerViewerLayerService(Map<UserId, List<LayerId>> visibleByUser) {
            this.visibleByUser = visibleByUser;
        }

        @Override
        public List<LayerView> layers(Viewer v) {
            List<LayerView> views = new ArrayList<>();
            for (LayerId id : visibleByUser.getOrDefault(v.userId(), List.of())) {
                MapLayer layer = new MapLayer(id, "Layer " + id.value(), LayerKind.TEAM,
                        new Ownership(v.userId(), GroupId.random()), List.of(), Instant.now());
                views.add(new LayerView(layer, AccessLevel.VIEW));
            }
            return views;
        }

        @Override
        public MapLayer create(Viewer v, LayerSpec spec) {
            throw new UnsupportedOperationException();
        }

        @Override
        public MapLayer rename(Viewer v, LayerId id, String name) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void delete(Viewer v, LayerId id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public MapLayer setGrants(Viewer v, LayerId id, List<LayerGrant> grants) {
            throw new UnsupportedOperationException();
        }

        @Override
        public LayerId copLayerId() {
            throw new UnsupportedOperationException();
        }
    }
}
