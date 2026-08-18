package com.drones.vision.api.ratelimit;

import com.drones.vision.api.security.CurrentUser;
import com.drones.vision.api.security.PrincipalResolver;
import com.drones.vision.kernel.UserId;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Every test uses a mocked {@link ScheduledExecutorService} (via {@link #newFilter}) so {@link
 * RateLimitFilter#evictIdleBuckets()} is only ever invoked directly — never on a real timer — the
 * same reason {@code LiveUpdateRegistryTest} injects its own scheduler. Time-sensitive tests
 * (refill, eviction) additionally inject a fake {@link LongSupplier} clock instead of sleeping.
 */
class RateLimitFilterTest {

    private static final long ONE_MINUTE_NANOS = TimeUnit.MINUTES.toNanos(1);

    @Test
    void allowsRequestsUnderTheLimit() throws Exception {
        RateLimitFilter filter = filterFor(fixedUserResolver(UserId.random()), 5);
        AtomicInteger chainCalls = new AtomicInteger();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(apiRequest("/api/streams"), response, countingChain(chainCalls));

        assertEquals(1, chainCalls.get());
        assertEquals(200, response.getStatus());
    }

    @Test
    void returns429OnBreach() throws Exception {
        RateLimitFilter filter = filterFor(fixedUserResolver(UserId.random()), 1);
        AtomicInteger chainCalls = new AtomicInteger();

        filter.doFilter(apiRequest("/api/streams"), new MockHttpServletResponse(), countingChain(chainCalls));
        assertEquals(1, chainCalls.get(), "first request consumes the bucket's only permit");

        MockHttpServletResponse second = new MockHttpServletResponse();
        filter.doFilter(apiRequest("/api/streams"), second, countingChain(chainCalls));

        assertEquals(1, chainCalls.get(), "a request over budget must never reach the chain");
        assertEquals(429, second.getStatus());
        assertEquals("application/json", second.getContentType());
        assertTrue(second.getContentAsString().contains("TOO_MANY_REQUESTS"));
    }

    @Test
    void perPrincipalBucketsAreIndependent() throws Exception {
        UserId userA = UserId.random();
        UserId userB = UserId.random();
        AtomicReference<UserId> active = new AtomicReference<>(userA);
        PrincipalResolver resolver = mock(PrincipalResolver.class);
        when(resolver.userId()).thenAnswer(invocation -> active.get());
        RateLimitFilter filter = filterFor(resolver, 1);
        AtomicInteger chainCalls = new AtomicInteger();

        filter.doFilter(apiRequest("/api/streams"), new MockHttpServletResponse(), countingChain(chainCalls));
        MockHttpServletResponse aSecond = new MockHttpServletResponse();
        filter.doFilter(apiRequest("/api/streams"), aSecond, countingChain(chainCalls));
        assertEquals(429, aSecond.getStatus(), "user A's single permit is already spent");

        active.set(userB);
        MockHttpServletResponse bFirst = new MockHttpServletResponse();
        filter.doFilter(apiRequest("/api/streams"), bFirst, countingChain(chainCalls));

        assertEquals(200, bFirst.getStatus(), "user B's budget is untouched by user A's breach");
    }

    @Test
    void refillsContinuouslyOverTime() throws Exception {
        AtomicLong now = new AtomicLong(0L);
        RateLimitFilter filter = newFilter(fixedUserResolver(UserId.random()), 1, now::get);

        filter.doFilter(apiRequest("/api/streams"), new MockHttpServletResponse(), countingChain(new AtomicInteger()));
        MockHttpServletResponse exhausted = new MockHttpServletResponse();
        filter.doFilter(apiRequest("/api/streams"), exhausted, countingChain(new AtomicInteger()));
        assertEquals(429, exhausted.getStatus(), "a one-permit-per-minute bucket is empty on its second call");

        now.addAndGet(ONE_MINUTE_NANOS);

        MockHttpServletResponse refilled = new MockHttpServletResponse();
        filter.doFilter(apiRequest("/api/streams"), refilled, countingChain(new AtomicInteger()));
        assertEquals(200, refilled.getStatus(), "a full minute later the bucket has refilled to capacity");
    }

    @Test
    void doesNotThrottleTheLiveStreamOrItsTopicsEndpoint() throws Exception {
        RateLimitFilter filter = filterFor(fixedUserResolver(UserId.random()), 1);
        AtomicInteger chainCalls = new AtomicInteger();

        for (int i = 0; i < 10; i++) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(apiRequest("/api/live"), response, countingChain(chainCalls));
            assertEquals(200, response.getStatus());
        }
        for (int i = 0; i < 10; i++) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(apiRequest("/api/live/conn-1/topics"), response, countingChain(chainCalls));
            assertEquals(200, response.getStatus());
        }

        assertEquals(20, chainCalls.get(), "a one-permit budget would have 429'd request #2 onward if this path were limited");
        assertEquals(0, filter.bucketCount(), "an excluded path must never even allocate a bucket");
    }

    @Test
    void doesNotThrottleNonApiPaths() throws Exception {
        RateLimitFilter filter = filterFor(fixedUserResolver(UserId.random()), 1);
        AtomicInteger chainCalls = new AtomicInteger();

        filter.doFilter(pathRequest("/hls/stream-1/index.m3u8"), new MockHttpServletResponse(), countingChain(chainCalls));

        assertEquals(1, chainCalls.get());
        assertEquals(0, filter.bucketCount());
    }

    @Test
    void anonymousCallersKeyOnRemoteAddressNotOneSharedBucket() throws Exception {
        PrincipalResolver resolver = mock(PrincipalResolver.class);
        when(resolver.userId()).thenThrow(new IllegalStateException("no authenticated principal on the current request"));
        RateLimitFilter filter = filterFor(resolver, 1);
        AtomicInteger chainCalls = new AtomicInteger();

        MockHttpServletRequest fromA = apiRequest("/api/auth/login");
        fromA.setRemoteAddr("10.0.0.1");
        filter.doFilter(fromA, new MockHttpServletResponse(), countingChain(chainCalls));

        MockHttpServletRequest fromB = apiRequest("/api/auth/login");
        fromB.setRemoteAddr("10.0.0.2");
        MockHttpServletResponse bResponse = new MockHttpServletResponse();
        filter.doFilter(fromB, bResponse, countingChain(chainCalls));

        assertEquals(200, bResponse.getStatus(), "a different anonymous caller must not inherit A's spent budget");
        assertEquals(2, filter.bucketCount(), "two distinct remote addresses hold two distinct buckets");
    }

    @Test
    void evictsOnlyBucketsIdlePastTheWindow() {
        AtomicLong now = new AtomicLong(0L);
        UserId userA = UserId.random();
        UserId userB = UserId.random();
        AtomicReference<UserId> active = new AtomicReference<>(userA);
        PrincipalResolver resolver = mock(PrincipalResolver.class);
        when(resolver.userId()).thenAnswer(invocation -> active.get());
        RateLimitFilter filter = newFilter(resolver, 100, now::get);

        touch(filter, "/api/streams");
        active.set(userB);
        touch(filter, "/api/streams");
        assertEquals(2, filter.bucketCount());

        long halfWindow = TimeUnit.MILLISECONDS.toNanos(RateLimitFilter.BUCKET_IDLE_MILLIS) / 2;
        now.addAndGet(halfWindow);
        active.set(userA);
        touch(filter, "/api/streams"); // A touched again at the halfway point; B is left untouched from here

        now.addAndGet(halfWindow + 1);
        filter.evictIdleBuckets();

        assertEquals(1, filter.bucketCount(), "only B, idle for the full window, is evicted");
    }

    @Test
    void rejectsNonPositivePermitsPerMinute() {
        assertThrows(IllegalArgumentException.class, () -> filterFor(fixedUserResolver(UserId.random()), 0));
    }

    private static void touch(RateLimitFilter filter, String path) {
        try {
            filter.doFilter(apiRequest(path), new MockHttpServletResponse(), countingChain(new AtomicInteger()));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static RateLimitFilter filterFor(PrincipalResolver resolver, int permitsPerMinute) {
        return newFilter(resolver, permitsPerMinute, System::nanoTime);
    }

    private static RateLimitFilter newFilter(PrincipalResolver resolver, int permitsPerMinute, LongSupplier clock) {
        return new RateLimitFilter(new CurrentUser(resolver), permitsPerMinute, noOpScheduler(), clock);
    }

    private static ScheduledExecutorService noOpScheduler() {
        return mock(ScheduledExecutorService.class);
    }

    private static PrincipalResolver fixedUserResolver(UserId id) {
        PrincipalResolver resolver = mock(PrincipalResolver.class);
        when(resolver.userId()).thenReturn(id);
        return resolver;
    }

    private static MockHttpServletRequest apiRequest(String path) {
        return pathRequest(path);
    }

    private static MockHttpServletRequest pathRequest(String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setServletPath(path);
        return request;
    }

    private static FilterChain countingChain(AtomicInteger counter) {
        return (request, response) -> counter.incrementAndGet();
    }
}
