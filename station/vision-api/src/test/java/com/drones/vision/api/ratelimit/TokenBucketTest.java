package com.drones.vision.api.ratelimit;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TokenBucketTest {

    @Test
    void startsFullAndDrainsOneTokenPerConsume() {
        TokenBucket bucket = new TokenBucket(3, 0L);

        assertTrue(bucket.tryConsume(0L));
        assertTrue(bucket.tryConsume(0L));
        assertTrue(bucket.tryConsume(0L));
        assertFalse(bucket.tryConsume(0L), "capacity was exactly 3, a 4th immediate consume must fail");
    }

    @Test
    void refillsProportionallyToElapsedTimeAndCapsAtCapacity() {
        TokenBucket bucket = new TokenBucket(60, 0L); // 1 token/second
        assertTrue(bucket.tryConsume(0L));
        assertTrue(bucket.tryConsume(0L));
        // 58 tokens remain; wait far longer than needed to refill to capacity
        long tenMinutesLater = TimeUnit.MINUTES.toNanos(10);
        assertTrue(bucket.tryConsume(tenMinutesLater), "refill must cap at capacity, not overflow past it");

        // draining exactly 60 more from a capped-at-60 bucket must be the last one that succeeds
        for (int i = 0; i < 59; i++) {
            assertTrue(bucket.tryConsume(tenMinutesLater));
        }
        assertFalse(bucket.tryConsume(tenMinutesLater));
    }

    @Test
    void idleSinceReflectsTheLastTryConsumeCall() {
        TokenBucket bucket = new TokenBucket(1, 1_000L);
        assertFalse(bucket.idleSince(1_000L), "created at 1000, not idle since before 1000");

        bucket.tryConsume(5_000L);
        assertFalse(bucket.idleSince(5_000L), "touched at 5000, not idle since before 5000");
        assertTrue(bucket.idleSince(5_001L), "touched at 5000, idle since any cutoff after that");
    }
}
