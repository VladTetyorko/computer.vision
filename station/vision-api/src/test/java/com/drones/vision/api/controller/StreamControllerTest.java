package com.drones.vision.api.controller;

import com.drones.vision.api.exception.ApiExceptionHandler;
import com.drones.vision.api.security.CurrentUser;
import com.drones.vision.api.security.PrincipalResolver;
import com.drones.vision.api.security.StreamAccess;
import com.drones.vision.map.application.MapAccessPolicy;
import com.drones.vision.perception.application.pipeline.TrackingStats;
import com.drones.vision.perception.application.profile.CvProfileService;
import com.drones.vision.perception.application.profile.EffectiveProfile;
import com.drones.vision.perception.application.profile.ProfileSource;
import com.drones.vision.perception.application.stream.ActiveStream;
import com.drones.vision.perception.application.stream.PipelineConfigPatch;
import com.drones.vision.perception.application.stream.TrackingConfigPatch;
import com.drones.vision.perception.application.stream.StreamService;
import com.drones.vision.perception.application.stream.UnsupportedProtocolException;
import com.drones.vision.perception.application.stream.UpdateOutcome;
import com.drones.vision.perception.application.pipeline.DetectionRate;
import com.drones.vision.platform.VisibilityScope;
import com.drones.vision.platform.Authority;
import com.drones.vision.identity.domain.model.Role;
import com.drones.vision.warehouse.domain.model.Asset;
import com.drones.vision.warehouse.domain.model.Custody;
import com.drones.vision.warehouse.domain.model.Identity;
import com.drones.vision.warehouse.domain.port.AssetRepositoryPort;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.BoundingBox;
import com.drones.vision.kernel.CategoryId;
import com.drones.vision.perception.domain.model.Detection;
import com.drones.vision.perception.domain.model.DetectionQuery;
import com.drones.vision.perception.domain.model.DetectionResult;
import com.drones.vision.perception.domain.model.DetectionState;
import com.drones.vision.kernel.DeviceId;
import com.drones.vision.kernel.GroupId;
import com.drones.vision.kernel.Ownership;
import com.drones.vision.perception.domain.model.ModelRef;
import com.drones.vision.perception.domain.model.PipelineConfig;
import com.drones.vision.perception.domain.model.PixelFormat;
import com.drones.vision.kernel.StreamId;
import com.drones.vision.perception.domain.model.StreamState;
import com.drones.vision.perception.domain.model.TargetLock;
import com.drones.vision.perception.domain.model.TrackRef;
import com.drones.vision.perception.domain.model.TrackState;
import com.drones.vision.perception.domain.model.TrackedObject;
import com.drones.vision.perception.domain.model.TrackingCapability;
import com.drones.vision.perception.domain.model.TrackingConfig;
import com.drones.vision.perception.domain.model.TrackingMode;
import com.drones.vision.perception.domain.model.TrackingTelemetry;
import com.drones.vision.perception.domain.model.DetectionSource;
import com.drones.vision.perception.domain.model.DetectorReason;
import com.drones.vision.kernel.UserId;
import com.drones.vision.perception.domain.model.VideoFrame;
import com.drones.vision.perception.domain.port.DetectionRepositoryPort;
import com.drones.vision.perception.domain.port.StreamPublisherPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import com.drones.vision.api.live.LiveAndPollDetectionDemand;
import com.drones.vision.api.support.SnapshotJpegEncoder;
import com.drones.vision.api.support.StreamDetectionSupport;
import com.drones.vision.api.support.VisionApiProperties;

