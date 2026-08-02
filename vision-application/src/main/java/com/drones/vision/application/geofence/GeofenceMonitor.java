package com.drones.vision.application.geofence;

import com.drones.vision.domain.model.AssetId;
import com.drones.vision.domain.model.Event;
import com.drones.vision.domain.model.EventType;
import com.drones.vision.domain.model.GeoPosition;
import com.drones.vision.domain.model.GeofenceZone;
import com.drones.vision.domain.model.Telemetry;
import com.drones.vision.domain.model.ZoneId;
import com.drones.vision.domain.model.ZoneKind;
import com.drones.vision.domain.port.out.EventPublisherPort;
import com.drones.vision.domain.port.out.GeofenceRepositoryPort;
import com.drones.vision.domain.port.out.LiveUpdatePublisherPort;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import com.drones.vision.application.pipeline.UsageTracker;

/**
 * Evaluates live telemetry against the enabled {@link GeofenceZone} set and raises {@link
 * EventType#GEOFENCE_BREACH} events on edge transitions (docs/OPS-CORE-PLAN.md §G).
 *
 * <p>Called from {@link UsageTracker#applySample} once per telemetry sample — the same hot path
 * that already persists/live-announces the sample — so {@link #evaluate(AssetId, Telemetry)} must
 * stay cheap: no repository I/O per call beyond the enabled-zone cache (refreshed out of band, see
 * below) and, on an actual breach edge, one {@link EventPublisherPort#publish}/{@link
 * LiveUpdatePublisherPort#publishEvent} call.
 *
 * <h2>Zone cache</h2>
 * The enabled zone set is cached in {@link #enabledZones}, a plain volatile reference swapped by
 * {@link #refresh()} — {@code GeofenceService} calls it after every create/update/delete so the
 * cache reflects the latest CRUD, and this class also self-populates it lazily, on the very first
 * {@link #evaluate} call, in case telemetry starts flowing before any CRUD ever touches this
 * instance (e.g. zones already existed in storage before this JVM booted). A disabled zone is
 * simply absent from the cache — it is never evaluated, and any previously-open breach against it
 * is left exactly as it was (no synthetic "exit" fires just because a zone was disabled — see
 * Gotchas).
 *
 * <h2>Breach rules (docs/OPS-CORE-PLAN.md §G, frozen)</h2>
 * For a positioned sample (see below), each enabled zone independently evaluates:
 * <ul>
 *   <li>{@link ZoneKind#KEEP_OUT}: breach iff the position is inside the zone's polygon, at any
 *       altitude — {@code maxAltitudeMeters} has no effect on a {@code KEEP_OUT} zone (it is
 *       already a breach the moment the position is inside, regardless of altitude).</li>
 *   <li>{@link ZoneKind#KEEP_IN}: breach iff <b>at least one enabled {@code KEEP_IN} zone
 *       exists</b> and the position is outside <b>every</b> enabled {@code KEEP_IN} zone (the
 *       union of all of them is the allowed area) — <b>or</b> the position is inside this
 *       particular zone's polygon but its altitude exceeds this zone's own {@code
 *       maxAltitudeMeters}. The first condition is evaluated once per sample and shared across
 *       every enabled {@code KEEP_IN} zone (see Gotchas for what that implies when more than one
 *       is configured); the altitude condition is per-zone.</li>
 * </ul>
 * A sample with no {@code latitude}/{@code longitude} is ignored entirely — no zone's state is
 * touched, since "unknown position" must never be silently treated as either compliant or
 * breaching. A sample with a position but no {@code altitudeMeters} simply never trips an altitude
 * ceiling (the same honest-unknown treatment).
 *
 * <h2>Per-(asset, zone) breach state and edge transitions</h2>
 * {@link #breachedByKey} holds one boolean per {@code (assetId, zoneId)} pair ever evaluated,
 * in-heap only (see Gotchas). {@link #evaluate} recomputes the current breach boolean for every
 * enabled zone and compares it against the stored one: only a change (false→true or true→false)
 * raises one {@link Event} of type {@link EventType#GEOFENCE_BREACH}, with {@code streamId=null}
 * (a geofence breach is asset-scoped, not stream-scoped) and {@code attributes} {@code
 * {assetId, zoneId, zoneName, kind, direction}} ({@code direction} is {@code "enter"} or {@code
 * "exit"}) — published via both {@link EventPublisherPort#publish} and {@link
 * LiveUpdatePublisherPort#publishEvent}, mirroring every other event this application layer
 * raises. Staying in the same state (still breaching, or still compliant) is silent — no repeat
 * spam on every sample while an asset lingers inside a keep-out zone.
 *
 * <h2>Threading</h2>
 * {@link #enabledZones} is a plain volatile reference (safe publication, no lock needed to read
 * it) and {@link #breachedByKey} is a {@link ConcurrentHashMap} — concurrent {@link #evaluate}
 * calls for different assets never contend, matching {@code UsageTracker}'s own per-asset
 * concurrency model. A benign race is possible if {@link #refresh()} and the lazy self-populate in
 * {@link #evaluate} both fire concurrently on a cold cache; both simply re-read the repository and
 * the last write wins, which is harmless (both results are equally valid, momentarily-stale
 * snapshots of the same store).
 */
public final class GeofenceMonitor {

    private final GeofenceRepositoryPort geofenceRepository;
    private final EventPublisherPort eventPublisher;
    private final LiveUpdatePublisherPort liveUpdatePublisherPort;

