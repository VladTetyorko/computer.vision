package com.drones.vision.perception.domain.model;

import com.drones.vision.kernel.BoundingBox;
import com.drones.vision.kernel.StreamId;
import java.util.List;

/**
 * One object, mirrored (docs/plans/active/CV-ORCHESTRATION-PLAN.md §4.5) — the platform's whole
 * current view of one tracked or dormant identity, grouped by facet instead of a box with facts
 * bolted on as flat scalars. Keyed by {@code (streamId, id)}; {@code id} is the same number as
 * {@link Detection}'s {@code TrackRef#trackId()}.
 *
 * <p><strong>A {@code null} nested group is not the forbidden "null means the feature is
 * off."</strong> It means this configuration does not <em>compute</em> those facts at all — e.g.
 * {@code kinematics.predictedBox()} is never populated in FOLLOW mode, where the book is never
 * whole-predicted, so the absence is honest, not a missing default. A field inside a
 * <em>present</em> group, by contrast, is always a genuine fact, including a genuine zero. There
 * is deliberately no {@code hasX()} ceremony here — a consumer checks the accessor for
 * {@code null} directly, same as every other nullable component in this package.
 *
 * <p>{@code memory}/{@code lock} are named {@link MemoryFacts}/{@link LockFacts} here, not the
 * wire's bare {@code Memory}/{@code Lock} — those two words as top-level Java type names in a
 * domain package would read as generic utility types, not "recovery facts about this object" /
 * "follow-lock facts about this object." The accessors stay {@code memory()}/{@code lock()} so
 * the JSON/wire field names this record mirrors are unchanged.
 *
 * @param id         per-stream track id, matching the {@link TrackRef#trackId()} of the {@link
 *                   Detection} this frame also carries for the same object; must be positive —
 *                   {@code 0} is the wire's untracked sentinel and must never reach the domain
 * @param lifecycle  where this identity is in its life; never {@code null}
 * @param streamId   stream this object belongs to; never {@code null}
 * @param identity   label and label-election facts, or {@code null} if not computed
 * @param kinematics box and motion facts, or {@code null} if not computed
 * @param belief     confidence/existence facts, or {@code null} if not computed
 * @param provenance which contributor(s) produced this frame's view of the object, or {@code
 *                   null} if not computed
 * @param memory     re-acquisition facts, or {@code null} if not computed
 * @param lock       FOLLOW-lock facts, or {@code null} if not computed
 * @param timing     the lifecycle counters death is decided from, or {@code null} if not computed
 */
