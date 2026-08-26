package com.drones.vision.api.controller;

import com.drones.vision.api.exception.ApiExceptionHandler;
import com.drones.vision.api.security.CurrentUser;
import com.drones.vision.api.security.PrincipalResolver;
import com.drones.vision.api.security.StreamAccess;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.CategoryId;
import com.drones.vision.kernel.DeviceId;
import com.drones.vision.kernel.GroupId;
import com.drones.vision.kernel.Ownership;
import com.drones.vision.kernel.UserId;
import com.drones.vision.map.application.MapAccessPolicy;
import com.drones.vision.perception.application.stream.ActiveStream;
import com.drones.vision.perception.application.stream.StreamService;
import com.drones.vision.perception.domain.model.DetectionEvent;
import com.drones.vision.perception.domain.model.DetectionEventId;
import com.drones.vision.perception.domain.model.DetectionEventState;
import com.drones.vision.kernel.GeoPosition;
import com.drones.vision.kernel.StreamId;
import com.drones.vision.perception.domain.port.DetectionEventRepositoryPort;
import com.drones.vision.platform.VisibilityScope;
import com.drones.vision.warehouse.domain.model.Asset;
import com.drones.vision.warehouse.domain.port.AssetRepositoryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Every pre-existing test below runs against {@link #currentUser} — {@link
 * CurrentUser#CurrentUser(Ownership)}'s unbounded-scope convenience constructor — so {@link
 * EventController}'s scope gate (docs/plans/active/ARCHITECTURE-AUDIT-2026-08-26.md R7, finding A2)
 * is exercised separately below, via {@link #currentUserWithScope}, mirroring {@code
 * StreamControllerTest}'s own idiom for the same reason.
 */
class EventControllerTest {

    private DetectionEventRepositoryPort detectionEventRepositoryPort;
    private StreamService streamService;
    /** Backs {@link StreamAccess}'s device&rarr;asset&rarr;owner resolution, unstubbed (empty) by default. */
    private AssetRepositoryPort assetRepositoryPort;
    private MockMvc mockMvc;

    private final StreamId streamId = StreamId.random();
    private final AssetId assetId = AssetId.random();
    private final UserId ownerId = UserId.random();
    private final Ownership ownership = new Ownership(ownerId, GroupId.random());
    /** Unbounded (auth-off-equivalent) by default, so every pre-existing test below is unaffected. */
    private final CurrentUser currentUser = new CurrentUser(ownership);

    @BeforeEach
    void setUp() {
        detectionEventRepositoryPort = mock(DetectionEventRepositoryPort.class);
        streamService = mock(StreamService.class);
        when(streamService.streams()).thenReturn(List.of());
        assetRepositoryPort = mock(AssetRepositoryPort.class);
        mockMvc = mockMvcFor(currentUser);
    }

    /**
     * A {@link CurrentUser} answering with {@link #ownership}/{@link #ownerId} but a caller-supplied
     * {@link VisibilityScope} — for the scope-gate tests below, which need a PILOT scope rather than
     * the class-level {@link #currentUser}'s unbounded one. Same idiom {@code StreamControllerTest}
     * uses; {@link PrincipalResolver#viewer()} is never called by {@link EventController}/{@link
     * StreamAccess}, so it throws rather than fake a map viewer no test here needs.
     */
    private CurrentUser currentUserWithScope(VisibilityScope scope) {
        return new CurrentUser(new PrincipalResolver() {
            @Override
            public UserId userId() {
                return ownerId;
            }

            @Override
            public Ownership ownership() {
                return ownership;
            }

            @Override
            public VisibilityScope scope() {
                return scope;
            }

            @Override
            public MapAccessPolicy.Viewer viewer() {
                throw new UnsupportedOperationException("EventController never calls viewer()");
            }
        });
    }

