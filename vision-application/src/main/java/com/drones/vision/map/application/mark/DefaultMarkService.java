package com.drones.vision.map.application.mark;

import com.drones.vision.map.domain.model.Affiliation;
import com.drones.vision.kernel.GeoPosition;
import com.drones.vision.kernel.GeoProjection;
import com.drones.vision.map.domain.model.LayerId;
import com.drones.vision.map.domain.model.MapEvent;
import com.drones.vision.map.domain.model.MapLayer;
import com.drones.vision.map.domain.model.Mark;
import com.drones.vision.map.domain.model.MarkId;
import com.drones.vision.map.domain.model.MarkKind;
import com.drones.vision.map.domain.model.MarkSource;
import com.drones.vision.map.domain.model.MarkStatus;
import com.drones.vision.kernel.Ownership;
import com.drones.vision.flight.domain.model.Telemetry;
import com.drones.vision.map.domain.model.Verification;
import com.drones.vision.map.domain.model.Verification.VerificationState;
import com.drones.vision.events.domain.port.LiveUpdatePublisherPort;
import com.drones.vision.map.domain.port.MarkRepositoryPort;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.stream.Collectors;
import com.drones.vision.map.application.LayerResolver;
import com.drones.vision.map.application.MapAccessPolicy;
import com.drones.vision.map.application.MapAccessPolicy.Viewer;
import com.drones.vision.perception.application.pipeline.UsageTracker;
import com.drones.vision.platform.AccessDeniedException;

/**
 * The one implementation of {@link MarkService}.
 *
 * <h2>Geolocation</h2>
 * {@link #geolocate} is the one place this class does more than CRUD: it reads {@code assetId}'s
 * freshest sample via {@link UsageTracker#latestTelemetry}, honestly validates it (latitude,
 * longitude and heading present; altitude present and positive), and only then builds the drone's
 * {@link GeoPosition} and calls {@link GeoProjection#project} — {@code GeoProjection} itself accepts
 * no nullable telemetry (docs/plans/done/TACTICAL-MARKS-PLAN.md's M1 handoff note), so this null-checking is
 * this class's job, not the pure geo-math's.
 *
 * <h2>Threading</h2>
 * Holds no mutable state of its own — all shared state is reached through the injected
 * ports/collaborators.
 */
public final class DefaultMarkService implements MarkService {

    private static final String TELEMETRY_INCOMPLETE = "cannot geolocate: telemetry incomplete";

    private final MarkRepositoryPort markRepository;
    private final UsageTracker usageTracker;
    private final LiveUpdatePublisherPort liveUpdatePublisher;
    private final MapAccessPolicy policy;
    private final LayerResolver layerResolver;

    public DefaultMarkService(MarkRepositoryPort markRepository, UsageTracker usageTracker,
                               LiveUpdatePublisherPort liveUpdatePublisher, MapAccessPolicy policy,
                               LayerResolver layerResolver) {
        this.markRepository = Objects.requireNonNull(markRepository, "markRepository must not be null");
        this.usageTracker = Objects.requireNonNull(usageTracker, "usageTracker must not be null");
        this.liveUpdatePublisher =
                Objects.requireNonNull(liveUpdatePublisher, "liveUpdatePublisher must not be null");
        this.policy = Objects.requireNonNull(policy, "policy must not be null");
        this.layerResolver = Objects.requireNonNull(layerResolver, "layerResolver must not be null");
    }

    @Override
    public List<Mark> list(Viewer v) {
        Objects.requireNonNull(v, "v must not be null");
        Map<LayerId, MapLayer> layersById =
                layerResolver.findAll().stream().collect(Collectors.toMap(MapLayer::id, layer -> layer));
        return markRepository.findAll().stream()
                .filter(mark -> mark.status() == MarkStatus.ACTIVE)
                .filter(mark -> isVisible(v, mark, layersById))
                .sorted(Comparator.comparing(Mark::createdAt).reversed())
                .toList();
    }

    private boolean isVisible(Viewer v, Mark mark, Map<LayerId, MapLayer> layersById) {
        MapLayer layer = layersById.get(mark.layerId());
        return layer != null && policy.canView(v, layer);
    }

