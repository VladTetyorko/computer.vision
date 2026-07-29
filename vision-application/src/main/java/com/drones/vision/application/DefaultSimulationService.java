package com.drones.vision.application;

import com.drones.vision.domain.model.Asset;
import com.drones.vision.domain.model.AssetId;
import com.drones.vision.domain.model.Capability;
import com.drones.vision.domain.model.CategoryId;
import com.drones.vision.domain.model.Device;
import com.drones.vision.domain.model.FeedId;
import com.drones.vision.domain.model.FeedSpec;
import com.drones.vision.domain.model.Ownership;
import com.drones.vision.domain.model.PipelineConfig;
import com.drones.vision.domain.model.StreamDescriptor;
import com.drones.vision.domain.model.StreamId;
import com.drones.vision.domain.model.UserId;
import com.drones.vision.domain.port.out.CategoryRepositoryPort;
import com.drones.vision.domain.port.out.FeedTransmitterPort;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The one implementation of {@link SimulationService}.
 *
 * <p>Builds one {@link AssetSpec} with two devices — a video device (either {@code "file"}-protocol,
 * played back in-process; {@code "sim"}-protocol, the synthetic renderer, when {@link
 * SimulationSpec#videoPath()} is {@code null} (docs/CYCLES-PLAN.md §9, CU-a); or — for a wired
 * {@link SimulationTransport} ({@link SimulationTransport#RTSP}/{@link SimulationTransport#MJPEG})
 * — pointing at a feed pushed out by whichever {@link FeedTransmitterPort} {@link #feedTransmitters}
 * selects for that transport) and a telemetry device — {@code "sim"}-protocol by default, or {@code
 * "mavlink"}-protocol when {@link SimulationSpec#telemetryTransport()} is {@link
 * TelemetryTransport#MAVLINK} (a real UDP feed via {@code MavlinkFeedTransmitter}, adapter-mavlink's
 * own natural follow-up — see {@link #wireMavlinkTelemetryDevice} and adapter-mavlink/MODULE.md's
 * Gotchas), independently of whichever {@link SimulationTransport} the video device uses — and
 * delegates the actual creation/streaming to {@link AssetService}, so every rule {@code
 * AssetService#create}/{@code #startStream} already enforces (category validation, audit, device
 * registration) applies here too instead of being duplicated.
 *
 * <p>{@link #resumeAll} (the simulated-feed resume-on-boot mechanism) is the read side of the same
 * bookkeeping: given a persisted, {@code ACTIVE}, {@code simulated}-category asset whose {@code
 * rtsp} video device structurally looks like one of this app's own TX-fed feeds, it rebuilds the
 * {@link FeedSpec} that would have produced it and restarts the transmit side — see that method's
 * own javadoc for the full "frozen video after a restart" story this closes. <b>A MAVLink telemetry
 * feed is never a candidate</b> — {@link #telemetryFeedByAsset} is never consulted by {@link
 * #resumeAll} at all, and its own device-matching (video-capability, {@code rtsp}-protocol) can
 * never accidentally match a {@code mavlink}-protocol, telemetry-capability device in the first
 * place; the TX side is exactly as ephemeral as {@link SimulationTransport#MJPEG}'s own excluded
 * case (a freshly allocated loopback port every JVM start, so a persisted device's URI is already
 * stale after any restart regardless of any matching heuristic) — only a fresh {@link #simulate}
 * call can give one a valid feed again.
 *
 * <h2>Threading</h2>
 * {@link #feedByAsset}/{@link #telemetryFeedByAsset} are the only mutable state, both {@link
 * ConcurrentHashMap}s safe for concurrent {@link #simulate}/{@link #stop}/{@link #resumeAll} calls
 * across different assets; all other shared state is reached through the injected collaborators.
 */
public final class DefaultSimulationService implements SimulationService {

    private static final System.Logger LOG = System.getLogger(DefaultSimulationService.class.getName());

    /** The category every simulated asset is created under; seeded by devsupport at startup. */
    static final CategoryId SIMULATED_CATEGORY = new CategoryId("simulated");

    /** {@code AssetSpec#attributes()} key {@link #simulate} records the source video path under — read back by {@link #resumeAll}. */
    private static final String SOURCE_ATTRIBUTE = "source";

    /**
     * The URL path prefix {@link com.drones.vision.domain.port.out.FeedTransmitterPort#start} uses
     * for an RTSP feed's target (duplicated from {@code adapter-rtsp}'s {@code RtspFeedTransmitter}
     * — a private constant there, and this class may not depend on that adapter module anyway).
     * {@link #resumeAll} parses this same shape back out of a persisted device's URI to recover its
     * {@link FeedId} — a deliberate, narrow coupling to one adapter's URL convention, not enforced
     * by {@link com.drones.vision.domain.port.out.FeedTransmitterPort}'s own contract, which makes
     * no promise about URL shape at all.
     */
    private static final String FEED_PATH_PREFIX = "feed-";

    /** {@code FeedSpec}/{@code StreamDescriptor} protocol key for the RTSP transport. */
    private static final String FEED_PROTOCOL_RTSP = "rtsp";

    /** {@code FeedSpec}/{@code StreamDescriptor} protocol key for the MJPEG transport (docs/CYCLES-PLAN.md §5). */
    private static final String FEED_PROTOCOL_MJPEG = "mjpeg";

    /**
     * {@code StreamDescriptor} protocol key for the synthetic renderer — used both for the
     * telemetry device (always) and, since docs/CYCLES-PLAN.md §9 (CU-a), the video device of a
     * spec with no {@link SimulationSpec#videoPath()}.
     */
    private static final String SIM_PROTOCOL = "sim";

    /**
     * Display name a fully synthetic simulation (docs/CYCLES-PLAN.md §9, CU-a — {@link
     * SimulationSpec#videoPath()} {@code null}) falls back to when {@link
     * SimulationSpec#displayName()} is absent/blank, mirroring how a video-path spec falls back to
     * the file's own name.
     */
    static final String SYNTHETIC_DISPLAY_NAME = "Synthetic drone";

    /**
     * RTSP receive-side option key/value applied to a {@code transport=RTSP} video device's
     * {@link StreamDescriptor}, on top of whatever {@link FeedTransmitterPort#start} returns.
     *
     * <p>Empirically required (see adapter-rtsp/MODULE.md Gotchas): {@code FfmpegVideoSource}'s
     * default RTSP {@code timeout}/{@code rw_timeout} is 10s, which — when the RX side and {@code
     * RtspFeedTransmitter} (TX) run in this same JVM against the same mediamtx path, exactly the
     * shape {@code transport=RTSP} creates — causes the transmitter's own grab/record loop to
     * silently stall for ~10s before mediamtx drops the now-stale publisher connection and the
     * transmitter's next {@code record()} fails with EPIPE. A short RX-side timeout (2s) reliably
     * eliminates the contention. This is RTSP-specific: the mjpeg TX/RX pair has no such
     * contention (see adapter-mjpeg/MODULE.md), so {@link SimulationTransport#MJPEG} never gets
     * this augmentation.
     */
    private static final String RTSP_RX_TIMEOUT_OPTION = "timeout";
    static final String RTSP_RX_TIMEOUT_MICROS = "2000000";

    /** See {@link #awaitFeedEstablished()}. RTSP-specific; MJPEG never pays this delay. */
    static final long RTSP_FEED_ESTABLISH_DELAY_MILLIS = 1000L;

    /** {@code FeedSpec}/{@code StreamDescriptor} protocol key for the MAVLink telemetry transport. */
    private static final String TELEMETRY_PROTOCOL_MAVLINK = "mavlink";

    /** Loopback host every MAVLink-transport simulation's telemetry feed binds/pushes to. */
    private static final String MAVLINK_LOOPBACK_HOST = "127.0.0.1";

    /**
     * {@code MavlinkTelemetrySource}/{@code MavlinkFeedTransmitter} (adapter-mavlink) option key
     * pinning a device/feed to one MAVLink system id. Duplicated here, not imported, since
     * vision-application may not depend on any adapter module (ArchUnit-enforced) — the same
     * reasoning as {@link #FEED_PATH_PREFIX}'s own duplication of {@code RtspFeedTransmitter}'s URL
     * convention.
     */
    private static final String MAVLINK_OPTION_SYSID = "sysid";

    /**
     * Fixed system id every MAVLink-transport simulation's feed/device uses. Sysid uniqueness
     * exists in the real fleet gateway (adapter-mavlink's {@code MavlinkSocketHub}) purely to
     * disambiguate several vehicles sharing <em>one</em> real UDP port — here, every simulation
     * allocates its own fresh loopback port ({@link #allocateLoopbackUdpPort()}), so no two
     * MAVLink-transport simulations ever share a destination and a fixed sysid can never collide.
     */
    static final int MAVLINK_TELEMETRY_SYSID = 1;

    /** {@code MavlinkFeedTransmitter}'s (adapter-mavlink) option key for its required flight route. */
    private static final String MAVLINK_OPTION_ROUTE = "route";

    /** {@code MavlinkFeedTransmitter}'s option key for cruise speed along the route. */
    private static final String MAVLINK_OPTION_SPEED_MPS = "speedMps";

    /**
     * Offset (degrees latitude) of the second point in the minimal two-point route {@link
     * #mavlinkRoute} synthesizes when {@link SimulationSpec#plan()} carries no {@link
     * TelemetryPlan#route()}. Unlike {@code SimulatedTelemetrySource} (adapter-simulation), {@code
     * MavlinkFeedTransmitter} has no home-point-only circular-track fallback of its own — its {@code
     * route} option is required and throws without one (adapter-mavlink/MODULE.md) — so a bare
     * lat/lon (or no lat/lon at all) still needs <em>some</em> route. {@code MavlinkRoute}'s
     * LOOP-only engine turns two distinct points into a there-and-back oscillation rather than a
     * fixed point, so the synthesized feed still visibly moves.
     */
    static final double MAVLINK_FALLBACK_ROUTE_OFFSET_DEGREES = 0.001; // ~110m at the equator

    /**
     * Mirrors {@code SimulatedTelemetrySource}'s own default circular-track center
     * (adapter-simulation/MODULE.md) so a MAVLink-transport simulation with no explicit lat/lon/plan
     * still starts near the same default location a SIM-transport one would. Duplicated, not
     * shared, for the same cross-module reason as {@link #MAVLINK_OPTION_SYSID}.
     */
    static final double MAVLINK_FALLBACK_LATITUDE = 50.45;
    static final double MAVLINK_FALLBACK_LONGITUDE = 30.52;

    private final AssetService assetService;
    private final CategoryRepositoryPort categoryRepository;
    private final FeedTransmitterRegistry feedTransmitters;
    private final URI mediamtxRtspBase;

    /** Tracks which transmitter/{@link FeedId} pair (if any) backs each wired-transport asset's video feed. */
    private final Map<AssetId, TrackedFeed> feedByAsset = new ConcurrentHashMap<>();

    /**
     * Tracks which transmitter/{@link FeedId} pair (if any) backs each MAVLink-transport asset's
     * telemetry feed — kept separate from {@link #feedByAsset} since video and telemetry transports
     * are orthogonal (docs/DRONE-INFRA-PLAN.md's natural follow-up): an asset can have a tracked
     * video feed, a tracked telemetry feed, both, or neither, independently. Never consulted by
     * {@link #resumeAll} — see the class javadoc's "A MAVLink telemetry feed is never a candidate"
     * paragraph.
     */
    private final Map<AssetId, TrackedFeed> telemetryFeedByAsset = new ConcurrentHashMap<>();

    /**
     * @param mediamtxRtspBase this app's own configured mediamtx RTSP push target (the same {@code
     *                         URI} {@code vision-app} already hands {@code RtspFeedTransmitter}) —
     *                         used only by {@link #resumeAll} to recognize which persisted {@code
     *                         rtsp} devices are this app's own TX-fed simulation feeds, structurally
     *                         (host:port match), rather than a real external camera
     */
    public DefaultSimulationService(AssetService assetService, CategoryRepositoryPort categoryRepository,
                                     FeedTransmitterRegistry feedTransmitters, URI mediamtxRtspBase) {
        this.assetService = Objects.requireNonNull(assetService, "assetService must not be null");
        this.categoryRepository = Objects.requireNonNull(categoryRepository, "categoryRepository must not be null");
        this.feedTransmitters = Objects.requireNonNull(feedTransmitters, "feedTransmitters must not be null");
        this.mediamtxRtspBase = Objects.requireNonNull(mediamtxRtspBase, "mediamtxRtspBase must not be null");
    }

    @Override
    public SimulatedAsset simulate(SimulationSpec spec, Ownership ownership, UserId actor) {
        Objects.requireNonNull(spec, "spec must not be null");
        Objects.requireNonNull(ownership, "ownership must not be null");
        Objects.requireNonNull(actor, "actor must not be null");

        Path videoPath = spec.videoPath() == null ? null : validateVideoPath(spec.videoPath());
        requireSimulatedCategorySeeded();

        String displayName = resolveDisplayName(spec.displayName(), videoPath);

        boolean wiredVideo = spec.transport() != SimulationTransport.DIRECT;
        FeedId videoFeedId = wiredVideo ? FeedId.random() : null;
        FeedTransmitterPort videoTransmitter = null;
        DeviceRegistration videoDevice;
        if (wiredVideo) {
            // spec's compact ctor guarantees videoPath != null whenever transport != DIRECT.
            WiredVideoDevice wiredDevice = wireVideoDevice(displayName, videoPath, videoFeedId, spec.transport());
            videoDevice = wiredDevice.device();
            videoTransmitter = wiredDevice.transmitter();
        } else if (videoPath == null) {
            videoDevice = syntheticVideoDevice(displayName);
        } else {
            videoDevice = videoDevice(displayName, videoPath);
        }

        boolean mavlinkTelemetry = spec.telemetryTransport() == TelemetryTransport.MAVLINK;
        FeedId telemetryFeedId = null;
        FeedTransmitterPort telemetryTransmitter = null;
        DeviceRegistration telemetryDevice;
        if (mavlinkTelemetry) {
            try {
                WiredTelemetryDevice wiredTelemetryDevice = wireMavlinkTelemetryDevice(displayName, spec);
                telemetryDevice = wiredTelemetryDevice.device();
                telemetryTransmitter = wiredTelemetryDevice.transmitter();
                telemetryFeedId = wiredTelemetryDevice.feedId();
            } catch (RuntimeException e) {
                // Video and telemetry transports are independent -- a telemetry-wiring failure must
                // still unwind an already-started video feed, exactly like assetService.create's own
                // catch block below unwinds both when it fails.
                stopFeedQuietly(videoTransmitter, videoFeedId);
                throw e;
            }
        } else {
            telemetryDevice = telemetryDevice(displayName, spec);
        }

        Map<String, String> attributes = videoPath == null ? Map.of() : Map.of("source", videoPath.toString());
        AssetSpec assetSpec = new AssetSpec(displayName, SIMULATED_CATEGORY, attributes,
                List.of(videoDevice, telemetryDevice));

        Asset asset;
        try {
            asset = assetService.create(assetSpec, ownership, actor);
        } catch (RuntimeException e) {
            stopFeedQuietly(videoTransmitter, videoFeedId);
            stopFeedQuietly(telemetryTransmitter, telemetryFeedId);
            throw e;
        }
        if (videoFeedId != null) {
            feedByAsset.put(asset.id(), new TrackedFeed(videoTransmitter, videoFeedId));
        }
        if (telemetryFeedId != null) {
            telemetryFeedByAsset.put(asset.id(), new TrackedFeed(telemetryTransmitter, telemetryFeedId));
        }

        StreamId streamId = null;
        if (spec.autoStart()) {
            if (spec.transport() == SimulationTransport.RTSP) {
                awaitFeedEstablished();
            }
            try {
                streamId = assetService.startStream(asset.id(), null, PipelineConfig.defaults());
            } catch (RuntimeException e) {
                feedByAsset.remove(asset.id());
                telemetryFeedByAsset.remove(asset.id());
                stopFeedQuietly(videoTransmitter, videoFeedId);
                stopFeedQuietly(telemetryTransmitter, telemetryFeedId);
                throw e;
            }
        }
        return new SimulatedAsset(asset.id(), streamId);
    }

    /**
     * A short, fixed delay between starting an RTSP feed and opening the RX side via {@link
     * AssetService#startStream} for {@code autoStart} — empirically required (discovered while
     * verifying this class's docker-gated E2E test): {@code RtspFeedTransmitter}'s transmit thread
     * takes on the order of tens of milliseconds to reach mediamtx's ANNOUNCE/SETUP/RECORD
     * handshake, and mediamtx answers the RX side's DESCRIBE with a bare 404 for a path with no
     * publisher yet — not a "not ready, try again" signal. {@code FfmpegVideoSource} has no
     * reconnect logic (a single failed connect closes the pipeline for good), so without this
     * delay {@code startStream} reliably raced ahead of the transmitter and killed the stream
     * before a single frame flowed. This is exactly the caller-side responsibility {@link
     * FeedTransmitterPort#start}'s own javadoc calls out ("callers that need frames to actually be
     * flowing before opening the RX side must poll or retry rather than assume readiness") — a
     * fixed delay is the simplest thing that could work here, and matches the margin
     * adapter-rtsp's own {@code MediamtxDockerIntegrationTest} already validated empirically
     * ({@code Thread.sleep(1000)} before its own RX open attempt). Only paid when {@code
     * autoStart} actually needs to open the RX side immediately; a manually-started stream started
     * later never hits this path.
     */
    private static void awaitFeedEstablished() {
        try {
            Thread.sleep(RTSP_FEED_ESTABLISH_DELAY_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void stop(AssetId assetId) {
        Objects.requireNonNull(assetId, "assetId must not be null");
        assetService.stopStream(assetId);
        TrackedFeed video = feedByAsset.remove(assetId);
        if (video != null) {
            video.transmitter().stop(video.feedId());
        }
        TrackedFeed telemetry = telemetryFeedByAsset.remove(assetId);
        if (telemetry != null) {
            telemetry.transmitter().stop(telemetry.feedId());
        }
    }

    private static void stopFeedQuietly(FeedTransmitterPort transmitter, FeedId feedId) {
        if (feedId != null) {
            transmitter.stop(feedId);
        }
    }

    // --- Simulated-feed resume-on-boot ----------------------------------------

    @Override
    public List<AssetId> resumeAll() {
        List<AssetId> resumed = new ArrayList<>();
        int candidateCount = 0;
        for (AssetSummary summary : assetService.assets()) {
            Asset asset = summary.asset();
            if (!SIMULATED_CATEGORY.equals(asset.category()) || !asset.isActive()) {
                continue; // not our concern, or deliberately out of service -- never spring back on its own
            }
            if (feedByAsset.containsKey(asset.id())) {
                continue; // already resumed earlier in this same process run, or currently simulated -- idempotent
            }
            Optional<Device> ownFeedDevice = findOwnRtspFeedDevice(asset.id());
            if (ownFeedDevice.isEmpty()) {
                continue; // e.g. transport=DIRECT/MJPEG, or a real (not ours) rtsp camera -- nothing to resume, not an error
            }
            candidateCount++;
            resumeOne(asset, ownFeedDevice.get()).ifPresent(resumed::add);
        }
        LOG.log(System.Logger.Level.INFO,
                "Resumed " + resumed.size() + " of " + candidateCount + " simulated RTSP feed(s) found on boot");
        return resumed;
    }

    /** Attempts to resume one candidate; every failure is logged and skipped, never thrown. */
    private Optional<AssetId> resumeOne(Asset asset, Device device) {
        Optional<FeedId> feedId = parseFeedId(device.stream().uri());
        if (feedId.isEmpty()) {
            LOG.log(System.Logger.Level.WARNING, () -> "Skipping " + describe(asset)
                    + ": could not parse a feed id from its video device uri " + device.stream().uri());
            return Optional.empty();
        }
        String source = asset.attributes().get(SOURCE_ATTRIBUTE);
        if (source == null || source.isBlank()) {
            LOG.log(System.Logger.Level.WARNING, () -> "Skipping " + describe(asset)
                    + ": no recorded source video path (attributes.source) to rebuild its feed from");
            return Optional.empty();
        }
        Path videoPath;
        try {
            videoPath = validateVideoPath(source);
        } catch (IllegalArgumentException e) {
            LOG.log(System.Logger.Level.WARNING, "Skipping " + describe(asset) + ": " + e.getMessage());
            return Optional.empty();
        }
        FeedSpec feedSpec = new FeedSpec(FEED_PROTOCOL_RTSP, videoPath.toUri(), Map.of("loop", "true"));
        FeedTransmitterPort transmitter;
        try {
            transmitter = feedTransmitters.transmitterFor(feedSpec);
        } catch (IllegalArgumentException e) {
            LOG.log(System.Logger.Level.WARNING,
                    "Skipping " + describe(asset) + ": no transmitter supports its feed: " + e.getMessage());
            return Optional.empty();
        }
        transmitter.start(feedId.get(), feedSpec);
        feedByAsset.put(asset.id(), new TrackedFeed(transmitter, feedId.get()));
        LOG.log(System.Logger.Level.INFO,
                () -> "Resumed simulated feed for " + describe(asset) + " (feed " + feedId.get().value() + ")");
        return Optional.of(asset.id());
    }

    /**
     * The asset's own currently-active, {@code VIDEO}-capable device whose {@code rtsp} URI's
     * host:port matches {@link #mediamtxRtspBase} — this app's own TX-fed simulation feed, not a
     * real external camera (structurally indistinguishable from one in what's actually persisted;
     * see {@link #resumeAll}'s own javadoc). At most one is expected in practice (every {@code
     * simulate()}-created asset has exactly one video device); the first match wins if somehow more
     * than one qualifies.
     */
    private Optional<Device> findOwnRtspFeedDevice(AssetId assetId) {
        return assetService.details(assetId).devices().stream()
                .filter(Device::isActive)
                .filter(device -> device.capabilities().contains(Capability.VIDEO))
                .filter(device -> isOwnRtspFeedUri(device.stream()))
                .findFirst();
    }

    private boolean isOwnRtspFeedUri(StreamDescriptor stream) {
        if (!FEED_PROTOCOL_RTSP.equals(stream.protocol())) {
            return false; // "file"/"sim"/"mjpeg" video devices are never a resumable rtsp TX feed
        }
        URI uri = stream.uri();
        return Objects.equals(uri.getHost(), mediamtxRtspBase.getHost()) && uri.getPort() == mediamtxRtspBase.getPort();
    }

    /** Reverses {@code RtspFeedTransmitter#targetUri} -- see {@link #FEED_PATH_PREFIX}'s own javadoc for the coupling this implies. */
    private static Optional<FeedId> parseFeedId(URI uri) {
        String path = uri.getPath();
        if (path == null) {
            return Optional.empty();
        }
        String segment = path.startsWith("/") ? path.substring(1) : path;
        if (!segment.startsWith(FEED_PATH_PREFIX)) {
            return Optional.empty();
        }
        try {
            return Optional.of(FeedId.of(segment.substring(FEED_PATH_PREFIX.length())));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static String describe(Asset asset) {
        return "asset " + asset.displayName() + " (" + asset.id().value() + ")";
    }

    /**
     * Confirms {@code rawPath} names an existing, regular, readable file — the three checks a
     * simulation must pass before anything is created, so a bad path never leaves behind a
     * half-registered asset.
     */
    private static Path validateVideoPath(String rawPath) {
        Path path;
        try {
            path = Path.of(rawPath).toAbsolutePath();
        } catch (InvalidPathException e) {
            throw new IllegalArgumentException("Invalid video path: " + rawPath, e);
        }
        if (!Files.exists(path)) {
            throw new IllegalArgumentException("Video file does not exist: " + path);
        }
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("Video path is not a regular file: " + path);
        }
        if (!Files.isReadable(path)) {
            throw new IllegalArgumentException("Video file is not readable: " + path);
        }
        return path;
    }

    private void requireSimulatedCategorySeeded() {
        categoryRepository.findById(SIMULATED_CATEGORY)
                .orElseThrow(() -> new IllegalStateException("category 'simulated' is not seeded"));
    }

    /**
     * Derives a display name from the file name (extension stripped) when none was supplied, or —
     * for a fully synthetic spec (docs/CYCLES-PLAN.md §9, CU-a — {@code videoPath == null}) —
     * {@link #SYNTHETIC_DISPLAY_NAME}, since there is no file name to derive one from.
     */
    private static String resolveDisplayName(String requested, Path videoPath) {
        if (requested != null && !requested.isBlank()) {
            return requested;
        }
        if (videoPath == null) {
            return SYNTHETIC_DISPLAY_NAME;
        }
        String fileName = videoPath.getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    private static DeviceRegistration videoDevice(String displayName, Path videoPath) {
        return new DeviceRegistration(displayName + " · video", Set.of(Capability.VIDEO),
                new StreamDescriptor("file", videoPath.toUri(), Map.of("loop", "true")));
    }

    /**
     * Builds a {@code "sim"}-protocol video device (docs/CYCLES-PLAN.md §9, CU-a) for a spec with no
     * {@link SimulationSpec#videoPath()} — the same synthetic renderer {@link #telemetryDevice}
     * already points a telemetry device at ({@code SimulatedVideoSource}, adapter-simulation), so a
     * caller with zero hardware and no video file still gets a watchable, moving asset. Unlike
     * {@link #videoDevice}'s {@code "file"}-protocol device, no {@code loop} option is set: the
     * synthetic renderer runs forever on its own, with nothing to loop.
     */
    private static DeviceRegistration syntheticVideoDevice(String displayName) {
        URI syntheticUri = URI.create(SIM_PROTOCOL + "://" + slug(displayName));
        return new DeviceRegistration(displayName + " · video", Set.of(Capability.VIDEO),
                new StreamDescriptor(SIM_PROTOCOL, syntheticUri, Map.of()));
    }

    /**
     * Starts a feed for {@code videoPath} via whichever {@link FeedTransmitterPort} {@link
     * #feedTransmitters} selects for {@code transport}, and returns a video device pointing at the
     * descriptor it returns. {@link SimulationTransport#RTSP}'s descriptor is augmented with the
     * short RX-side {@code timeout} option ({@link #RTSP_RX_TIMEOUT_MICROS}) so this same-JVM RX
     * (opened later against this same descriptor) never contends with the transmitter's own
     * grab/record loop; {@link SimulationTransport#MJPEG}'s descriptor is registered as-is — the
     * mjpeg TX/RX pair has no such contention (see adapter-mjpeg/MODULE.md).
     *
     * @throws IllegalArgumentException if no registered {@link FeedTransmitterPort} supports the
     *                                   built {@link FeedSpec}
     */
    private WiredVideoDevice wireVideoDevice(String displayName, Path videoPath, FeedId feedId,
                                              SimulationTransport transport) {
        FeedSpec feedSpec = new FeedSpec(feedProtocolFor(transport), videoPath.toUri(), Map.of("loop", "true"));
        FeedTransmitterPort transmitter = feedTransmitters.transmitterFor(feedSpec);
        StreamDescriptor started = transmitter.start(feedId, feedSpec);
        StreamDescriptor descriptor = transport == SimulationTransport.RTSP ? withRxTimeout(started) : started;
        DeviceRegistration device =
                new DeviceRegistration(displayName + " · video", Set.of(Capability.VIDEO), descriptor);
        return new WiredVideoDevice(device, transmitter);
    }

    /** The {@code FeedSpec}/{@code StreamDescriptor} protocol key a wired transport transmits over. */
    private static String feedProtocolFor(SimulationTransport transport) {
        return switch (transport) {
            case RTSP -> FEED_PROTOCOL_RTSP;
            case MJPEG -> FEED_PROTOCOL_MJPEG;
            case DIRECT -> throw new IllegalStateException("DIRECT transport has no feed protocol");
        };
    }

    /** Returns a copy of {@code descriptor} with the RX-side contention-fix timeout option added. */
    private static StreamDescriptor withRxTimeout(StreamDescriptor descriptor) {
        Map<String, String> options = new LinkedHashMap<>(descriptor.options());
        options.put(RTSP_RX_TIMEOUT_OPTION, RTSP_RX_TIMEOUT_MICROS);
        return new StreamDescriptor(descriptor.protocol(), descriptor.uri(), options);
    }

    /** A newly wired video device and the transmitter that started its feed, for post-create tracking. */
    private record WiredVideoDevice(DeviceRegistration device, FeedTransmitterPort transmitter) {}

    /** A newly wired MAVLink telemetry device and the transmitter/feed id that started its feed, for post-create tracking. */
    private record WiredTelemetryDevice(DeviceRegistration device, FeedTransmitterPort transmitter, FeedId feedId) {}

    /** Which {@link FeedTransmitterPort} started a tracked asset's feed, so {@link #stop} stops it via the same adapter. */
    private record TrackedFeed(FeedTransmitterPort transmitter, FeedId feedId) {}

    /**
     * Builds a {@code "mavlink"}-protocol telemetry device backed by a real {@code
     * MavlinkFeedTransmitter} feed (adapter-mavlink's own natural follow-up, see that module's
     * MODULE.md Gotchas): allocates a free loopback UDP port, starts a feed pushing to it, and
     * registers a device listening on that same address — the "one shared {@code udp://host:port}
     * for both ends" contract {@code MavlinkFeedTransmitter}'s own javadoc documents ({@code
     * FeedSpec#source()} is repurposed as the transmit <em>destination</em> for that transmitter,
     * unlike the RTSP/MJPEG transmitters' {@code source()}, which names a local video <em>file</em>
     * — read carefully before assuming symmetry with {@link #wireVideoDevice}). The device is pinned
     * to {@link #MAVLINK_TELEMETRY_SYSID} via the {@code sysid} option so it deterministically claims
     * the one vehicle this feed transmits, rather than leaving it to unpinned first-heard-wins
     * claiming (adapter-mavlink/MODULE.md) — harmless either way since the port is exclusive to this
     * one feed, but deterministic is simpler to reason about.
     */
    private WiredTelemetryDevice wireMavlinkTelemetryDevice(String displayName, SimulationSpec spec) {
        int port = allocateLoopbackUdpPort();
        URI destination = URI.create("udp://" + MAVLINK_LOOPBACK_HOST + ":" + port);

        Map<String, String> deviceOptions = Map.of(MAVLINK_OPTION_SYSID, String.valueOf(MAVLINK_TELEMETRY_SYSID));
        DeviceRegistration device = new DeviceRegistration(displayName + " · telemetry", Set.of(Capability.TELEMETRY),
                new StreamDescriptor(TELEMETRY_PROTOCOL_MAVLINK, destination, deviceOptions));

        FeedSpec feedSpec = new FeedSpec(TELEMETRY_PROTOCOL_MAVLINK, destination, mavlinkFeedOptions(spec));
        FeedTransmitterPort transmitter = feedTransmitters.transmitterFor(feedSpec);
        FeedId feedId = FeedId.random();
        transmitter.start(feedId, feedSpec);
        return new WiredTelemetryDevice(device, transmitter, feedId);
    }

    /**
     * Binds an ephemeral {@link DatagramSocket} purely to learn a currently-free loopback UDP port,
     * then releases it immediately so {@code MavlinkFeedTransmitter}/{@code MavlinkTelemetrySource}
     * (adapter-mavlink) can each open their own socket on it afterward. A small TOCTOU race is
     * possible — another process could grab the port between this close and the transmitter's own
     * bind — accepted as good-enough for dev/demo tooling, the same posture this class already takes
     * for RTSP's fixed startup delay (see {@link #awaitFeedEstablished()}'s own javadoc).
     */
    private static int allocateLoopbackUdpPort() {
        try (DatagramSocket socket = new DatagramSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException("Unable to allocate a loopback UDP port for a MAVLink telemetry feed", e);
        }
    }

    /**
     * Maps {@link SimulationSpec#plan()} onto {@code MavlinkFeedTransmitter}'s (adapter-mavlink)
     * supported options — {@code route} (required by that transmitter) and {@code speedMps} — the
     * same "a route-carrying plan wins" precedence {@link #telemetryDevice} already applies for
     * {@code SimulatedTelemetrySource}. Unlike that method, {@code route} can never be left unset
     * here: {@code MavlinkFeedTransmitter#start} throws without one, so a plan with no route (or no
     * plan at all) falls back to {@link #mavlinkRoute}'s synthesized minimal route.
     *
     * <p>{@code batteryDrainPerSecond}/{@code positionRateHz}/{@code failsafeBatteryPercent} are
     * left unset (the transmitter's own defaults apply) since neither {@link TelemetryPlan} nor
     * {@link SimulationSpec} carries anything to map them from — not a dropped field, since there
     * was never one to drop. {@link TelemetryPlan#mode()} <em>is</em> a real drop whenever it names
     * anything other than {@link RouteMode#LOOP}: {@code MavlinkFeedTransmitter}'s route engine
     * ({@code MavlinkRoute}) only loops, unlike {@code adapter-simulation}'s fuller {@code
     * RoutePlan} — logged at {@code WARNING}, honest rather than silently ignored.
     */
    private static Map<String, String> mavlinkFeedOptions(SimulationSpec spec) {
        Map<String, String> options = new LinkedHashMap<>();
        options.put(MAVLINK_OPTION_ROUTE, mavlinkRoute(spec));
        // Explicit, not left to MavlinkFeedTransmitter's own default coinciding with
        // MAVLINK_TELEMETRY_SYSID by coincidence -- the transmitted messages must claim the exact
        // sysid the telemetry device itself is pinned to (StreamDescriptor.options["sysid"] above).
        options.put(MAVLINK_OPTION_SYSID, String.valueOf(MAVLINK_TELEMETRY_SYSID));
        TelemetryPlan plan = spec.plan();
        if (plan != null) {
            if (plan.speedMps() != null) {
                options.put(MAVLINK_OPTION_SPEED_MPS, formatDouble(plan.speedMps()));
            }
            if (plan.mode() != null && plan.mode() != RouteMode.LOOP) {
                LOG.log(System.Logger.Level.WARNING, () -> "TelemetryPlan#mode()=" + plan.mode()
                        + " is not supported by the MAVLink telemetry transport (its route engine only loops, "
                        + "see adapter-mavlink/MODULE.md) -- ignoring; the feed will loop regardless");
            }
        }
        return options;
    }

    /**
     * The {@code route} option value for a MAVLink-transport telemetry feed: {@link
     * SimulationSpec#plan()}'s route, serialized exactly like {@link #telemetryDevice} already does
     * for {@code SimulatedTelemetrySource}, when one is given — otherwise a synthesized minimal
     * two-point route (see {@link #MAVLINK_FALLBACK_ROUTE_OFFSET_DEGREES}'s own javadoc for why one
     * is needed at all) anchored at {@link SimulationSpec#latitude()}/{@link
     * SimulationSpec#longitude()}, or {@link #MAVLINK_FALLBACK_LATITUDE}/{@link
     * #MAVLINK_FALLBACK_LONGITUDE} when even those are absent.
     */
    private static String mavlinkRoute(SimulationSpec spec) {
        TelemetryPlan plan = spec.plan();
        if (plan != null && plan.route() != null) {
            return serializeRoute(plan.route());
        }
        double lat = spec.latitude() != null ? spec.latitude() : MAVLINK_FALLBACK_LATITUDE;
        double lon = spec.longitude() != null ? spec.longitude() : MAVLINK_FALLBACK_LONGITUDE;
        double offsetLat = lat + MAVLINK_FALLBACK_ROUTE_OFFSET_DEGREES;
        return formatDouble(lat) + "," + formatDouble(lon) + ";" + formatDouble(offsetLat) + "," + formatDouble(lon);
    }

    /** {@code SimulatedTelemetrySource} (adapter-simulation) device option keys this method emits. */
    private static final String TELEMETRY_OPTION_LAT = "lat";
    private static final String TELEMETRY_OPTION_LON = "lon";
    private static final String TELEMETRY_OPTION_ROUTE = "route";
    private static final String TELEMETRY_OPTION_SPEED_MPS = "speedMps";
    private static final String TELEMETRY_OPTION_ROUTE_MODE = "routeMode";

    /**
     * Builds the telemetry device's options: a {@link SimulationSpec#plan()} (docs/CYCLES-PLAN.md
     * §7, CT-a) wins over the bare {@link SimulationSpec#latitude()}/{@link
     * SimulationSpec#longitude()} home-point fields whenever it carries a route — in that case the
     * route's first waypoint doubles as the {@code lat}/{@code lon} start position (so even a
     * degenerate route string on the adapter side would fall back to a circle centered on the
     * intended start, not the adapter's own unrelated default center), and the route itself,
     * {@code speedMps}, and {@code routeMode} are serialized as {@code SimulatedTelemetrySource}
     * option strings. Without a route-carrying plan, behavior is unchanged from before CT-a: bare
     * {@code lat}/{@code lon} options are set only when the spec provides them.
     */
    private static DeviceRegistration telemetryDevice(String displayName, SimulationSpec spec) {
        Map<String, String> options = new LinkedHashMap<>();
        TelemetryPlan plan = spec.plan();
        if (plan != null && plan.route() != null) {
            Waypoint start = plan.route().get(0);
            options.put(TELEMETRY_OPTION_LAT, formatDouble(start.latitude()));
            options.put(TELEMETRY_OPTION_LON, formatDouble(start.longitude()));
            options.put(TELEMETRY_OPTION_ROUTE, serializeRoute(plan.route()));
            if (plan.speedMps() != null) {
                options.put(TELEMETRY_OPTION_SPEED_MPS, formatDouble(plan.speedMps()));
            }
            if (plan.mode() != null) {
                options.put(TELEMETRY_OPTION_ROUTE_MODE, plan.mode().name().toLowerCase(Locale.ROOT));
            }
        } else {
            if (spec.latitude() != null) {
                options.put(TELEMETRY_OPTION_LAT, formatDouble(spec.latitude()));
            }
            if (spec.longitude() != null) {
                options.put(TELEMETRY_OPTION_LON, formatDouble(spec.longitude()));
            }
        }
        URI telemetryUri = URI.create(SIM_PROTOCOL + "://" + slug(displayName) + "-telemetry");
        return new DeviceRegistration(displayName + " · telemetry", Set.of(Capability.TELEMETRY),
                new StreamDescriptor(SIM_PROTOCOL, telemetryUri, options));
    }

    /** {@code lat,lon[,altM];lat,lon[,altM];…} — the format {@code RoutePlan} (adapter-simulation) parses. */
    private static String serializeRoute(List<Waypoint> route) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < route.size(); i++) {
            if (i > 0) {
                builder.append(';');
            }
            Waypoint waypoint = route.get(i);
            builder.append(formatDouble(waypoint.latitude())).append(',').append(formatDouble(waypoint.longitude()));
            if (waypoint.altitudeMeters() != null) {
                builder.append(',').append(formatDouble(waypoint.altitudeMeters()));
            }
        }
        return builder.toString();
    }

    /**
     * Explicit {@link Locale#ROOT} so a locale using {@code ,} as the decimal separator can never
     * corrupt the {@code lat,lon[,altM];…} route format, whose own field separators are also
     * {@code ,}/{@code ;}.
     */
    private static String formatDouble(double value) {
        return String.format(Locale.ROOT, "%s", value);
    }

    /** A URI-safe stand-in for the display name; purely descriptive, never looked up. */
    private static String slug(String displayName) {
        String slug = displayName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        return slug.isBlank() ? "sim" : slug;
    }
}
