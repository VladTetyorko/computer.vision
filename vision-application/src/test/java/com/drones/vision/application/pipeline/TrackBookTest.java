package com.drones.vision.application.pipeline;

import com.drones.vision.kernel.BoundingBox;
import com.drones.vision.perception.domain.model.Detection;
import com.drones.vision.perception.domain.model.DetectionResult;
import com.drones.vision.perception.domain.model.DetectionSource;
import com.drones.vision.perception.domain.model.ModelRef;
import com.drones.vision.kernel.StreamId;
import com.drones.vision.perception.domain.model.TrackRef;
import com.drones.vision.perception.domain.model.TrackState;
import com.drones.vision.perception.domain.model.TrackedObject;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every timestamp here comes from the result's own {@code capturedAt}, never {@code Instant.now()} —
 * {@link TrackBook} has no clock by design, so these assertions are exact rather than tolerant.
 */
class TrackBookTest {

    private static final ModelRef MODEL = new ModelRef("yolo26n.pt", "latest");
    private static final Instant T0 = Instant.parse("2026-08-11T10:00:00Z");
    private static final Duration RETENTION = Duration.ofSeconds(5);

    private final StreamId streamId = StreamId.random();

    private DetectionResult result(long frameSequence, Instant capturedAt, Detection... detections) {
        return new DetectionResult(streamId, frameSequence, capturedAt, List.of(detections), Duration.ZERO);
    }

    private static Detection tracked(String label, long trackId, TrackState state, double x) {
        return new Detection(label, 0.8, new BoundingBox(x, 0.4, 0.1, 0.1), MODEL,
                new TrackRef(trackId, state, DetectionSource.TRACKER));
    }

    private static Detection untracked(String label, double x) {
        return new Detection(label, 0.8, new BoundingBox(x, 0.4, 0.1, 0.1), MODEL);
    }

    private static TrackedObject only(List<TrackedObject> tracks) {
        assertEquals(1, tracks.size(), () -> "expected exactly one booked track, got " + tracks);
        return tracks.getFirst();
    }

    @Test
    void anEmptyBookHasNoTracks() {
        assertTrue(new TrackBook(RETENTION).tracks().isEmpty());
    }

    @Test
    void booksATrackedDetectionUnderItsTrackId() {
        TrackBook book = new TrackBook(RETENTION);

        book.accept(result(0, T0, tracked("car", 7, TrackState.CONFIRMED, 0.3)));

        TrackedObject booked = only(book.tracks());
        assertEquals(7L, booked.trackId());
        assertEquals("car", booked.detection().label());
        assertEquals(T0, booked.firstSeen());
        assertEquals(T0, booked.lastSeen());
    }

    @Test
    void aSecondResultPreservesFirstSeenAndAdvancesLastSeen() {
        TrackBook book = new TrackBook(RETENTION);
        Instant later = T0.plusMillis(400);

        book.accept(result(0, T0, tracked("car", 7, TrackState.CONFIRMED, 0.30)));
        book.accept(result(1, later, tracked("car", 7, TrackState.CONFIRMED, 0.34)));

        TrackedObject booked = only(book.tracks());
        assertEquals(T0, booked.firstSeen(), "firstSeen is the track's birth, not its latest observation");
        assertEquals(later, booked.lastSeen());
        assertEquals(0.34, booked.detection().box().x(), 1e-9, "the freshest observation is the booked one");
    }

    @Test
    void aLostTrackExpiresOutOfTheBookWhileALiveOneStays() {
        TrackBook book = new TrackBook(RETENTION);
        book.accept(result(0, T0,
                tracked("car", 7, TrackState.CONFIRMED, 0.30),
                tracked("car", 8, TrackState.CONFIRMED, 0.60)));
        assertEquals(2, book.tracks().size());

        book.accept(result(1, T0.plusMillis(400),
                tracked("car", 7, TrackState.LOST, 0.31),
                tracked("car", 8, TrackState.CONFIRMED, 0.62)));

        assertEquals(List.of(8L), book.tracks().stream().map(TrackedObject::trackId).toList());
    }

