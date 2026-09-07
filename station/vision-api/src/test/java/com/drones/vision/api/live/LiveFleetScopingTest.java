package com.drones.vision.api.live;

import com.drones.vision.api.controller.LiveController;
import com.drones.vision.api.exception.ApiExceptionHandler;
import com.drones.vision.api.security.CurrentUser;
import com.drones.vision.api.security.StreamAccess;
import com.drones.vision.identity.application.scope.ScopeResolver;
import com.drones.vision.identity.domain.model.User;
import com.drones.vision.identity.domain.port.UserRepositoryPort;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.CategoryId;
import com.drones.vision.kernel.DeviceId;
import com.drones.vision.kernel.GroupId;
import com.drones.vision.kernel.Ownership;
import com.drones.vision.kernel.UserId;
import com.drones.vision.map.application.MapLayerService;
import com.drones.vision.perception.application.stream.StreamService;
import com.drones.vision.perception.domain.port.DetectionEventRepositoryPort;
import com.drones.vision.perception.domain.port.StreamPublisherPort;
import com.drones.vision.platform.VisibilityScope;
import com.drones.vision.warehouse.application.asset.AssetService;
import com.drones.vision.warehouse.application.asset.AssetStatus;
import com.drones.vision.warehouse.application.asset.AssetSummary;
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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;

/**
 * The fleet-topic twin of {@link com.drones.vision.api.controller.LiveAssetScopingTest} — real
 * {@code GET /api/live} round trips over the real {@link LiveController}/{@link LiveUpdateRegistry}/
 * {@link LiveAssetAccess}/{@link StreamAccess} chain, proving the fleet-topic scope leak is closed:
 * {@code LiveUpdateRegistry#freshFleetEnvelope} builds its snapshot from the unscoped {@code
 * AssetService#assets()} overload, so it was, until this fix, handed unfiltered to every connected
 * viewer on the {@code fleet} topic (connect-time snapshot, {@code Last-Event-ID} resume, and every
 * later broadcast alike) — including through {@code vision-web}'s Fly drone picker, which layers this
 * SSE snapshot straight over its own correctly-scoped {@code GET /api/assets} read.
 *
 * <p>Three viewers, two assets: {@code groupAsset} is owned by {@code groupId}, {@code otherAsset}
 * by a different group. {@code manager} is {@link VisibilityScope#groups}-scoped to {@code groupId}
 * (sees {@code groupAsset} only), {@code admin} is {@link VisibilityScope#unbounded()} (sees both),
 * {@code nobody} is {@code groups}-scoped to an empty set (sees neither). Only {@code
 * ScopeResolver#scopeFor(User)} varies per viewer — {@link LiveAssetAccess#canView} re-derives from
 * that on every check, exactly as it does for the per-asset topics {@code LiveAssetScopingTest}
 * already covers (see that class's own javadoc for why {@link CurrentUser#scope()} itself can stay a
 * fixed {@link VisibilityScope#unbounded()} placeholder without leaking anything: it is only ever the
 * fallback for a {@link UserId} that resolves to no {@link User} row, and every viewer here resolves
 * to one).
 */
class LiveFleetScopingTest {

    private static final long TTL_MILLIS = 5000;

    private final GroupId groupId = GroupId.random();
    private final GroupId otherGroupId = GroupId.random();
    private final AssetId groupAssetId = AssetId.random();
    private final AssetId otherAssetId = AssetId.random();

    private final UserId managerUserId = UserId.random();
    private final UserId adminUserId = UserId.random();
    private final UserId nobodyUserId = UserId.random();
    private final User managerUser = user(managerUserId, "manager");
    private final User adminUser = user(adminUserId, "admin");
    private final User nobodyUser = user(nobodyUserId, "nobody");

    private final CurrentUser manager = new CurrentUser(new Ownership(managerUserId, groupId));
    private final CurrentUser admin = new CurrentUser(new Ownership(adminUserId, GroupId.random()));
    private final CurrentUser nobody = new CurrentUser(new Ownership(nobodyUserId, GroupId.random()));

    private LiveUpdateRegistry registry;
    private MockMvc managerMvc;
    private MockMvc adminMvc;
    private MockMvc nobodyMvc;

