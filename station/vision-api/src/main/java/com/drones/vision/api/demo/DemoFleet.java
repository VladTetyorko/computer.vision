package com.drones.vision.api.demo;

import com.drones.vision.warehouse.application.asset.AssetService;
import com.drones.vision.perception.application.stream.AssetStreamService;
import com.drones.vision.simulation.application.RouteMode;
import com.drones.vision.simulation.application.SimulatedAsset;
import com.drones.vision.simulation.application.SimulationService;
import com.drones.vision.simulation.application.SimulationSpec;
import com.drones.vision.simulation.application.SimulationTransport;
import com.drones.vision.simulation.application.TelemetryPlan;
import com.drones.vision.simulation.application.Waypoint;
import com.drones.vision.kernel.Ownership;
import com.drones.vision.perception.domain.model.PipelineConfig;
import com.drones.vision.kernel.UserId;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.function.Consumer;

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
 * only {@link DemoPlan#startStreams()} of them through {@link AssetStreamService#startStream}
 * (docs/plans/active/DOMAIN-SEPARATION-W1.md &sect;15, W1.6e — split off {@link AssetService},
 * which this class still uses for {@link #registeredNames}), keeps a failed stream to one reported
 * line instead of one lost asset.
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

    /**
     * Airframes the demo fleet is named after, in the order they are handed out — real types rather
     * than {@code Demo 01}, so a demo reads like a fleet someone actually flies. Cycled with a
     * numeric suffix once exhausted, the same shape {@link DemoPeople}'s own call-sign
     * roster uses; {@link #nextCallSigns(int)} does the cycling here, because unlike a person's call sign
     * an asset name has to dodge what is already registered.
     *
     * <p>Mixed deliberately — fixed-wing recon, strike, and FPV/bomber quads — so the demo map shows
     * a plausible mixed fleet rather than ten of one thing.
     */
    private static final List<String> CALL_SIGNS = List.of(
            "FPV Pis-UN", "FPV Vyriy", "Skyfall Vampire", "Bayraktar TB2", "Leleka-100",
            "Furia", "Shark", "PD-2", "Poseidon H10", "Punisher",
            "RAM II", "Warmate", "Backfire", "Gor");

    private final SimulationService simulations;
    private final AssetService assets;
    private final AssetStreamService assetStreams;
    private final DemoVideoLibrary videos;
    /** The deployment's default {@link PipelineConfig} for a newly started stream (docs/plans/done/CV-DEMAND-PLAN.md §3.7/§3.8). */
    private final PipelineConfig defaultConfig;

    public DemoFleet(SimulationService simulations, AssetService assets, AssetStreamService assetStreams,
                      DemoVideoLibrary videos, PipelineConfig defaultConfig) {
        this.simulations = Objects.requireNonNull(simulations, "simulations must not be null");
        this.assets = Objects.requireNonNull(assets, "assets must not be null");
        this.assetStreams = Objects.requireNonNull(assetStreams, "assetStreams must not be null");
        this.videos = Objects.requireNonNull(videos, "videos must not be null");
        this.defaultConfig = Objects.requireNonNull(defaultConfig, "defaultConfig must not be null");
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
        List<String> callSigns = nextCallSigns(count);
        List<DemoAsset> created = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            Path video = library.isEmpty() ? null : library.get(index % library.size());
            String displayName = callSigns.get(index);
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
                assetStreams.startStream(asset.id(), null, defaultConfig);
                started++;
            } catch (RuntimeException e) {
                problems.accept("stream " + asset.displayName() + ": " + describe(e));
            }
        }
        return started;
    }

    /**
     * The next {@code count} free call signs, in hand-out order.
     *
     * <p>Replaces the old "one past the highest {@code Demo NN}" scan: with a fixed roster the
     * sequence number is no longer *in* the name, so there is nothing to parse back out. Instead
     * each candidate is checked against the names already registered, and the roster is cycled with
     * a numeric suffix ({@code Furia}, then {@code Furia 2}…) once exhausted — so a second press
     * extends the fleet rather than minting a duplicate of the first one.
     *
     * <p>Asset names are not unique keys — nothing rejects a duplicate — so this is a readability
     * guarantee, not an invariant. {@code taken} is also added to as names are chosen, which is what
     * keeps this batch free of duplicates among itself.
     */
    private List<String> nextCallSigns(int count) {
        Set<String> taken = registeredNames();
        List<String> chosen = new ArrayList<>(count);
        // Terminates because CALL_SIGNS is a non-empty constant: every lap adds a distinct suffix,
        // so each lap contributes at least one name that `taken` cannot already hold.
        for (int lap = 0; chosen.size() < count; lap++) {
            for (String base : CALL_SIGNS) {
                if (chosen.size() == count) {
                    break;
                }
                String candidate = lap == 0 ? base : base + " " + (lap + 1);
                if (taken.add(candidate)) {
                    chosen.add(candidate);
                }
            }
        }
        return chosen;
    }

    /**
     * Display names already registered, so a call sign is not handed out twice.
     *
     * <p>includeDeleted: a soft-deleted {@code Furia} is still in the archive view, so reusing the
     * name would put two of them side by side there. Degrades to "nothing is taken" if the lookup
     * fails — a demo press that names an asset twice is better than one that refuses to seed.
     */
    private Set<String> registeredNames() {
        try {
            return assets.assets(true).stream()
                    .map(summary -> summary.asset().displayName())
                    .collect(Collectors.toCollection(HashSet::new));
        } catch (RuntimeException e) {
            return new HashSet<>();
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
