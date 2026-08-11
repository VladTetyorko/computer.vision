package com.drones.vision.map.application;

import com.drones.vision.map.domain.model.AccessLevel;
import com.drones.vision.map.domain.model.Drawing;
import com.drones.vision.kernel.GroupId;
import com.drones.vision.map.domain.model.LayerGrant;
import com.drones.vision.map.domain.model.LayerId;
import com.drones.vision.map.domain.model.LayerKind;
import com.drones.vision.map.domain.model.MapEvent;
import com.drones.vision.map.domain.model.MapLayer;
import com.drones.vision.map.domain.model.Mark;
import com.drones.vision.kernel.Ownership;
import com.drones.vision.identity.domain.model.Role;
import com.drones.vision.map.domain.port.DrawingRepositoryPort;
import com.drones.vision.events.domain.port.LiveUpdatePublisherPort;
import com.drones.vision.map.domain.port.MarkRepositoryPort;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import com.drones.vision.map.application.MapAccessPolicy.Viewer;
import com.drones.vision.platform.AccessDeniedException;

/**
 * The one implementation of {@link MapLayerService}.
 *
 * <h2>Threading</h2>
 * Holds no mutable state of its own — all shared state is reached through the injected
 * ports/collaborators.
 */
public final class DefaultMapLayerService implements MapLayerService {

    private final LayerResolver layerResolver;
    private final MarkRepositoryPort markRepository;
    private final DrawingRepositoryPort drawingRepository;
    private final LiveUpdatePublisherPort liveUpdatePublisher;
    private final MapAccessPolicy policy;

    public DefaultMapLayerService(LayerResolver layerResolver, MarkRepositoryPort markRepository,
                                   DrawingRepositoryPort drawingRepository, LiveUpdatePublisherPort liveUpdatePublisher,
                                   MapAccessPolicy policy) {
        this.layerResolver = Objects.requireNonNull(layerResolver, "layerResolver must not be null");
        this.markRepository = Objects.requireNonNull(markRepository, "markRepository must not be null");
        this.drawingRepository = Objects.requireNonNull(drawingRepository, "drawingRepository must not be null");
        this.liveUpdatePublisher =
                Objects.requireNonNull(liveUpdatePublisher, "liveUpdatePublisher must not be null");
        this.policy = Objects.requireNonNull(policy, "policy must not be null");
    }

    @Override
    public List<LayerView> layers(Viewer v) {
        Objects.requireNonNull(v, "v must not be null");
        List<LayerView> views = new ArrayList<>();
        for (MapLayer layer : layerResolver.findAll()) {
            AccessLevel level = policy.accessTo(v, layer);
            if (level != null) {
                views.add(new LayerView(layer, level));
            }
        }
        views.sort(Comparator
                .comparing((LayerView view) -> view.layer().kind() != LayerKind.COP)
                .thenComparing(view -> view.layer().name(), String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(views);
    }

    @Override
    public MapLayer create(Viewer v, LayerSpec spec) {
        Objects.requireNonNull(v, "v must not be null");
        Objects.requireNonNull(spec, "spec must not be null");

        GroupId groupId;
        if (spec.kind() == LayerKind.TEAM) {
            boolean allowed = v.topRole() == Role.ADMIN
                    || (v.topRole() == Role.MANAGER && v.groups().contains(spec.groupId()));
            if (!allowed) {
                throw new AccessDeniedException(
                        "not permitted to create a team layer for group " + spec.groupId().value());
            }
            groupId = spec.groupId();
        } else {
            groupId = spec.groupId() != null ? spec.groupId() : LayerResolver.homeGroupOf(v);
        }

        MapLayer layer = new MapLayer(LayerId.random(), spec.name(), spec.kind(),
                new Ownership(v.userId(), groupId), List.of(), Instant.now());
        MapLayer saved = layerResolver.save(layer);
        publish(MapEvent.Action.CREATED, saved);
        return saved;
    }

    @Override
    public MapLayer rename(Viewer v, LayerId id, String name) {
        Objects.requireNonNull(v, "v must not be null");
        Objects.requireNonNull(id, "id must not be null");

        MapLayer layer = layerResolver.require(id);
        requireNotCop(layer, "renamed");
        requireManage(v, layer);

        MapLayer saved = layerResolver.save(layer.withName(name));
        publish(MapEvent.Action.UPDATED, saved);
        return saved;
    }

    @Override
    public void delete(Viewer v, LayerId id) {
        Objects.requireNonNull(v, "v must not be null");
        Objects.requireNonNull(id, "id must not be null");

        MapLayer layer = layerResolver.require(id);
        requireNotCop(layer, "deleted");
        requireManage(v, layer);

        for (Mark mark : markRepository.findAll()) {
            if (mark.layerId().equals(id)) {
                markRepository.deleteById(mark.id());
                liveUpdatePublisher.publishMapEvent(
                        new MapEvent(MapEvent.EntityType.MARK, MapEvent.Action.DELETED, id, mark));
            }
        }
        for (Drawing drawing : drawingRepository.findAll()) {
            if (drawing.layerId().equals(id)) {
                drawingRepository.deleteById(drawing.id());
                liveUpdatePublisher.publishMapEvent(
                        new MapEvent(MapEvent.EntityType.DRAWING, MapEvent.Action.DELETED, id, drawing));
            }
        }

        layerResolver.deleteById(id);
        publish(MapEvent.Action.DELETED, layer);
    }

    @Override
    public MapLayer setGrants(Viewer v, LayerId id, List<LayerGrant> grants) {
        Objects.requireNonNull(v, "v must not be null");
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(grants, "grants must not be null");

        MapLayer layer = layerResolver.require(id);
        requireManage(v, layer);

        MapLayer saved = layerResolver.save(layer.withGrants(grants));
        publish(MapEvent.Action.UPDATED, saved);
        return saved;
    }

    @Override
    public LayerId copLayerId() {
        return layerResolver.copLayerId();
    }

    private void requireNotCop(MapLayer layer, String verb) {
        if (layer.kind() == LayerKind.COP) {
            throw new IllegalStateException("The COP layer cannot be " + verb);
        }
    }

    private void requireManage(Viewer v, MapLayer layer) {
        // A layer the viewer may not even view must be indistinguishable from one that does not
        // exist (docs/plans/done/MAP-REWORK-PLAN.md §4.1: out-of-scope → 404, never 403); only a viewer who
        // can see the layer but lacks MANAGE gets an honest 403.
        if (!policy.canView(v, layer)) {
            throw new NoSuchElementException("Unknown layer: " + layer.id().value());
        }
        if (!policy.canManage(v, layer)) {
            throw new AccessDeniedException("not permitted to manage layer " + layer.id().value());
        }
    }

    private void publish(MapEvent.Action action, MapLayer layer) {
        liveUpdatePublisher.publishMapEvent(new MapEvent(MapEvent.EntityType.LAYER, action, layer.id(), layer));
    }
}