    @BeforeEach
    void setUp() {
        AssetService assetService = mock(AssetService.class);
        when(assetService.assets())
                .thenReturn(List.of(summary(groupAssetId, groupId), summary(otherAssetId, otherGroupId)));
        DeviceService deviceService = mock(DeviceService.class);
        when(deviceService.devices()).thenReturn(List.of());
        StreamService registryStreamService = mock(StreamService.class);
        when(registryStreamService.streams()).thenReturn(List.of());
        StreamPublisherPort streamPublisherPort = mock(StreamPublisherPort.class);
        DetectionEventRepositoryPort detectionEvents = mock(DetectionEventRepositoryPort.class);
        when(detectionEvents.findRecent(null, LiveUpdateRegistry.DETECTION_EVENT_BUFFER_CAPACITY))
                .thenReturn(List.of());

        registry = new LiveUpdateRegistry(provider(assetService), provider(deviceService),
                provider(registryStreamService), streamPublisherPort, provider(detectionEvents));

        AssetRepositoryPort assetRepositoryPort = mock(AssetRepositoryPort.class);
        when(assetRepositoryPort.findById(groupAssetId)).thenReturn(Optional.of(asset(groupAssetId, groupId)));
        when(assetRepositoryPort.findById(otherAssetId)).thenReturn(Optional.of(asset(otherAssetId, otherGroupId)));
        // Unused by StreamAccess#visibleAsset -- only present because StreamAccess's constructor
        // requires them non-null (same precedent as LiveAssetScopingTest's own setup).
        StreamService unusedStreamService = mock(StreamService.class);
        CurrentUser unusedCurrentUser = new CurrentUser(new Ownership(UserId.random(), GroupId.random()));
        StreamAccess streamAccess = new StreamAccess(unusedStreamService, assetRepositoryPort, unusedCurrentUser);

        UserRepositoryPort userRepositoryPort = mock(UserRepositoryPort.class);
        when(userRepositoryPort.findById(managerUserId)).thenReturn(Optional.of(managerUser));
        when(userRepositoryPort.findById(adminUserId)).thenReturn(Optional.of(adminUser));
        when(userRepositoryPort.findById(nobodyUserId)).thenReturn(Optional.of(nobodyUser));

        ScopeResolver scopeResolver = mock(ScopeResolver.class);
        when(scopeResolver.scopeFor(managerUser)).thenReturn(VisibilityScope.groups(Set.of(groupId)));
        when(scopeResolver.scopeFor(adminUser)).thenReturn(VisibilityScope.unbounded());
        when(scopeResolver.scopeFor(nobodyUser)).thenReturn(VisibilityScope.groups(Set.of()));

        LiveAssetAccess assetAccess = new LiveAssetAccess(streamAccess, scopeResolver, userRepositoryPort, TTL_MILLIS);

        MapLayerService mapLayerService = mock(MapLayerService.class);
        when(mapLayerService.layers(any())).thenReturn(List.of());
        MapVisibility mapVisibility = new MapVisibility(mapLayerService);

        managerMvc = mvcFor(mapVisibility, assetAccess, manager);
        adminMvc = mvcFor(mapVisibility, assetAccess, admin);
        nobodyMvc = mvcFor(mapVisibility, assetAccess, nobody);
    }