    @Test
    void aTrackThatSimplyStopsArrivingExpiresAfterTheRetentionWindow() {
        TrackBook book = new TrackBook(RETENTION);
        book.accept(result(0, T0, tracked("car", 7, TrackState.CONFIRMED, 0.30)));

        // A different track arriving RETENTION later is what advances the book's notion of "now".
        book.accept(result(1, T0.plus(RETENTION), tracked("car", 9, TrackState.CONFIRMED, 0.70)));

        assertEquals(List.of(9L), book.tracks().stream().map(TrackedObject::trackId).toList());
    }

    @Test
    void aTrackStillWithinTheRetentionWindowSurvivesAResultThatDoesNotMentionIt() {
        TrackBook book = new TrackBook(RETENTION);
        book.accept(result(0, T0, tracked("car", 7, TrackState.CONFIRMED, 0.30)));

        book.accept(result(1, T0.plus(RETENTION).minusMillis(1), tracked("car", 9, TrackState.CONFIRMED, 0.70)));

        assertEquals(List.of(7L, 9L), book.tracks().stream().map(TrackedObject::trackId).toList());
    }

    @Test
    void untrackedDetectionsAreIgnoredEntirelySoATrackingOffStreamBooksNothing() {
        TrackBook book = new TrackBook(RETENTION);

        book.accept(result(0, T0, untracked("car", 0.3), untracked("person", 0.6)));

        assertTrue(book.tracks().isEmpty());
    }

    @Test
    void tracksAreOrderedByTrackIdAscending() {
        TrackBook book = new TrackBook(RETENTION);

        book.accept(result(0, T0,
                tracked("car", 12, TrackState.CONFIRMED, 0.1),
                tracked("car", 3, TrackState.TENTATIVE, 0.2),
                tracked("car", 7, TrackState.COASTING, 0.3)));

        assertEquals(List.of(3L, 7L, 12L), book.tracks().stream().map(TrackedObject::trackId).toList());
    }

    @Test
    void anOutOfOrderResultNeverRewindsTheBookedObservation() {
        TrackBook book = new TrackBook(RETENTION);
        Instant later = T0.plusMillis(400);
        book.accept(result(1, later, tracked("car", 7, TrackState.CONFIRMED, 0.34)));

        // An inference that completed late but was captured earlier: widens the lifetime backwards,
        // never replaces the fresher observation already booked.
        book.accept(result(0, T0, tracked("car", 7, TrackState.TENTATIVE, 0.30)));

        TrackedObject booked = only(book.tracks());
        assertEquals(T0, booked.firstSeen());
        assertEquals(later, booked.lastSeen());
        assertEquals(TrackState.CONFIRMED, booked.detection().track().state());
    }

    @Test
    void clearEmptiesTheBook() {
        TrackBook book = new TrackBook(RETENTION);
        book.accept(result(0, T0, tracked("car", 7, TrackState.CONFIRMED, 0.3)));

        book.clear();

        assertTrue(book.tracks().isEmpty());
    }

    @Test
    void tracksReturnsAnImmutableSnapshotThatALaterAcceptCannotMutate() {
        TrackBook book = new TrackBook(RETENTION);
        book.accept(result(0, T0, tracked("car", 7, TrackState.CONFIRMED, 0.3)));
        List<TrackedObject> snapshot = book.tracks();

        book.accept(result(1, T0.plusMillis(100), tracked("car", 8, TrackState.CONFIRMED, 0.6)));

        assertEquals(1, snapshot.size());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.add(snapshot.getFirst()));
    }

    @Test
    void aNonPositiveRetentionIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new TrackBook(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new TrackBook(Duration.ofSeconds(-1)));
    }
}