    /** {@code null} until the first {@link #refresh()} (explicit or lazy); see the class javadoc. */
    private volatile List<GeofenceZone> enabledZones;

    private final ConcurrentHashMap<BreachKey, Boolean> breachedByKey = new ConcurrentHashMap<>();

    /**
     * @param geofenceRepository      required — the source of truth {@link #refresh()} reads from
     * @param eventPublisher          nullable: {@code null} means breach transitions are never
     *                                published through this port, following the same
     *                                nullable-collaborator convention as {@code UsageTracker}'s own
     *                                {@code liveUpdatePublisherPort}
     * @param liveUpdatePublisherPort nullable, same convention
     */
    public GeofenceMonitor(GeofenceRepositoryPort geofenceRepository, EventPublisherPort eventPublisher,
                           LiveUpdatePublisherPort liveUpdatePublisherPort) {
        this.geofenceRepository = Objects.requireNonNull(geofenceRepository, "geofenceRepository must not be null");
        this.eventPublisher = eventPublisher; // nullable: no EventPublisherPort announcements when absent
        this.liveUpdatePublisherPort = liveUpdatePublisherPort; // nullable: no live-update announcements when absent
    }

    /**
     * Reloads the enabled-zone cache from {@link GeofenceRepositoryPort#findAll()}. Called by
     * {@code GeofenceService} after every create/update/delete; safe to call from any thread at any
     * time (a plain, cheap repository read plus a volatile reference swap).
     */
    public void refresh() {
        enabledZones = geofenceRepository.findAll().stream().filter(GeofenceZone::enabled).toList();
    }

    /**
     * Evaluates one telemetry sample against every enabled zone for {@code assetId}, publishing one
     * {@link EventType#GEOFENCE_BREACH} event per zone whose breach state just changed.
     *
     * @param assetId the asset the sample belongs to
     * @param sample  the telemetry sample; ignored entirely (no state change) if it carries no
     *                {@code latitude}/{@code longitude}
     */
    public void evaluate(AssetId assetId, Telemetry sample) {
        Objects.requireNonNull(assetId, "assetId must not be null");
        Objects.requireNonNull(sample, "sample must not be null");
        if (sample.latitude() == null || sample.longitude() == null) {
            return; // honest unknown: never a breach, never a state change
        }
        List<GeofenceZone> zones = zonesSnapshot();
        if (zones.isEmpty()) {
            return;
        }
        GeoPosition position = new GeoPosition(sample.latitude(), sample.longitude(), sample.altitudeMeters());
        boolean outsideAllEnabledKeepInZones = zones.stream().anyMatch(zone -> zone.kind() == ZoneKind.KEEP_IN)
                && zones.stream().filter(zone -> zone.kind() == ZoneKind.KEEP_IN).noneMatch(zone -> zone.contains(position));
        for (GeofenceZone zone : zones) {
            boolean breached = isBreached(zone, position, sample.altitudeMeters(), outsideAllEnabledKeepInZones);
            applyTransition(assetId, zone, breached);
        }
    }

    private List<GeofenceZone> zonesSnapshot() {
        List<GeofenceZone> zones = enabledZones;
        if (zones == null) {
            refresh(); // lazy self-populate: see class javadoc
            zones = enabledZones;
        }
        return zones;
    }

    private static boolean isBreached(GeofenceZone zone, GeoPosition position, Double altitudeMeters,
                                       boolean outsideAllEnabledKeepInZones) {
        return switch (zone.kind()) {
            case KEEP_OUT -> zone.contains(position);
            case KEEP_IN -> outsideAllEnabledKeepInZones || altitudeCeilingExceeded(zone, position, altitudeMeters);
        };
    }

    private static boolean altitudeCeilingExceeded(GeofenceZone zone, GeoPosition position, Double altitudeMeters) {
        return zone.maxAltitudeMeters() != null && altitudeMeters != null && altitudeMeters > zone.maxAltitudeMeters()
                && zone.contains(position);
    }

    private void applyTransition(AssetId assetId, GeofenceZone zone, boolean breached) {
        BreachKey key = new BreachKey(assetId, zone.id());
        Boolean previouslyBreached = breachedByKey.put(key, breached);
        boolean wasBreached = previouslyBreached != null && previouslyBreached;
        if (breached == wasBreached) {
            return; // no edge: silent, whether staying compliant or staying in breach
        }
        publishBreachEvent(assetId, zone, breached);
    }

    private void publishBreachEvent(AssetId assetId, GeofenceZone zone, boolean entering) {
        String direction = entering ? "enter" : "exit";
        Map<String, String> attributes = Map.of(
                "assetId", assetId.value().toString(),
                "zoneId", zone.id().value().toString(),
                "zoneName", zone.name(),
                "kind", zone.kind().name(),
                "direction", direction);
        String message = (entering ? "Entered " : "Exited ") + zone.kind() + " zone \"" + zone.name() + "\"";
        Event event = new Event(UUID.randomUUID().toString(), null, Instant.now(), EventType.GEOFENCE_BREACH, message,
                attributes);
        if (eventPublisher != null) {
            eventPublisher.publish(event);
        }
        if (liveUpdatePublisherPort != null) {
            liveUpdatePublisherPort.publishEvent(event);
        }
    }

    private record BreachKey(AssetId assetId, ZoneId zoneId) {
    }
}
