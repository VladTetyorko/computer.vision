package com.drones.vision.application;

import com.drones.vision.domain.model.Asset;
import com.drones.vision.domain.model.AssetId;
import com.drones.vision.domain.model.Capability;
import com.drones.vision.domain.model.CategoryId;
import com.drones.vision.domain.model.FeedId;
import com.drones.vision.domain.model.FeedSpec;
import com.drones.vision.domain.model.Ownership;
import com.drones.vision.domain.model.PipelineConfig;
import com.drones.vision.domain.model.StreamDescriptor;
import com.drones.vision.domain.model.StreamId;
import com.drones.vision.domain.model.UserId;
import com.drones.vision.domain.port.out.CategoryRepositoryPort;
import com.drones.vision.domain.port.out.FeedTransmitterPort;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
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
 * selects for that transport) and a {@code "sim"}-protocol telemetry device — and delegates the
 * actual creation/streaming to {@link AssetService}, so every rule {@code AssetService#create}/
 * {@code #startStream} already enforces (category validation, audit, device registration) applies
 * here too instead of being duplicated.
 *
 * <h2>Threading</h2>
 * {@link #feedByAsset} is the only mutable state, a {@link ConcurrentHashMap} safe for concurrent
 * {@link #simulate}/{@link #stop} calls across different assets; all other shared state is reached
 * through the injected collaborators.
 */
public final class DefaultSimulationService implements SimulationService {

    /** The category every simulated asset is created under; seeded by devsupport at startup. */
    static final CategoryId SIMULATED_CATEGORY = new CategoryId("simulated");

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

    private final AssetService assetService;
    private final CategoryRepositoryPort categoryRepository;
    private final FeedTransmitterRegistry feedTransmitters;

    /** Tracks which transmitter/{@link FeedId} pair (if any) backs each wired-transport asset's feed. */
    private final Map<AssetId, TrackedFeed> feedByAsset = new ConcurrentHashMap<>();

    public DefaultSimulationService(AssetService assetService, CategoryRepositoryPort categoryRepository,
                                     FeedTransmitterRegistry feedTransmitters) {
        this.assetService = Objects.requireNonNull(assetService, "assetService must not be null");
        this.categoryRepository = Objects.requireNonNull(categoryRepository, "categoryRepository must not be null");
        this.feedTransmitters = Objects.requireNonNull(feedTransmitters, "feedTransmitters must not be null");
    }

    @Override
    public SimulatedAsset simulate(SimulationSpec spec, Ownership ownership, UserId actor) {
        Objects.requireNonNull(spec, "spec must not be null");
        Objects.requireNonNull(ownership, "ownership must not be null");
        Objects.requireNonNull(actor, "actor must not be null");

        Path videoPath = spec.videoPath() == null ? null : validateVideoPath(spec.videoPath());
        requireSimulatedCategorySeeded();

        String displayName = resolveDisplayName(spec.displayName(), videoPath);

        boolean wired = spec.transport() != SimulationTransport.DIRECT;
        FeedId feedId = wired ? FeedId.random() : null;
        FeedTransmitterPort transmitter = null;
        DeviceRegistration videoDevice;
        if (wired) {
            // spec's compact ctor guarantees videoPath != null whenever transport != DIRECT.
            WiredVideoDevice wiredDevice = wireVideoDevice(displayName, videoPath, feedId, spec.transport());
            videoDevice = wiredDevice.device();
            transmitter = wiredDevice.transmitter();
        } else if (videoPath == null) {
            videoDevice = syntheticVideoDevice(displayName);
        } else {
            videoDevice = videoDevice(displayName, videoPath);
        }

        Map<String, String> attributes = videoPath == null ? Map.of() : Map.of("source", videoPath.toString());
        AssetSpec assetSpec = new AssetSpec(displayName, SIMULATED_CATEGORY, attributes,
                List.of(videoDevice, telemetryDevice(displayName, spec)));

        Asset asset;
        try {
            asset = assetService.create(assetSpec, ownership, actor);
        } catch (RuntimeException e) {
            stopFeedQuietly(transmitter, feedId);
            throw e;
        }
        if (feedId != null) {
            feedByAsset.put(asset.id(), new TrackedFeed(transmitter, feedId));
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
                stopFeedQuietly(transmitter, feedId);
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
        TrackedFeed tracked = feedByAsset.remove(assetId);
        if (tracked != null) {
            tracked.transmitter().stop(tracked.feedId());
        }
    }

    private static void stopFeedQuietly(FeedTransmitterPort transmitter, FeedId feedId) {
        if (feedId != null) {
            transmitter.stop(feedId);
        }
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

    /** Which {@link FeedTransmitterPort} started a tracked asset's feed, so {@link #stop} stops it via the same adapter. */
    private record TrackedFeed(FeedTransmitterPort transmitter, FeedId feedId) {}

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