import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class StreamControllerTest {

    private StreamService streamService;
    private StreamPublisherPort streamPublisherPort;
    private DetectionRepositoryPort detectionRepositoryPort;
    /** Backs {@link StreamAccess}'s device&rarr;asset&rarr;owner resolution (docs/plans/done/LIVE-SCOPE-PLAN.md §2, W2). */
    private AssetRepositoryPort assetRepositoryPort;
    /**
     * Stubbed to answer every {@link CvProfileService#effective} call with a {@link
     * ProfileSource#PLATFORM} resolution wrapping whatever {@code platformDefault} was actually
     * passed in — byte-identical to the pre-{@code CvProfileService} behavior of merging {@link
     * StreamController}'s start body straight onto {@link StreamDetectionSupport#defaultConfig()},
     * so every pre-existing test below is unaffected. Individual tests may re-stub this for a
     * specific asset to exercise the override-fold behavior instead.
     */
    private CvProfileService cvProfileService;
    /**
     * A real instance (not a mock -- {@code LiveAndPollDetectionDemand} is {@code final} and this
     * repo carries no inline Mockito mock-maker), constructed with a never-watching SSE predicate so
     * only the poll half (docs/plans/done/CV-DEMAND-PLAN.md §3.5) is exercised via {@link
     * #detectionDemand}'s own {@code detectionWanted}/{@code touched} reads in tests below.
     */
    private LiveAndPollDetectionDemand detectionDemand;
    private MockMvc mockMvc;

    private final DeviceId deviceId = DeviceId.random();

    private final UserId ownerId = UserId.random();
    private final Ownership ownership = new Ownership(ownerId, GroupId.random());
    /** Unbounded (auth-off-equivalent) by default, so every pre-existing test below is unaffected. */
    private final CurrentUser currentUser = new CurrentUser(ownership);
    /** The asset {@link #deviceId} belongs to, for the authority tests near the end of this file. */
    private final Asset ownedAsset = Asset.register(AssetId.random(), "my drone", new CategoryId("drone"), ownership,
            Set.of(deviceId), Map.of(), Identity.NONE, Custody.NONE);
    /** An asset the PILOT authority tests below are deliberately NOT scoped to. */
    private final AssetId otherAssetId = AssetId.random();

    @BeforeEach
    void setUp() {
        streamService = mock(StreamService.class);
        streamPublisherPort = mock(StreamPublisherPort.class);
        detectionRepositoryPort = mock(DetectionRepositoryPort.class);
        assetRepositoryPort = mock(AssetRepositoryPort.class);
        when(assetRepositoryPort.findByDeviceId(deviceId)).thenReturn(Optional.of(ownedAsset));
        detectionDemand = new LiveAndPollDetectionDemand(assetId -> false, Duration.ofSeconds(10));
        cvProfileService = mock(CvProfileService.class);
        when(cvProfileService.effective(any(), any(), any(), any())).thenAnswer(invocation -> new EffectiveProfile(
                invocation.getArgument(0), null, null, ProfileSource.PLATFORM, invocation.getArgument(1)));

        mockMvc = mockMvcFor(currentUser);
    }

    /**
     * A {@link CurrentUser} answering with {@link #ownership}/{@link #ownerId} (so stubs keyed on
     * them keep working) but a caller-supplied {@link VisibilityScope} — for the authority tests
     * near the end of this file, which need a PILOT/MANAGER scope rather than the class-level
     * {@link #currentUser}'s unbounded one. {@link PrincipalResolver#viewer()} is never called by
     * {@link StreamController}/{@link StreamAccess}, so it throws rather than fake a map viewer no
     * test here needs — the same idiom {@code AssetControllerTest} uses.
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
                throw new UnsupportedOperationException("StreamController never calls viewer()");
            }

            @Override
            public Role role() {
                throw new UnsupportedOperationException("StreamController never calls role()");
            }

            @Override
            public Authority authority() {
                throw new UnsupportedOperationException("StreamController never calls authority()");
            }
        });
    }

    /**
     * A {@link MockMvc} bound to a fresh {@link StreamController} acting as {@code user} — same
     * mocked {@link #streamService}/{@link #streamPublisherPort}/{@link #detectionRepositoryPort}/
     * {@link #assetRepositoryPort}/{@link #cvProfileService}, only the {@link StreamAccess}'s/{@link
     * StreamDetectionSupport}'s {@link CurrentUser} changes. Rebuilds {@link StreamDetectionSupport}
     * fresh each call (it is a record, cheap to construct) rather than sharing one instance across
     * every {@code currentUser}/{@link #currentUserWithScope} variant — {@code
     * StreamDetectionSupport#resolveStartConfig} now threads its own {@code currentUser} into
     * {@link #cvProfileService}, so a shared instance would silently ignore whichever scope a given
     * test asked for.
     */
    private MockMvc mockMvcFor(CurrentUser user) {
        StreamAccess streamAccess = new StreamAccess(streamService, assetRepositoryPort, user);
        StreamDetectionSupport streamDetectionSupport = new StreamDetectionSupport(PipelineConfig.defaults(),
                detectionDemand, cvProfileService, assetRepositoryPort, user);
        return MockMvcBuilders
                .standaloneSetup(new StreamController(streamService, streamPublisherPort, detectionRepositoryPort,
                        new SnapshotJpegEncoder(VisionApiProperties.defaults()), streamDetectionSupport,
                        streamAccess))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private static DetectionResult detectionResult(StreamId streamId, long frameSequence, Instant capturedAt) {
        Detection detection = new Detection("person", 0.87, new BoundingBox(0.1, 0.2, 0.3, 0.4),
                new ModelRef("yolo", "latest"));
        return new DetectionResult(streamId, frameSequence, capturedAt, List.of(detection), Duration.ofMillis(42));
    }

    @Test
    void startReturns201WithStreamIdAndViewUrlWhenPublisherHasOne() throws Exception {
        StreamId streamId = StreamId.random();
        when(streamService.start(any(), any(), any())).thenReturn(streamId);
        when(streamPublisherPort.viewUrl(streamId))
                .thenReturn(Optional.of(URI.create("http://localhost:8888/" + streamId.value() + "/index.m3u8")));

        mockMvc.perform(post("/api/devices/{deviceId}/stream", deviceId.value()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.streamId").value(streamId.value().toString()))
                .andExpect(jsonPath("$.viewUrl")
                        .value("http://localhost:8888/" + streamId.value() + "/index.m3u8"));
    }

    @Test
    void startOmitsViewUrlWhenPublisherHasNone() throws Exception {
        StreamId streamId = StreamId.random();
        when(streamService.start(any(), any(), any())).thenReturn(streamId);
        when(streamPublisherPort.viewUrl(streamId)).thenReturn(Optional.empty());

        mockMvc.perform(post("/api/devices/{deviceId}/stream", deviceId.value()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.streamId").value(streamId.value().toString()))
                .andExpect(jsonPath("$.viewUrl").doesNotExist());
    }

    // ---- docs/plans/done/MVP2-PLAN.md §L: whepUrl beside viewUrl ----

    @Test
    void startReturns201WithWhepUrlWhenPublisherHasOne() throws Exception {
        StreamId streamId = StreamId.random();
        when(streamService.start(any(), any(), any())).thenReturn(streamId);
        when(streamPublisherPort.viewUrl(streamId))
                .thenReturn(Optional.of(URI.create("http://localhost:8888/" + streamId.value() + "/index.m3u8")));
        when(streamPublisherPort.whepUrl(streamId))
                .thenReturn(Optional.of(URI.create("http://localhost:18889/" + streamId.value() + "/whep")));

        mockMvc.perform(post("/api/devices/{deviceId}/stream", deviceId.value()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.streamId").value(streamId.value().toString()))
                .andExpect(jsonPath("$.viewUrl")
                        .value("http://localhost:8888/" + streamId.value() + "/index.m3u8"))
                .andExpect(jsonPath("$.whepUrl")
                        .value("http://localhost:18889/" + streamId.value() + "/whep"));
    }

    @Test
    void startOmitsWhepUrlWhenPublisherHasNoWebRtcEndpoint() throws Exception {
        StreamId streamId = StreamId.random();
        when(streamService.start(any(), any(), any())).thenReturn(streamId);
        when(streamPublisherPort.viewUrl(streamId))
                .thenReturn(Optional.of(URI.create("http://localhost:8888/" + streamId.value() + "/index.m3u8")));
        when(streamPublisherPort.whepUrl(streamId)).thenReturn(Optional.empty());

        mockMvc.perform(post("/api/devices/{deviceId}/stream", deviceId.value()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.viewUrl")
                        .value("http://localhost:8888/" + streamId.value() + "/index.m3u8"))
                .andExpect(jsonPath("$.whepUrl").doesNotExist());
    }

    // --- STREAM-STATE-PLAN S2: the truth on the wire -------------------------------------------

    @Test
    void listCarriesTheStateAndDetectionIntentAClientUsedToGuess() throws Exception {
        StreamId streamId = StreamId.random();
        when(streamService.streams()).thenReturn(List.of(
                new ActiveStream(streamId, DeviceId.random(), Instant.now(), StreamState.LIVE, true)));
        when(streamService.detectionState(streamId)).thenReturn(Optional.of(DetectionState.RUNNING));

        mockMvc.perform(get("/api/streams"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].state").value("LIVE"))
                .andExpect(jsonPath("$[0].detectionEnabled").value(true))
                .andExpect(jsonPath("$[0].detectionState").value("RUNNING"));
    }

    @Test
    void listReportsDetectionOffAndAStalledSourceAsTwoIndependentFacts() throws Exception {
        // The whole point of keeping the axes apart: "the video died" and "the operator turned
        // detection off" must be separately readable, not folded into one status.
        StreamId streamId = StreamId.random();
        when(streamService.streams()).thenReturn(List.of(
                new ActiveStream(streamId, DeviceId.random(), Instant.now(), StreamState.STALLED, false)));
        when(streamService.detectionState(streamId)).thenReturn(Optional.of(DetectionState.OFF));

        mockMvc.perform(get("/api/streams"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].state").value("STALLED"))
                .andExpect(jsonPath("$[0].detectionEnabled").value(false))
                .andExpect(jsonPath("$[0].detectionState").value("OFF"));
    }

    @Test
    void configReadsBackWhatWasOnlyEverWritable() throws Exception {
        StreamId streamId = StreamId.random();
        PipelineConfig running = new PipelineConfig(new ModelRef("yolo26n.pt", "latest"), 0.55, 12, 2,
                Set.of("person", "car"), PipelineConfig.defaults().eventRule(), true,
                new TrackingConfig(TrackingMode.FOLLOW, "cost", 2000, 15, 30, 30, 3, 0, 0, null),
                Set.of("bird"));
        when(streamService.config(streamId)).thenReturn(Optional.of(running));

        mockMvc.perform(get("/api/streams/{id}/config", streamId.value()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.model").value("yolo26n.pt"))
                .andExpect(jsonPath("$.confidenceThreshold").value(0.55))
                .andExpect(jsonPath("$.inferenceFps").value(12))
                .andExpect(jsonPath("$.detectionEnabled").value(true))
                .andExpect(jsonPath("$.labelFilter", hasSize(2)))
                .andExpect(jsonPath("$.labelDenyFilter", hasSize(1)))
                .andExpect(jsonPath("$.tracking.mode").value("FOLLOW"))
                .andExpect(jsonPath("$.tracking.engineId").value("cost"));
    }

    @Test
    void configIs404ForAnUnknownStreamRatherThanPlausibleDefaults() throws Exception {
        // Deliberately NOT the forgiving 200-with-defaults idiom `tracks` uses: a made-up config for
        // a stream that does not exist is exactly the fiction this endpoint exists to abolish.
        when(streamService.config(any())).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/streams/{id}/config", StreamId.random().value()))
                .andExpect(status().isNotFound());
    }

    @Test
    void startUsesPipelineConfigDefaultsWhenBodyAbsent() throws Exception {
        StreamId streamId = StreamId.random();
        when(streamService.start(any(), any(), any())).thenReturn(streamId);
        when(streamPublisherPort.viewUrl(streamId)).thenReturn(Optional.empty());

        mockMvc.perform(post("/api/devices/{deviceId}/stream", deviceId.value()))
                .andExpect(status().isCreated());

        ArgumentCaptor<PipelineConfig> captor = ArgumentCaptor.forClass(PipelineConfig.class);
        verify(streamService).start(eq(deviceId), captor.capture(), any());
        assertEquals(PipelineConfig.defaults(), captor.getValue());
    }

    @Test
    void startMergesRequestOverridesOntoDefaults() throws Exception {
        StreamId streamId = StreamId.random();
        when(streamService.start(any(), any(), any())).thenReturn(streamId);
        when(streamPublisherPort.viewUrl(streamId)).thenReturn(Optional.empty());

        String body = """
                {"confidenceThreshold":0.75,"inferenceFps":10}
                """;

        mockMvc.perform(post("/api/devices/{deviceId}/stream", deviceId.value())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());

        ArgumentCaptor<PipelineConfig> captor = ArgumentCaptor.forClass(PipelineConfig.class);
        verify(streamService).start(eq(deviceId), captor.capture(), any());
        PipelineConfig defaults = PipelineConfig.defaults();
        PipelineConfig config = captor.getValue();
        assertEquals(0.75, config.confidenceThreshold());
        assertEquals(10, config.inferenceFps());
        assertEquals(defaults.model(), config.model());
        assertEquals(defaults.maxInFlightInferences(), config.maxInFlightInferences());
        assertEquals(defaults.labelFilter(), config.labelFilter());
        assertEquals(defaults.labelDenyFilter(), config.labelDenyFilter());
    }

    @Test
    void startMergesPartialOverrideKeepingOtherDefault() throws Exception {
        StreamId streamId = StreamId.random();
        when(streamService.start(any(), any(), any())).thenReturn(streamId);
        when(streamPublisherPort.viewUrl(streamId)).thenReturn(Optional.empty());

        String body = """
                {"inferenceFps":15}
                """;

        mockMvc.perform(post("/api/devices/{deviceId}/stream", deviceId.value())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());

        ArgumentCaptor<PipelineConfig> captor = ArgumentCaptor.forClass(PipelineConfig.class);
        verify(streamService).start(eq(deviceId), captor.capture(), any());
        PipelineConfig defaults = PipelineConfig.defaults();
        PipelineConfig config = captor.getValue();
        assertEquals(defaults.confidenceThreshold(), config.confidenceThreshold());
        assertEquals(15, config.inferenceFps());
    }

    @Test
    void startMergesLabelDenyFilterOverrideOntoDefaults() throws Exception {
        // docs/plans/done/CV-CLEAN-FEED-PLAN.md D-2: labelDenyFilter is per-stream settable exactly
        // like labelFilter/confidenceThreshold/inferenceFps above.
        StreamId streamId = StreamId.random();
        when(streamService.start(any(), any(), any())).thenReturn(streamId);
        when(streamPublisherPort.viewUrl(streamId)).thenReturn(Optional.empty());

        String body = """
                {"labelDenyFilter":["bird"]}
                """;

        mockMvc.perform(post("/api/devices/{deviceId}/stream", deviceId.value())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());

        ArgumentCaptor<PipelineConfig> captor = ArgumentCaptor.forClass(PipelineConfig.class);
        verify(streamService).start(eq(deviceId), captor.capture(), any());
        PipelineConfig defaults = PipelineConfig.defaults();
        PipelineConfig config = captor.getValue();
        assertEquals(Set.of("bird"), config.labelDenyFilter());
        assertEquals(defaults.confidenceThreshold(), config.confidenceThreshold());
        assertEquals(defaults.inferenceFps(), config.inferenceFps());
    }

    @Test
    void startMergesModelOverrideOntoDefaults() throws Exception {
        // The frontend's detection-model picker sends a model id here -- possibly a
        // comma-composite ("yolo11n.pt,orion12l.pt") cv-service's own registry parses
        // server-side; this DTO/PipelineConfig must carry it through verbatim, unsplit.
        StreamId streamId = StreamId.random();
        when(streamService.start(any(), any(), any())).thenReturn(streamId);
        when(streamPublisherPort.viewUrl(streamId)).thenReturn(Optional.empty());

        String body = """
                {"model":"yolo11n.pt,orion12l.pt"}
                """;

        mockMvc.perform(post("/api/devices/{deviceId}/stream", deviceId.value())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());

        ArgumentCaptor<PipelineConfig> captor = ArgumentCaptor.forClass(PipelineConfig.class);
        verify(streamService).start(eq(deviceId), captor.capture(), any());
        PipelineConfig defaults = PipelineConfig.defaults();
        PipelineConfig config = captor.getValue();
        assertEquals("yolo11n.pt,orion12l.pt", config.model().id());
        assertEquals(defaults.model().version(), config.model().version());
        assertEquals(defaults.confidenceThreshold(), config.confidenceThreshold());
        assertEquals(defaults.inferenceFps(), config.inferenceFps());
    }

    @Test
    void startWithoutModelKeepsTheDefaultModel() throws Exception {
        StreamId streamId = StreamId.random();
        when(streamService.start(any(), any(), any())).thenReturn(streamId);
        when(streamPublisherPort.viewUrl(streamId)).thenReturn(Optional.empty());

        String body = """
                {"confidenceThreshold":0.6}
                """;

        mockMvc.perform(post("/api/devices/{deviceId}/stream", deviceId.value())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());

        ArgumentCaptor<PipelineConfig> captor = ArgumentCaptor.forClass(PipelineConfig.class);
        verify(streamService).start(eq(deviceId), captor.capture(), any());
        assertEquals(PipelineConfig.defaults().model(), captor.getValue().model());
    }

    @Test
    void startTreatsABlankModelAsAbsent() throws Exception {
        StreamId streamId = StreamId.random();
        when(streamService.start(any(), any(), any())).thenReturn(streamId);
        when(streamPublisherPort.viewUrl(streamId)).thenReturn(Optional.empty());

        String body = """
                {"model":"   "}
                """;

        mockMvc.perform(post("/api/devices/{deviceId}/stream", deviceId.value())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());

        ArgumentCaptor<PipelineConfig> captor = ArgumentCaptor.forClass(PipelineConfig.class);
        verify(streamService).start(eq(deviceId), captor.capture(), any());
        assertEquals(PipelineConfig.defaults().model(), captor.getValue().model());
    }

    // ---- docs/plans/done/CV-CONTROL-PLAN.md §2: labelFilter/detectionEnabled start overrides ----

    @Test
    void startMergesLabelFilterOverrideOntoDefaults() throws Exception {
        StreamId streamId = StreamId.random();
        when(streamService.start(any(), any(), any())).thenReturn(streamId);
        when(streamPublisherPort.viewUrl(streamId)).thenReturn(Optional.empty());

        String body = """
                {"labelFilter":["person","car"]}
                """;

        mockMvc.perform(post("/api/devices/{deviceId}/stream", deviceId.value())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());

        ArgumentCaptor<PipelineConfig> captor = ArgumentCaptor.forClass(PipelineConfig.class);
        verify(streamService).start(eq(deviceId), captor.capture(), any());
        assertEquals(Set.of("person", "car"), captor.getValue().labelFilter());
    }

    @Test
    void startWithoutLabelFilterKeepsTheDefaultEmptySet() throws Exception {
        StreamId streamId = StreamId.random();
        when(streamService.start(any(), any(), any())).thenReturn(streamId);
        when(streamPublisherPort.viewUrl(streamId)).thenReturn(Optional.empty());

        mockMvc.perform(post("/api/devices/{deviceId}/stream", deviceId.value()))
                .andExpect(status().isCreated());

        ArgumentCaptor<PipelineConfig> captor = ArgumentCaptor.forClass(PipelineConfig.class);
        verify(streamService).start(eq(deviceId), captor.capture(), any());
        assertEquals(PipelineConfig.defaults().labelFilter(), captor.getValue().labelFilter());
    }

    @Test
    void startMergesDetectionEnabledFalseOverrideOntoDefaults() throws Exception {
        StreamId streamId = StreamId.random();
        when(streamService.start(any(), any(), any())).thenReturn(streamId);
        when(streamPublisherPort.viewUrl(streamId)).thenReturn(Optional.empty());

        String body = """
                {"detectionEnabled":false}
                """;

        mockMvc.perform(post("/api/devices/{deviceId}/stream", deviceId.value())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());

        ArgumentCaptor<PipelineConfig> captor = ArgumentCaptor.forClass(PipelineConfig.class);
        verify(streamService).start(eq(deviceId), captor.capture(), any());
        assertFalse(captor.getValue().detectionEnabled());
    }

    @Test
    void startWithoutDetectionEnabledKeepsWhateverStreamDetectionSupportsDefaultConfigSays() throws Exception {
        // docs/plans/done/CV-DEMAND-PLAN.md §1/§3.8 (wave D1, already landed): PipelineConfig.defaults()'s
        // own detectionEnabled flipped to false -- a new stream is video-only until an operator or a
        // vision.cv.detection-default-enabled=true deployment override turns it on. This controller
        // never hardcodes that value itself; it merges purely onto whatever StreamDetectionSupport
        // hands it (setUp() below wires PipelineConfig.defaults() verbatim), so this test pins "an
        // absent field falls through to the supplied default", not a literal true/false.
        StreamId streamId = StreamId.random();
        when(streamService.start(any(), any(), any())).thenReturn(streamId);
        when(streamPublisherPort.viewUrl(streamId)).thenReturn(Optional.empty());

        mockMvc.perform(post("/api/devices/{deviceId}/stream", deviceId.value()))
                .andExpect(status().isCreated());

        ArgumentCaptor<PipelineConfig> captor = ArgumentCaptor.forClass(PipelineConfig.class);
        verify(streamService).start(eq(deviceId), captor.capture(), any());
        assertEquals(PipelineConfig.defaults().detectionEnabled(), captor.getValue().detectionEnabled());
    }

    @Test
    void startReturns404WhenDeviceIsUnknown() throws Exception {
        when(streamService.start(any(), any(), any()))
                .thenThrow(new NoSuchElementException("Unknown device: " + deviceId.value()));

        mockMvc.perform(post("/api/devices/{deviceId}/stream", deviceId.value()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }

    @Test
    void startReturns409WhenDeviceAlreadyStreaming() throws Exception {
        when(streamService.start(any(), any(), any()))
                .thenThrow(new IllegalStateException("Device already has an active stream: " + deviceId.value()));

        mockMvc.perform(post("/api/devices/{deviceId}/stream", deviceId.value()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("CONFLICT"));
    }

    @Test
    void startReturns400ForUnsupportedProtocol() throws Exception {
        when(streamService.start(any(), any(), any())).thenThrow(new UnsupportedProtocolException("mjpeg"));

        mockMvc.perform(post("/api/devices/{deviceId}/stream", deviceId.value()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));
    }

    @Test
    void listReturnsActiveStreamsWithViewUrlWhenPresent() throws Exception {
        StreamId streamId = StreamId.random();
        Instant startedAt = Instant.parse("2026-07-22T10:00:00Z");
        when(streamService.streams())
                .thenReturn(List.of(new ActiveStream(streamId, deviceId, startedAt)));
        when(streamPublisherPort.viewUrl(streamId))
                .thenReturn(Optional.of(URI.create("http://localhost:8888/" + streamId.value() + "/index.m3u8")));

        mockMvc.perform(get("/api/streams"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].streamId").value(streamId.value().toString()))
                .andExpect(jsonPath("$[0].deviceId").value(deviceId.value().toString()))
                .andExpect(jsonPath("$[0].startedAt").value("2026-07-22T10:00:00Z"))
                .andExpect(jsonPath("$[0].viewUrl")
                        .value("http://localhost:8888/" + streamId.value() + "/index.m3u8"));
    }

    @Test
    void listOmitsViewUrlWhenAbsent() throws Exception {
        StreamId streamId = StreamId.random();
        Instant startedAt = Instant.parse("2026-07-22T10:00:00Z");
        when(streamService.streams())
                .thenReturn(List.of(new ActiveStream(streamId, deviceId, startedAt)));
        when(streamPublisherPort.viewUrl(streamId)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/streams"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].viewUrl").doesNotExist());
    }

    @Test
    void listReturnsActiveStreamsWithWhepUrlWhenPresent() throws Exception {
        StreamId streamId = StreamId.random();
        Instant startedAt = Instant.parse("2026-07-22T10:00:00Z");
        when(streamService.streams())
                .thenReturn(List.of(new ActiveStream(streamId, deviceId, startedAt)));
        when(streamPublisherPort.viewUrl(streamId))
                .thenReturn(Optional.of(URI.create("http://localhost:8888/" + streamId.value() + "/index.m3u8")));
        when(streamPublisherPort.whepUrl(streamId))
                .thenReturn(Optional.of(URI.create("http://localhost:18889/" + streamId.value() + "/whep")));

        mockMvc.perform(get("/api/streams"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].whepUrl")
                        .value("http://localhost:18889/" + streamId.value() + "/whep"));
    }

    @Test
    void listOmitsWhepUrlWhenAbsent() throws Exception {
        StreamId streamId = StreamId.random();
        Instant startedAt = Instant.parse("2026-07-22T10:00:00Z");
        when(streamService.streams())
                .thenReturn(List.of(new ActiveStream(streamId, deviceId, startedAt)));
        when(streamPublisherPort.viewUrl(streamId))
                .thenReturn(Optional.of(URI.create("http://localhost:8888/" + streamId.value() + "/index.m3u8")));
        when(streamPublisherPort.whepUrl(streamId)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/streams"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].whepUrl").doesNotExist());
    }

    @Test
    void listReturnsEmptyWhenNoActiveStreams() throws Exception {
        when(streamService.streams()).thenReturn(List.of());

        mockMvc.perform(get("/api/streams"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void stopReturns204AndDelegatesToService() throws Exception {
        StreamId streamId = StreamId.random();

        mockMvc.perform(delete("/api/streams/{streamId}", streamId.value()))
                .andExpect(status().isNoContent());

        verify(streamService).stop(eq(streamId));
    }

    @Test
    void stopReturns204EvenWhenStreamIsUnknownPerNoOpContract() throws Exception {
        // StreamService.stop is documented as a no-op for unknown/already
        // stopped streams; the controller never sees an exception here, so
        // there is no 404 case for this endpoint.
        StreamId streamId = StreamId.random();

        mockMvc.perform(delete("/api/streams/{streamId}", streamId.value()))
                .andExpect(status().isNoContent());
    }

    @Test
    void detectionsMapsRepositoryResultsAndUsesDefaultLimitOfFifty() throws Exception {
        StreamId streamId = StreamId.random();
        Instant capturedAt = Instant.parse("2026-07-23T10:00:00Z");
        DetectionResult result = detectionResult(streamId, 7, capturedAt);
        when(detectionRepositoryPort.query(any())).thenReturn(List.of(result));

        mockMvc.perform(get("/api/streams/{streamId}/detections", streamId.value()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].streamId").value(streamId.value().toString()))
                .andExpect(jsonPath("$[0].frameSequence").value(7))
                .andExpect(jsonPath("$[0].capturedAt").value("2026-07-23T10:00:00Z"))
                .andExpect(jsonPath("$[0].inferenceMillis").value(42))
                .andExpect(jsonPath("$[0].detections", hasSize(1)))
                .andExpect(jsonPath("$[0].detections[0].label").value("person"))
                .andExpect(jsonPath("$[0].detections[0].confidence").value(0.87))
                .andExpect(jsonPath("$[0].detections[0].box.x").value(0.1))
                .andExpect(jsonPath("$[0].detections[0].box.y").value(0.2))
                .andExpect(jsonPath("$[0].detections[0].box.width").value(0.3))
                .andExpect(jsonPath("$[0].detections[0].box.height").value(0.4))
                .andExpect(jsonPath("$[0].detections[0].modelId").value("yolo"))
                .andExpect(jsonPath("$[0].detections[0].modelVersion").value("latest"));

        ArgumentCaptor<DetectionQuery> captor = ArgumentCaptor.forClass(DetectionQuery.class);
        verify(detectionRepositoryPort).query(captor.capture());
        DetectionQuery query = captor.getValue();
        assertEquals(streamId, query.streamId());
        assertEquals(50, query.limit());
        assertEquals(null, query.from());
        assertEquals(null, query.to());
        assertEquals(null, query.label());
    }

    @Test
    void detectionsPassesExplicitLimitThrough() throws Exception {
        StreamId streamId = StreamId.random();
        when(detectionRepositoryPort.query(any())).thenReturn(List.of());

        mockMvc.perform(get("/api/streams/{streamId}/detections", streamId.value()).param("limit", "5"))
                .andExpect(status().isOk());

        ArgumentCaptor<DetectionQuery> captor = ArgumentCaptor.forClass(DetectionQuery.class);
        verify(detectionRepositoryPort).query(captor.capture());
        assertEquals(5, captor.getValue().limit());
    }

    @Test
    void detectionsSortsResultsNewestFirstRegardlessOfRepositoryOrder() throws Exception {
        StreamId streamId = StreamId.random();
        Instant older = Instant.parse("2026-07-23T10:00:00Z");
        Instant newer = Instant.parse("2026-07-23T10:00:05Z");
        DetectionResult olderResult = detectionResult(streamId, 1, older);
        DetectionResult newerResult = detectionResult(streamId, 2, newer);
        // Deliberately returned oldest-first, to prove the controller sorts rather than trusting order.
        when(detectionRepositoryPort.query(any())).thenReturn(List.of(olderResult, newerResult));

        mockMvc.perform(get("/api/streams/{streamId}/detections", streamId.value()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].frameSequence").value(2))
                .andExpect(jsonPath("$[1].frameSequence").value(1));
    }

    @Test
    void detectionsReturns400ForNonPositiveLimit() throws Exception {
        StreamId streamId = StreamId.random();

        mockMvc.perform(get("/api/streams/{streamId}/detections", streamId.value()).param("limit", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));
    }

    @Test
    void detectionsReturnsEmptyListForUnknownStream() throws Exception {
        StreamId streamId = StreamId.random();
        when(detectionRepositoryPort.query(any())).thenReturn(List.of());

        mockMvc.perform(get("/api/streams/{streamId}/detections", streamId.value()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void detectionsTouchesTheDemandPortSoAPollingReaderCountsAsDemand() throws Exception {
        // docs/plans/done/CV-DEMAND-PLAN.md §3.5's poll half: a Wall/Live page hitting this endpoint
        // has no asset id to subscribe to over SSE, so the read itself has to register the demand.
        StreamId streamId = StreamId.random();
        when(detectionRepositoryPort.query(any())).thenReturn(List.of());
        assertFalse(detectionDemand.detectionWanted(streamId, null), "not demanded before the first read");

        mockMvc.perform(get("/api/streams/{streamId}/detections", streamId.value()))
                .andExpect(status().isOk());

        assertTrue(detectionDemand.detectionWanted(streamId, null), "reading detections counts as demand");
    }

    // ---- docs/plans/done/CV-CONTROL-PLAN.md §3: PATCH /api/streams/{streamId}/config ----

    @Test
    void updateConfigReturnsStreamIdAndModelReArmedFalseForHotKnobs() throws Exception {
        StreamId streamId = StreamId.random();
        when(streamService.updateConfig(eq(streamId), any())).thenReturn(new UpdateOutcome(false));

        String body = """
                {"confidenceThreshold":0.5,"inferenceFps":5}
                """;

        mockMvc.perform(patch("/api/streams/{streamId}/config", streamId.value())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.streamId").value(streamId.value().toString()))
                .andExpect(jsonPath("$.modelReArmed").value(false));

        ArgumentCaptor<PipelineConfigPatch> captor = ArgumentCaptor.forClass(PipelineConfigPatch.class);
        verify(streamService).updateConfig(eq(streamId), captor.capture());
        PipelineConfigPatch patch = captor.getValue();
        assertEquals(0.5, patch.confidenceThreshold());
        assertEquals(5, patch.inferenceFps());
        assertNull(patch.labelFilter());
        assertNull(patch.detectionEnabled());
        assertNull(patch.modelId());
    }

    @Test
    void updateConfigReturnsModelReArmedTrueWhenTheModelChanged() throws Exception {
        StreamId streamId = StreamId.random();
        when(streamService.updateConfig(eq(streamId), any())).thenReturn(new UpdateOutcome(true));

        String body = """
                {"model":"yoloe-26s-seg-pf.pt"}
                """;

        mockMvc.perform(patch("/api/streams/{streamId}/config", streamId.value())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.streamId").value(streamId.value().toString()))
                .andExpect(jsonPath("$.modelReArmed").value(true));

        ArgumentCaptor<PipelineConfigPatch> captor = ArgumentCaptor.forClass(PipelineConfigPatch.class);
        verify(streamService).updateConfig(eq(streamId), captor.capture());
        assertEquals("yoloe-26s-seg-pf.pt", captor.getValue().modelId());
    }

    @Test
    void updateConfigThreadsLabelFilterAndDetectionEnabledThroughToThePatch() throws Exception {
        StreamId streamId = StreamId.random();
        when(streamService.updateConfig(eq(streamId), any())).thenReturn(new UpdateOutcome(false));

        String body = """
                {"labelFilter":["person","building"],"detectionEnabled":false}
                """;

        mockMvc.perform(patch("/api/streams/{streamId}/config", streamId.value())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());

        ArgumentCaptor<PipelineConfigPatch> captor = ArgumentCaptor.forClass(PipelineConfigPatch.class);
        verify(streamService).updateConfig(eq(streamId), captor.capture());
        PipelineConfigPatch patch = captor.getValue();
        assertEquals(Set.of("person", "building"), patch.labelFilter());
        assertFalse(patch.detectionEnabled());
    }

    @Test
    void updateConfigReturns404ForAnUnknownOrNotRunningStream() throws Exception {
        StreamId streamId = StreamId.random();
        when(streamService.updateConfig(eq(streamId), any()))
                .thenThrow(new NoSuchElementException("Unknown or not-running stream: " + streamId.value()));

        mockMvc.perform(patch("/api/streams/{streamId}/config", streamId.value())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }

    @Test
    void updateConfigReturns400ForAnInvalidValue() throws Exception {
        StreamId streamId = StreamId.random();
        when(streamService.updateConfig(eq(streamId), any()))
                .thenThrow(new IllegalArgumentException(
                        "PipelineConfig confidenceThreshold must be within [0,1]: 2.0"));

        String body = """
                {"confidenceThreshold":2.0}
                """;

        mockMvc.perform(patch("/api/streams/{streamId}/config", streamId.value())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));
    }

    @Test
    void updateConfigAcceptsAnAbsentBodyAsANoOpPatch() throws Exception {
        StreamId streamId = StreamId.random();
        when(streamService.updateConfig(eq(streamId), any())).thenReturn(new UpdateOutcome(false));

        mockMvc.perform(patch("/api/streams/{streamId}/config", streamId.value()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.modelReArmed").value(false));

        ArgumentCaptor<PipelineConfigPatch> captor = ArgumentCaptor.forClass(PipelineConfigPatch.class);
        verify(streamService).updateConfig(eq(streamId), captor.capture());
        assertEquals(PipelineConfigPatch.NOTHING, captor.getValue());
    }

    // ---- docs/plans/done/MVP3-PLAN.md C-a: GET /api/streams/{streamId}/snapshot ----

    private static byte[] tinyJpeg(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", out);
        return out.toByteArray();
    }

    @Test
    void snapshotReturnsJpegBytesWithNoStoreCacheControl() throws Exception {
        StreamId streamId = StreamId.random();
        byte[] jpegBytes = tinyJpeg(2, 2); // already small: SnapshotJpegEncoder passes it through untouched
        VideoFrame frame = new VideoFrame(streamId, 0, Instant.now(), 2, 2, PixelFormat.JPEG,
                ByteBuffer.wrap(jpegBytes));
        when(streamService.latestFrame(streamId)).thenReturn(Optional.of(frame));

        mockMvc.perform(get("/api/streams/{streamId}/snapshot", streamId.value()))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", MediaType.IMAGE_JPEG_VALUE))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(result -> assertArrayEquals(jpegBytes, result.getResponse().getContentAsByteArray()));
    }

    @Test
    void snapshotReturns404WhenTheStreamHasNoFrameYet() throws Exception {
        StreamId streamId = StreamId.random();
        when(streamService.latestFrame(streamId)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/streams/{streamId}/snapshot", streamId.value()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }

    @Test
    void snapshotReturns404ForAnUnknownStream() throws Exception {
        // StreamService#latestFrame makes no distinction between "unknown" and "known but no frame
        // yet" -- both are Optional.empty(), and both map to the same 404 here.
        when(streamService.latestFrame(any())).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/streams/{streamId}/snapshot", StreamId.random().value()))
                .andExpect(status().isNotFound());
    }

    @Test
    void snapshotReturns400ForAMalformedStreamId() throws Exception {
        mockMvc.perform(get("/api/streams/{streamId}/snapshot", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));
    }

    // ---- docs/plans/done/TRACKING-PLAN.md §4.D: the `tracking` object on PATCH .../config ----

    @Test
    void updateConfigThreadsTheTrackingObjectThroughToThePatchAndReportsItChanged() throws Exception {
        StreamId streamId = StreamId.random();
        when(streamService.updateConfig(eq(streamId), any())).thenReturn(new UpdateOutcome(false, true));

        String body = """
                {"tracking":{"mode":"FOLLOW","engineId":"lk","verifyEveryMillis":1500,"followFps":20,
                "redetectIouPercent":40,"maxAgeFrames":25,"minHits":2}}
                """;

        mockMvc.perform(patch("/api/streams/{streamId}/config", streamId.value())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.modelReArmed").value(false))
                .andExpect(jsonPath("$.trackingChanged").value(true));

        ArgumentCaptor<PipelineConfigPatch> captor = ArgumentCaptor.forClass(PipelineConfigPatch.class);
        verify(streamService).updateConfig(eq(streamId), captor.capture());
        TrackingConfigPatch tracking = captor.getValue().tracking();
        assertEquals(TrackingMode.FOLLOW, tracking.mode());
        assertEquals("lk", tracking.engineId());
        assertEquals(1500, tracking.verifyEveryMillis());
        assertEquals(20, tracking.followFps());
        assertEquals(40, tracking.redetectIouPercent());
        assertEquals(25, tracking.maxAgeFrames());
        assertEquals(2, tracking.minHits());
        assertNull(tracking.lock());
        // A tracking change is a hot knob: it must never look like a model swap.
        assertNull(captor.getValue().modelId());
    }

    // ---- docs/plans/active/TRACKING-V3-BAND1-CONTEXT.md §2: capabilityLevel/reupdateMaxGapMillis on PATCH ----

    @Test
    void updateConfigThreadsCapabilityLevelAndReupdateMaxGapMillisThroughToThePatch() throws Exception {
        StreamId streamId = StreamId.random();
        when(streamService.updateConfig(eq(streamId), any())).thenReturn(new UpdateOutcome(false, true));

        String body = """
                {"tracking":{"capabilityLevel":2,"reupdateMaxGapMillis":750}}
                """;

        mockMvc.perform(patch("/api/streams/{streamId}/config", streamId.value())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trackingChanged").value(true));

        ArgumentCaptor<PipelineConfigPatch> captor = ArgumentCaptor.forClass(PipelineConfigPatch.class);
        verify(streamService).updateConfig(eq(streamId), captor.capture());
        TrackingConfigPatch tracking = captor.getValue().tracking();
        assertEquals(2, tracking.capabilityLevel(), "a request states a CEILING -- what actually ran is a "
                + "response fact, never mirrored back onto the request-mapping side (invariant B5)");
        assertEquals(750, tracking.reupdateMaxGapMillis());
        assertNull(tracking.mode(), "a capability-only patch states nothing about the mode");
    }

    @Test
    void updateConfigWithoutATrackingObjectLeavesTheTrackingPatchNullAndReadsNoStats() throws Exception {
        StreamId streamId = StreamId.random();
        when(streamService.updateConfig(eq(streamId), any())).thenReturn(new UpdateOutcome(false));

        mockMvc.perform(patch("/api/streams/{streamId}/config", streamId.value())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"inferenceFps\":5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trackingChanged").value(false));

        ArgumentCaptor<PipelineConfigPatch> captor = ArgumentCaptor.forClass(PipelineConfigPatch.class);
        verify(streamService).updateConfig(eq(streamId), captor.capture());
        assertNull(captor.getValue().tracking());
        verify(streamService, never()).trackingStats(any());
    }

    @Test
    void updateConfigLockCarriesLockSeqZeroForTheApplicationLayerToStamp() throws Exception {
        StreamId streamId = StreamId.random();
        when(streamService.updateConfig(eq(streamId), any())).thenReturn(new UpdateOutcome(false, true));

        String body = """
                {"tracking":{"mode":"FOLLOW","lock":{"trackId":7}}}
                """;

        mockMvc.perform(patch("/api/streams/{streamId}/config", streamId.value())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());

        ArgumentCaptor<PipelineConfigPatch> captor = ArgumentCaptor.forClass(PipelineConfigPatch.class);
        verify(streamService).updateConfig(eq(streamId), captor.capture());
        TargetLock lock = captor.getValue().tracking().lock();
        assertEquals(7L, lock.trackId());
        assertEquals(0L, lock.lockSeq(), "a client never allocates lockSeq -- DefaultStreamService stamps it");
        assertFalse(lock.release());
    }

    @Test
    void updateConfigReturns400WhenTheLockNamesTwoOfItsThreeForms() throws Exception {
        StreamId streamId = StreamId.random();

        String body = """
                {"tracking":{"mode":"FOLLOW","lock":{"trackId":7,"pointX":0.5,"pointY":0.5}}}
                """;

        mockMvc.perform(patch("/api/streams/{streamId}/config", streamId.value())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));

        verify(streamService, never()).updateConfig(any(), any());
    }

    @Test
    void updateConfigReturns400ForAnUnknownTrackingMode() throws Exception {
        StreamId streamId = StreamId.random();

        mockMvc.perform(patch("/api/streams/{streamId}/config", streamId.value())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tracking\":{\"mode\":\"CHASE\"}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));

        verify(streamService, never()).updateConfig(any(), any());
    }

    @Test
    void updateConfigPassesAPartialTrackingObjectThroughAsAPartialPatchAndReconstructsNothing() throws Exception {
        // The SPA sends one knob at a time ({"tracking":{"verifyEveryMillis":5000}}). Everything this
        // edge does not carry stays null -- "leave that knob alone" -- and the fold onto the running
        // configuration happens in the application layer, which is the only place that holds it. This
        // controller reading state back off trackingStats is precisely what dropped the cadences.
        StreamId streamId = StreamId.random();
        when(streamService.updateConfig(eq(streamId), any())).thenReturn(new UpdateOutcome(false, true));

        mockMvc.perform(patch("/api/streams/{streamId}/config", streamId.value())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tracking\":{\"verifyEveryMillis\":5000}}"))
                .andExpect(status().isOk());

        ArgumentCaptor<PipelineConfigPatch> captor = ArgumentCaptor.forClass(PipelineConfigPatch.class);
        verify(streamService).updateConfig(eq(streamId), captor.capture());
        TrackingConfigPatch tracking = captor.getValue().tracking();
        assertEquals(5000, tracking.verifyEveryMillis());
        assertNull(tracking.mode(), "a cadence tweak states nothing about the mode");
        assertNull(tracking.engineId(), "a cadence tweak states nothing about the engine");
        assertNull(tracking.followFps(), "the other cadence sliders are untouched, not restated");
        assertNull(tracking.redetectIouPercent());
        assertNull(tracking.maxAgeFrames());
        assertNull(tracking.minHits());
        assertNull(tracking.lock(), "an absent lock leaves whatever the stream is holding alone");
        verify(streamService, never()).trackingStats(any());
    }

    @Test
    void updateConfigNeverReadsTheStreamBackToFillInAbsentTrackingFields() throws Exception {
        StreamId streamId = StreamId.random();
        when(streamService.updateConfig(eq(streamId), any())).thenReturn(new UpdateOutcome(false, true));

        mockMvc.perform(patch("/api/streams/{streamId}/config", streamId.value())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tracking\":{\"engineId\":\"ncc\"}}"))
                .andExpect(status().isOk());

        ArgumentCaptor<PipelineConfigPatch> captor = ArgumentCaptor.forClass(PipelineConfigPatch.class);
        verify(streamService).updateConfig(eq(streamId), captor.capture());
        assertEquals(new TrackingConfigPatch(null, "ncc", null, null, null, null, null, null),
                captor.getValue().tracking());
        verify(streamService, never()).trackingStats(any());
    }

    // ---- docs/plans/done/TRACKING-PLAN.md §4.D: the `tracking` object on POST /api/devices/{id}/stream ----

    @Test
    void startStatesNothingAboutTrackingWhenTheRequestDoesNot() throws Exception {
        // The deployment seed is applied inside the application layer (one place, every start path),
        // so this edge hands over the code default plus an empty patch and nothing else.
        when(streamService.start(eq(deviceId), any(), any())).thenReturn(StreamId.random());

        mockMvc.perform(post("/api/devices/{deviceId}/stream", deviceId.value())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated());

        ArgumentCaptor<PipelineConfig> config = ArgumentCaptor.forClass(PipelineConfig.class);
        ArgumentCaptor<TrackingConfigPatch> tracking = ArgumentCaptor.forClass(TrackingConfigPatch.class);
        verify(streamService).start(eq(deviceId), config.capture(), tracking.capture());
        assertEquals(PipelineConfig.defaults().tracking(), config.getValue().tracking());
        assertEquals(TrackingConfigPatch.NOTHING, tracking.getValue());
    }

    @Test
    void startPassesTheRequestsTrackingObjectAsAPatchForTheSeedToFoldUnder() throws Exception {
        when(streamService.start(eq(deviceId), any(), any())).thenReturn(StreamId.random());

        mockMvc.perform(post("/api/devices/{deviceId}/stream", deviceId.value())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tracking\":{\"mode\":\"ASSOCIATE\",\"engineId\":\"bytetrack\"}}"))
                .andExpect(status().isCreated());

        ArgumentCaptor<TrackingConfigPatch> captor = ArgumentCaptor.forClass(TrackingConfigPatch.class);
        verify(streamService).start(eq(deviceId), any(), captor.capture());
        assertEquals(TrackingMode.ASSOCIATE, captor.getValue().mode());
        assertEquals("bytetrack", captor.getValue().engineId());
        assertNull(captor.getValue().followFps(), "what the request leaves unsaid comes from the deployment seed");
    }

    @Test
    void startReturns400WhenTheRequestTriesToLockATrackThatCannotExistYet() throws Exception {
        mockMvc.perform(post("/api/devices/{deviceId}/stream", deviceId.value())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tracking\":{\"mode\":\"FOLLOW\",\"lock\":{\"trackId\":7}}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));

        verify(streamService, never()).start(any(), any(), any());
    }

    // ---- docs/plans/done/TRACKING-PLAN.md §4.E: GET /api/streams/{streamId}/tracks ----

    @Test
    void tracksReturnsTheBookedTracksWithLockedTrackIdHoistedAboveStats() throws Exception {
        StreamId streamId = StreamId.random();
        Detection detection = new Detection("car", 0.82, new BoundingBox(0.31, 0.44, 0.09, 0.07),
                new ModelRef("yolo26n.pt", "latest"),
                new TrackRef(7L, TrackState.CONFIRMED, DetectionSource.TRACKER, 0.012, -0.001, 143, true, 0.0, 0L));
        Instant firstSeen = Instant.parse("2026-08-11T10:22:31.104Z");
        Instant lastSeen = Instant.parse("2026-08-11T10:22:40.671Z");
        when(streamService.tracks(streamId))
                .thenReturn(List.of(new TrackedObject(7L, detection, firstSeen, lastSeen)));
        when(streamService.trackingStats(streamId)).thenReturn(Optional.of(
                new TrackingStats(TrackingMode.FOLLOW, "lk", Duration.ofSeconds(30), 12, 348, 0.034, 0.4, 0.9,
                        DetectorReason.CADENCE, 7L, Map.of(TrackState.CONFIRMED, 3, TrackState.COASTING, 1))));

        mockMvc.perform(get("/api/streams/{streamId}/tracks", streamId.value()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.streamId").value(streamId.value().toString()))
                .andExpect(jsonPath("$.lockedTrackId").value(7))
                .andExpect(jsonPath("$.stats.lockedTrackId").doesNotExist())
                .andExpect(jsonPath("$.tracks", hasSize(1)))
                .andExpect(jsonPath("$.tracks[0].trackId").value(7))
                .andExpect(jsonPath("$.tracks[0].label").value("car"))
                .andExpect(jsonPath("$.tracks[0].confidence").value(0.82))
                .andExpect(jsonPath("$.tracks[0].box.x").value(0.31))
                .andExpect(jsonPath("$.tracks[0].state").value("CONFIRMED"))
                .andExpect(jsonPath("$.tracks[0].source").value("TRACKER"))
                .andExpect(jsonPath("$.tracks[0].velocityX").value(0.012))
                .andExpect(jsonPath("$.tracks[0].ageFrames").value(143))
                .andExpect(jsonPath("$.tracks[0].reupdated").value(true))
                .andExpect(jsonPath("$.tracks[0].firstSeen").value(firstSeen.toString()))
                .andExpect(jsonPath("$.tracks[0].lastSeen").value(lastSeen.toString()))
                .andExpect(jsonPath("$.stats.mode").value("FOLLOW"))
                .andExpect(jsonPath("$.stats.engineId").value("lk"))
                .andExpect(jsonPath("$.stats.windowSeconds").value(30))
                .andExpect(jsonPath("$.stats.detectorPasses").value(12))
                .andExpect(jsonPath("$.stats.trackerFrames").value(348))
                .andExpect(jsonPath("$.stats.dutyRatio").value(0.034))
                .andExpect(jsonPath("$.stats.trackerMillisP50").value(0.4))
                .andExpect(jsonPath("$.stats.trackerMillisP95").value(0.9))
                .andExpect(jsonPath("$.stats.lastDetectorReason").value("CADENCE"))
                .andExpect(jsonPath("$.stats.byState.CONFIRMED").value(3))
                .andExpect(jsonPath("$.stats.byState.LOST").value(0));
    }

    @Test
    void tracksReturnsAnEmptyListAndNoStatsForAnUnknownOrStoppedStream() throws Exception {
        when(streamService.tracks(any())).thenReturn(List.of());
        when(streamService.trackingStats(any())).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/streams/{streamId}/tracks", StreamId.random().value()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tracks", hasSize(0)))
                .andExpect(jsonPath("$.lockedTrackId").value(0))
                .andExpect(jsonPath("$.stats").doesNotExist());
    }

    @Test
    void tracksOmitsStatsUntilTheWindowHasSeenADetectorPass() throws Exception {
        // TrackingStats.empty(..) reports no lastDetectorReason at all; the flow strip has no
        // rendering for a half-populated strip, so the whole object is omitted instead.
        StreamId streamId = StreamId.random();
        when(streamService.tracks(streamId)).thenReturn(List.of());
        when(streamService.trackingStats(streamId))
                .thenReturn(Optional.of(TrackingStats.empty(TrackingMode.OFF, Duration.ofSeconds(30))));

        mockMvc.perform(get("/api/streams/{streamId}/tracks", streamId.value()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stats").doesNotExist())
                .andExpect(jsonPath("$.lockedTrackId").value(0));
    }

    @Test
    void tracksReportsWhyTheStreamIsSamplingAtTheRateItIs() throws Exception {
        // docs/plans/done/CV-RATE-CONTROL-PLAN.md §1: `rate` sits BESIDE `latency` because a completion
        // rate cannot say why it fell short -- a starving source and a saturated detector look
        // identical from `effectiveFps` alone and have opposite fixes.
        StreamId streamId = StreamId.random();
        when(streamService.tracks(streamId)).thenReturn(List.of());
        when(streamService.detectionRate(streamId)).thenReturn(Optional.of(
                new DetectionRate(Duration.ofSeconds(30), 24.0, 10.0, 18.0, 7.5, 15L, 5L, 0L, 3L)));

        mockMvc.perform(get("/api/streams/{streamId}/tracks", streamId.value()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rate.windowSeconds").value(30))
                .andExpect(jsonPath("$.rate.sourceFps").value(24.0))
                .andExpect(jsonPath("$.rate.targetFps").value(10.0))
                .andExpect(jsonPath("$.rate.demandFps").value(18.0))
                .andExpect(jsonPath("$.rate.submittedFps").value(7.5))
                .andExpect(jsonPath("$.rate.submitted").value(15))
                .andExpect(jsonPath("$.rate.droppedInFlight").value(5))
                .andExpect(jsonPath("$.rate.droppedOutage").value(0))
                .andExpect(jsonPath("$.rate.missedDeadlines").value(3))
                .andExpect(jsonPath("$.rate.dropRatio").value(0.25));
    }

    @Test
    void tracksReportsWhichDetectionGateExplainsTheCurrentState() throws Exception {
        // docs/plans/done/CV-DEMAND-PLAN.md §3.6: "no boxes" has three causes an operator must be
        // able to tell apart, and this is how the wire distinguishes them.
        StreamId streamId = StreamId.random();
        when(streamService.tracks(streamId)).thenReturn(List.of());
        when(streamService.detectionState(streamId)).thenReturn(Optional.of(DetectionState.IDLE_NO_VIEWERS));

        mockMvc.perform(get("/api/streams/{streamId}/tracks", streamId.value()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.detectionState").value("IDLE_NO_VIEWERS"));
    }

    @Test
    void tracksOmitsDetectionStateForAnUnknownOrStoppedStream() throws Exception {
        when(streamService.tracks(any())).thenReturn(List.of());
        when(streamService.detectionState(any())).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/streams/{streamId}/tracks", StreamId.random().value()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.detectionState").doesNotExist());
    }

    @Test
    void tracksReportsTransportAndDecodeMillisP50ForAPullModeStream() throws Exception {
        // docs/plans/done/MEDIA-SOT-PLAN.md §5.4/§7, wave M5: the two additive fields.
        StreamId streamId = StreamId.random();
        when(streamService.tracks(streamId)).thenReturn(List.of());
        when(streamService.detectionRate(streamId)).thenReturn(Optional.of(
                new DetectionRate(Duration.ofSeconds(30), 9.9, 10.0, 0.0, 9.5, 0L, 2L, 0L, 1L,
                        DetectionRate.TRANSPORT_PULL, 4.0)));

        mockMvc.perform(get("/api/streams/{streamId}/tracks", streamId.value()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rate.transport").value("pull"))
                .andExpect(jsonPath("$.rate.decodeMillisP50").value(4.0));
    }

    @Test
    void tracksReportsTransportPushAndZeroDecodeMillisForAPlainPushStream() throws Exception {
        StreamId streamId = StreamId.random();
        when(streamService.tracks(streamId)).thenReturn(List.of());
        when(streamService.detectionRate(streamId)).thenReturn(Optional.of(
                new DetectionRate(Duration.ofSeconds(30), 24.0, 10.0, 18.0, 7.5, 15L, 5L, 0L, 3L)));

        mockMvc.perform(get("/api/streams/{streamId}/tracks", streamId.value()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rate.transport").value("push"))
                .andExpect(jsonPath("$.rate.decodeMillisP50").value(0.0));
    }

    @Test
    void tracksOmitsTheRateObjectUntilADeadlineHasActuallyBeenServed() throws Exception {
        StreamId streamId = StreamId.random();
        when(streamService.tracks(streamId)).thenReturn(List.of());
        when(streamService.detectionRate(streamId))
                .thenReturn(Optional.of(DetectionRate.empty(Duration.ofSeconds(30), 24.0, 10.0)));

        mockMvc.perform(get("/api/streams/{streamId}/tracks", streamId.value()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rate").doesNotExist());
    }

    @Test
    void tracksReturns400ForAMalformedStreamId() throws Exception {
        mockMvc.perform(get("/api/streams/{streamId}/tracks", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));
    }

    // ---- docs/plans/done/TRACKING-PLAN.md §4.G: the nested track/tracking objects on the detections wire ----

    @Test
    void detectionsCarryTheNestedTrackAndTrackingObjectsWhenTrackingIsOn() throws Exception {
        StreamId streamId = StreamId.random();
        Detection tracked = new Detection("person", 0.9, new BoundingBox(0.1, 0.2, 0.3, 0.4),
                new ModelRef("yolo26n.pt", "latest"),
                new TrackRef(3L, TrackState.COASTING, DetectionSource.TRACKER, 0.01, -0.02, 12));
        DetectionResult result = new DetectionResult(streamId, 42, Instant.parse("2026-08-11T10:00:00Z"),
                List.of(tracked), Duration.ofMillis(7),
                new TrackingTelemetry(true, DetectorReason.CADENCE, Duration.ofNanos(400_000), "lk", 3L));
        when(detectionRepositoryPort.query(any(DetectionQuery.class))).thenReturn(List.of(result));

        mockMvc.perform(get("/api/streams/{streamId}/detections", streamId.value()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].detections[0].track.id").value(3))
                .andExpect(jsonPath("$[0].detections[0].track.state").value("COASTING"))
                .andExpect(jsonPath("$[0].detections[0].track.source").value("TRACKER"))
                .andExpect(jsonPath("$[0].detections[0].track.velocityX").value(0.01))
                .andExpect(jsonPath("$[0].detections[0].track.velocityY").value(-0.02))
                .andExpect(jsonPath("$[0].detections[0].track.ageFrames").doesNotExist())
                .andExpect(jsonPath("$[0].detections[0].track.reupdated").value(false))
                .andExpect(jsonPath("$[0].tracking.detectorRan").value(true))
                .andExpect(jsonPath("$[0].tracking.detectorReason").value("CADENCE"))
                .andExpect(jsonPath("$[0].tracking.trackerMillis").value(0.4))
                .andExpect(jsonPath("$[0].tracking.engineId").value("lk"))
                .andExpect(jsonPath("$[0].tracking.lockedTrackId").value(3))
                // docs/plans/active/TRACKING-V3-BAND1-CONTEXT.md §2 / invariant B3: a TrackingTelemetry
                // built through the pre-V3 5-arg convenience constructor carries zero V3 facts, and
                // `capability` -- built from a nullable domain field -- must be absent, not a fabricated
                // zero-level object.
                .andExpect(jsonPath("$[0].tracking.detectionLagMillis").value(0))
                .andExpect(jsonPath("$[0].tracking.reupdateMillis").value(0))
                .andExpect(jsonPath("$[0].tracking.reupdatedTracks").value(0))
                .andExpect(jsonPath("$[0].tracking.capability").doesNotExist());
    }

    @Test
    void detectionsCarryTheV3ReupdateAndCapabilityFactsWhenTheServerReportsThem() throws Exception {
        // docs/plans/active/TRACKING-V3-BAND1-CONTEXT.md §2 -- the six previously-unread response
        // fields, now surfaced end to end: a track's own `reupdated` flag, the frame-level ORU
        // cost/count and detection lag, and the served capability object.
        StreamId streamId = StreamId.random();
        Detection tracked = new Detection("person", 0.9, new BoundingBox(0.1, 0.2, 0.3, 0.4),
                new ModelRef("yolo26n.pt", "latest"),
                new TrackRef(3L, TrackState.COASTING, DetectionSource.TRACKER, 0.01, -0.02, 12, true, 0.0, 0L));
        DetectionResult result = new DetectionResult(streamId, 44, Instant.parse("2026-08-11T10:00:02Z"),
                List.of(tracked), Duration.ofMillis(7),
                new TrackingTelemetry(true, DetectorReason.CADENCE, Duration.ofNanos(400_000), "lk", 3L,
                        Duration.ofMillis(42), Duration.ofMillis(6), 2, new TrackingCapability(2, "")));
        when(detectionRepositoryPort.query(any(DetectionQuery.class))).thenReturn(List.of(result));

        mockMvc.perform(get("/api/streams/{streamId}/detections", streamId.value()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].detections[0].track.reupdated").value(true))
                .andExpect(jsonPath("$[0].tracking.detectionLagMillis").value(42))
                .andExpect(jsonPath("$[0].tracking.reupdateMillis").value(6))
                .andExpect(jsonPath("$[0].tracking.reupdatedTracks").value(2))
                .andExpect(jsonPath("$[0].tracking.capability.levelServed").value(2))
                .andExpect(jsonPath("$[0].tracking.capability.reason").value(""));
    }

    @Test
    void aServedCapabilityBelowWhatWasRequestedRendersTheServedValueNeverTheRequest() throws Exception {
        // Invariant B5: a level is a ceiling. This response has no concept of "what was requested" at
        // all -- FrameTrackingResponse.capability is built purely from TrackingTelemetry#capability(),
        // so the served level a client renders can never be, nor be confused with, the ceiling a
        // request stated. Here the (hypothetical) request asked for L4; the server could only afford
        // L2, and that -- and only that -- is what reaches the wire.
        StreamId streamId = StreamId.random();
        DetectionResult result = new DetectionResult(streamId, 45, Instant.parse("2026-08-11T10:00:03Z"),
                List.of(new Detection("person", 0.9, new BoundingBox(0.1, 0.2, 0.3, 0.4),
                        new ModelRef("yolo26n.pt", "latest"),
                        new TrackRef(3L, TrackState.CONFIRMED, DetectionSource.TRACKER))),
                Duration.ZERO,
                new TrackingTelemetry(false, null, Duration.ZERO, "", 0, Duration.ZERO, Duration.ZERO, 0,
                        new TrackingCapability(2, "OpenVINO unavailable; degraded from requested L4 to L2")));
        when(detectionRepositoryPort.query(any(DetectionQuery.class))).thenReturn(List.of(result));

        mockMvc.perform(get("/api/streams/{streamId}/detections", streamId.value()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].tracking.capability.levelServed").value(2))
                .andExpect(jsonPath("$[0].tracking.capability.reason")
                        .value("OpenVINO unavailable; degraded from requested L4 to L2"));
    }

    @Test
    void detectionsOmitDetectorReasonOnATrackerOnlyFrame() throws Exception {
        StreamId streamId = StreamId.random();
        DetectionResult result = new DetectionResult(streamId, 43, Instant.parse("2026-08-11T10:00:01Z"),
                List.of(new Detection("person", 0.9, new BoundingBox(0.1, 0.2, 0.3, 0.4),
                        new ModelRef("yolo26n.pt", "latest"),
                        new TrackRef(3L, TrackState.CONFIRMED, DetectionSource.TRACKER))),
                Duration.ZERO,
                new TrackingTelemetry(false, null, Duration.ofNanos(370_000), "lk", 3L));
        when(detectionRepositoryPort.query(any(DetectionQuery.class))).thenReturn(List.of(result));

        mockMvc.perform(get("/api/streams/{streamId}/detections", streamId.value()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].tracking.detectorRan").value(false))
                .andExpect(jsonPath("$[0].tracking.detectorReason").doesNotExist());
    }

    @Test
    void anUntrackedDetectionsPayloadIsByteIdenticalToThePreTrackingWire() throws Exception {
        StreamId streamId = StreamId.random();
        when(detectionRepositoryPort.query(any(DetectionQuery.class)))
                .thenReturn(List.of(detectionResult(streamId, 1, Instant.parse("2026-08-11T10:00:00Z"))));

        mockMvc.perform(get("/api/streams/{streamId}/detections", streamId.value()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].detections[0].track").doesNotExist())
                .andExpect(jsonPath("$[0].tracking").doesNotExist());
    }

    // ---- docs/plans/done/LIVE-SCOPE-PLAN.md §2, W2: authority --------------------------------
    //
    // Every test above runs under the class-level `currentUser` (unbounded scope, matching how a
    // deployment with `vision.auth.enabled=false` behaves today) and is untouched by this wave --
    // that is the "default-config suites stay green" bar. The tests below are the ones that fail
    // before StreamAccess existed: a PILOT scoped to `otherAssetId` must be turned away from
    // `deviceId`'s stream (404, existence hidden), and a PILOT scoped to `ownedAsset` (the asset
    // `deviceId` actually belongs to, per `setUp`'s `assetRepositoryPort` stub) must keep working --
    // "a PILOT must keep working on their own assigned assets" is the hard constraint this whole
    // wave must not break.

    private ActiveStream runningOnOwnedDevice(StreamId streamId) {
        return new ActiveStream(streamId, deviceId, Instant.now());
    }

    private MockMvc pilotScopedTo(AssetId assetId) {
        return mockMvcFor(currentUserWithScope(VisibilityScope.assignedAssets(Set.of(assetId))));
    }

    @Test
    void startReturns201ForAPilotAssignedToTheDevicesOwnAsset() throws Exception {
        StreamId streamId = StreamId.random();
        when(streamService.start(any(), any(), any())).thenReturn(streamId);

        pilotScopedTo(ownedAsset.id()).perform(post("/api/devices/{deviceId}/stream", deviceId.value()))
                .andExpect(status().isCreated());
    }

    @Test
    void startReturns404ForAPilotAssignedToADifferentAsset() throws Exception {
        pilotScopedTo(otherAssetId).perform(post("/api/devices/{deviceId}/stream", deviceId.value()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));

        verify(streamService, never()).start(any(), any(), any());
    }

    @Test
    void startReturns404ForAPilotWhenTheDeviceBelongsToNoAssetAtAll() throws Exception {
        // An unowned device is a fleet-administration concern, not any one PILOT's -- see
        // StreamAccess's own javadoc for why canAdminister(), not includes(), is the fallback here.
        DeviceId unowned = DeviceId.random();
        when(assetRepositoryPort.findByDeviceId(unowned)).thenReturn(Optional.empty());

        pilotScopedTo(ownedAsset.id()).perform(post("/api/devices/{deviceId}/stream", unowned.value()))
                .andExpect(status().isNotFound());
    }

    @Test
    void listFiltersOutStreamsThePilotsScopeCannotReach() throws Exception {
        StreamId visibleStreamId = StreamId.random();
        StreamId hiddenStreamId = StreamId.random();
        DeviceId otherDeviceId = DeviceId.random();
        Asset otherAsset = Asset.register(otherAssetId, "someone else's drone", new CategoryId("drone"),
                new Ownership(UserId.random(), GroupId.random()), Set.of(otherDeviceId), Map.of(), Identity.NONE,
                Custody.NONE);
        when(assetRepositoryPort.findByDeviceId(otherDeviceId)).thenReturn(Optional.of(otherAsset));
        when(streamService.streams()).thenReturn(List.of(
                new ActiveStream(visibleStreamId, deviceId, Instant.now()),
                new ActiveStream(hiddenStreamId, otherDeviceId, Instant.now())));

        pilotScopedTo(ownedAsset.id()).perform(get("/api/streams"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].streamId").value(visibleStreamId.value().toString()));
    }

    @Test
    void stopReturns404ForAPilotAssignedElsewhereOnARunningStream() throws Exception {
        StreamId streamId = StreamId.random();
        when(streamService.streams()).thenReturn(List.of(runningOnOwnedDevice(streamId)));

        pilotScopedTo(otherAssetId).perform(delete("/api/streams/{streamId}", streamId.value()))
                .andExpect(status().isNotFound());

        verify(streamService, never()).stop(any());
    }

    @Test
    void stopReturns204ForAPilotAssignedToTheStreamsOwnAsset() throws Exception {
        StreamId streamId = StreamId.random();
        when(streamService.streams()).thenReturn(List.of(runningOnOwnedDevice(streamId)));

        pilotScopedTo(ownedAsset.id()).perform(delete("/api/streams/{streamId}", streamId.value()))
                .andExpect(status().isNoContent());

        verify(streamService).stop(eq(streamId));
    }

    @Test
    void updateConfigReturns404ForAPilotAssignedElsewhereOnARunningStream() throws Exception {
        StreamId streamId = StreamId.random();
        when(streamService.streams()).thenReturn(List.of(runningOnOwnedDevice(streamId)));

        pilotScopedTo(otherAssetId).perform(patch("/api/streams/{streamId}/config", streamId.value())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound());

        verify(streamService, never()).updateConfig(any(), any());
    }

    @Test
    void updateConfigReturns200ForAPilotAssignedToTheStreamsOwnAsset() throws Exception {
        StreamId streamId = StreamId.random();
        when(streamService.streams()).thenReturn(List.of(runningOnOwnedDevice(streamId)));
        when(streamService.updateConfig(eq(streamId), any())).thenReturn(new UpdateOutcome(false));

        pilotScopedTo(ownedAsset.id()).perform(patch("/api/streams/{streamId}/config", streamId.value())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
    }

    @Test
    void configReturns404ForAPilotAssignedElsewhereOnARunningStream() throws Exception {
        StreamId streamId = StreamId.random();
        when(streamService.streams()).thenReturn(List.of(runningOnOwnedDevice(streamId)));

        pilotScopedTo(otherAssetId).perform(get("/api/streams/{streamId}/config", streamId.value()))
                .andExpect(status().isNotFound());
    }

    @Test
    void configReturns200ForAPilotAssignedToTheStreamsOwnAsset() throws Exception {
        StreamId streamId = StreamId.random();
        when(streamService.streams()).thenReturn(List.of(runningOnOwnedDevice(streamId)));
        when(streamService.config(streamId)).thenReturn(Optional.of(PipelineConfig.defaults()));

        pilotScopedTo(ownedAsset.id()).perform(get("/api/streams/{streamId}/config", streamId.value()))
                .andExpect(status().isOk());
    }

    @Test
    void tracksReturns404ForAPilotAssignedElsewhereOnARunningStream() throws Exception {
        // Unlike an unknown/stopped stream (which #tracks forgivingly answers with a 200 of
        // defaults), a RUNNING-but-invisible stream must 404 -- this is exactly the gap
        // LIVE-SCOPE-PLAN.md's audit found unguarded, since #tracks was otherwise the most
        // forgiving of the eight handlers.
        StreamId streamId = StreamId.random();
        when(streamService.streams()).thenReturn(List.of(runningOnOwnedDevice(streamId)));

        pilotScopedTo(otherAssetId).perform(get("/api/streams/{streamId}/tracks", streamId.value()))
                .andExpect(status().isNotFound());
    }

    @Test
    void tracksReturns200ForAPilotAssignedToTheStreamsOwnAsset() throws Exception {
        StreamId streamId = StreamId.random();
        when(streamService.streams()).thenReturn(List.of(runningOnOwnedDevice(streamId)));

        pilotScopedTo(ownedAsset.id()).perform(get("/api/streams/{streamId}/tracks", streamId.value()))
                .andExpect(status().isOk());
    }

    @Test
    void detectionsReturns404ForAPilotAssignedElsewhereOnARunningStream() throws Exception {
        StreamId streamId = StreamId.random();
        when(streamService.streams()).thenReturn(List.of(runningOnOwnedDevice(streamId)));

        pilotScopedTo(otherAssetId).perform(get("/api/streams/{streamId}/detections", streamId.value()))
                .andExpect(status().isNotFound());

        verify(detectionRepositoryPort, never()).query(any());
    }

    @Test
    void detectionsReturns200ForAPilotAssignedToTheStreamsOwnAsset() throws Exception {
        StreamId streamId = StreamId.random();
        when(streamService.streams()).thenReturn(List.of(runningOnOwnedDevice(streamId)));
        when(detectionRepositoryPort.query(any(DetectionQuery.class))).thenReturn(List.of());

        pilotScopedTo(ownedAsset.id()).perform(get("/api/streams/{streamId}/detections", streamId.value()))
                .andExpect(status().isOk());
    }

    @Test
    void snapshotReturns404ForAPilotAssignedElsewhereOnARunningStream() throws Exception {
        StreamId streamId = StreamId.random();
        when(streamService.streams()).thenReturn(List.of(runningOnOwnedDevice(streamId)));

        pilotScopedTo(otherAssetId).perform(get("/api/streams/{streamId}/snapshot", streamId.value()))
                .andExpect(status().isNotFound());

        verify(streamService, never()).latestFrame(any());
    }

    @Test
    void snapshotReturns200ForAPilotAssignedToTheStreamsOwnAsset() throws Exception {
        StreamId streamId = StreamId.random();
        when(streamService.streams()).thenReturn(List.of(runningOnOwnedDevice(streamId)));
        byte[] jpegBytes = tinyJpeg(2, 2);
        VideoFrame frame = new VideoFrame(streamId, 0, Instant.now(), 2, 2, PixelFormat.JPEG,
                ByteBuffer.wrap(jpegBytes));
        when(streamService.latestFrame(streamId)).thenReturn(Optional.of(frame));

        pilotScopedTo(ownedAsset.id()).perform(get("/api/streams/{streamId}/snapshot", streamId.value()))
                .andExpect(status().isOk());
    }
}
