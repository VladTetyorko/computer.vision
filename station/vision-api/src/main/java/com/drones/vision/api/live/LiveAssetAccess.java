package com.drones.vision.api.live;

import com.drones.vision.platform.VisibilityScope;
import com.drones.vision.api.dto.UpdateLiveTopicsRequest;
import com.drones.vision.api.security.StreamAccess;
import com.drones.vision.identity.application.scope.ScopeResolver;
import com.drones.vision.identity.domain.model.User;
import com.drones.vision.identity.domain.port.UserRepositoryPort;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.UserId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * The live-operations authority seam for per-asset SSE topics ({@code telemetry:<assetId>}/{@code
 * detections:<assetId>}/{@code geo:<assetId>}) — docs/plans/active/LIVE-SCOPE-PLAN.md §2, W3. {@link
 * StreamAccess} already answers "may this caller reach this asset?" for {@code StreamController}'s
 * REST handlers (W2); this class reuses that exact same policy ({@link
 * StreamAccess#visibleAsset(AssetId, com.drones.vision.platform.VisibilityScope)}) rather than
 * inventing a second one, and adds only what the SSE data plane needs beyond it:
 *
 * <ul>
 *   <li><b>A fresh {@code VisibilityScope} per check, not the request's own.</b> {@link
 *       StreamController}'s handlers run on the connecting request's own thread, so they can read
 *       {@code CurrentUser.scope()} directly. A live connection's per-delivery re-check
 *       (below) runs later — on {@code LiveUpdateRegistry}'s shared dispatcher thread, for a
 *       connection some other request opened — so there is no request-bound scope to read. This
 *       class instead re-derives one from just a {@link UserId}: {@link
 *       UserRepositoryPort#findById(UserId)} to get the current {@link User}, then {@link
 *       ScopeResolver#scopeFor(User)} — the same seam {@code CurrentUser}'s own production {@code
 *       PrincipalResolver} calls, just invoked directly instead of through the security context.
 *       Both ports are plain, thread-safe application/driven ports with no session affinity.</li>
 *   <li><b>Caching.</b> Re-deriving a scope is a repository read (or two); re-running it once per
 *       envelope per connection would turn every coalesced telemetry/detections tick into a
 *       database hit per open connection. The answer is cached per {@code (UserId, AssetId)} pair
 *       for {@code ttlMillis} — see the constructor's own javadoc for the bound this buys and why a
 *       plain TTL, not event-driven invalidation, was chosen (the same reasoning {@link
 *       MapVisibility} already documents for the analogous {@code map}-topic problem).</li>
 * </ul>
 *
 * <h2>Two authorization points, one policy</h2>
 * <ol>
 *   <li><b>At subscribe time</b> — {@link #filterTopicsParam} (a fresh {@code GET /api/live}) and
 *       {@link #filterAdditions} (a {@code PATCH .../topics} {@code add} list) both silently drop
 *       any per-asset topic the caller may not currently see, called directly by {@link
 *       LiveController} before the (now-vetted) request ever reaches {@link LiveUpdateRegistry}. A
 *       caller therefore never even subscribes to an asset outside their scope — closing
 *       docs/plans/active/LIVE-SCOPE-PLAN.md §2's defects 1 and 2 (a foreign topic silently
 *       filtered, exactly like an invisible entry {@link StreamAccess#filterVisible} drops from a
 *       list, not a 403 that would fail an otherwise-legitimate mixed request).</li>
 *   <li><b>At delivery time</b> — {@link #deliveryPredicate(UserId)} is what {@link LiveController}
 *       hands to {@link LiveUpdateRegistry#connect}, stored on the {@link LiveConnection} and
 *       consulted by {@link LiveConnection#mayReceive} on every broadcast <em>and</em> every
 *       snapshot/resume replay — exactly how {@link MapVisibility}'s predicate is threaded through
 *       for the {@code map} topic. This is what makes authorization hold for the life of the
 *       connection, not only at the moment a topic was added: a scope can change mid-connection (an
 *       assignment revoked) with no further {@code PATCH} to re-trigger the subscribe-time check,
 *       so delivery re-checks independently, bounded by the same TTL.</li>
 * </ol>
 * Malformed topic strings are left to {@link LiveTopic#parse}/{@link
 * LiveTopic#parseTopicsParam}'s own {@link IllegalArgumentException} (400 via {@code
 * ApiExceptionHandler}) — this class only ever removes a well-formed, currently-invisible entry,
 * never rewrites or rejects a malformed one itself.
 */
@Component
@ConditionalOnProperty(prefix = "vision.live", name = "enabled", matchIfMissing = true)
public class LiveAssetAccess {

    /**
     * Above this many cached {@code (UserId, AssetId)} pairs, stale entries are swept on the next
     * write — same reasoning and threshold as {@link MapVisibility#SWEEP_THRESHOLD}: small in
     * practice (distinct users times the distinct assets each has ever subscribed to since this
     * process started), but not self-limiting on its own.
     */
    static final int SWEEP_THRESHOLD = 256;

    private final StreamAccess streamAccess;
    private final ScopeResolver scopeResolver;
    private final UserRepositoryPort userRepositoryPort;
    private final long ttlMillis;
    private final ConcurrentHashMap<Key, Entry> cache = new ConcurrentHashMap<>();

    /**
     * @param streamAccess       the reused asset/ownership policy (docs/plans/active/LIVE-SCOPE-PLAN.md §2, W2)
     * @param scopeResolver      re-derives a user's current {@link com.drones.vision.platform.VisibilityScope}
     * @param userRepositoryPort resolves the {@link User} a cached check's {@link UserId} names
     * @param ttlMillis          how long a resolved answer is reused before being re-derived —
     *                           {@code vision.live.asset-access-ttl-ms} in {@code application.yaml}
     *                           (default 5000). This is the staleness window a revoked assignment
     *                           (or a newly granted one) buys: up to this long after the change, a
     *                           mid-connection viewer may still receive (or still be denied) an
     *                           asset's telemetry/detections/geo topic. Shorter than {@link
     *                           MapVisibility#TTL_MILLIS} deliberately — this cache bounds exposure
     *                           of an aircraft's live position, a narrower and more sensitive fact
     *                           than a map layer's mere existence.
     */
    public LiveAssetAccess(StreamAccess streamAccess, ScopeResolver scopeResolver,
                            UserRepositoryPort userRepositoryPort,
                            @Value("${vision.live.asset-access-ttl-ms:5000}") long ttlMillis) {
        this.streamAccess = Objects.requireNonNull(streamAccess, "streamAccess must not be null");
        this.scopeResolver = Objects.requireNonNull(scopeResolver, "scopeResolver must not be null");
        this.userRepositoryPort =
                Objects.requireNonNull(userRepositoryPort, "userRepositoryPort must not be null");
        if (ttlMillis <= 0) {
            throw new IllegalArgumentException("ttlMillis must be positive, was " + ttlMillis);
        }
        this.ttlMillis = ttlMillis;
    }

    /**
     * Builds the per-connection delivery predicate {@link LiveController} hands to {@link
     * LiveUpdateRegistry#connect}, closing over the connecting user's id — never their scope, which
     * would freeze the very snapshot this class exists to avoid trusting.
     *
     * @param userId who the connection belongs to
     * @return a predicate over a per-asset topic's {@link AssetId}
     */
    public Predicate<AssetId> deliveryPredicate(UserId userId, VisibilityScope connectScope) {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(connectScope, "connectScope must not be null");
        return assetId -> canView(userId, assetId, connectScope);
    }

    /**
     * Filters a fresh {@code GET /api/live?topics=} value down to entries {@code userId} may
     * currently see. Non-asset-scoped entries ({@code fleet}/{@code event}/...) are never removed;
     * a malformed entry is left for {@link LiveTopic#parseTopicsParam} to reject exactly as before
     * (this method parses with the same method, so a bad entry throws here just as early).
     *
     * @param userId      the connecting caller
     * @param topicsParam the raw query parameter value; {@code null}/blank is returned unchanged
     * @return {@code topicsParam}, with any currently-invisible per-asset entry removed
     * @throws IllegalArgumentException if an entry is malformed (unchanged behavior — 400 via
     *                                   {@code ApiExceptionHandler})
     */
    public String filterTopicsParam(UserId userId, String topicsParam, VisibilityScope requestScope) {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(requestScope, "requestScope must not be null");
        Set<LiveTopic> requested = LiveTopic.parseTopicsParam(topicsParam);
        if (requested.isEmpty()) {
            return topicsParam; // nothing requested -- nothing to filter, and null/blank stays as-is
        }
        return requested.stream()
                .filter(topic -> topic.assetId() == null || canView(userId, topic.assetId(), requestScope))
                .map(LiveTopic::wire)
                .collect(Collectors.joining(","));
    }

    /**
     * Filters a {@code PATCH /api/live/{connectionId}/topics} request's {@code add} list the same
     * way {@link #filterTopicsParam} filters a fresh connect (docs/plans/active/LIVE-SCOPE-PLAN.md
     * §2, W3 defect 2) — a caller who has learned another connection's id, or simply guessed at
     * another asset's id, may not use this endpoint to add a topic naming an asset outside their
     * scope. {@code remove} is returned unchanged: narrowing one's own subscription is never a
     * visibility concern.
     *
     * @param userId  the caller (already proven to own the target connection by the time this is
     *                called — see {@code LiveUpdateRegistry#updateTopics})
     * @param request the requested changes
     * @return {@code request}, with any currently-invisible entry removed from {@code add}
     * @throws IllegalArgumentException if an {@code add} entry is malformed (unchanged behavior)
     */
    public UpdateLiveTopicsRequest filterAdditions(UserId userId, UpdateLiveTopicsRequest request,
                                                   VisibilityScope requestScope) {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(requestScope, "requestScope must not be null");
        List<String> keptAdds = request.add().stream()
                .filter(raw -> {
                    LiveTopic topic = LiveTopic.parse(raw);
                    return topic.assetId() == null || canView(userId, topic.assetId(), requestScope);
                })
                .toList();
        return new UpdateLiveTopicsRequest(keptAdds, request.remove());
    }

    /**
     * Whether {@code userId}'s current scope may see {@code assetId}, served from the {@code
     * (userId, assetId)} cache when fresh, otherwise re-derived (docs/plans/active/LIVE-SCOPE-PLAN.md
     * §2, W3's "re-resolve rather than trusting a snapshot").
     */
    boolean canView(UserId userId, AssetId assetId, VisibilityScope requestScope) {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(assetId, "assetId must not be null");
        Objects.requireNonNull(requestScope, "requestScope must not be null");
        Key key = new Key(userId, assetId);
        long now = System.currentTimeMillis();
        Entry cached = cache.get(key);
        if (cached != null && now - cached.resolvedAt() <= ttlMillis) {
            return cached.visible();
        }
        boolean visible = resolve(userId, assetId, requestScope);
        store(key, new Entry(visible, now));
        return visible;
    }

    /**
     * Re-derives the answer from the repository, because a scope can change mid-connection.
     *
     * <p>When the {@link UserId} resolves to no row, the caller's own scope decides — but only if it
     * is unbounded. That is not a loophole, it is the one principal that has no row <em>by design</em>:
     * with {@code vision.auth.enabled=false} (the default for a local run) {@code CurrentUser} is a
     * synthetic dev admin who was never persisted, and re-resolving it from the users table denied
     * every per-asset topic — which silently took the whole live plane down with it, detection demand
     * included, since demand is derived from who is subscribed. A real user who was deleted
     * mid-connection has a bounded scope, so this still fails closed for them.
     */
    private boolean resolve(UserId userId, AssetId assetId, VisibilityScope requestScope) {
        return userRepositoryPort.findById(userId)
                .map(user -> streamAccess.visibleAsset(assetId, scopeResolver.scopeFor(user)))
                .orElseGet(() -> requestScope.canAdminister()
                        && streamAccess.visibleAsset(assetId, requestScope));
    }

    private void store(Key key, Entry entry) {
        if (cache.size() > SWEEP_THRESHOLD) {
            cache.values().removeIf(cached -> entry.resolvedAt() - cached.resolvedAt() > ttlMillis);
        }
        cache.put(key, entry);
    }

    private record Key(UserId userId, AssetId assetId) {
    }

    private record Entry(boolean visible, long resolvedAt) {
    }
}
