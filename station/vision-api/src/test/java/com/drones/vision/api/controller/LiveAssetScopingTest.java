package com.drones.vision.api.controller;

import com.drones.vision.api.exception.ApiExceptionHandler;
import com.drones.vision.api.live.LiveAssetAccess;
import com.drones.vision.api.live.LiveUpdateRegistry;
import com.drones.vision.api.live.MapVisibility;
import com.drones.vision.api.security.CurrentUser;
import com.drones.vision.api.security.StreamAccess;
import com.drones.vision.identity.application.scope.ScopeResolver;
import com.drones.vision.identity.domain.model.User;
import com.drones.vision.identity.domain.port.UserRepositoryPort;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.BoundingBox;
import com.drones.vision.kernel.CategoryId;
import com.drones.vision.kernel.DeviceId;
import com.drones.vision.kernel.GroupId;
import com.drones.vision.kernel.Ownership;
import com.drones.vision.kernel.StreamId;
import com.drones.vision.kernel.Telemetry;
import com.drones.vision.kernel.UserId;
import com.drones.vision.map.application.MapLayerService;
import com.drones.vision.perception.application.stream.StreamService;
import com.drones.vision.perception.domain.model.Detection;
import com.drones.vision.perception.domain.model.DetectionResult;
import com.drones.vision.perception.domain.model.ModelRef;
import com.drones.vision.perception.domain.port.DetectionEventRepositoryPort;
import com.drones.vision.perception.domain.port.StreamPublisherPort;
import com.drones.vision.platform.VisibilityScope;
import com.drones.vision.warehouse.application.asset.AssetService;
import com.drones.vision.warehouse.application.device.DeviceService;
import com.drones.vision.warehouse.domain.model.Asset;
import com.drones.vision.warehouse.domain.model.Custody;
import com.drones.vision.warehouse.domain.model.Identity;
import com.drones.vision.warehouse.domain.port.AssetRepositoryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The security-critical half of docs/plans/done/LIVE-SCOPE-PLAN.md §2, W3, end to end over real
 * SSE frames — the per-asset twin of {@link LiveMapScopingTest}. Two pilots, two assets, each
 * scoped ({@link VisibilityScope#assignedAssets(Set)}) to only their own: alpha may see {@code
 * alphaAsset}, bravo may see {@code bravoAsset}, and this class proves neither can reach the
 * other's {@code telemetry}/{@code detections} topic through any of {@code connect}, {@code PATCH
 * .../topics}, an already-open connection whose scope changes mid-stream, or a {@code
 * Last-Event-ID} resume.
 *
 * <p>Uses the real {@link LiveUpdateRegistry} (real ring buffers, real background coalescing
 * scheduler — the same 150ms default {@link com.drones.vision.api.support.VisionApiProperties.Live}
 * every other test in this package that predates {@code live}-properties wiring runs against) and a
 * real {@link LiveAssetAccess} backed by a real {@link StreamAccess} over a hand-stubbed {@link
 * AssetRepositoryPort} — only {@link ScopeResolver}/{@link UserRepositoryPort} are Mockito mocks,
 * since those are the two seams this test needs to swap per user and, for one test, mid-test (a
 * revoked assignment).
 *
 * <p>{@link #TTL_MILLIS} is deliberately far below the {@code application.yaml} production default
 * (5000ms) so {@link #aRevokedScopeStopsDeliveryOnAnAlreadyOpenConnection} can observe the cache
 * going stale within the test's own timeout budget; it proves the re-check happens at all, not
 * anything about the production value's staleness/DB-load trade-off (that is {@code
 * LiveAssetAccessTest}'s job).
 */
class LiveAssetScopingTest {

    private static final long TTL_MILLIS = 50;

    private final AssetId alphaAsset = AssetId.random();
    private final AssetId bravoAsset = AssetId.random();

    private final UserId alphaUserId = UserId.random();
    private final UserId bravoUserId = UserId.random();
    private final User alphaUser = user(alphaUserId, "alpha");
    private final User bravoUser = user(bravoUserId, "bravo");

    private final CurrentUser alpha = new CurrentUser(new Ownership(alphaUserId, GroupId.random()));
    private final CurrentUser bravo = new CurrentUser(new Ownership(bravoUserId, GroupId.random()));

    private LiveUpdateRegistry registry;
    private ScopeResolver scopeResolver;
    private MockMvc alphaMvc;
    private MockMvc bravoMvc;

    @BeforeEach
    void setUp() {
        AssetService assetService = mock(AssetService.class);
        when(assetService.assets()).thenReturn(List.of());
        DeviceService deviceService = mock(DeviceService.class);
        when(deviceService.devices()).thenReturn(List.of());
        StreamService registryStreamService = mock(StreamService.class);
        when(registryStreamService.streams()).thenReturn(List.of());
        StreamPublisherPort streamPublisherPort = mock(StreamPublisherPort.class);
        DetectionEventRepositoryPort detectionEvents = mock(DetectionEventRepositoryPort.class);
        when(detectionEvents.findRecent(null, 300)).thenReturn(List.of());

        registry = new LiveUpdateRegistry(provider(assetService), provider(deviceService),
                provider(registryStreamService), streamPublisherPort, provider(detectionEvents));

        AssetRepositoryPort assetRepositoryPort = mock(AssetRepositoryPort.class);
        when(assetRepositoryPort.findById(alphaAsset)).thenReturn(Optional.of(asset(alphaAsset, alphaUserId)));
        when(assetRepositoryPort.findById(bravoAsset)).thenReturn(Optional.of(asset(bravoAsset, bravoUserId)));
        // Unused by StreamAccess#visibleAsset -- only present because StreamAccess's constructor
        // requires them non-null.
        StreamService unusedStreamService = mock(StreamService.class);
        CurrentUser unusedCurrentUser = new CurrentUser(new Ownership(UserId.random(), GroupId.random()));
        StreamAccess streamAccess = new StreamAccess(unusedStreamService, assetRepositoryPort, unusedCurrentUser);

        UserRepositoryPort userRepositoryPort = mock(UserRepositoryPort.class);
        when(userRepositoryPort.findById(alphaUserId)).thenReturn(Optional.of(alphaUser));
        when(userRepositoryPort.findById(bravoUserId)).thenReturn(Optional.of(bravoUser));

        scopeResolver = mock(ScopeResolver.class);
        scopeFor(alphaUser, VisibilityScope.assignedAssets(Set.of(alphaAsset)));
        scopeFor(bravoUser, VisibilityScope.assignedAssets(Set.of(bravoAsset)));

        LiveAssetAccess assetAccess = new LiveAssetAccess(streamAccess, scopeResolver, userRepositoryPort, TTL_MILLIS);

        MapLayerService mapLayerService = mock(MapLayerService.class);
        when(mapLayerService.layers(any())).thenReturn(List.of());
        MapVisibility mapVisibility = new MapVisibility(mapLayerService);

        alphaMvc = mvcFor(mapVisibility, assetAccess, alpha);
        bravoMvc = mvcFor(mapVisibility, assetAccess, bravo);
    }

    private void scopeFor(User user, VisibilityScope scope) {
        when(scopeResolver.scopeFor(user)).thenReturn(scope);
    }

    private MockMvc mvcFor(MapVisibility mapVisibility, LiveAssetAccess assetAccess, CurrentUser user) {
        return MockMvcBuilders.standaloneSetup(new LiveController(registry, mapVisibility, assetAccess, user))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void aPilotsOwnAssignedAssetStillStreamsThroughConnectPatchAndResume() throws Exception {
        MvcResult stream = connect(alphaMvc, "telemetry:" + alphaAsset.value());
        JsonNode connected = json(dataLines(stream).get(0));
        assertTrue(topicsInclude(connected, "telemetry:" + alphaAsset.value()),
                "a pilot's own assigned asset's telemetry topic must be granted at connect");
        String connectionId = connected.get("connectionId").asString();

        registry.publishTelemetryAppended(alphaAsset, telemetry(1.0));
        awaitEnvelope(stream, "telemetry", alphaAsset);

        JsonNode patchResponse = json(alphaMvc.perform(patch("/api/live/{id}/topics", connectionId)
                        .contentType("application/json")
                        .content("{\"add\":[\"detections:" + alphaAsset.value() + "\"]}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertTrue(patchResponse.get("topics").toString().contains("detections:" + alphaAsset.value()),
                "PATCH must grant a topic naming the caller's own assigned asset");

        registry.publishDetections(alphaAsset, detectionResult(StreamId.random(), 0));
        awaitEnvelope(stream, "detections", alphaAsset);

        MvcResult resumed = alphaMvc.perform(get("/api/live")
                        .param("topics", "telemetry:" + alphaAsset.value())
                        .header("Last-Event-ID", "0"))
                .andExpect(request().asyncStarted())
                .andReturn();
        assertTrue(bodyOf(resumed).contains("\"seq\""), "a resume must still replay the buffered history the caller may see");
    }

    @Test
    void connectSilentlyDropsAForeignAssetsTelemetryTopic() throws Exception {
        MvcResult alphaStream = connect(alphaMvc, "telemetry:" + bravoAsset.value());
        JsonNode connected = json(dataLines(alphaStream).get(0));
        assertFalse(topicsInclude(connected, "telemetry:" + bravoAsset.value()),
                "a topic naming an asset outside the caller's scope must never be granted at connect");

        MvcResult bravoStream = connect(bravoMvc, "telemetry:" + bravoAsset.value());
        int alphaCountBefore = dataLines(alphaStream).size();

        registry.publishTelemetryAppended(bravoAsset, telemetry(7.0));
        awaitEnvelope(bravoStream, "telemetry", bravoAsset); // proves this flush cycle actually ran

        assertEquals(alphaCountBefore, dataLines(alphaStream).size(),
                "a connection never granted a foreign asset topic must never receive it");
        assertFalse(bodyOf(alphaStream).contains(bravoAsset.value().toString()),
                "the foreign asset's id must not appear anywhere in this connection's stream");
    }

    @Test
    void connectSilentlyDropsAForeignAssetsDetectionsTopic() throws Exception {
        MvcResult alphaStream = connect(alphaMvc, "detections:" + bravoAsset.value());
        JsonNode connected = json(dataLines(alphaStream).get(0));
        assertFalse(topicsInclude(connected, "detections:" + bravoAsset.value()),
                "a topic naming an asset outside the caller's scope must never be granted at connect");

        MvcResult bravoStream = connect(bravoMvc, "detections:" + bravoAsset.value());
        int alphaCountBefore = dataLines(alphaStream).size();

        registry.publishDetections(bravoAsset, detectionResult(StreamId.random(), 0));
        awaitEnvelope(bravoStream, "detections", bravoAsset); // proves this flush cycle actually ran

        assertEquals(alphaCountBefore, dataLines(alphaStream).size(),
                "a connection never granted a foreign asset topic must never receive it");
        assertFalse(bodyOf(alphaStream).contains(bravoAsset.value().toString()),
                "the foreign asset's id must not appear anywhere in this connection's stream");
    }

    @Test
    void updateTopicsRefusesACallerWhoDoesNotOwnTheConnection() throws Exception {
        MvcResult alphaStream = connect(alphaMvc, null);
        String alphaConnectionId = json(dataLines(alphaStream).get(0)).get("connectionId").asString();

        bravoMvc.perform(patch("/api/live/{id}/topics", alphaConnectionId)
                        .contentType("application/json")
                        .content("{\"add\":[\"telemetry:" + bravoAsset.value() + "\"]}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void updateTopicsCannotAddAForeignAssetsTopic() throws Exception {
        MvcResult alphaStream = connect(alphaMvc, null);
        String connectionId = json(dataLines(alphaStream).get(0)).get("connectionId").asString();

        JsonNode response = json(alphaMvc.perform(patch("/api/live/{id}/topics", connectionId)
                        .contentType("application/json")
                        .content("{\"add\":[\"telemetry:" + bravoAsset.value() + "\"]}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertFalse(response.get("topics").toString().contains("telemetry:" + bravoAsset.value()),
                "a PATCH must not be able to add a topic naming an asset outside the caller's scope");

        MvcResult bravoStream = connect(bravoMvc, "telemetry:" + bravoAsset.value());
        int alphaCountBefore = dataLines(alphaStream).size();

        registry.publishTelemetryAppended(bravoAsset, telemetry(3.0));
        awaitEnvelope(bravoStream, "telemetry", bravoAsset); // proves this flush cycle actually ran

        assertEquals(alphaCountBefore, dataLines(alphaStream).size(),
                "a PATCH-rejected topic must never actually deliver to the connection");
    }

    /**
     * The core proof of docs/plans/done/LIVE-SCOPE-PLAN.md §2, W3 defect 3: authorization must
     * hold per delivery, not only at the moment a topic was subscribed. Alpha's connection keeps its
     * {@code telemetry:<alphaAsset>} subscription throughout -- there is no {@code PATCH}, no
     * reconnect, nothing that would give {@code LiveController} another chance to re-run {@link
     * LiveAssetAccess#filterTopicsParam}. Only the caller's underlying scope changes, and only
     * {@link com.drones.vision.api.live.LiveConnection#project}'s independent per-envelope
     * re-check is what stops the next sample.
     */
    @Test
    void aRevokedScopeStopsDeliveryOnAnAlreadyOpenConnection() throws Exception {
        MvcResult alphaStream = connect(alphaMvc, "telemetry:" + alphaAsset.value());
        MvcResult bravoStream = connect(bravoMvc, "telemetry:" + bravoAsset.value());

        registry.publishTelemetryAppended(alphaAsset, telemetry(1.0));
        awaitEnvelope(alphaStream, "telemetry", alphaAsset);
        int alphaCountAfterFirstSample = dataLines(alphaStream).size();

        // Revoke -- no PATCH, no reconnect, the connection's own subscribed topic set is untouched.
        scopeFor(alphaUser, VisibilityScope.assignedAssets(Set.of()));
        Thread.sleep(TTL_MILLIS + 20); // let LiveAssetAccess's cached answer go stale

        registry.publishTelemetryAppended(alphaAsset, telemetry(99.0));
        registry.publishTelemetryAppended(bravoAsset, telemetry(2.0)); // control: proves this flush cycle ran
        awaitEnvelope(bravoStream, "telemetry", bravoAsset);

        assertEquals(alphaCountAfterFirstSample, dataLines(alphaStream).size(),
                "a connection must stop receiving an asset's updates once the caller's scope no "
                        + "longer includes it, even with no PATCH or reconnect in between");
        assertFalse(bodyOf(alphaStream).contains("99.0"), "the post-revocation sample must never reach the connection");
    }

    /**
     * The resume half of defect 3: {@code Last-Event-ID} reconnect runs through {@code
     * LiveController#connect} exactly like a fresh connect, so a revoked asset's topic is stripped
     * from the resumed connection's topic set the same way {@link
     * #connectSilentlyDropsAForeignAssetsTelemetryTopic} proves for a topic that was never visible
     * -- closing the plan's "resume must apply the same authorization as live delivery" without a
     * second, parallel authorization mechanism for replay.
     */
    @Test
    void aLastEventIdResumeDoesNotReplayAnAssetTheCallersCurrentScopeExcludes() throws Exception {
        registry.publishTelemetryAppended(alphaAsset, telemetry(1.0));
        MvcResult witness = connect(bravoMvc, "telemetry:" + bravoAsset.value());
        registry.publishTelemetryAppended(bravoAsset, telemetry(5.0));
        awaitEnvelope(witness, "telemetry", bravoAsset); // proves alpha's sample above was flushed too

        // Revoke alpha's assignment before the resume.
        scopeFor(alphaUser, VisibilityScope.assignedAssets(Set.of()));
        Thread.sleep(TTL_MILLIS + 20);

        MvcResult resumed = alphaMvc.perform(get("/api/live")
                        .param("topics", "telemetry:" + alphaAsset.value())
                        .header("Last-Event-ID", "0"))
                .andExpect(request().asyncStarted())
                .andReturn();

        JsonNode connected = json(dataLines(resumed).get(0));
        assertFalse(topicsInclude(connected, "telemetry:" + alphaAsset.value()),
                "a resume must not re-grant a topic the caller's current scope no longer includes");
        assertFalse(bodyOf(resumed).contains(alphaAsset.value().toString()),
                "the buffered sample from before revocation must not be replayed on resume");
    }

    private MvcResult connect(MockMvc mvc, String topics) throws Exception {
        var builder = get("/api/live");
        if (topics != null) {
            builder = builder.param("topics", topics);
        }
        return mvc.perform(builder)
                .andExpect(request().asyncStarted())
                .andReturn();
    }

    private static <T> ObjectProvider<T> provider(T value) {
        return new ObjectProvider<>() {
            @Override
            public T getObject() {
                return value;
            }
        };
    }

    private static Asset asset(AssetId id, UserId ownerId) {
        return Asset.register(id, "asset-" + id.value(), new CategoryId("drone"),
                new Ownership(ownerId, GroupId.random()), Set.of(DeviceId.random()), Map.of(), Identity.NONE,
                Custody.NONE);
    }

    private static User user(UserId id, String name) {
        return new User(id, name, name, name + "@example.com", "hash", true);
    }

    private static Telemetry telemetry(double lat) {
        return new Telemetry(DeviceId.random(), Instant.now(), lat, 10.0, null, null, null, Map.of());
    }

    private static DetectionResult detectionResult(StreamId streamId, long frameSequence) {
        Detection detection = new Detection("person", 0.9, new BoundingBox(0.1, 0.1, 0.2, 0.2),
                new ModelRef("yolo", "latest"));
        return new DetectionResult(streamId, frameSequence, Instant.now(), List.of(detection), Duration.ZERO);
    }

    private static boolean topicsInclude(JsonNode connected, String topic) {
        return connected.get("topics").toString().contains("\"" + topic + "\"");
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

    /** Polls until an envelope of {@code type} carrying {@code assetId} appears on this stream. */
    private static JsonNode awaitEnvelope(MvcResult result, String type, AssetId assetId) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            for (String line : dataLines(result)) {
                JsonNode node = json(line);
                if (node.has("type") && type.equals(node.get("type").asString())
                        && node.has("assetId") && assetId.value().toString().equals(node.get("assetId").asString())) {
                    return node;
                }
            }
            Thread.sleep(20);
        }
        fail("timed out waiting for a \"" + type + "\" envelope for asset " + assetId.value());
        return null;
    }

    private static JsonNode json(String data) {
        return new JsonMapper().readTree(data);
    }
}