    /**
     * A {@link MockMvc} bound to a fresh {@link EventController} acting as {@code user} — same mocked
     * {@link #detectionEventRepositoryPort}/{@link #streamService}/{@link #assetRepositoryPort}, only
     * the {@link StreamAccess}'s {@link CurrentUser} changes.
     */
    private MockMvc mockMvcFor(CurrentUser user) {
        StreamAccess streamAccess = new StreamAccess(streamService, assetRepositoryPort, user);
        return MockMvcBuilders.standaloneSetup(new EventController(detectionEventRepositoryPort, streamAccess, user))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private DetectionEvent event(String label, DetectionEventState state, AssetId assetId, GeoPosition position) {
        Instant t0 = Instant.parse("2024-01-01T00:00:00Z");
        return new DetectionEvent(DetectionEventId.random(), streamId, assetId, label, 0.87, t0,
                t0.plusSeconds(3), state, position);
    }

    @Test
    void recentReturnsMappedEventsNewestFirstOrderPreserved() throws Exception {
        DetectionEvent first = event("person", DetectionEventState.OPEN, assetId,
                new GeoPosition(10.0, 20.0, null));
        DetectionEvent second = event("car", DetectionEventState.CLOSED, null, null);
        when(detectionEventRepositoryPort.findRecent(isNull(), eq(EventController.DEFAULT_LIMIT)))
                .thenReturn(List.of(first, second));

        mockMvc.perform(get("/api/events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].id").value(first.id().value().toString()))
                .andExpect(jsonPath("$[0].streamId").value(streamId.value().toString()))
                .andExpect(jsonPath("$[0].assetId").value(assetId.value().toString()))
                .andExpect(jsonPath("$[0].label").value("person"))
                .andExpect(jsonPath("$[0].peakConfidence").value(0.87))
                .andExpect(jsonPath("$[0].state").value("OPEN"))
                .andExpect(jsonPath("$[0].position.latitude").value(10.0))
                .andExpect(jsonPath("$[0].position.longitude").value(20.0))
                .andExpect(jsonPath("$[1].id").value(second.id().value().toString()))
                .andExpect(jsonPath("$[1].label").value("car"))
                .andExpect(jsonPath("$[1].state").value("CLOSED"))
                .andExpect(jsonPath("$[1].assetId").doesNotExist())
                .andExpect(jsonPath("$[1].position").doesNotExist());
    }

    @Test
    void recentPassesSinceMsThroughAsAnInstant() throws Exception {
        when(detectionEventRepositoryPort.findRecent(any(), anyInt())).thenReturn(List.of());
        long sinceMs = 1_700_000_000_000L;

        mockMvc.perform(get("/api/events").param("sinceMs", String.valueOf(sinceMs)))
                .andExpect(status().isOk());

        ArgumentCaptor<Instant> captor = ArgumentCaptor.forClass(Instant.class);
        verify(detectionEventRepositoryPort).findRecent(captor.capture(), eq(EventController.DEFAULT_LIMIT));
        assertEquals(Instant.ofEpochMilli(sinceMs), captor.getValue());
    }

    @Test
    void recentPassesNullSinceWhenAbsent() throws Exception {
        when(detectionEventRepositoryPort.findRecent(any(), anyInt())).thenReturn(List.of());

        mockMvc.perform(get("/api/events")).andExpect(status().isOk());

        verify(detectionEventRepositoryPort).findRecent(isNull(), eq(EventController.DEFAULT_LIMIT));
    }

    @Test
    void recentPassesLimitThrough() throws Exception {
        when(detectionEventRepositoryPort.findRecent(any(), anyInt())).thenReturn(List.of());

        mockMvc.perform(get("/api/events").param("limit", "5")).andExpect(status().isOk());

        verify(detectionEventRepositoryPort).findRecent(isNull(), eq(5));
    }

    @Test
    void recentReturns400ForNonPositiveLimit() throws Exception {
        mockMvc.perform(get("/api/events").param("limit", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));

        mockMvc.perform(get("/api/events").param("limit", "-1"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void forStreamReturnsMappedEvents() throws Exception {
        DetectionEvent detected = event("person", DetectionEventState.OPEN, assetId, null);
        when(detectionEventRepositoryPort.findByStream(eq(streamId), eq(EventController.DEFAULT_LIMIT)))
                .thenReturn(List.of(detected));

        mockMvc.perform(get("/api/streams/{streamId}/events", streamId.value()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].label").value("person"));
    }

    @Test
    void forStreamPassesLimitThrough() throws Exception {
        when(detectionEventRepositoryPort.findByStream(eq(streamId), eq(10))).thenReturn(List.of());

        mockMvc.perform(get("/api/streams/{streamId}/events", streamId.value()).param("limit", "10"))
                .andExpect(status().isOk());

        verify(detectionEventRepositoryPort).findByStream(streamId, 10);
    }

    @Test
    void forStreamReturns400ForNonPositiveLimit() throws Exception {
        mockMvc.perform(get("/api/streams/{streamId}/events", streamId.value()).param("limit", "0"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void forStreamReturns400ForAMalformedStreamId() throws Exception {
        mockMvc.perform(get("/api/streams/{streamId}/events", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));
    }

    @Test
    void forStreamReturnsAnEmptyListRatherThan404ForAnUnknownStream() throws Exception {
        StreamId unknown = StreamId.random();
        when(detectionEventRepositoryPort.findByStream(eq(unknown), anyInt())).thenReturn(List.of());

        mockMvc.perform(get("/api/streams/{streamId}/events", unknown.value()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    /**
     * The wave's own acceptance case (docs/plans/active/ARCHITECTURE-AUDIT-2026-08-26.md R7, finding
     * A2): {@link EventController#recent} filters the fleet-wide list down to what the caller's
     * scope may see rather than 403ing the whole request — mirroring {@code StreamController#list}'s
     * {@code filterVisible} posture. Three shapes in one request: an event on the caller's own
     * assigned asset (kept), one on a foreign asset (dropped), and one with a {@code null} assetId —
     * an unowned device, visible only to a caller who {@link VisibilityScope#canAdminister()}, which
     * a PILOT never does (dropped).
     */
    @Test
    void recentFiltersOutEventsOnAssetsOutsideTheCallersScope() throws Exception {
        AssetId ownedAssetId = AssetId.random();
        AssetId foreignAssetId = AssetId.random();
        Asset ownedAsset = new Asset(ownedAssetId, "my drone", new CategoryId("drone"), ownership,
                Set.of(DeviceId.random()), Map.of());
        when(assetRepositoryPort.findById(ownedAssetId)).thenReturn(Optional.of(ownedAsset));
        when(assetRepositoryPort.findById(foreignAssetId)).thenReturn(Optional.empty());

        DetectionEvent onOwnAsset = event("person", DetectionEventState.OPEN, ownedAssetId, null);
        DetectionEvent onForeignAsset = event("car", DetectionEventState.OPEN, foreignAssetId, null);
        DetectionEvent onUnownedDevice = event("dog", DetectionEventState.OPEN, null, null);
        when(detectionEventRepositoryPort.findRecent(isNull(), eq(EventController.DEFAULT_LIMIT)))
                .thenReturn(List.of(onOwnAsset, onForeignAsset, onUnownedDevice));

        MockMvc pilotMockMvc = mockMvcFor(currentUserWithScope(VisibilityScope.assignedAssets(Set.of(ownedAssetId))));

        pilotMockMvc.perform(get("/api/events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].label").value("person"));
    }

    /**
     * The counterpart of {@link #recentFiltersOutEventsOnAssetsOutsideTheCallersScope}: a stream
     * {@link StreamService} reports as genuinely running, on a device belonging to an asset the
     * caller's scope does not reach, must 404 rather than reveal its events — the same "existence is
     * never revealed" rule {@link StreamController#detections} already follows.
     */
    @Test
    void forStreamReturns404ForARunningStreamOnADeviceOutsideTheCallersScope() throws Exception {
        DeviceId deviceId = DeviceId.random();
        AssetId foreignAssetId = AssetId.random();
        Asset foreignAsset = new Asset(foreignAssetId, "someone else's drone", new CategoryId("drone"),
                new Ownership(UserId.random(), GroupId.random()), Set.of(deviceId), Map.of());
        when(streamService.streams())
                .thenReturn(List.of(new ActiveStream(streamId, deviceId, Instant.now())));
        when(assetRepositoryPort.findByDeviceId(deviceId)).thenReturn(Optional.of(foreignAsset));

        MockMvc pilotMockMvc = mockMvcFor(currentUserWithScope(VisibilityScope.assignedAssets(Set.of(AssetId.random()))));

        pilotMockMvc.perform(get("/api/streams/{streamId}/events", streamId.value()))
                .andExpect(status().isNotFound());
    }

    /** The visible-counterpart of the test above: a running stream on the caller's own assigned asset still answers. */
    @Test
    void forStreamStillReturnsEventsForARunningStreamOnTheCallersOwnAssignedAsset() throws Exception {
        DeviceId deviceId = DeviceId.random();
        AssetId ownedAssetId = AssetId.random();
        Asset ownedAsset = new Asset(ownedAssetId, "my drone", new CategoryId("drone"), ownership,
                Set.of(deviceId), Map.of());
        when(streamService.streams())
                .thenReturn(List.of(new ActiveStream(streamId, deviceId, Instant.now())));
        when(assetRepositoryPort.findByDeviceId(deviceId)).thenReturn(Optional.of(ownedAsset));
        DetectionEvent detected = event("person", DetectionEventState.OPEN, ownedAssetId, null);
        when(detectionEventRepositoryPort.findByStream(eq(streamId), eq(EventController.DEFAULT_LIMIT)))
                .thenReturn(List.of(detected));

        MockMvc pilotMockMvc = mockMvcFor(currentUserWithScope(VisibilityScope.assignedAssets(Set.of(ownedAssetId))));

        pilotMockMvc.perform(get("/api/streams/{streamId}/events", streamId.value()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].label").value("person"));
    }
}
