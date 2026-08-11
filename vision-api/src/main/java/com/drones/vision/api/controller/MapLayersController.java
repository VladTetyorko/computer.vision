package com.drones.vision.api.controller;

import com.drones.vision.api.dto.CreateLayerRequest;
import com.drones.vision.api.dto.LayerResponse;
import com.drones.vision.api.dto.RenameLayerRequest;
import com.drones.vision.api.dto.SetGrantsRequest;
import com.drones.vision.api.exception.ApiExceptionHandler;
import com.drones.vision.api.security.CurrentUser;
import com.drones.vision.application.map.DrawingService;
import com.drones.vision.application.map.LayerView;
import com.drones.vision.application.map.MapAccessPolicy.Viewer;
import com.drones.vision.application.map.MapLayerService;
import com.drones.vision.application.mark.MarkService;
import com.drones.vision.map.domain.model.AccessLevel;
import com.drones.vision.map.domain.model.Drawing;
import com.drones.vision.map.domain.model.LayerId;
import com.drones.vision.map.domain.model.MapLayer;
import com.drones.vision.map.domain.model.Mark;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Layer CRUD and grant management (docs/plans/done/MAP-REWORK-PLAN.md §4.1's frozen wire contract) — the
 * access-controlled surfaces marks and drawings live on.
 *
 * <h2>Who is acting</h2>
 * Every method resolves the acting {@link Viewer} from {@link CurrentUser#viewer()} and passes it
 * down as an argument; {@link MapLayerService} never sees a token, a header, or Spring Security.
 * Authorization is entirely {@code MapAccessPolicy}'s, applied inside the service — there are no
 * per-role HTTP rules for these paths, only the same authenticated-session requirement every other
 * {@code /api/**} route has.
 *
 * <h2>Why mark/drawing counts need two more collaborators</h2>
 * {@link LayerResponse#markCount()}/{@link LayerResponse#drawingCount()} are per-viewer facts the
 * data-layer panel renders, and {@link MapLayerService} deliberately does not compute them (it owns
 * layers, not their contents). Rather than widen that service's contract for a display concern, this
 * controller reads the two already-scoped lists it would otherwise have to and groups them by layer
 * — four constructor dependencies, still inside the five-parameter ceiling.
 *
 * <h2>Errors</h2>
 * Entirely the services' own exceptions surfacing through {@link ApiExceptionHandler}: {@link
 * IllegalArgumentException} (malformed id, unrecognized {@code kind}/{@code level}, blank name) →
 * 400; {@link java.util.NoSuchElementException} (unknown <em>or</em> invisible layer) → 404; {@link
 * com.drones.vision.application.scope.AccessDeniedException} (visible but insufficient access) →
 * 403; {@link IllegalStateException} (renaming/deleting the COP layer) → 409.
 */
@RestController
@RequestMapping("/api/map/layers")
public class MapLayersController {

    private final MapLayerService layers;
    private final MarkService marks;
    private final DrawingService drawings;
    private final CurrentUser currentUser;

    public MapLayersController(MapLayerService layers, MarkService marks, DrawingService drawings,
                                CurrentUser currentUser) {
        this.layers = Objects.requireNonNull(layers, "layers must not be null");
        this.marks = Objects.requireNonNull(marks, "marks must not be null");
        this.drawings = Objects.requireNonNull(drawings, "drawings must not be null");
        this.currentUser = Objects.requireNonNull(currentUser, "currentUser must not be null");
    }

    /**
     * Lists every layer the caller may see — the COP layer first, then the rest by name.
     *
     * @return the visible layers, each with the caller's own access level and content counts
     */
    @GetMapping
    public List<LayerResponse> list() {
        Viewer viewer = currentUser.viewer();
        Map<LayerId, Long> markCounts = countBy(marks.list(viewer), Mark::layerId);
        Map<LayerId, Long> drawingCounts = countBy(drawings.list(viewer), Drawing::layerId);
        return layers.layers(viewer).stream()
                .map(view -> LayerResponse.from(view,
                        count(markCounts, view.layer().id()),
                        count(drawingCounts, view.layer().id())))
                .toList();
    }

    /**
     * Creates a TEAM or PERSONAL layer.
     *
     * @param request what to create
     * @return the created layer; the creator always manages it, so its grants are included
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public LayerResponse create(@RequestBody CreateLayerRequest request) {
        return empty(layers.create(currentUser.viewer(), request.toSpec()));
    }

    /**
     * Renames a layer. The COP layer cannot be renamed (409).
     *
     * @param id      the layer to rename, as a canonical UUID string
     * @param request the replacement name
     * @return the renamed layer
     */
    @PatchMapping("/{id}")
    public LayerResponse rename(@PathVariable String id, @RequestBody RenameLayerRequest request) {
        return withCounts(layers.rename(currentUser.viewer(), LayerId.of(id), request.name()));
    }

    /**
     * Deletes a layer, cascading to every mark and drawing on it. The COP layer cannot be deleted
     * (409).
     *
     * @param id the layer to delete, as a canonical UUID string
     */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) {
        layers.delete(currentUser.viewer(), LayerId.of(id));
    }

    /**
     * Replaces a layer's access list wholesale — whatever is sent becomes the complete grant set.
     *
     * @param id      the layer to update, as a canonical UUID string
     * @param request the complete replacement grant list
     * @return the updated layer, grants included (the caller manages it, or this would have 403'd)
     */
    @PutMapping("/{id}/grants")
    public LayerResponse setGrants(@PathVariable String id, @RequestBody SetGrantsRequest request) {
        return withCounts(layers.setGrants(currentUser.viewer(), LayerId.of(id), request.toGrants()));
    }

    /**
     * Maps a layer a mutation just returned, re-reading the caller's content counts for it.
     *
     * <p>A mutation's own return value carries no counts, and the caller's access level after the
     * change is exactly what the service just enforced ({@code MANAGE} for rename/setGrants), but
     * re-deriving it from the visible-layers list keeps one mapping path instead of two.
     */
    private LayerResponse withCounts(MapLayer layer) {
        Viewer viewer = currentUser.viewer();
        Map<LayerId, Long> markCounts = countBy(marks.list(viewer), Mark::layerId);
        Map<LayerId, Long> drawingCounts = countBy(drawings.list(viewer), Drawing::layerId);
        return LayerResponse.from(new LayerView(layer, AccessLevel.MANAGE),
                count(markCounts, layer.id()), count(drawingCounts, layer.id()));
    }

    /** A just-created layer is empty by construction, so its counts need no query. */
    private static LayerResponse empty(MapLayer layer) {
        return LayerResponse.from(new LayerView(layer, AccessLevel.MANAGE), 0, 0);
    }

    private static <T> Map<LayerId, Long> countBy(List<T> items, Function<T, LayerId> layerOf) {
        return items.stream().collect(Collectors.groupingBy(layerOf, Collectors.counting()));
    }

    private static int count(Map<LayerId, Long> counts, LayerId layerId) {
        return counts.getOrDefault(layerId, 0L).intValue();
    }
}
