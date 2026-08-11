package com.drones.vision.api.demo;

import com.drones.vision.application.geofence.GeofenceService;
import com.drones.vision.application.geofence.GeofenceZoneSpec;
import com.drones.vision.application.map.MapAccessPolicy.Viewer;
import com.drones.vision.application.map.MapLayerService;
import com.drones.vision.application.mark.MarkService;
import com.drones.vision.application.mark.MarkSpec;
import com.drones.vision.map.domain.model.Affiliation;
import com.drones.vision.kernel.GeoPosition;
import com.drones.vision.map.domain.model.LayerId;
import com.drones.vision.map.domain.model.Mark;
import com.drones.vision.map.domain.model.MarkKind;
import com.drones.vision.flight.domain.model.ZoneKind;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The common-operational-picture half of the demo scenario: the geofence zones the fleet flies
 * inside and the tactical marks a crew would have dropped on the map, both created through their
 * own application services so the map, the live SSE {@code map} topic and the geofence monitor all
 * see them exactly as they would a hand-placed one.
 *
 * <p>Both passes are name-idempotent: a second press adds neither a duplicate zone nor a duplicate
 * mark, so the button can be pressed repeatedly without turning the map into a pile.
 *
 * <h2>Which layer the demo marks land on (docs/plans/done/MAP-REWORK-PLAN.md Wave C)</h2>
 * The COP layer, explicitly — {@link MapLayerService#copLayerId()}, not the default layer {@code
 * LayerResolver} would pick. A demo exists to show the shared picture, and the COP layer is the one
 * every role can see; letting the marks fall onto the pressing user's own TEAM/PERSONAL layer would
 * make them invisible to exactly the other-role windows a demo is usually being shown in.
 *
 * <p>Their affiliations follow docs/plans/done/MAP-REWORK-PLAN.md §2.2's own old-kind → (kind, affiliation)
 * migration table, so the seeded set matches what a pre-rework deployment's marks become after
 * {@code V12__map_layers.sql} runs: {@code TARGET→(TARGET, HOSTILE)}, {@code HAZARD→(HAZARD,
 * UNKNOWN)}, {@code POI→(POI, NEUTRAL)}, {@code FRIENDLY→(UNIT, FRIENDLY)}.
 */
@Component
@ConditionalOnProperty(prefix = "vision.demo", name = "enabled", matchIfMissing = true)
public class DemoOperations {

    /** Half-width (degrees) of the keep-in box drawn around the fleet's ring — comfortably outside it. */
    private static final double AREA_HALF_WIDTH_DEGREES = 0.025;

    /** Ceiling of the keep-in box, metres — above every cruise altitude {@link DemoFleet} assigns. */
    private static final double AREA_CEILING_METERS = 250.0;

    /** Half-width (degrees) of the small no-fly box parked north-east of the centre. */
    private static final double NO_FLY_HALF_WIDTH_DEGREES = 0.004;
    private static final double NO_FLY_OFFSET_DEGREES = 0.014;

    private final GeofenceService geofences;
    private final MarkService marks;
    private final MapLayerService layers;

    public DemoOperations(GeofenceService geofences, MarkService marks, MapLayerService layers) {
        this.geofences = Objects.requireNonNull(geofences, "geofences must not be null");
        this.marks = Objects.requireNonNull(marks, "marks must not be null");
        this.layers = Objects.requireNonNull(layers, "layers must not be null");
    }

    /**
     * Creates the demo's keep-in area and no-fly box, skipping either one that already exists.
     *
     * @param problems sink for one human-readable line per zone that could not be created
     * @return how many zones this press actually created
     */
    public int seedZones(Consumer<String> problems) {
        Objects.requireNonNull(problems, "problems must not be null");
        Set<String> existing = new LinkedHashSet<>();
        try {
            geofences.zones().forEach(zone -> existing.add(zone.name()));
        } catch (RuntimeException e) {
            problems.accept("zones: " + describe(e));
            return 0;
        }

        int created = 0;
        for (GeofenceZoneSpec spec : zoneSpecs()) {
            if (existing.contains(spec.name())) {
                continue;
            }
            try {
                geofences.create(spec);
                created++;
            } catch (RuntimeException e) {
                problems.accept("zone " + spec.name() + ": " + describe(e));
            }
        }
        return created;
    }

