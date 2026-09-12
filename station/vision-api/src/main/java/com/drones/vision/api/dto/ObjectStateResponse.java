package com.drones.vision.api.dto;

import com.drones.vision.perception.domain.model.EvidenceSource;
import com.drones.vision.perception.domain.model.ObjectLifecycle;
import com.drones.vision.perception.domain.model.ObjectState;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Wire mirror of {@link ObjectState} (docs/plans/active/CV-ORCHESTRATION-PLAN.md §4.5, wave W1) —
 * the platform's whole current view of one tracked or dormant identity, grouped by facet instead
 * of a box with facts bolted on as flat scalars. Keyed by {@code (streamId, id)}; {@code id} is the
 * same number as the sibling {@code TrackResponse}'s own {@code trackId}.
 *
 * <p><b>A missing nested group is not the forbidden "null means the feature is off."</b> {@code
 * @JsonInclude(NON_NULL)} means a {@code null} domain group (this configuration does not
 * <em>compute</em> those facts at all — e.g. {@code kinematics.predictedBox} is never populated in
 * FOLLOW mode) serializes as a missing JSON key, never a zeroed object — a different statement from
 * a genuine zero, and collapsing the two is the exact honesty defect this wave exists to remove. A
 * field inside a <em>present</em> group is always a genuine fact, including a genuine zero.
 *
 * @param id         per-stream track id, matching the sibling {@code TrackResponse}'s own {@code
 *                   trackId} for the same object
 * @param lifecycle  where this identity is in its life
 * @param streamId   the stream this object belongs to, as a canonical UUID string
 * @param identity   label and label-election facts, or absent if not computed
 * @param kinematics box and motion facts, or absent if not computed
 * @param belief     confidence/existence facts, or absent if not computed
 * @param provenance which contributor(s) produced this frame's view of the object, or absent if
 *                   not computed
 * @param memory     re-acquisition facts, or absent if not computed
 * @param lock       FOLLOW-lock facts, or absent if not computed
 * @param timing     the lifecycle counters death is decided from, or absent if not computed
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ObjectStateResponse(long id, ObjectLifecycle lifecycle, String streamId, Identity identity,
                                   Kinematics kinematics, Belief belief, Provenance provenance, MemoryFacts memory,
                                   LockFacts lock, Timing timing) {

    /**
     * Maps a domain {@link ObjectState} to its wire representation.
     *
     * @param state the object to map
     * @return the response body for {@code state}
     */
    public static ObjectStateResponse from(ObjectState state) {
        return new ObjectStateResponse(state.id(), state.lifecycle(), state.streamId().value().toString(),
                state.identity() == null ? null : Identity.from(state.identity()),
                state.kinematics() == null ? null : Kinematics.from(state.kinematics()),
                state.belief() == null ? null : Belief.from(state.belief()),
                state.provenance() == null ? null : Provenance.from(state.provenance()),
                state.memory() == null ? null : MemoryFacts.from(state.memory()),
                state.lock() == null ? null : LockFacts.from(state.lock()),
                state.timing() == null ? null : Timing.from(state.timing()));
    }

    /**
     * Wire mirror of {@link ObjectState.LabelCandidate} — one entry of the label-vote tally behind
     * {@link Identity#label()}, the distribution rather than just the winner.
     *
     * @param label  a candidate label
     * @param weight decayed, confidence-weighted tally; comparable only within one frame
     */
    public record LabelCandidate(String label, double weight) {
        public static LabelCandidate from(ObjectState.LabelCandidate candidate) {
            return new LabelCandidate(candidate.label(), candidate.weight());
        }
    }

    /**
     * Wire mirror of {@link ObjectState.Identity} (JSON key {@code identity}) — what the object is
     * called, and how settled that name is. {@code labelRaw} may be blank — a frame with no
     * detector observation this frame (a pure coast, or a recovery from memory) still carries the
     * elected {@code label}, but has no raw detector label of its own to report.
     *
     * @param label      elected label — hysteresis-gated, what an operator reads
     * @param labelRaw   this frame's own detector label, un-elected; may be blank
     * @param candidates the tally behind the election, best-first
     * @param stability  consecutive elections since {@code label} last changed
     */
    public record Identity(String label, String labelRaw, List<LabelCandidate> candidates, int stability) {
        public static Identity from(ObjectState.Identity identity) {
            return new Identity(identity.label(), identity.labelRaw(),
                    identity.candidates().stream().map(LabelCandidate::from).toList(), identity.stability());
        }
    }

    /**
     * Wire mirror of {@link ObjectState.Kinematics} (JSON key {@code kinematics}) — where the
     * object is, and how that was arrived at. {@code box} is the elected box — identical to the
     * sibling {@code DetectionResponse}/{@code TrackResponse} box for the same object — and always
     * present; {@code detectorBox}/{@code trackerBox}/{@code predictedBox} each say what one source
     * independently claimed this frame and are individually omitted (not zeroed) when that source
     * produced nothing this frame, the same {@code @JsonInclude(NON_NULL)} idiom the enclosing
     * record uses.
     *
     * @param box                the elected box
     * @param detectorBox        what the detector claimed this frame, or absent if none
     * @param trackerBox         what the single-object tracker claimed, or absent; FOLLOW only
     * @param predictedBox       constant-velocity extrapolation to this frame's instant, or absent
     *                           if not computed
     * @param horizonMillis      how far {@code predictedBox} was extrapolated
     * @param velocityX          normalized frame-widths per second
     * @param velocityY          normalized frame-heights per second
     * @param displacementX      box-centre delta since the previous update, normalized
     * @param displacementY      box-centre delta since the previous update, normalized
     * @param motionCompensated  whether a non-identity ego-motion transform was applied
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Kinematics(BoundingBoxResponse box, BoundingBoxResponse detectorBox, BoundingBoxResponse trackerBox,
                              BoundingBoxResponse predictedBox, long horizonMillis, double velocityX,
                              double velocityY, double displacementX, double displacementY,
                              boolean motionCompensated) {
        public static Kinematics from(ObjectState.Kinematics kinematics) {
            return new Kinematics(BoundingBoxResponse.from(kinematics.box()),
                    kinematics.detectorBox() == null ? null : BoundingBoxResponse.from(kinematics.detectorBox()),
                    kinematics.trackerBox() == null ? null : BoundingBoxResponse.from(kinematics.trackerBox()),
                    kinematics.predictedBox() == null ? null : BoundingBoxResponse.from(kinematics.predictedBox()),
                    kinematics.horizonMillis(), kinematics.velocityX(), kinematics.velocityY(),
                    kinematics.displacementX(), kinematics.displacementY(), kinematics.motionCompensated());
        }
    }

    /**
     * Wire mirror of {@link ObjectState.Belief} (JSON key {@code belief}) — how strongly the
     * platform believes the object is there at all.
     *
     * @param confidenceRaw        this frame's own detector confidence, range [0,1]
     * @param confidenceSmoothed   EMA across this identity's observations, range [0,1]
     * @param existence            [0,1] from hits/misses — survives a frame with no detection
     * @param sinceConfirmedMillis time since the last DETECTOR confirmation, not since birth
     */
    public record Belief(double confidenceRaw, double confidenceSmoothed, double existence,
                          long sinceConfirmedMillis) {
        public static Belief from(ObjectState.Belief belief) {
            return new Belief(belief.confidenceRaw(), belief.confidenceSmoothed(), belief.existence(),
                    belief.sinceConfirmedMillis());
        }
    }

    /**
     * Wire mirror of {@link ObjectState.Provenance} (JSON key {@code provenance}) — who said so,
     * this frame. {@code contributors} is the ledger's own attribution, summarized — the claims
     * themselves stay in the (not yet mirrored) ledger, never here.
     *
     * @param source       what kind of evidence produced this frame's view
     * @param contributors contributor ids that made a claim about this object this frame
     * @param assocCost    the matched pair's total cost; {@code 0} when nothing matched
     * @param reupdated    whether ORU reconstructed this track's gap this frame
     */
    public record Provenance(EvidenceSource source, List<String> contributors, double assocCost, boolean reupdated) {
        public static Provenance from(ObjectState.Provenance provenance) {
            return new Provenance(provenance.source(), provenance.contributors(), provenance.assocCost(),
                    provenance.reupdated());
        }
    }

    /**
     * Wire mirror of {@link ObjectState.MemoryFacts} (JSON key {@code memory} — the Java type is
     * named {@code MemoryFacts}, not the wire's bare {@code memory}, to avoid a generic top-level
     * DTO name; the JSON key and accessor stay {@code memory} on the enclosing record).
     * Re-acquisition facts: whether this frame recovered the identity from the dormant gallery, and
     * the evidence behind that answer.
     *
     * @param recovered          this frame re-acquired the identity from the gallery
     * @param identityConfidence the match score that justified it, range [0,1]
     * @param dormantMillis      how long it had been dormant — for a DORMANT object, still is
     * @param galleryMatches     dormant identities considered before this answer
     * @param matchDistance      best appearance distance across the gallery, range [0,1]
     */
    public record MemoryFacts(boolean recovered, double identityConfidence, long dormantMillis, int galleryMatches,
                               double matchDistance) {
        public static MemoryFacts from(ObjectState.MemoryFacts memory) {
            return new MemoryFacts(memory.recovered(), memory.identityConfidence(), memory.dormantMillis(),
                    memory.galleryMatches(), memory.matchDistance());
        }
    }

    /**
     * Wire mirror of {@link ObjectState.LockFacts} (JSON key {@code lock} — same naming rationale
     * as {@link MemoryFacts}). Whether FOLLOW is holding this object, on the object itself rather
     * than only as the frame-level {@code lockedTrackId} a renderer would otherwise have to
     * cross-reference.
     *
     * @param locked         whether FOLLOW currently holds this object
     * @param lockSeqApplied the lock generation actually in force
     */
    public record LockFacts(boolean locked, long lockSeqApplied) {
        public static LockFacts from(ObjectState.LockFacts lock) {
            return new LockFacts(lock.locked(), lock.lockSeqApplied());
        }
    }

    /**
     * Wire mirror of {@link ObjectState.Timing} (JSON key {@code timing}) — the counters death is
     * decided from.
     *
     * <p><b>The three instants are not epoch time.</b> They share the timebase of the enclosing
     * result's own {@code capturedAt} ({@code DetectionResultResponse}/{@code
     * StreamTracksResponse}) and of no other clock — see {@link ObjectState.Timing}'s own javadoc.
     * Render them as an age against that same result's {@code capturedAt}, never against a reader's
     * wall clock.
     *
     * @param firstSeenMillis     when this identity was first observed, on the result's timebase
     * @param lastSeenMillis      when this identity was last updated by any evidence, on the
     *                            result's timebase
     * @param lastConfirmedMillis when the detector last confirmed this identity, on the result's
     *                            timebase
     * @param ageFrames           frames since this identity was born
     * @param hits                confirming frames
     * @param misses              consecutive unmatched frames
     */
    public record Timing(long firstSeenMillis, long lastSeenMillis, long lastConfirmedMillis, int ageFrames,
                          int hits, int misses) {
        public static Timing from(ObjectState.Timing timing) {
            return new Timing(timing.firstSeenMillis(), timing.lastSeenMillis(), timing.lastConfirmedMillis(),
                    timing.ageFrames(), timing.hits(), timing.misses());
        }
    }
}
