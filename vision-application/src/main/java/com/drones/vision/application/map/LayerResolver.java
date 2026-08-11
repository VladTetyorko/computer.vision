package com.drones.vision.application.map;

import com.drones.vision.kernel.GroupId;
import com.drones.vision.map.domain.model.LayerId;
import com.drones.vision.map.domain.model.LayerKind;
import com.drones.vision.map.domain.model.MapEvent;
import com.drones.vision.map.domain.model.MapLayer;
import com.drones.vision.kernel.Ownership;
import com.drones.vision.kernel.UserId;
import com.drones.vision.events.domain.port.LiveUpdatePublisherPort;
import com.drones.vision.map.domain.port.MapLayerRepositoryPort;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;
import com.drones.vision.application.map.MapAccessPolicy.Viewer;

/**
 * Shared layer-lookup and default-layer-selection collaborator for {@code DefaultMapLayerService}
 * (same package), {@code DefaultMarkService} and {@code DefaultDrawingService} (package {@code
 * com.drones.vision.application.mark}/{@code map}) — the one place {@link
 * com.drones.vision.map.domain.port.MapLayerRepositoryPort} is reached from this module, so the
 * COP-bootstrap and default-personal-layer-bootstrap invariants ("exactly one COP layer", "at most
 * one personal layer per user") can never drift between the three services that all need them.
 *
 * <p>No interface: exactly one implementation is ever plausible (a thin, in-process wrapper over
 * one repository port — see java-clean-code SKILL.md §1), and it is {@code public} rather than
 * package-private only because cross-package construction/use requires it, the same "widened to
 * public" precedent {@code SupervisedPublisher}/{@code DetectionEventEngine} already set in this
 * module (see vision-application/MODULE.md's Package structure table).
 *
 * <h2>Threading</h2>
 * {@link #copLayerId()} and {@link #defaultLayerFor} are {@code synchronized} on this instance:
 * both are lazy find-or-create bootstraps with a real uniqueness invariant to protect ("exactly one
 * COP layer", "at most one personal layer per user"), so two concurrent callers racing the very
 * first bootstrap must not both create one. Every other method is a stateless pass-through.
 */
public final class LayerResolver {

    /** Name stamped on the single, lazily-bootstrapped {@link LayerKind#COP} layer. */
    static final String COP_LAYER_NAME = "Common picture";

    /** Name stamped on a user's lazily auto-created {@link LayerKind#PERSONAL} layer. */
    static final String PERSONAL_LAYER_NAME = "Personal";

    /**
     * Fixed system principal used to satisfy {@link Ownership}'s non-null-{@code groupId}/{@code
     * ownerId} contract when there is no acting user to attribute a bootstrap to — {@link
     * #copLayerId()} takes no {@link Viewer} at all (docs/plans/done/MAP-REWORK-PLAN.md §3 pins it as a
     * zero-arg method, called lazily by whichever request happens to trigger it first, or by a Wave
     * C startup initializer with no user context), so "use the acting admin/manager's ownership" is
     * unreachable literally as stated; this sentinel is the fallback. Same well-known {@code
     * UUID(0,0)}/{@code UUID(0,1)} values {@code vision-app}'s {@code DevPrincipal} uses for the
     * dev/no-auth profile — redefined here (not imported) since {@code vision-application} may not
     * depend on {@code vision-app} (ArchUnit-enforced dependency direction) — so under that profile
     * the two coincide by construction; under a real deployment this is simply an inert sentinel,
     * never a real registered user, and nothing here (or anywhere else — no port cross-references
     * {@code Ownership} against {@code UserRepositoryPort}) ever checks it for existence.
     */
    static final UserId SYSTEM_USER_ID = new UserId(new UUID(0, 0));

    /** @see #SYSTEM_USER_ID */
    static final GroupId SYSTEM_GROUP_ID = new GroupId(new UUID(0, 1));

    private final MapLayerRepositoryPort mapLayerRepository;
    private final LiveUpdatePublisherPort liveUpdatePublisher;

    public LayerResolver(MapLayerRepositoryPort mapLayerRepository, LiveUpdatePublisherPort liveUpdatePublisher) {
        this.mapLayerRepository = Objects.requireNonNull(mapLayerRepository, "mapLayerRepository must not be null");
        this.liveUpdatePublisher =
                Objects.requireNonNull(liveUpdatePublisher, "liveUpdatePublisher must not be null");
    }

    /**
     * Loads a layer by id.
     *
     * @param id the layer id
     * @return the layer
     * @throws NoSuchElementException if no layer has that id
     */
    public MapLayer require(LayerId id) {
        Objects.requireNonNull(id, "id must not be null");
        return mapLayerRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Unknown map layer: " + id.value()));
    }

    /**
     * @return an immutable snapshot of every layer, unfiltered
     */
    public List<MapLayer> findAll() {
        return mapLayerRepository.findAll();
    }