    @Override
    public Mark create(Viewer v, MarkSpec spec) {
        Objects.requireNonNull(v, "v must not be null");
        Objects.requireNonNull(spec, "spec must not be null");

        LayerId layerId = resolveLayer(v, spec.layerId());
        Mark mark = new Mark(MarkId.random(), layerId, spec.position(), spec.kind(), spec.affiliation(),
                spec.label(), spec.note(), ownershipFor(v), Instant.now(), MarkStatus.ACTIVE, MarkSource.MANUAL,
                Verification.unverified());
        return saveAndPublish(mark, MapEvent.Action.CREATED, layerId);
    }

    @Override
    public Mark geolocate(Viewer v, GeolocateSpec spec) {
        Objects.requireNonNull(v, "v must not be null");
        Objects.requireNonNull(spec, "spec must not be null");

        LayerId layerId = resolveLayer(v, spec.layerId());

        Telemetry telemetry = usageTracker.latestTelemetry(spec.assetId())
                .orElseThrow(() -> new IllegalArgumentException(TELEMETRY_INCOMPLETE));
        if (telemetry.latitude() == null || telemetry.longitude() == null || telemetry.headingDegrees() == null
                || telemetry.altitudeMeters() == null || telemetry.altitudeMeters() <= 0) {
            throw new IllegalArgumentException(TELEMETRY_INCOMPLETE);
        }

        GeoPosition drone = new GeoPosition(telemetry.latitude(), telemetry.longitude(), telemetry.altitudeMeters());
        GeoPosition ground = GeoProjection.project(drone, telemetry.headingDegrees(), telemetry.altitudeMeters(),
                spec.depressionDegrees());

        Mark mark = new Mark(MarkId.random(), layerId, ground, spec.kind(), spec.affiliation(), spec.label(),
                spec.note(), ownershipFor(v), Instant.now(), MarkStatus.ACTIVE, MarkSource.DETECTION,
                Verification.unverified());
        return saveAndPublish(mark, MapEvent.Action.CREATED, layerId);
    }

    /**
     * Resolves the layer a create/geolocate call lands on: {@code explicitLayerId} if given (must
     * exist), else the caller's default layer (see {@link LayerResolver#defaultLayerFor}) — either
     * way, {@code v} must {@link MapAccessPolicy#canContribute} to the resolved layer.
     */
    private LayerId resolveLayer(Viewer v, LayerId explicitLayerId) {
        LayerId layerId = explicitLayerId != null ? explicitLayerId : layerResolver.defaultLayerFor(v);
        MapLayer layer = layerResolver.require(layerId);
        if (!policy.canContribute(v, layer)) {
            throw new AccessDeniedException("not permitted to contribute to layer " + layerId.value());
        }
        return layerId;
    }

    private Ownership ownershipFor(Viewer v) {
        return new Ownership(v.userId(), LayerResolver.homeGroupOf(v));
    }

    private Mark saveAndPublish(Mark mark, MapEvent.Action action, LayerId layerId) {
        Mark saved = markRepository.save(mark);
        liveUpdatePublisher.publishMapEvent(new MapEvent(MapEvent.EntityType.MARK, action, layerId, saved));
        return saved;
    }

    @Override
    public Mark patch(Viewer v, MarkId id, MarkPatch patch) {
        Objects.requireNonNull(v, "v must not be null");
        Objects.requireNonNull(patch, "patch must not be null");

        Mark mark = require(id);
        MapLayer layer = layerResolver.require(mark.layerId());
        requireVisible(v, mark, layer);
        requireEditable(v, mark, layer);

        MarkKind kind = patch.kind().orElse(mark.kind());
        Affiliation affiliation = patch.affiliation().orElse(mark.affiliation());
        String label = patch.label().orElse(mark.label());
        String note = patch.note().orElse(mark.note());
        GeoPosition position = patch.position().orElse(mark.position());
        Mark repositioned = mark.withPosition(position);
        Mark annotated = repositioned.withDetails(label, note, kind, affiliation);
        Mark updated = patch.status().isPresent() ? annotated.withStatus(patch.status().get()) : annotated;

        Mark saved = markRepository.save(updated);
        MapEvent.Action action = saved.status() == MarkStatus.CLEARED ? MapEvent.Action.CLEARED : MapEvent.Action.UPDATED;
        liveUpdatePublisher.publishMapEvent(new MapEvent(MapEvent.EntityType.MARK, action, saved.layerId(), saved));
        return saved;
    }