    /**
     * Drops the demo's tactical marks onto the COP layer, skipping any whose label is already on the
     * board.
     *
     * @param viewer   the pressing user — who the marks are owned by and attributed to, and whose
     *                 access {@link MarkService} checks against the COP layer (any MANAGER/ADMIN may
     *                 contribute to it; a PILOT pressing the demo button gets one {@code problems}
     *                 line per mark rather than an error status, matching this class's
     *                 fault-tolerant contract)
     * @param problems sink for one human-readable line per mark that could not be created
     * @return how many marks this press actually created
     */
    public int seedMarks(Viewer viewer, Consumer<String> problems) {
        Objects.requireNonNull(viewer, "viewer must not be null");
        Objects.requireNonNull(problems, "problems must not be null");
        Set<String> existing = new LinkedHashSet<>();
        LayerId cop;
        try {
            marks.list(viewer).stream().map(Mark::label).forEach(existing::add);
            cop = layers.copLayerId();
        } catch (RuntimeException e) {
            problems.accept("marks: " + describe(e));
            return 0;
        }

        int created = 0;
        for (MarkSpec spec : markSpecs(cop)) {
            if (existing.contains(spec.label())) {
                continue;
            }
            try {
                marks.create(viewer, spec);
                created++;
            } catch (RuntimeException e) {
                problems.accept("mark " + spec.label() + ": " + describe(e));
            }
        }
        return created;
    }

    private static List<GeofenceZoneSpec> zoneSpecs() {
        return List.of(
                new GeofenceZoneSpec("Demo operating area", ZoneKind.KEEP_IN,
                        box(DemoFleet.CENTRE_LATITUDE, DemoFleet.CENTRE_LONGITUDE, AREA_HALF_WIDTH_DEGREES),
                        AREA_CEILING_METERS, true),
                new GeofenceZoneSpec("Demo no-fly box", ZoneKind.KEEP_OUT,
                        box(DemoFleet.CENTRE_LATITUDE + NO_FLY_OFFSET_DEGREES,
                                DemoFleet.CENTRE_LONGITUDE + NO_FLY_OFFSET_DEGREES, NO_FLY_HALF_WIDTH_DEGREES),
                        null, true));
    }

    /**
     * The five demo marks, each carrying the (kind, affiliation) pair docs/plans/done/MAP-REWORK-PLAN.md §2.2's
     * migration table assigns to its pre-rework kind — so a freshly seeded demo and a migrated
     * deployment show the same symbology. Note "Demo ground team" is now {@code (UNIT, FRIENDLY)}:
     * the old {@code MarkKind.FRIENDLY} is gone, since "whose it is" became {@link Affiliation}.
     */
    private static List<MarkSpec> markSpecs(LayerId layerId) {
        return List.of(
                new MarkSpec(layerId, MarkKind.TARGET, Affiliation.HOSTILE, "Demo contact 1",
                        "Vehicle spotted on the northern track", position(0.012, 0.004, 0.0)),
                new MarkSpec(layerId, MarkKind.TARGET, Affiliation.HOSTILE, "Demo contact 2",
                        "Second contact, moving south", position(-0.009, 0.011, 0.0)),
                new MarkSpec(layerId, MarkKind.HAZARD, Affiliation.UNKNOWN, "Demo hazard",
                        "Power line crossing the valley", position(0.004, -0.013, 40.0)),
                new MarkSpec(layerId, MarkKind.UNIT, Affiliation.FRIENDLY, "Demo ground team",
                        "Ground team holding at the crossroads", position(-0.014, -0.006, 0.0)),
                new MarkSpec(layerId, MarkKind.POI, Affiliation.NEUTRAL, "Demo launch point",
                        "Where the demo fleet lifts off", position(0.0, 0.0, 0.0)));
    }

    /** A square polygon centred on one point — the simplest shape the geofence contract accepts. */
    private static List<GeoPosition> box(double latitude, double longitude, double halfWidthDegrees) {
        double longitudeHalfWidth = halfWidthDegrees / Math.cos(Math.toRadians(latitude));
        return List.of(new GeoPosition(latitude - halfWidthDegrees, longitude - longitudeHalfWidth, null),
                new GeoPosition(latitude - halfWidthDegrees, longitude + longitudeHalfWidth, null),
                new GeoPosition(latitude + halfWidthDegrees, longitude + longitudeHalfWidth, null),
                new GeoPosition(latitude + halfWidthDegrees, longitude - longitudeHalfWidth, null));
    }

    private static GeoPosition position(double latitudeOffset, double longitudeOffset, double altitude) {
        return new GeoPosition(DemoFleet.CENTRE_LATITUDE + latitudeOffset,
                DemoFleet.CENTRE_LONGITUDE + longitudeOffset, altitude);
    }

    private static String describe(RuntimeException e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
