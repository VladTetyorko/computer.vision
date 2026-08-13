package com.drones.vision.api.live;

import com.drones.vision.map.application.LayerView;
import com.drones.vision.map.application.MapAccessPolicy.Viewer;
import com.drones.vision.map.application.MapLayerService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Answers "may this viewer see events about this layer?" for the {@code map} SSE topic
 * (docs/plans/done/MAP-REWORK-PLAN.md §4.3's security-critical rework), cheaply enough to ask once per event
 * per connection.
 *
 * <h2>Why a cache at all</h2>
 * Every other live topic broadcasts one envelope to every subscribed connection. {@code map} cannot:
 * visibility is a property of the data. The authoritative answer is {@link
 * MapLayerService#layers(Viewer)} — which already applies {@code MapAccessPolicy} — but calling it
 * per event per connection means a repository scan per event per connection. So the visible-layer-id
 * set is cached per {@link Viewer} (a record, so a sound map key) for {@value #TTL_MILLIS}ms.
 *
 * <h2>Staleness bound, and why it is safe</h2>
 * docs/plans/done/MAP-REWORK-PLAN.md §4.3 fixes the acceptance bar at <em>"stale-grant visibility beyond 30s is
 * a bug"</em> and leaves the mechanism to the implementer. This class chooses a plain TTL over
 * event-driven invalidation, for two reasons:
 * <ul>
 *   <li>A TTL bounds <em>every</em> source of staleness, not just the ones that emit a map event. A
 *       revoked group membership, a deleted user, a changed role — none of those produce a {@code
 *       LAYER} event, so an invalidate-on-layer-event scheme would leave them stale indefinitely.</li>
 *   <li>It needs no extra collaborator on {@code LiveUpdateRegistry}, which already sits at the
 *       five-constructor-parameter ceiling ({@code .claude/skills/java-clean-code/SKILL.md} §3).</li>
 * </ul>
 * {@value #TTL_MILLIS}ms is a third of the bar, leaving room for clock jitter.
 *
 * <p><strong>Negative answers are re-checked, positives are not.</strong> A cached "yes" can only be
 * stale in the direction of over-sharing, which the TTL bounds. A cached "no", though, is the case a
 * user notices immediately — create a layer, and the very next event about it would be filtered out
 * of your own stream for up to a full TTL. So a miss triggers one bounded refresh (at most one extra
 * lookup per viewer per {@value #NEGATIVE_TTL_MILLIS}ms) before answering {@code false}.
 *
 * <h2>Threading</h2>
 * Safe for concurrent use: the cache is a {@link ConcurrentHashMap} and entries are immutable
 * snapshots, so a refresh races benignly (two threads may both recompute; both answers are correct).
 */
@Component
@ConditionalOnProperty(prefix = "vision.live", name = "enabled", matchIfMissing = true)
public class MapVisibility {

    /** How long a viewer's visible-layer set is reused before being recomputed. */
    static final long TTL_MILLIS = 10_000L;

    /** The shorter floor a <em>miss</em> may force a recompute at — see the class javadoc. */
    static final long NEGATIVE_TTL_MILLIS = 1_000L;

    /**
     * Above this many cached viewers, expired entries are swept on the next refresh. Entries are
     * keyed by {@link Viewer}, so the natural bound is "distinct users who connected since this
     * process started" — small in practice, but not self-limiting, and a viewer whose memberships
     * change becomes a brand-new key rather than replacing the old one.
     */
    static final int SWEEP_THRESHOLD = 256;

    private final MapLayerService layers;
    private final ConcurrentHashMap<Viewer, Entry> cache = new ConcurrentHashMap<>();

    public MapVisibility(MapLayerService layers) {
        this.layers = Objects.requireNonNull(layers, "layers must not be null");
    }

    /**
     * Builds the per-connection delivery predicate {@code LiveController} hands to {@link
     * LiveUpdateRegistry#connect}, closing over the viewer captured at connect time.
     *
     * <p>The predicate is re-evaluated on every event <em>and</em> on every {@code Last-Event-ID}
     * resume replay, so a resuming connection is re-filtered against what its viewer may see
     * <em>now</em>, never against what it could see when the events were buffered.
     *
     * @param viewer who the connection is
     * @return a predicate over an event's {@code layerId} (canonical UUID string)
     */
    public Predicate<String> deliveryPredicate(Viewer viewer) {
        Objects.requireNonNull(viewer, "viewer must not be null");
        return layerId -> canView(viewer, layerId);
    }

    /**
     * @param viewer  who is asking
     * @param layerId the layer a map event belongs to, as a canonical UUID string
     * @return whether {@code viewer} may see events about that layer
     */
    boolean canView(Viewer viewer, String layerId) {
        if (layerId == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        Entry entry = current(viewer, now);
        if (entry.layerIds().contains(layerId)) {
            return true;
        }
        // A "no" may simply be a stale positive set -- the viewer just created a layer, or was just
        // granted one. Re-resolve before answering, but at most once per NEGATIVE_TTL_MILLIS per
        // viewer, so a storm of events about a genuinely invisible layer cannot turn into a storm of
        // lookups. The rate is anchored to the last *miss-triggered* refresh, not to when the entry
        // was filled: an entry that has never been re-checked is always eligible, which is what makes
        // "create a layer, immediately receive its own event" work instead of blacking out for a
        // whole window.
        if (now - entry.missCheckedAt() < NEGATIVE_TTL_MILLIS) {
            return false;
        }
        return store(viewer, new Entry(resolve(viewer), now, now)).layerIds().contains(layerId);
    }

    /** The viewer's cached visible-layer set, resolved afresh if absent or older than the TTL. */
    private Entry current(Viewer viewer, long now) {
        Entry entry = cache.get(viewer);
        if (entry != null && now - entry.resolvedAt() <= TTL_MILLIS) {
            return entry;
        }
        return store(viewer, new Entry(resolve(viewer), now, NEVER_MISS_CHECKED));
    }

    private Entry store(Viewer viewer, Entry entry) {
        if (cache.size() > SWEEP_THRESHOLD) {
            cache.values().removeIf(cached -> entry.resolvedAt() - cached.resolvedAt() > TTL_MILLIS);
        }
        cache.put(viewer, entry);
        return entry;
    }

    private Set<String> resolve(Viewer viewer) {
        return layers.layers(viewer).stream()
                .map(LayerView::layer)
                .map(layer -> layer.id().value().toString())
                .collect(Collectors.toUnmodifiableSet());
    }

    /** Sentinel for "this entry has never been re-checked after a miss" — always eligible. */
    private static final long NEVER_MISS_CHECKED = Long.MIN_VALUE / 2;

    /**
     * One viewer's visible-layer snapshot, when it was taken, and when a miss last forced a
     * re-resolve for that viewer ({@link #NEVER_MISS_CHECKED} if none has).
     */
    private record Entry(Set<String> layerIds, long resolvedAt, long missCheckedAt) {
    }
}
