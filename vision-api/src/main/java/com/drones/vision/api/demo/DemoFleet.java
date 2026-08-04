package com.drones.vision.api.demo;

import com.drones.vision.application.asset.AssetService;
import com.drones.vision.application.simulation.RouteMode;
import com.drones.vision.application.simulation.SimulatedAsset;
import com.drones.vision.application.simulation.SimulationService;
import com.drones.vision.application.simulation.SimulationSpec;
import com.drones.vision.application.simulation.SimulationTransport;
import com.drones.vision.application.simulation.TelemetryPlan;
import com.drones.vision.application.simulation.Waypoint;
import com.drones.vision.domain.model.Ownership;
import com.drones.vision.domain.model.PipelineConfig;
import com.drones.vision.domain.model.UserId;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The aircraft half of the demo scenario: simulated assets flying their own routes, each backed by
 * a video file from {@link DemoVideoLibrary} (round-robin when there are fewer files than assets,
 * fully synthetic when there are none).
 *
 * <p>Everything goes through {@link SimulationService#simulate} — the same call
 * {@code POST /api/simulations} makes — so each asset arrives fully formed: {@code simulated}
 * category, a video device plus a {@code sim} telemetry device carrying the route below, an audit
 * entry, and a fleet-changed live update. Nothing here reaches past the application layer.
 *
 * <h2>Why creation and streaming are two passes</h2>
 * {@link SimulationService#simulate} with {@code autoStart} would abort the whole call when a
 * stream fails to open (no mediamtx, a codec the machine cannot decode) — and the asset it already
 * created would be left behind, invisible to the caller. Creating every asset first, then starting
 * only {@link DemoPlan#startStreams()} of them through {@link AssetService#startStream}, keeps a
 * failed stream to one reported line instead of one lost asset.
 */
@Component
@ConditionalOnProperty(prefix = "vision.demo", name = "enabled", matchIfMissing = true)
public class DemoFleet {

    /** Centre of the demo's area of operations — the same home point the simulation defaults use. */
    static final double CENTRE_LATITUDE = 50.45;
    static final double CENTRE_LONGITUDE = 30.52;

    /** Radius (degrees latitude, ~1.1km) of the ring the assets' home points are spread around. */
    private static final double RING_RADIUS_DEGREES = 0.01;

    /** Half-width (degrees, ~330m) of the diamond route each asset loops around its own home point. */
    private static final double ROUTE_RADIUS_DEGREES = 0.003;

    /** Cruise speeds cycled across the fleet so the map does not move as one block. */
    private static final double[] SPEEDS_MPS = {9.0, 12.0, 15.0, 18.0};

    /** Cruise altitudes cycled across the fleet, metres AGL. */
    private static final double[] ALTITUDES_METERS = {80.0, 110.0, 140.0, 170.0};

    /** Call-sign shape, and the pattern {@link #nextCallSignNumber()} reads back out of it. */
    private static final String CALL_SIGN_FORMAT = "Demo %02d";
    private static final Pattern CALL_SIGN = Pattern.compile("Demo (\\d+)");

    private final SimulationService simulations;
    private final AssetService assets;
    private final DemoVideoLibrary videos;

    public DemoFleet(SimulationService simulations, AssetService assets, DemoVideoLibrary videos) {
        this.simulations = Objects.requireNonNull(simulations, "simulations must not be null");
        this.assets = Objects.requireNonNull(assets, "assets must not be null");
        this.videos = Objects.requireNonNull(videos, "videos must not be null");
    }

    /**
     * Creates {@code count} simulated assets, none of them streaming yet.
     *
     * @param count     how many to create
     * @param ownership who owns them — the pressing user, exactly like a manual simulation
     * @param actor     who the audit trail attributes the creation to
     * @param problems  sink for one human-readable line per asset that could not be created
     * @return the created assets, in creation order (possibly shorter than {@code count})
     */
    public List<DemoAsset> seed(int count, Ownership ownership, UserId actor, Consumer<String> problems) {
        Objects.requireNonNull(ownership, "ownership must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(problems, "problems must not be null");
        if (count <= 0) {
            return List.of();
        }

        List<Path> library = videos.videos();
        int firstNumber = nextCallSignNumber();
        List<DemoAsset> created = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            Path video = library.isEmpty() ? null : library.get(index % library.size());
            String displayName = String.format(CALL_SIGN_FORMAT, firstNumber + index);
            try {
                SimulatedAsset simulated =
                        simulations.simulate(spec(displayName, video, index, count), ownership, actor);
                created.add(new DemoAsset(simulated.assetId(), displayName,
                        video == null ? null : video.getFileName().toString()));
            } catch (RuntimeException e) {
                problems.accept("asset " + displayName + ": " + describe(e));
            }
        }
        return List.copyOf(created);
    }

    /**
     * Puts the first {@code howMany} of {@code fleet} on the air, one stream each.
     *
     * @param fleet    the assets {@link #seed} created
     * @param howMany  how many of them to start, from the front of the list
     * @param problems sink for one human-readable line per stream that failed to open
     * @return how many streams actually started
     */
    public int startStreams(List<DemoAsset> fleet, int howMany, Consumer<String> problems) {
        Objects.requireNonNull(fleet, "fleet must not be null");
        Objects.requireNonNull(problems, "problems must not be null");
        int started = 0;
        for (DemoAsset asset : fleet.subList(0, Math.max(0, Math.min(howMany, fleet.size())))) {
            try {
                assets.startStream(asset.id(), null, PipelineConfig.defaults());
                started++;
            } catch (RuntimeException e) {
                problems.accept("stream " + asset.displayName() + ": " + describe(e));
            }
        }
        return started;
    }

    /**
     * Where this press's call signs start: one past the highest {@code Demo NN} already registered,
     * so a second press extends the fleet ({@code Demo 11}, {@code Demo 12}…) instead of minting a
     * second {@code Demo 01}. Asset names are not unique keys — nothing rejects a duplicate — so
     * this is a readability guarantee, not an invariant.
     */
    private int nextCallSignNumber() {
        try {
            // includeDeleted: a soft-deleted "Demo 03" is still in the archive view, so reusing its
            // number would put two of them side by side there.
            return assets.assets(true).stream()
                    .map(summary -> CALL_SIGN.matcher(summary.asset().displayName()))
                    .filter(Matcher::matches)
                    .mapToInt(matcher -> Integer.parseInt(matcher.group(1)))
                    .max()
                    .orElse(0) + 1;
        } catch (RuntimeException e) {
            return 1;
        }
    }

    /** Distinct file names backing {@code fleet}, in first-use order — reported back to the caller. */
    public List<String> videosUsed(List<DemoAsset> fleet) {
        return fleet.stream().map(DemoAsset::videoName).filter(Objects::nonNull).distinct().toList();
    }

    /**
     * One asset's simulation spec: its own home point on the ring, its own diamond route around it,
     * and its own cruise speed, so the map shows a fleet rather than a stack.
     */
    private static SimulationSpec spec(String displayName, Path video, int index, int fleetSize) {
        double angle = 2 * Math.PI * index / Math.max(1, fleetSize);
        double homeLatitude = CENTRE_LATITUDE + RING_RADIUS_DEGREES * Math.cos(angle);
        double homeLongitude = CENTRE_LONGITUDE
                + RING_RADIUS_DEGREES * Math.sin(angle) / Math.cos(Math.toRadians(homeLatitude));
        double altitude = ALTITUDES_METERS[index % ALTITUDES_METERS.length];
        TelemetryPlan plan = new TelemetryPlan(SPEEDS_MPS[index % SPEEDS_MPS.length], RouteMode.LOOP,
                diamond(homeLatitude, homeLongitude, altitude));
        return new SimulationSpec(displayName, video == null ? null : video.toString(), homeLatitude,
                homeLongitude, false, SimulationTransport.DIRECT, plan);
    }

    /** A four-point closed route around one home point — the smallest shape that reads as a patrol. */
    private static List<Waypoint> diamond(double latitude, double longitude, double altitude) {
        double longitudeRadius = ROUTE_RADIUS_DEGREES / Math.cos(Math.toRadians(latitude));
        return List.of(new Waypoint(latitude + ROUTE_RADIUS_DEGREES, longitude, altitude),
                new Waypoint(latitude, longitude + longitudeRadius, altitude),
                new Waypoint(latitude - ROUTE_RADIUS_DEGREES, longitude, altitude),
                new Waypoint(latitude, longitude - longitudeRadius, altitude));
    }

    private static String describe(RuntimeException e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
