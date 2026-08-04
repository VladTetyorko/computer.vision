package com.drones.vision.api.demo;

import com.drones.vision.application.geofence.GeofenceService;
import com.drones.vision.application.geofence.GeofenceZoneSpec;
import com.drones.vision.application.mark.MarkService;
import com.drones.vision.application.mark.MarkSpec;
import com.drones.vision.domain.model.GeoPosition;
import com.drones.vision.domain.model.Mark;
import com.drones.vision.domain.model.MarkKind;
import com.drones.vision.domain.model.Ownership;
import com.drones.vision.domain.model.UserId;
import com.drones.vision.domain.model.ZoneKind;
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
 * own application services so the map, the live SSE {@code marks} topic and the geofence monitor
 * all see them exactly as they would a hand-placed one.
 *
 * <p>Both passes are name-idempotent: a second press adds neither a duplicate zone nor a duplicate
 * mark, so the button can be pressed repeatedly without turning the map into a pile.
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

    public DemoOperations(GeofenceService geofences, MarkService marks) {
        this.geofences = Objects.requireNonNull(geofences, "geofences must not be null");
        this.marks = Objects.requireNonNull(marks, "marks must not be null");
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
     * Drops the demo's tactical marks, skipping any whose label is already on the board.
     *
     * @param ownership who owns the marks — the pressing user
     * @param actor     who the marks are attributed to
     * @param problems  sink for one human-readable line per mark that could not be created
     * @return how many marks this press actually created
     */
    public int seedMarks(Ownership ownership, UserId actor, Consumer<String> problems) {
        Objects.requireNonNull(ownership, "ownership must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(problems, "problems must not be null");
        Set<String> existing = new LinkedHashSet<>();
        try {
            marks.list().stream().map(Mark::label).forEach(existing::add);
        } catch (RuntimeException e) {
            problems.accept("marks: " + describe(e));
            return 0;
        }

        int created = 0;
        for (MarkSpec spec : markSpecs()) {
            if (existing.contains(spec.label())) {
                continue;
            }
            try {
                marks.create(spec, ownership, actor);
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

    private static List<MarkSpec> markSpecs() {
        return List.of(
                new MarkSpec(MarkKind.TARGET, "Demo contact 1", "Vehicle spotted on the northern track",
                        position(0.012, 0.004, 0.0)),
                new MarkSpec(MarkKind.TARGET, "Demo contact 2", "Second contact, moving south",
                        position(-0.009, 0.011, 0.0)),
                new MarkSpec(MarkKind.HAZARD, "Demo hazard", "Power line crossing the valley",
                        position(0.004, -0.013, 40.0)),
                new MarkSpec(MarkKind.FRIENDLY, "Demo ground team", "Ground team holding at the crossroads",
                        position(-0.014, -0.006, 0.0)),
                new MarkSpec(MarkKind.POI, "Demo launch point", "Where the demo fleet lifts off",
                        position(0.0, 0.0, 0.0)));
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