    private MockMvc mvcFor(MapVisibility mapVisibility, LiveAssetAccess assetAccess, CurrentUser user) {
        return MockMvcBuilders.standaloneSetup(new LiveController(registry, mapVisibility, assetAccess, user))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    /**
     * The actual leak path today: {@code connect()}'s handshake burst seeds the {@code fleet}
     * buffer from {@code freshFleetEnvelope()}'s unscoped snapshot and used to hand it straight to
     * the connecting viewer with no per-connection narrowing at all.
     */
    @Test
    void connectsSeededFleetSnapshotIsAlreadyNarrowedToAGroupsScopedViewersScope() throws Exception {
        MvcResult stream = connect(managerMvc);

        JsonNode fleetEnvelope = envelopeOfType(dataLines(stream), "fleet");
        assertEquals(List.of(groupAssetId.value().toString()), assetIds(fleetEnvelope),
                "a fresh connect's seeded fleet snapshot must already be narrowed to the caller's own scope");
    }

    @Test
    void aFleetBroadcastDeltaIsAlsoNarrowedToAGroupsScopedViewersScope() throws Exception {
        MvcResult stream = connect(managerMvc);
        int before = dataLines(stream).size();

        registry.publishFleetChanged();

        JsonNode delta = awaitFleetDelta(stream, before);
        assertEquals(List.of(groupAssetId.value().toString()), assetIds(delta));
    }

    /**
     * Proves both halves of "leave the buffer unfiltered, filter at delivery time": {@code admin}'s
     * own connect seeds the shared {@code fleet} buffer with the raw, unfiltered snapshot (both
     * assets); a later resume by a narrowly-scoped viewer must still only see its own asset (the
     * buffer was never filtered to the seeding viewer's scope), and a resume by the unbounded viewer
     * must still see everything (nothing was lost either).
     */
    @Test
    void aLastEventIdResumeReplaysTheUnfilteredBufferReFilteredPerViewer() throws Exception {
        MvcResult seeding = connect(adminMvc);
        JsonNode seededFleet = envelopeOfType(dataLines(seeding), "fleet");
        long fleetSeq = seededFleet.get("seq").asLong();
        assertEquals(2, assetIds(seededFleet).size(), "sanity: the admin's own seeded snapshot carries both assets");

        MvcResult managerResume = managerMvc.perform(
                        get("/api/live").header("Last-Event-ID", Long.toString(fleetSeq - 1)))
                .andExpect(request().asyncStarted())
                .andReturn();
        MvcResult adminResume = adminMvc.perform(
                        get("/api/live").header("Last-Event-ID", Long.toString(fleetSeq - 1)))
                .andExpect(request().asyncStarted())
                .andReturn();

        assertEquals(List.of(groupAssetId.value().toString()),
                assetIds(envelopeOfType(dataLines(managerResume), "fleet")),
                "resume must re-filter the buffered (unfiltered) snapshot against the resuming viewer's own scope");
        assertEquals(2, assetIds(envelopeOfType(dataLines(adminResume), "fleet")).size(),
                "an unbounded viewer's resume must still see everything -- proves the buffer itself was "
                        + "never filtered at seed time");
    }

    @Test
    void anUnboundedAdminViewerStillReceivesEveryAssetOnTheFleetTopic() throws Exception {
        MvcResult stream = connect(adminMvc);

        List<String> ids = assetIds(envelopeOfType(dataLines(stream), "fleet"));
        assertEquals(2, ids.size());
        assertTrue(ids.contains(groupAssetId.value().toString()));
        assertTrue(ids.contains(otherAssetId.value().toString()));
    }

    @Test
    void aViewerWithNothingVisibleReceivesAnEmptyFleetListNotADroppedEnvelope() throws Exception {
        MvcResult stream = connect(nobodyMvc);

        JsonNode fleetEnvelope = envelopeOfType(dataLines(stream), "fleet");
        assertNotNull(fleetEnvelope, "an envelope must still be sent -- an empty list is the correct answer, not silence");
        assertEquals(0, fleetEnvelope.get("payload").size());
    }

    private MvcResult connect(MockMvc mvc) throws Exception {
        return mvc.perform(get("/api/live"))
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

    private static Asset asset(AssetId id, GroupId groupId) {
        return Asset.register(id, "asset-" + id.value(), new CategoryId("drone"),
                new Ownership(UserId.random(), groupId), Set.of(DeviceId.random()), Map.of(), Identity.NONE,
                Custody.NONE);
    }

    private static AssetSummary summary(AssetId id, GroupId groupId) {
        Asset asset = asset(id, groupId);
        return new AssetSummary(asset, "Drone", AssetStatus.OFFLINE, null, null, asset.inventoryState(),
                asset.identity(), asset.custody());
    }

    private static User user(UserId id, String name) {
        return new User(id, name, name, name + "@example.com", "hash", true);
    }

    private static List<String> assetIds(JsonNode fleetEnvelope) {
        List<String> ids = new ArrayList<>();
        for (JsonNode assetNode : fleetEnvelope.get("payload")) {
            ids.add(assetNode.get("assetId").asString());
        }
        return ids;
    }

    private static JsonNode envelopeOfType(List<String> dataLines, String type) {
        return dataLines.stream().map(LiveFleetScopingTest::json)
                .filter(node -> node.has("type") && type.equals(node.get("type").asString()))
                .reduce((first, second) -> second) // the latest match -- a delta after the initial snapshot
                .orElseGet(() -> fail("no \"" + type + "\" envelope found in stream"));
    }

    /** Polls until at least one new SSE line lands after {@code before}, then returns its fleet envelope. */
    private static JsonNode awaitFleetDelta(MvcResult result, int before) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            List<String> lines = dataLines(result);
            if (lines.size() > before) {
                return envelopeOfType(lines, "fleet");
            }
            Thread.sleep(20);
        }
        return fail("timed out waiting for a fleet delta after the initial snapshot");
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

    private static JsonNode json(String data) {
        return new JsonMapper().readTree(data);
    }
}
