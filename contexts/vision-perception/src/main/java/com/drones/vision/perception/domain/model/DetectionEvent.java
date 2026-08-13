package com.drones.vision.perception.domain.model;

import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.GeoPosition;
import com.drones.vision.kernel.StreamId;
import java.time.Instant;

/**
 * A debounced, human-meaningful occurrence (docs/plans/done/MVP2-PLAN.md §E, E-a): label X seen at/above a
 * confidence threshold across several consecutive {@link DetectionResult}s on a stream — the CV
 * pipeline's noisy frame-by-frame results collapsed into "something happened", rather than one
 * event per completed inference. Opened and evolved by {@code DetectionEventEngine}
 * (vision-application, per-stream, one instance per running pipeline); this record itself is a
 * plain, immutable snapshot with no behavior beyond its own {@code with*}-style copy methods.
 *
 * <p>{@code assetId} is nullable — the device streaming may not (yet) belong to any asset.
 * {@code position} is nullable and, when present, is stamped exactly <b>once</b>, at the moment
 * the event opens, from the freshest position the stream's asset had accumulated at that instant
 * ({@code null} when the asset is unresolvable, has no currently open usage, or that usage has no
 * position yet) — it is never updated afterward even while the event stays open and the asset
 * keeps moving; see {@code DetectionEventEngine}'s javadoc for the full reasoning.
 *
 * @param id             typed event identity
 * @param streamId       the stream this event was observed on
 * @param assetId        the asset owning the stream's device, or {@code null} if unresolvable
 * @param label          the detection label this event tracks (e.g. {@code "person"}); must not be blank
 * @param peakConfidence highest confidence observed for this label while the event has been open, range [0,1]
 * @param firstSeen      when the qualifying streak that opened this event began
 * @param lastSeen       the most recent time this label was actually seen at/above threshold; must not be before {@code firstSeen}
 * @param state          {@link DetectionEventState#OPEN} while still active, {@link DetectionEventState#CLOSED} once absence closed it
 * @param position       best-effort position at open time, or {@code null} if unavailable
 */
public record DetectionEvent(DetectionEventId id, StreamId streamId, AssetId assetId, String label,
                              double peakConfidence, Instant firstSeen, Instant lastSeen, DetectionEventState state,
                              GeoPosition position) {

    public DetectionEvent {
        if (id == null) {
            throw new IllegalArgumentException("DetectionEvent id must not be null");
        }
        if (streamId == null) {
            throw new IllegalArgumentException("DetectionEvent streamId must not be null");
        }
        if (label == null || label.isBlank()) {
            throw new IllegalArgumentException("DetectionEvent label must not be blank");
        }
        if (Double.isNaN(peakConfidence) || peakConfidence < 0.0 || peakConfidence > 1.0) {
            throw new IllegalArgumentException(
                    "DetectionEvent peakConfidence must be within [0,1]: " + peakConfidence);
        }
        if (firstSeen == null) {
            throw new IllegalArgumentException("DetectionEvent firstSeen must not be null");
        }
        if (lastSeen == null) {
            throw new IllegalArgumentException("DetectionEvent lastSeen must not be null");
        }
        if (lastSeen.isBefore(firstSeen)) {
            throw new IllegalArgumentException(
                    "DetectionEvent lastSeen must not be before firstSeen: " + lastSeen + " < " + firstSeen);
        }
        if (state == null) {
            throw new IllegalArgumentException("DetectionEvent state must not be null");
        }
    }

    /**
     * Returns a copy with {@code lastSeen} advanced to {@code seenAt} and {@code peakConfidence}
     * raised to the higher of the current value and {@code confidence} — called whenever an
     * already-open event's label is seen again at/above threshold. {@code state} is left
     * unchanged (callers only ever invoke this while the event is still {@link
     * DetectionEventState#OPEN}).
     *
     * @param seenAt     the new observation's timestamp; must not be before {@link #firstSeen()}
     * @param confidence the new observation's confidence, range [0,1]
     * @return a new {@code DetectionEvent} with {@code lastSeen}/{@code peakConfidence} updated
     */
    public DetectionEvent withObservation(Instant seenAt, double confidence) {
        return new DetectionEvent(id, streamId, assetId, label, Math.max(peakConfidence, confidence), firstSeen,
                seenAt, state, position);
    }

    /**
     * Returns a copy closed: {@code state} becomes {@link DetectionEventState#CLOSED}. {@code
     * lastSeen} is left exactly as the last real observation — not moved forward to the moment the
     * absence was confirmed — so it always answers "when was this last actually seen".
     *
     * @return a new {@code DetectionEvent} with {@code state} set to {@code CLOSED}
     */
    public DetectionEvent closed() {
        return new DetectionEvent(id, streamId, assetId, label, peakConfidence, firstSeen, lastSeen,
                DetectionEventState.CLOSED, position);
    }
}
