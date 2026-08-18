package com.drones.vision.api.ratelimit;

import com.drones.vision.api.dto.ErrorResponse;
import com.drones.vision.api.security.CurrentUser;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * A per-principal token-bucket limiter on {@code /api/**} (docs/plans/active/SCALE-100-PLAN.md §5 S6 item 3).
 *
 * <p><strong>What this is, and what it deliberately is not</strong> — the plan says it plainly:
 * this is <em>not</em> security hardening, it is a blast-radius bound. Today one misbehaving
 * browser tab (a runaway retry loop, a poll that never backs off) can issue enough requests to
 * degrade the app for every other user on the same JVM. This filter gives each acting principal an
 * independent, generously-sized budget so that failure mode stays confined to the one caller who
 * triggered it. It is not an auth-aware policy, has no per-endpoint tiers, and is not a DoS
 * defence — one flat limit, one knob.
 *
 * <h2>Keying (per principal, not per deployment)</h2>
 * The bucket key is {@link CurrentUser#userId()} — the same identity every write on the request is
 * already attributed to, so the key degrades exactly the way the rest of the request pipeline
 * does: one shared identity (the fixed dev principal, {@code DevPrincipal}) when {@code
 * vision.auth.enabled=false}, so every dev-mode caller shares one generous bucket; one bucket per
 * real user when it is {@code true}. {@code SecurityContextPrincipalResolver} (vision-app) throws
 * {@link IllegalStateException} for a request with no authenticated session — under the secured
 * filter chain ({@code SecurityConfig}) the only {@code /api/**} paths reachable that way are the
 * permit-all {@code /api/auth/login}/{@code /api/auth/logout}, since every other {@code /api/**}
 * path is rejected with {@code 401} before this filter ever runs (see "Ordering" below). Those two
 * fall back to the caller's remote address instead of sharing one bucket with every other
 * unauthenticated caller, which would turn a login-page hammering by one client into a lockout for
 * everyone else trying to log in.
 *
 * <h2>Path scope — must never sit in front of a long-lived stream</h2>
 * Only {@code /api/**} is limited, and {@link #shouldNotFilter} excludes {@code /api/live} — the
 * SSE stream itself — and everything under it (e.g. {@code /api/live/{connectionId}/topics}, its
 * subscription-management endpoint), because a token bucket in front of a long-lived connection is
 * a self-inflicted outage: reconnect/backoff churn from one client would count against, and could
 * exhaust, that client's own budget for every other {@code /api/**} call it makes. {@code
 * /hls/**} (the video path) never reaches the limited scope at all — it does not match the {@code
 * /api/} prefix — but is called out here because it is the other long-lived path this filter must
 * never throttle.
 *
 * <h2>Ordering — why {@link CurrentUser#userId()} is safe to call here</h2>
 * This is registered as a plain {@code Filter} bean with no explicit order, so it runs at Spring
 * Boot's default {@code LOWEST_PRECEDENCE} — after {@code springSecurityFilterChain} (order {@code
 * -100} whichever {@code SecurityFilterChain} in {@code SecurityConfig} is active). By the time
 * this filter runs, Spring Security has already rejected an unauthenticated request to a protected
 * path (401, this filter never sees it) or populated the session's {@code SecurityContext} — so
 * {@link CurrentUser#userId()} either resolves the real caller or throws for one of the two known
 * permit-all exceptions above, never silently misattributes a request.
 *
 * <h2>Memory — bounded, evicted</h2>
 * {@link #buckets} is a plain {@code ConcurrentHashMap} keyed by principal; left alone it would
 * retain one bucket per principal ever seen since boot, the exact class of leak
 * docs/plans/active/SCALE-100-PLAN.md §5 S2 fixed for {@code LiveUpdateRegistry}'s per-asset
 * buffers. {@link #evictIdleBuckets()} sweeps it on a background timer in the same shape as that
 * fix (single daemon thread, package-private sweep method so a test can trigger it directly
 * instead of waiting on the real timer).
 */
public final class RateLimitFilter extends OncePerRequestFilter {

    /**
     * Default bucket capacity and refill rate, permits per rolling minute, used when {@code
     * vision-app} doesn't override it via {@code vision.api.rate-limit.permits-per-minute}
     * (docs/plans/active/SCALE-100-PLAN.md §6). Chosen generously: the plan's §2.1 measures an
     * ungated cockpit tab at roughly 1 request/second; 600/minute (10/s sustained on average,
     * refilling continuously, burstable up to a full minute's allotment at once) comfortably
     * covers several such tabs open at once under one principal, while still bounding the runaway
     * loop this filter exists to catch.
     */
    public static final int DEFAULT_PERMITS_PER_MINUTE = 600;

    /**
     * How often {@link #evictIdleBuckets()} sweeps {@link #buckets} for principals idle longer
     * than {@link #BUCKET_IDLE_MILLIS} — see that field's javadoc for why the map needs bounding at
     * all. Ten minutes: frequent enough that the map never meaningfully outgrows the set of
     * recently-active principals, infrequent enough that the sweep itself (an O(map size) pass) is
     * a non-event at the scale this plan targets (20-100 concurrent users).
     */
    static final long BUCKET_EVICTION_MILLIS = 600_000L;

    /**
     * How long a bucket may sit untouched before {@link #evictIdleBuckets()} removes it. A
     * returning caller after this window simply gets a fresh, full bucket — harmless, since the
     * whole point of a generous limit is that hitting it at all is already the abnormal case.
     */
    static final long BUCKET_IDLE_MILLIS = 600_000L;

    /** Every path this filter considers is under this prefix — see {@link #shouldNotFilter}. */
    private static final String API_PREFIX = "/api/";

    /** The SSE stream namespace, excluded in full — see class javadoc "Path scope". */
    private static final String LIVE_PATH = "/api/live";

    private final JsonMapper jsonMapper = new JsonMapper();
    private final CurrentUser currentUser;
    private final int permitsPerMinute;
    private final ConcurrentHashMap<Object, TokenBucket> buckets = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler;
    private final LongSupplier nanoClock;

    /** Production constructor: {@link #DEFAULT_PERMITS_PER_MINUTE}, a real daemon eviction thread, the real clock. */
    public RateLimitFilter(CurrentUser currentUser) {
        this(currentUser, DEFAULT_PERMITS_PER_MINUTE);
    }

    /**
     * @param currentUser      the request-identity seam this filter keys buckets on; must not be {@code null}
     * @param permitsPerMinute bucket capacity/refill rate — the one knob {@code
     *                         vision.api.rate-limit.permits-per-minute} tunes; must be positive
     */
    public RateLimitFilter(CurrentUser currentUser, int permitsPerMinute) {
        this(currentUser, permitsPerMinute, defaultScheduler(), System::nanoTime);
    }

    /**
     * Test seam: an injectable scheduler (so a test never has to wait on the real {@value
     * #BUCKET_EVICTION_MILLIS}ms timer — call {@link #evictIdleBuckets()} directly instead) and an
     * injectable time source (so a test can simulate elapsed time and idle buckets without
     * sleeping).
     */
    RateLimitFilter(CurrentUser currentUser, int permitsPerMinute, ScheduledExecutorService scheduler,
                    LongSupplier nanoClock) {
        this.currentUser = Objects.requireNonNull(currentUser, "currentUser must not be null");
        if (permitsPerMinute <= 0) {
            throw new IllegalArgumentException("permitsPerMinute must be positive, was " + permitsPerMinute);
        }
        this.permitsPerMinute = permitsPerMinute;
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock must not be null");
        this.scheduler.scheduleAtFixedRate(this::evictIdleBuckets, BUCKET_EVICTION_MILLIS, BUCKET_EVICTION_MILLIS,
                TimeUnit.MILLISECONDS);
    }

    private static ScheduledExecutorService defaultScheduler() {
        return Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "rate-limit-bucket-eviction");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** {@code /api/**} except {@code /api/live} and everything under it — see class javadoc "Path scope". */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        return !path.startsWith(API_PREFIX) || path.equals(LIVE_PATH) || path.startsWith(LIVE_PATH + "/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        long now = nanoClock.getAsLong();
        TokenBucket bucket = buckets.computeIfAbsent(bucketKey(request), key -> new TokenBucket(permitsPerMinute, now));
        if (bucket.tryConsume(now)) {
            filterChain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(jsonMapper.writeValueAsString(
                new ErrorResponse("TOO_MANY_REQUESTS", "rate limit exceeded, try again shortly")));
    }

    /**
     * {@link CurrentUser#userId()} for the normal case; falls back to the caller's remote address
     * for the one class of {@code /api/**} request reachable with no authenticated principal — see
     * class javadoc "Keying".
     */
    private Object bucketKey(HttpServletRequest request) {
        try {
            return currentUser.userId();
        } catch (IllegalStateException noAuthenticatedPrincipal) {
            return "anon:" + request.getRemoteAddr();
        }
    }

    /**
     * Removes any bucket idle for longer than {@link #BUCKET_IDLE_MILLIS} — package-private so a
     * test can trigger it directly instead of waiting on the real {@value #BUCKET_EVICTION_MILLIS}ms
     * timer, the same shape {@code LiveUpdateRegistry#evictUnusedAssetBuffers} uses for its own
     * per-asset buffer sweep.
     */
    void evictIdleBuckets() {
        long cutoffNanos = nanoClock.getAsLong() - TimeUnit.MILLISECONDS.toNanos(BUCKET_IDLE_MILLIS);
        buckets.values().removeIf(bucket -> bucket.idleSince(cutoffNanos));
    }

    /** Test seam: the number of distinct principals currently holding a bucket. */
    int bucketCount() {
        return buckets.size();
    }
}
