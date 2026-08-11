package com.drones.vision.map.application;

import com.drones.vision.map.domain.model.Drawing;
import com.drones.vision.map.domain.model.DrawingId;
import com.drones.vision.kernel.GeoPosition;
import com.drones.vision.map.domain.model.LayerId;
import com.drones.vision.map.domain.model.MapEvent;
import com.drones.vision.map.domain.model.MapLayer;
import com.drones.vision.kernel.Ownership;
import com.drones.vision.map.domain.port.DrawingRepositoryPort;
import com.drones.vision.events.domain.port.LiveUpdatePublisherPort;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.stream.Collectors;
import com.drones.vision.map.application.MapAccessPolicy.Viewer;
import com.drones.vision.identity.application.scope.AccessDeniedException;

/**
 * The one implementation of {@link DrawingService}.
 *
 * <h2>Threading</h2>
 * Holds no mutable state of its own — all shared state is reached through the injected
 * ports/collaborators.
 */
public final class DefaultDrawingService implements DrawingService {

    private final DrawingRepositoryPort drawingRepository;
    private final LiveUpdatePublisherPort liveUpdatePublisher;
    private final MapAccessPolicy policy;
    private final LayerResolver layerResolver;

    public DefaultDrawingService(DrawingRepositoryPort drawingRepository, LiveUpdatePublisherPort liveUpdatePublisher,
                                  MapAccessPolicy policy, LayerResolver layerResolver) {
        this.drawingRepository = Objects.requireNonNull(drawingRepository, "drawingRepository must not be null");
        this.liveUpdatePublisher =
                Objects.requireNonNull(liveUpdatePublisher, "liveUpdatePublisher must not be null");
        this.policy = Objects.requireNonNull(policy, "policy must not be null");
        this.layerResolver = Objects.requireNonNull(layerResolver, "layerResolver must not be null");
    }

    @Override
    public List<Drawing> list(Viewer v) {
        Objects.requireNonNull(v, "v must not be null");
        Map<LayerId, MapLayer> layersById =
                layerResolver.findAll().stream().collect(Collectors.toMap(MapLayer::id, layer -> layer));
        return drawingRepository.findAll().stream()
                .filter(drawing -> isVisible(v, drawing, layersById))
                .sorted(Comparator.comparing(Drawing::createdAt).reversed())
                .toList();
    }

    private boolean isVisible(Viewer v, Drawing drawing, Map<LayerId, MapLayer> layersById) {
        MapLayer layer = layersById.get(drawing.layerId());
        return layer != null && policy.canView(v, layer);
    }

    @Override
    public Drawing create(Viewer v, DrawingSpec spec) {
        Objects.requireNonNull(v, "v must not be null");
        Objects.requireNonNull(spec, "spec must not be null");

        LayerId layerId = spec.layerId() != null ? spec.layerId() : layerResolver.defaultLayerFor(v);
        MapLayer layer = layerResolver.require(layerId);
        if (!policy.canContribute(v, layer)) {
            throw new AccessDeniedException("not permitted to contribute to layer " + layerId.value());
        }

        Ownership ownership = new Ownership(v.userId(), LayerResolver.homeGroupOf(v));
        Drawing drawing = new Drawing(DrawingId.random(), layerId, spec.kind(), spec.points(), spec.label(),
                spec.colorToken(), ownership, Instant.now());
        Drawing saved = drawingRepository.save(drawing);
        liveUpdatePublisher.publishMapEvent(
                new MapEvent(MapEvent.EntityType.DRAWING, MapEvent.Action.CREATED, layerId, saved));
        return saved;
    }

    @Override
    public Drawing patch(Viewer v, DrawingId id, DrawingPatch patch) {
        Objects.requireNonNull(v, "v must not be null");
        Objects.requireNonNull(patch, "patch must not be null");

        Drawing drawing = require(id);
        MapLayer layer = layerResolver.require(drawing.layerId());
        requireVisible(v, drawing, layer);
        requireCreatorOrManager(v, drawing, layer);

        List<GeoPosition> points = patch.points().orElse(drawing.points());
        String label = patch.label().orElse(drawing.label());
        String colorToken = patch.colorToken().orElse(drawing.colorToken());
        Drawing updated = drawing.withGeometry(points).withDetails(label, colorToken);

        Drawing saved = drawingRepository.save(updated);
        liveUpdatePublisher.publishMapEvent(
                new MapEvent(MapEvent.EntityType.DRAWING, MapEvent.Action.UPDATED, saved.layerId(), saved));
        return saved;
    }

    @Override
    public void delete(Viewer v, DrawingId id) {
        Objects.requireNonNull(v, "v must not be null");
        Objects.requireNonNull(id, "id must not be null");

        Drawing drawing = require(id);
        MapLayer layer = layerResolver.require(drawing.layerId());
        requireVisible(v, drawing, layer);
        requireCreatorOrManager(v, drawing, layer);

        drawingRepository.deleteById(id);
        liveUpdatePublisher.publishMapEvent(
                new MapEvent(MapEvent.EntityType.DRAWING, MapEvent.Action.DELETED, drawing.layerId(), drawing));
    }

    private Drawing require(DrawingId id) {
        Objects.requireNonNull(id, "id must not be null");
        return drawingRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Unknown drawing: " + id.value()));
    }

    /**
     * A drawing on a layer the viewer may not {@link MapAccessPolicy#canView view} must be
     * indistinguishable from a drawing that does not exist (docs/plans/done/MAP-REWORK-PLAN.md §4.1:
     * out-of-scope → 404, never 403). Checked before any per-action gate.
     */
    private void requireVisible(Viewer v, Drawing drawing, MapLayer layer) {
        if (!policy.canView(v, layer)) {
            throw new NoSuchElementException("Unknown drawing: " + drawing.id().value());
        }
    }

    /**
     * The gate shared by {@link #patch} and {@link #delete}: the drawing's own creator may always
     * edit/delete it (no verification carve-out — {@link Drawing} has none, unlike {@link
     * com.drones.vision.map.domain.model.Mark}), or a viewer with {@link MapAccessPolicy#canManage} on
     * its layer may.
     */
    private void requireCreatorOrManager(Viewer v, Drawing drawing, MapLayer layer) {
        if (!drawing.ownership().ownerId().equals(v.userId()) && !policy.canManage(v, layer)) {
            throw new AccessDeniedException(
                    "Drawing " + drawing.id().value() + " may only be edited or deleted by its creator or a manager");
        }
    }
}