    /**
     * Upserts a layer. Callers publish their own {@link MapEvent} afterward — this method carries no
     * event-publishing opinion of its own, since the correct {@link MapEvent.Action} (created vs.
     * updated) depends on context only the caller has.
     *
     * @param layer the layer to persist
     * @return the persisted layer
     */
    public MapLayer save(MapLayer layer) {
        return mapLayerRepository.save(layer);
    }

    /**
     * Deletes a layer by id. Idempotent, like the underlying port. Callers publish their own {@link
     * MapEvent} afterward.
     *
     * @param id the layer id to delete
     */
    public void deleteById(LayerId id) {
        mapLayerRepository.deleteById(id);
    }

    /**
     * The single, deployment-wide Common Operational Picture layer's id — lazily found, or created
     * on first call. Idempotent: every subsequent call (in this JVM or, once persisted, any other)
     * returns the same id, so {@code vision-app} needs no separate bootstrap step.
     *
     * @return the COP layer's id
     */
    public synchronized LayerId copLayerId() {
        return mapLayerRepository.findAll().stream()
                .filter(layer -> layer.kind() == LayerKind.COP)
                .findFirst()
                .map(MapLayer::id)
                .orElseGet(() -> {
                    MapLayer cop = new MapLayer(LayerId.random(), COP_LAYER_NAME, LayerKind.COP,
                            new Ownership(SYSTEM_USER_ID, SYSTEM_GROUP_ID), List.of(), Instant.now());
                    MapLayer saved = mapLayerRepository.save(cop);
                    publishCreated(saved);
                    return saved.id();
                });
    }

    /**
     * Resolves the layer a mark/drawing lands on when its creator didn't name one
     * (docs/plans/done/MAP-REWORK-PLAN.md §3): the viewer's first {@link LayerKind#TEAM} layer (a team layer
     * whose {@code ownership().groupId()} is one of {@link Viewer#groups()}, picked deterministically
     * — sorted by layer name case-insensitively, then by id, so ties never depend on repository
     * iteration order) — else the viewer's own {@link LayerKind#PERSONAL} layer, found or lazily
     * created (never the COP layer directly).
     *
     * @param viewer who the layer is being resolved for
     * @return the resolved layer's id
     */
    public synchronized LayerId defaultLayerFor(Viewer viewer) {
        Objects.requireNonNull(viewer, "viewer must not be null");
        List<MapLayer> allLayers = mapLayerRepository.findAll();

        return allLayers.stream()
                .filter(layer -> layer.kind() == LayerKind.TEAM && viewer.groups().contains(layer.ownership().groupId()))
                .min(Comparator.comparing(MapLayer::name, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(layer -> layer.id().value()))
                .map(MapLayer::id)
                .orElseGet(() -> personalLayerFor(viewer, allLayers));
    }

    private LayerId personalLayerFor(Viewer viewer, List<MapLayer> allLayers) {
        return allLayers.stream()
                .filter(layer -> layer.kind() == LayerKind.PERSONAL && layer.ownership().ownerId().equals(viewer.userId()))
                .findFirst()
                .map(MapLayer::id)
                .orElseGet(() -> {
                    MapLayer personal = new MapLayer(LayerId.random(), PERSONAL_LAYER_NAME, LayerKind.PERSONAL,
                            new Ownership(viewer.userId(), homeGroupOf(viewer)), List.of(), Instant.now());
                    MapLayer saved = mapLayerRepository.save(personal);
                    publishCreated(saved);
                    return saved.id();
                });
    }

    private void publishCreated(MapLayer layer) {
        liveUpdatePublisher.publishMapEvent(new MapEvent(MapEvent.EntityType.LAYER, MapEvent.Action.CREATED,
                layer.id(), layer));
    }

    /**
     * Picks a {@link GroupId} to satisfy {@link Ownership}'s non-null contract when constructing a
     * mark, drawing, or personal layer on {@code viewer}'s behalf. Deliberately inert for
     * authorization purposes — {@link MapAccessPolicy}'s creator/ownerId rule already grants the
     * owner {@link com.drones.vision.map.domain.model.AccessLevel#MANAGE} regardless of which group is
     * recorded here — so this only needs to be <em>some</em> well-formed value: the viewer's own
     * lowest-UUID membership group (deterministic, so the same viewer always gets the same answer),
     * or {@link #SYSTEM_GROUP_ID} for a viewer with no memberships at all.
     *
     * @param viewer whose ownership is being built
     * @return a group id to record on that ownership
     */
    public static GroupId homeGroupOf(Viewer viewer) {
        Objects.requireNonNull(viewer, "viewer must not be null");
        return viewer.groups().stream()
                .min(Comparator.comparing(GroupId::value))
                .orElse(SYSTEM_GROUP_ID);
    }
}
