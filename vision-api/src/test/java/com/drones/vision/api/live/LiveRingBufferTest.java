package com.drones.vision.api.live;

import com.drones.vision.api.dto.LiveEnvelopeResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit tests for {@link LiveRingBuffer} — no Spring, no timing, deterministic
 * (docs/REALTIME-PLAN.md §4).
 */
class LiveRingBufferTest {

    private static LiveEnvelopeResponse envelope(long seq) {
        return new LiveEnvelopeResponse(seq, null, "fleet", List.of());
    }

    @Test
    void rejectsANonPositiveCapacity() {
        assertThrows(IllegalArgumentException.class, () -> new LiveRingBuffer(0, false));
        assertThrows(IllegalArgumentException.class, () -> new LiveRingBuffer(-1, true));
    }

    @Test
    void isEmptyBeforeAnythingIsAppended() {
        LiveRingBuffer buffer = new LiveRingBuffer(10, false);

        assertTrue(buffer.isEmpty());
        assertEquals(List.of(), buffer.snapshot());
    }

    @Test
    void fifoModeRetainsEveryEntryUpToCapacityOldestEvictedFirst() {
        LiveRingBuffer buffer = new LiveRingBuffer(3, false);

        for (long seq = 1; seq <= 5; seq++) {
            buffer.append(envelope(seq));
        }

        assertEquals(List.of(3L, 4L, 5L), buffer.snapshot().stream().map(LiveEnvelopeResponse::seq).toList());
    }

    @Test
    void collapseToLatestModeKeepsOnlyTheMostRecentAppend() {
        LiveRingBuffer buffer = new LiveRingBuffer(1, true);

        buffer.append(envelope(1));
        buffer.append(envelope(2));
        buffer.append(envelope(3));

        assertEquals(List.of(3L), buffer.snapshot().stream().map(LiveEnvelopeResponse::seq).toList());
    }

    @Test
    void sinceReturnsOnlyEntriesStrictlyAfterTheGivenSeq() {
        LiveRingBuffer buffer = new LiveRingBuffer(10, false);
        buffer.append(envelope(1));
        buffer.append(envelope(2));
        buffer.append(envelope(3));

        assertEquals(List.of(2L, 3L), buffer.since(1).stream().map(LiveEnvelopeResponse::seq).toList());
        assertEquals(List.of(), buffer.since(3));
    }

    @Test
    void canResumeFromIsTrueWhenNothingIsBufferedAtAll() {
        LiveRingBuffer buffer = new LiveRingBuffer(10, false);

        assertTrue(buffer.canResumeFrom(0));
        assertTrue(buffer.canResumeFrom(999));
    }

    @Test
    void canResumeFromIsAlwaysTrueForABufferThatHasNeverDroppedAnything() {
        // A buffer whose oldest retained entry happens to have a high seq (because the shared
        // global counter advanced elsewhere -- other topics were busy) must not be mistaken for a
        // gap: since() is correct for any sinceSeq as long as this buffer itself never evicted or
        // replaced anything, however far "behind" sinceSeq looks against this buffer's own entries.
        LiveRingBuffer buffer = new LiveRingBuffer(10, false);
        buffer.append(envelope(1000));
        buffer.append(envelope(1001));

        assertTrue(buffer.canResumeFrom(1)); // nothing dropped yet, however old
        assertTrue(buffer.canResumeFrom(1000));
        assertEquals(List.of(1000L, 1001L), buffer.since(1).stream().map(LiveEnvelopeResponse::seq).toList());
    }

    @Test
    void canResumeFromDetectsAGapAfterEviction() {
        LiveRingBuffer buffer = new LiveRingBuffer(2, false);
        for (long seq = 1; seq <= 4; seq++) {
            buffer.append(envelope(seq)); // retains {3, 4} after this loop, 1 and 2 evicted
        }

        // resuming from 2 (the client's last-seen seq before the gap) is exactly at the boundary --
        // entries 3 onward are all still present, so nothing was actually missed.
        assertTrue(buffer.canResumeFrom(2));
        // resuming from 1 means entry 2 (evicted) would have been missed.
        assertFalse(buffer.canResumeFrom(1));
    }

    @Test
    void collapseToLatestModeMarksItselfAsHavingDroppedOnTheSecondAppendOnward() {
        LiveRingBuffer buffer = new LiveRingBuffer(1, true);
        buffer.append(envelope(5));

        assertTrue(buffer.canResumeFrom(1), "a single append never having replaced anything is not a drop");

        buffer.append(envelope(6)); // replaces envelope(5) -- the first real "drop"

        assertTrue(buffer.canResumeFrom(5), "at the boundary: nothing between 5 and 6 was ever retained anyway");
        assertFalse(buffer.canResumeFrom(4), "envelope(5) was replaced without ever being resumable past it");
    }
}