public record ObjectState(long id, ObjectLifecycle lifecycle, StreamId streamId, Identity identity,
                           Kinematics kinematics, Belief belief, Provenance provenance, MemoryFacts memory,
                           LockFacts lock, Timing timing) {

    public ObjectState {
        if (id < 1) {
            throw new IllegalArgumentException(
                    "ObjectState id must be positive (0 is the wire's untracked sentinel): " + id);
        }
        if (lifecycle == null) {
            throw new IllegalArgumentException("ObjectState lifecycle must not be null");
        }
        if (streamId == null) {
            throw new IllegalArgumentException("ObjectState streamId must not be null");
        }
    }

    /**
     * One entry of the label-vote tally behind {@link Identity#label()} — the distribution, not
     * just the winner, so a training-capture consumer can audit an election it disagrees with.
     *
     * @param label  a candidate label; must not be {@code null}
     * @param weight decayed, confidence-weighted tally; comparable only within one frame; must be
     *               finite
     */
    public record LabelCandidate(String label, double weight) {
        public LabelCandidate {
            if (label == null) {
                throw new IllegalArgumentException("LabelCandidate label must not be null");
            }
            if (!Double.isFinite(weight)) {
                throw new IllegalArgumentException("LabelCandidate weight must be finite: " + weight);
            }
        }
    }

    /**
     * What the object is called, and how settled that name is.
     *
     * <p>{@code labelRaw} may be blank — a frame with no detector observation this frame (a pure
     * coast, or a recovery from memory) still carries the elected {@code label}, but has no raw
     * detector label of its own to report.
     *
     * @param label      elected label — hysteresis-gated, what an operator reads; must not be
     *                   {@code null}
     * @param labelRaw   this frame's own detector label, un-elected; must not be {@code null},
     *                   may be blank
     * @param candidates the tally behind the election, best-first; defensively copied, never
     *                   {@code null}
     * @param stability  consecutive elections since {@code label} last changed; must not be
     *                   negative
     */
    public record Identity(String label, String labelRaw, List<LabelCandidate> candidates, int stability) {
        public Identity {
            if (label == null) {
                throw new IllegalArgumentException("Identity label must not be null");
            }
            if (labelRaw == null) {
                throw new IllegalArgumentException("Identity labelRaw must not be null");
            }
            if (candidates == null) {
                throw new IllegalArgumentException("Identity candidates must not be null");
            }
            if (stability < 0) {
                throw new IllegalArgumentException("Identity stability must not be negative: " + stability);
            }
            candidates = List.copyOf(candidates);
        }
    }

    /**
     * Where the object is, and how that was arrived at. Three boxes are kept separate rather than
     * merged into one "current box" (the DeepStream separation, plan §4.5 R5): {@code box} is the
     * elected box — identical to the {@link Detection} box this frame also carries for the same
     * object — and the other three say what each source independently claimed, each {@code null}
     * when that source produced nothing this frame.
     *
     * @param box                the elected box; must not be {@code null}
     * @param detectorBox        what the detector claimed this frame, or {@code null} if none
     * @param trackerBox         what the single-object tracker claimed, or {@code null}; FOLLOW
     *                           only
     * @param predictedBox       constant-velocity extrapolation to this frame's instant, or
     *                           {@code null} if not computed
     * @param horizonMillis      how far {@code predictedBox} was extrapolated; must not be
     *                           negative
     * @param velocityX          normalized frame-widths per second, matching {@link
     *                           TrackRef#velocityX()}'s units; must be finite
     * @param velocityY          normalized frame-heights per second; must be finite
     * @param displacementX      box-centre delta since the previous update, normalized; must be
     *                           finite
     * @param displacementY      box-centre delta since the previous update, normalized; must be
     *                           finite
     * @param motionCompensated  whether a non-identity ego-motion transform was applied
     */
    public record Kinematics(BoundingBox box, BoundingBox detectorBox, BoundingBox trackerBox,
                              BoundingBox predictedBox, long horizonMillis, double velocityX, double velocityY,
                              double displacementX, double displacementY, boolean motionCompensated) {
        public Kinematics {
            if (box == null) {
                throw new IllegalArgumentException("Kinematics box must not be null");
            }
            if (horizonMillis < 0) {
                throw new IllegalArgumentException("Kinematics horizonMillis must not be negative: " + horizonMillis);
            }
            if (!Double.isFinite(velocityX)) {
                throw new IllegalArgumentException("Kinematics velocityX must be finite: " + velocityX);
            }
            if (!Double.isFinite(velocityY)) {
                throw new IllegalArgumentException("Kinematics velocityY must be finite: " + velocityY);
            }
            if (!Double.isFinite(displacementX)) {
                throw new IllegalArgumentException("Kinematics displacementX must be finite: " + displacementX);
            }
            if (!Double.isFinite(displacementY)) {
                throw new IllegalArgumentException("Kinematics displacementY must be finite: " + displacementY);
            }
        }
    }

    /**
     * How strongly the platform believes the object is there at all.
     *
     * @param confidenceRaw      this frame's own detector confidence, range [0,1]
     * @param confidenceSmoothed EMA across this identity's observations, range [0,1]
     * @param existence          [0,1] from hits/misses — survives a frame with no detection
     * @param sinceConfirmedMillis time since the last DETECTOR confirmation, not since birth;
     *                             must not be negative
     */
    public record Belief(double confidenceRaw, double confidenceSmoothed, double existence,
                          long sinceConfirmedMillis) {
        public Belief {
            requireUnitRange(confidenceRaw, "confidenceRaw");
            requireUnitRange(confidenceSmoothed, "confidenceSmoothed");
            requireUnitRange(existence, "existence");
            if (sinceConfirmedMillis < 0) {
                throw new IllegalArgumentException(
                        "Belief sinceConfirmedMillis must not be negative: " + sinceConfirmedMillis);
            }
        }
    }

    /**
     * Who said so, this frame. {@code contributors} is the ledger's own attribution, summarized —
     * the claims themselves stay in the ledger, never here (algorithm-shaped facts belong to the
     * ledger, not the mirror, plan §4.5 E7).
     *
     * @param source       what kind of evidence produced this frame's view; must not be {@code
     *                     null}
     * @param contributors contributor ids that made a claim about this object this frame;
     *                     defensively copied, never {@code null}
     * @param assocCost    the matched pair's total cost; {@code 0} when nothing matched; must not
     *                     be negative
     * @param reupdated    whether ORU reconstructed this track's gap this frame
     */
    public record Provenance(EvidenceSource source, List<String> contributors, double assocCost,
                              boolean reupdated) {
        public Provenance {
            if (source == null) {
                throw new IllegalArgumentException("Provenance source must not be null");
            }
            if (contributors == null) {
                throw new IllegalArgumentException("Provenance contributors must not be null");
            }
            if (assocCost < 0) {
                throw new IllegalArgumentException("Provenance assocCost must not be negative: " + assocCost);
            }
            contributors = List.copyOf(contributors);
        }
    }

    /**
     * Re-acquisition facts. Today's wire carries {@code identity_confidence}/{@code
     * dormant_millis} per detection, but the JSON republishes them only for the FOLLOW-locked
     * track (plan §4.5 D6); here they belong to the object itself, whoever is watching it.
     *
     * @param recovered          this frame re-acquired the identity from the gallery
     * @param identityConfidence the match score that justified it, range [0,1]
     * @param dormantMillis      how long it had been dormant — for a DORMANT object, still is;
     *                           must not be negative
     * @param galleryMatches     dormant identities considered before this answer; must not be
     *                           negative
     * @param matchDistance      best appearance distance across the gallery, range [0,1]
     */
    public record MemoryFacts(boolean recovered, double identityConfidence, long dormantMillis, int galleryMatches,
                               double matchDistance) {
        public MemoryFacts {
            requireUnitRange(identityConfidence, "identityConfidence");
            if (dormantMillis < 0) {
                throw new IllegalArgumentException(
                        "MemoryFacts dormantMillis must not be negative: " + dormantMillis);
            }
            if (galleryMatches < 0) {
                throw new IllegalArgumentException(
                        "MemoryFacts galleryMatches must not be negative: " + galleryMatches);
            }
            requireUnitRange(matchDistance, "matchDistance");
        }
    }

    /**
     * Whether FOLLOW is holding this object. The frame-level {@code locked_track_id} on the wire
     * says <em>which</em> object; this says so on the object itself, the only form a renderer can
     * use without a second lookup.
     *
     * @param locked          whether FOLLOW currently holds this object
     * @param lockSeqApplied  the lock generation actually in force; must not be negative
     */
    public record LockFacts(boolean locked, long lockSeqApplied) {
        public LockFacts {
            if (lockSeqApplied < 0) {
                throw new IllegalArgumentException(
                        "LockFacts lockSeqApplied must not be negative: " + lockSeqApplied);
            }
        }
    }

    /**
     * The counters death is decided from (plan §4.5 E4).
     *
     * <p><b>The three instants are not epoch time.</b> They share the timebase of the response's
     * own {@code timestamp_millis} and of no other clock: cv-service ages tracks on
     * {@code time.monotonic()} and rebases them once, at the wire, onto whatever timestamp that
     * frame carries. Under {@code DetectStream} that anchor is this JVM's clock (it stamped the
     * request), under {@code DetectPulled} it is the service host's. Compare them against
     * {@link DetectionResult#capturedAt()} of the result they arrived on, never against a reader's
     * wall clock. The durations elsewhere on this record family ({@code horizonMillis},
     * {@code sinceConfirmedMillis}, {@code dormantMillis}) carry no timebase at all and need no
     * such care.
     *
     * <p>The non-negative checks below therefore assume that anchor is a real epoch stamp, which
     * it is on every live path ({@code DetectionFrameCodec#encode} always stamps the frame's
     * capture time). A caller that sent {@code 0} would rebase these into the negative and this
     * constructor would reject that object — which is the intended outcome, and why the codec
     * drops the single offending object with a warning rather than letting it fail the frame.
     *
     * @param firstSeenMillis     when this identity was first observed, on the response's timebase;
     *                            must not be negative
     * @param lastSeenMillis      when this identity was last updated by any evidence, on the
     *                            response's timebase; must not be negative
     * @param lastConfirmedMillis when the detector last confirmed this identity, on the response's
     *                            timebase; must not be negative
     * @param ageFrames           frames since this identity was born; must not be negative
     * @param hits                confirming frames; must not be negative
     * @param misses              consecutive unmatched frames; must not be negative
     */
    public record Timing(long firstSeenMillis, long lastSeenMillis, long lastConfirmedMillis, int ageFrames,
                          int hits, int misses) {
        public Timing {
            if (firstSeenMillis < 0) {
                throw new IllegalArgumentException("Timing firstSeenMillis must not be negative: " + firstSeenMillis);
            }
            if (lastSeenMillis < 0) {
                throw new IllegalArgumentException("Timing lastSeenMillis must not be negative: " + lastSeenMillis);
            }
            if (lastConfirmedMillis < 0) {
                throw new IllegalArgumentException(
                        "Timing lastConfirmedMillis must not be negative: " + lastConfirmedMillis);
            }
            if (ageFrames < 0) {
                throw new IllegalArgumentException("Timing ageFrames must not be negative: " + ageFrames);
            }
            if (hits < 0) {
                throw new IllegalArgumentException("Timing hits must not be negative: " + hits);
            }
            if (misses < 0) {
                throw new IllegalArgumentException("Timing misses must not be negative: " + misses);
            }
        }
    }

    private static void requireUnitRange(double value, String name) {
        if (Double.isNaN(value) || value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException("ObjectState " + name + " must be within [0,1]: " + value);
        }
    }
}