    @Override
    public Mark verify(Viewer v, MarkId id, VerificationState decision) {
        Objects.requireNonNull(v, "v must not be null");
        Objects.requireNonNull(decision, "decision must not be null");
        if (decision != VerificationState.CONFIRMED && decision != VerificationState.REJECTED) {
            throw new IllegalArgumentException("verify decision must be CONFIRMED or REJECTED: " + decision);
        }

        Mark mark = require(id);
        MapLayer layer = layerResolver.require(mark.layerId());
        requireVisible(v, mark, layer);
        if (!policy.canManage(v, layer)) {
            throw new AccessDeniedException("not permitted to verify marks on layer " + layer.id().value());
        }

        Mark saved = markRepository.save(mark.withVerification(new Verification(decision, v.userId(), Instant.now())));
        liveUpdatePublisher.publishMapEvent(
                new MapEvent(MapEvent.EntityType.MARK, MapEvent.Action.UPDATED, saved.layerId(), saved));
        return saved;
    }

    @Override
    public Mark promote(Viewer v, MarkId id, LayerId targetOrNull) {
        Objects.requireNonNull(v, "v must not be null");
        Objects.requireNonNull(id, "id must not be null");

        Mark mark = require(id);
        MapLayer source = layerResolver.require(mark.layerId());
        requireVisible(v, mark, source);
        if (!policy.canManage(v, source)) {
            throw new AccessDeniedException("not permitted to promote marks from layer " + source.id().value());
        }

        LayerId targetId = targetOrNull != null ? targetOrNull : layerResolver.copLayerId();
        MapLayer target = layerResolver.require(targetId);
        if (!policy.canContribute(v, target)) {
            throw new AccessDeniedException("not permitted to promote to layer " + targetId.value());
        }

        Verification verification = mark.verification().state() == VerificationState.CONFIRMED
                ? mark.verification()
                : new Verification(VerificationState.CONFIRMED, v.userId(), Instant.now());
        Mark saved = markRepository.save(mark.withLayer(targetId).withVerification(verification));
        liveUpdatePublisher.publishMapEvent(
                new MapEvent(MapEvent.EntityType.MARK, MapEvent.Action.UPDATED, targetId, saved));
        return saved;
    }

    @Override
    public void delete(Viewer v, MarkId id) {
        Objects.requireNonNull(v, "v must not be null");

        Mark mark = require(id);
        MapLayer layer = layerResolver.require(mark.layerId());
        requireVisible(v, mark, layer);
        requireEditable(v, mark, layer);

        markRepository.deleteById(id);
        liveUpdatePublisher.publishMapEvent(
                new MapEvent(MapEvent.EntityType.MARK, MapEvent.Action.DELETED, mark.layerId(), mark));
    }

    private Mark require(MarkId id) {
        Objects.requireNonNull(id, "id must not be null");
        return markRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Unknown mark: " + id.value()));
    }

    /**
     * A mark on a layer the viewer may not {@link MapAccessPolicy#canView view} must be
     * indistinguishable from a mark that does not exist (docs/plans/done/MAP-REWORK-PLAN.md §4.1: out-of-scope
     * → 404, never 403 — a 403 would reveal the id is real). Checked before any per-action gate.
     */
    private void requireVisible(Viewer v, Mark mark, MapLayer layer) {
        if (!policy.canView(v, layer)) {
            throw new NoSuchElementException("Unknown mark: " + mark.id().value());
        }
    }

    /**
     * The gate shared by {@link #patch} (every field, including a plain annotation/drag-to-correct
     * with no status change) and {@link #delete}: the mark's own creator may manage it only while
     * its {@link Verification} is still {@link VerificationState#UNVERIFIED} — once a manager
     * confirms or rejects it, the creator's standing edit right lapses and only {@link
     * MapAccessPolicy#canManage} on its layer remains, else {@link AccessDeniedException}.
     */
    private void requireEditable(Viewer v, Mark mark, MapLayer layer) {
        boolean creatorMayEdit = mark.createdBy().equals(v.userId())
                && mark.verification().state() == VerificationState.UNVERIFIED;
        if (!creatorMayEdit && !policy.canManage(v, layer)) {
            throw new AccessDeniedException("Mark " + mark.id().value()
                    + " may only be edited or deleted by its creator (while unverified) or a manager");
        }
    }
}
