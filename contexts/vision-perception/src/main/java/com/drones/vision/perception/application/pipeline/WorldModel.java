package com.drones.vision.perception.application.pipeline;

import com.drones.vision.kernel.BoundingBox;
import com.drones.vision.perception.domain.model.Detection;
import com.drones.vision.perception.domain.model.DetectionEventId;
import com.drones.vision.perception.domain.model.DetectionResult;
import com.drones.vision.perception.domain.model.DetectionSource;
import com.drones.vision.perception.domain.model.FollowState;
import com.drones.vision.perception.domain.model.FollowStatus;
import com.drones.vision.perception.domain.model.ObjectLifecycle;
import com.drones.vision.perception.domain.model.ObjectState;
import com.drones.vision.perception.domain.model.RenderTier;
import com.drones.vision.perception.domain.model.TargetLock;
import com.drones.vision.perception.domain.model.TrackRef;
import com.drones.vision.perception.domain.model.TrackState;
import com.drones.vision.perception.domain.model.TrackedObject;
import com.drones.vision.perception.domain.model.TrackingTelemetry;
import com.drones.vision.perception.domain.model.WorldObject;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * The per-stream <b>world model</b> (docs/plans/active/CV-ORCHESTRATION-PLAN.md &sect;4.6): the
 * single owner of object state this pipeline used to split across three peers — {@code TrackBook}
 * (track lifetimes for {@link #tracks()}), {@code FollowTracker} (the {@code FOLLOW} lock's
 * lifecycle for {@link #followStatus()}) and {@code DetectionExtrapolator} (dead code: its query
 * method had no production caller, plan &sect;7 defect D4) — folds into one class instead. The
 * first two behaviors are ported here <b>unchanged</b>: {@link #tracks()} and {@link
 * #followStatus()} answer exactly what {@code TrackBook#tracks()}/{@code FollowTracker#status()}
 * used to. What is new is {@link #objects()}: a {@link WorldObject} per tracked-or-dormant
 * identity, folding this frame's {@link ObjectState} wire mirror together with two relations this
 * platform owns on top of it — {@code operator} (follow, deny) and {@code render} (which tier a
 * viewer should draw it at) — plus {@code event}, a lookup into {@link DetectionEventEngine}.
 *
 * <h2>Render tier</h2>
 * {@link #objects()}' {@code render.tier} is the server-side port of {@code
 * station/vision-web/src/app/shared/player/detection-overlay-logic.ts}'s {@code detectionTiers}
 * (research &sect;3.2): {@code T0} is the {@code FOLLOW}-locked object ({@link ObjectState.LockFacts#locked()}),
 * checked first and unconditionally; everything under {@link RenderTierSettings#subScaleFraction()}
 * on both box axes is {@code T3}; of what remains, a moving object ({@link
 * RenderTierSettings#movingDisplacementThreshold()}) or one of the top {@link
 * RenderTierSettings#notableTopK()} by (box area &times; confidence) is {@code T1}; everything else
 * is {@code T2}. Two deviations from the client algorithm are deliberate, not omissions:
 * <ul>
 *   <li><b>No hover / class-hover promotion.</b> The client's {@code hoveredDetection}/{@code
 *       hoveredClass} are pointer state a browser has and a server does not — there is nothing here
 *       to promote to {@code T0}/notable on that basis. A viewer that wants hover emphasis still
 *       applies it client-side, on top of the tier this class assigns.</li>
 *   <li><b>{@link RenderTierSettings#subScaleFraction()}, not CSS pixels.</b> The client compares
 *       {@code detection.box.width * contentWidthPx} against {@code SUB_SCALE_PX = 12}, a
 *       viewport-relative pixel count this class has no viewport to convert against — {@link
 *       ObjectState.Kinematics#box()} is normalized {@code [0,1]}, so the threshold is normalized
 *       too, chosen to approximate the client's constant at a typical letterboxed size (see {@link
 *       RenderTierSettings}'s own javadoc).</li>
 * </ul>
 * A third, narrower deviation: "moving" compares {@link ObjectState.Kinematics#displacementX()}/
 * {@link ObjectState.Kinematics#displacementY()} — the box-centre delta <i>since the previous
 * update</i>, already computed upstream — rather than re-deriving the client's own trail-window
 * integration from a point history this class does not keep. The same threshold constant is reused
 * for both: a one-frame delta and a short window's net displacement answer the same underlying
 * question ("did this box actually move, or is it detector jitter on a parked object") closely
 * enough that a second, independently-tuned constant would be false precision.
 *
 * <p>An object with no {@link ObjectState#kinematics()} (not computed for this frame's
 * configuration) is never sub-scale and never moving — there is no box to measure or displace —
 * and scores {@code 0} in the top-K ranking, so it settles into {@code T2} rather than being
 * promoted or demoted on data this class does not have.
 *
 * <h2>Suppressed (label-denied) objects</h2>
 * {@link StreamPipeline#applyLabelFilters} drops a label-denied object from {@link
 * DetectionResult#objects()} entirely before this class ever sees it — exactly like it drops the
 * matching {@link Detection}. {@link #accept} is additionally told <i>which</i> ids were dropped
 * this frame ({@code suppressedIds}, from {@link StreamPipeline}'s own diff of the raw and filtered
 * results) so that, for an id this model already knew about, it can flip that {@link WorldObject}'s
 * {@code operator.denied} to {@code true} and its {@code render.tier} to {@link RenderTier#HIDDEN}
 * instead of letting the object silently vanish. This is the behavior change {@link WorldObject}'s
 * own javadoc names: {@code HIDDEN} "exists in the world model only" — an operator who denies
 * "car" mid-stream still has this model's own bookkeeping know a car is there, even though every
 * viewer read model must now skip it. An id suppressed <i>before</i> this model ever saw a full
 * {@link ObjectState} for it is not added — there is no state to keep, and inventing one would be a
 * fact this platform does not have. A suppressed id's {@code lastSeenAt} bookkeeping is
 * deliberately <b>not</b> refreshed (see "Expiry" below): denial does not pin an object in memory
 * forever, it only hides it while it would otherwise still be considered live.
 *
 * <h2>Event link is one frame behind, on purpose</h2>
 * {@link WorldObject.EventLink#openEventId()} is resolved by calling back into {@link
 * DetectionEventEngine#openEventId} <b>before</b> {@link StreamPipeline#onDetectionResult} feeds
 * this same result to {@link DetectionEventEngine#accept} — {@link #accept} runs where {@code
 * trackBook.accept}/{@code followTracker.accept} used to, ahead of {@code eventEngine.accept} in
 * that method's fixed order (docs/plans/active/cv-orchestration/R2-backend-control-plane.md
 * &sect;3, carried forward verbatim). That means every {@code openEventId} this class reports
 * reflects whichever label states {@link DetectionEventEngine} had <i>after the previous frame</i>,
 * not this one. <b>This is not a bug to fix by reordering.</b> R2 &sect;3 freezes {@code
 * onDetectionResult}'s live-plane-before-durable-I/O order deliberately (a real regression,
 * {@code TrackingAssociateE2ETest} losing a detector pass, is what pinned it); moving {@link
 * DetectionEventEngine#accept} ahead of this call would fix the one-frame lag at the cost of
 * reopening that regression, for a lag that is already invisible at the polling/SSE cadence any
 * consumer of {@link #objects()} reads at. Inventing the event fact here instead — predicting
 * whether this frame's own qualifying streak will open an event before {@link
 * DetectionEventEngine} itself has decided that — was considered and rejected for the same reason
 * {@link RenderTier} does not invent hover state: this class mirrors facts it can look up, it does
 * not forecast ones it can't.
 *
 * <h2>Expiry</h2>
 * Two rules, evaluated at the end of every {@link #accept}, identical in spirit to {@code
 * TrackBook}'s own (see that class's history for why): an object whose {@link
 * ObjectState#lifecycle()} is {@link ObjectLifecycle#LOST} is dropped immediately — {@link
 * ObjectLifecycle#DORMANT}, by contrast, is <b>not</b> terminal, since a dormant identity is still
 * recoverable and this model's whole point is to keep knowing about it; and any id not refreshed
 * for {@code trackRetention}, measured against the newest {@link DetectionResult#capturedAt()}
 * this model has seen, is dropped on plain silence. {@link #tracks()}' own booking uses the exact
 * same two rules against {@link TrackRef#state()} instead, since that read model is untouched by
 * this wave.
 *
 * <h2>Time</h2>
 * Every timestamp comes from {@link DetectionResult#capturedAt()}, never wall-clock time — the
 * timebase {@code TrackBook}/{@code FollowTracker}/{@link DetectionEventEngine} already share. No
 * clock is injected because none is needed.
 *
 * <h2>Threading</h2>
 * Every method is {@code synchronized}: {@link #accept} runs on whichever executor thread completed
 * an inference, while every read method is read from an unrelated HTTP/SSE thread. {@link
 * #snapshot()} exists so a caller that needs more than one read model at once gets them from a
 * single critical section instead of three, each of which could observe a different {@link
 * #accept} landing in between.
 */
final class WorldModel {

    /**
     * Default follow-memory re-acquisition window — {@code FollowTracker}'s own former default,
     * mirroring cv-service's own ({@code session.py}/{@code memory.py:83}, docs/plans/active/
     * TRACK-FOLLOW-PLAN.md &sect;3.1). Not yet wired to a per-stream override; see that plan's own
     * note on why the constant, not a hardcoded literal, is still the honest current answer.
     */
    static final Duration DEFAULT_MEMORY_TTL = Duration.ofSeconds(30);

    private final Duration trackRetention;
    private final Duration followMemoryTtl;
    private final RenderTierSettings renderTierSettings;
    private final Function<String, DetectionEventId> openEventLookup;

    // --- ported from TrackBook, unchanged behavior: tracks() -------------------------------------
    private final Map<Long, TrackedObject> byTrackId = new HashMap<>();

    /** The newest {@link DetectionResult#capturedAt()} accepted so far — this model's notion of "now". */
    private Instant latestObservedAt;

    // --- ported from FollowTracker, unchanged behavior: followStatus()/lockRequested() -----------
    private FollowStatus followStatus;
    private boolean followRequestPending;
    private boolean followLockIsTrackIdForm;

    // --- new: the WorldObject fold, objects() -----------------------------------------------------
    private final Map<Long, WorldObject> byWorldId = new HashMap<>();
    private final Map<Long, Instant> worldLastSeenAt = new HashMap<>();

    // --- latest-filtered-result mirrors, latestDetections()/latestObjects() ------------------------
    private List<Detection> latestDetections = List.of();
    private List<ObjectState> latestObjects = List.of();

    /**
     * @param trackRetention      how long a track/object that stops arriving stays known before
     *                            being expired, measured against the newest observed {@code
     *                            capturedAt}; must be positive. The same value governs both {@link
     *                            #tracks()}'s booking and {@link #objects()}'s own — there is one
     *                            "how long is silence still recent" answer, not two
     * @param followMemoryTtl     how long after {@code lastSeenAt} a {@link FollowState#LOST} bind
     *                            is still {@link FollowStatus#reacquirable()}; must be positive
     * @param renderTierSettings  tunables for the render-tier port; must not be {@code null}
     * @param openEventLookup     resolves a label to the id of the {@link
     *                            com.drones.vision.perception.domain.model.DetectionEvent}
     *                            currently open for it, or {@code null} if none — {@link
     *                            DetectionEventEngine#openEventId} wrapped by {@link
     *                            StreamPipeline}'s own construction, or a constant {@code
     *                            label -> null} function when no engine is wired; must not be
     *                            {@code null} itself
     */
    WorldModel(Duration trackRetention, Duration followMemoryTtl, RenderTierSettings renderTierSettings,
               Function<String, DetectionEventId> openEventLookup) {
        Objects.requireNonNull(trackRetention, "trackRetention must not be null");
        if (trackRetention.isZero() || trackRetention.isNegative()) {
            throw new IllegalArgumentException("trackRetention must be positive, was " + trackRetention);
        }
        Objects.requireNonNull(followMemoryTtl, "followMemoryTtl must not be null");
        if (followMemoryTtl.isZero() || followMemoryTtl.isNegative()) {
            throw new IllegalArgumentException("followMemoryTtl must be positive, was " + followMemoryTtl);
        }
        this.trackRetention = trackRetention;
        this.followMemoryTtl = followMemoryTtl;
        this.renderTierSettings = Objects.requireNonNull(renderTierSettings, "renderTierSettings must not be null");
        this.openEventLookup = Objects.requireNonNull(openEventLookup, "openEventLookup must not be null");
    }

    /**
     * Folds one completed, already label-filtered result into every read model this class owns.
     *
     * @param filtered      the label-filtered result (see {@link StreamPipeline#applyLabelFilters});
     *                      never {@code null}
     * @param suppressedIds ids of every {@link ObjectState} the label filter dropped from {@code
     *                      filtered} this frame (see {@link StreamPipeline}'s diff of the raw and
     *                      filtered results); never {@code null}, empty when nothing was suppressed
     */
    synchronized void accept(DetectionResult filtered, List<ObjectState> suppressed) {
        Objects.requireNonNull(filtered, "filtered must not be null");
        Objects.requireNonNull(suppressed, "suppressed must not be null");
        Instant at = filtered.capturedAt();
        if (latestObservedAt == null || at.isAfter(latestObservedAt)) {
            latestObservedAt = at;
        }
        this.latestDetections = filtered.detections();
        this.latestObjects = filtered.objects();

        bookTracks(filtered, at);
        acceptFollow(filtered, at);
        foldWorldObjects(filtered, suppressed, at);

        expireTracks();
        expireWorldObjects();
    }

    // --- TrackBook, ported verbatim -----------------------------------------------------------

    private void bookTracks(DetectionResult result, Instant at) {
        for (Detection detection : result.detections()) {
            TrackRef track = detection.track();
            if (track != null) {
                book(track.trackId(), detection, at);
            }
        }
    }

    private void book(long trackId, Detection detection, Instant at) {
        TrackedObject existing = byTrackId.get(trackId);
        if (existing == null) {
            byTrackId.put(trackId, new TrackedObject(trackId, detection, at, at));
        } else if (!at.isBefore(existing.lastSeen())) {
            byTrackId.put(trackId, new TrackedObject(trackId, detection, existing.firstSeen(), at));
        } else if (at.isBefore(existing.firstSeen())) {
            // Out-of-order straggler that predates this track's booked lifetime: it widens the
            // lifetime but must not replace the fresher observation already booked.
            byTrackId.put(trackId, new TrackedObject(trackId, existing.detection(), at, existing.lastSeen()));
        }
    }

    private void expireTracks() {
        Instant staleBefore = latestObservedAt.minus(trackRetention);
        for (Iterator<TrackedObject> it = byTrackId.values().iterator(); it.hasNext(); ) {
            TrackedObject tracked = it.next();
            TrackRef track = tracked.detection().track();
            boolean lost = track != null && track.state() == TrackState.LOST;
            if (lost || !tracked.lastSeen().isAfter(staleBefore)) {
                it.remove();
            }
        }
    }

    // --- FollowTracker, ported verbatim -------------------------------------------------------

    private void acceptFollow(DetectionResult result, Instant at) {
        TrackingTelemetry telemetry = result.tracking();
        if (telemetry == null) {
            return;
        }
        long lockedTrackId = telemetry.lockedTrackId();
        if (lockedTrackId == 0L) {
            acceptFollowUnbound(at);
        } else {
            acceptFollowBound(result.detections(), lockedTrackId, at);
        }
    }

    private void acceptFollowUnbound(Instant at) {
        if (followRequestPending) {
            if (followStatus == null || followStatus.state() != FollowState.REQUESTING) {
                followStatus = new FollowStatus(FollowState.REQUESTING, 0L, "", at, null, null, false, 0L, 0.0);
            }
            return;
        }
        if (followStatus == null) {
            return; // never requested, or already released: nothing to report
        }
        if (followStatus.state() == FollowState.LOST) {
            // still lost: only the age-derived reacquirable flag may have moved
            followStatus = new FollowStatus(FollowState.LOST, followStatus.trackId(), followStatus.label(),
                    followStatus.since(), followStatus.lastSeenAt(), followStatus.lastBox(),
                    computeReacquirable(followStatus.lastSeenAt(), at), followStatus.recoveredAfterMillis(),
                    followStatus.recoveryConfidence());
            return;
        }
        // was HOLDING/COASTING and just dropped with no pending re-request: freshly lost. Freeze
        // lastSeenAt/lastBox/label at their bound value (D3/D5) -- only `since`/`state` change here.
        followStatus = new FollowStatus(FollowState.LOST, followStatus.trackId(), followStatus.label(), at,
                followStatus.lastSeenAt(), followStatus.lastBox(),
                computeReacquirable(followStatus.lastSeenAt(), at), followStatus.recoveredAfterMillis(),
                followStatus.recoveryConfidence());
    }

    private void acceptFollowBound(List<Detection> detections, long lockedTrackId, Instant at) {
        followRequestPending = false;
        Detection matched = findDetection(detections, lockedTrackId);
        FollowState previousState = followStatus == null ? null : followStatus.state();
        boolean continuedSameBind = (previousState == FollowState.HOLDING || previousState == FollowState.COASTING)
                && followStatus.trackId() == lockedTrackId;

        FollowState state;
        if (matched != null) {
            state = matched.track().source() == DetectionSource.DETECTOR ? FollowState.HOLDING : FollowState.COASTING;
        } else if (continuedSameBind) {
            // No fresh geometry this frame (e.g. this bind's label was just deny-filtered away by
            // StreamPipeline.applyLabelFilters) -- stay in whichever state was already true rather
            // than fabricating one.
            state = previousState;
        } else {
            state = FollowState.COASTING;
        }

        String label = matched != null ? matched.label() : (continuedSameBind ? followStatus.label() : "");
        Instant lastSeenAt = matched != null ? at : (continuedSameBind ? followStatus.lastSeenAt() : null);
        BoundingBox lastBox = matched != null ? matched.box() : (continuedSameBind ? followStatus.lastBox() : null);
        Instant since = (!continuedSameBind || state != previousState) ? at : followStatus.since();

        long recoveredAfterMillis;
        double recoveryConfidence;
        if (continuedSameBind) {
            recoveredAfterMillis = followStatus.recoveredAfterMillis();
            recoveryConfidence = followStatus.recoveryConfidence();
        } else if (matched != null && matched.track().identityConfidence() > 0.0) {
            recoveredAfterMillis = matched.track().dormantMillis();
            recoveryConfidence = matched.track().identityConfidence();
        } else {
            recoveredAfterMillis = 0L;
            recoveryConfidence = 0.0;
        }

        followStatus = new FollowStatus(state, lockedTrackId, label, since, lastSeenAt, lastBox, false,
                recoveredAfterMillis, recoveryConfidence);
    }

    private static Detection findDetection(List<Detection> detections, long trackId) {
        for (Detection detection : detections) {
            TrackRef track = detection.track();
            if (track != null && track.trackId() == trackId) {
                return detection;
            }
        }
        return null;
    }

    private boolean computeReacquirable(Instant lastSeenAt, Instant at) {
        if (lastSeenAt == null || !followLockIsTrackIdForm) {
            return false;
        }
        return Duration.between(lastSeenAt, at).toMillis() < followMemoryTtl.toMillis();
    }

    /**
     * Registers a genuine lock action — called only when the resolved lock actually changed. See
     * {@code FollowTracker#lockRequested}'s former javadoc for the full state-machine rationale,
     * ported unchanged.
     *
     * @param lock the newly folded lock; never {@code null}
     */
    synchronized void lockRequested(TargetLock lock) {
        Objects.requireNonNull(lock, "lock must not be null");
        if (lock.release()) {
            followStatus = null;
            followRequestPending = false;
            followLockIsTrackIdForm = false;
            return;
        }
        followRequestPending = true;
        followLockIsTrackIdForm = lock.trackId() != null;
    }

    // --- WorldObject fold, new in this wave -----------------------------------------------------

    private void foldWorldObjects(DetectionResult filtered, List<ObjectState> suppressed, Instant at) {
        Map<Long, RenderTier> tiers = computeRenderTiers(filtered.objects());
        for (ObjectState state : filtered.objects()) {
            long id = state.id();
            worldLastSeenAt.put(id, at);
            RenderTier tier = tiers.getOrDefault(id, RenderTier.T2);
            boolean followedNow = state.lock() != null && state.lock().locked();
            FollowState followFacet = null;
            if (followedNow && followStatus != null && followStatus.trackId() == id) {
                followFacet = followStatus.state();
            }
            DetectionEventId openEventId = state.identity() == null ? null : openEventLookup.apply(state.identity().label());
            WorldObject.Operator operator = new WorldObject.Operator(followedNow, false, followFacet);
            WorldObject.EventLink event = new WorldObject.EventLink(openEventId);
            WorldObject.Render render = new WorldObject.Render(tier);
            byWorldId.put(id, new WorldObject(state, operator, event, render));
        }
        for (ObjectState state : suppressed) {
            long id = state.id();
            // The denied object's OWN state this frame, not the last one that got through the
            // filter: an object denied from its very first frame has no earlier state to fall back
            // on, and a trace that could not show it at all would answer "why is this box not
            // drawn?" with silence -- the one question render.tier exists to answer.
            worldLastSeenAt.put(id, at);
            WorldObject.EventLink event = new WorldObject.EventLink(null);
            WorldObject.Operator deniedOperator = new WorldObject.Operator(false, true, null);
            WorldObject.Render hidden = new WorldObject.Render(RenderTier.HIDDEN);
            byWorldId.put(id, new WorldObject(state, deniedOperator, event, hidden));
        }
    }

    private Map<Long, RenderTier> computeRenderTiers(List<ObjectState> objects) {
        Map<Long, RenderTier> tiers = new HashMap<>();
        List<ObjectState> remainder = new ArrayList<>();
        for (ObjectState state : objects) {
            if (state.lock() != null && state.lock().locked()) {
                tiers.put(state.id(), RenderTier.T0);
            } else {
                remainder.add(state);
            }
        }

        List<ObjectState> subScale = new ArrayList<>();
        List<ObjectState> sizeEligible = new ArrayList<>();
        for (ObjectState state : remainder) {
            (isSubScale(state) ? subScale : sizeEligible).add(state);
        }
        for (ObjectState state : subScale) {
            tiers.put(state.id(), RenderTier.T3);
        }

        Set<Long> notable = new HashSet<>();
        for (ObjectState state : sizeEligible) {
            if (isMoving(state)) {
                notable.add(state.id());
            }
        }
        List<ObjectState> ranked = sizeEligible.stream()
                .filter(state -> !notable.contains(state.id()))
                .sorted(Comparator.comparingDouble(this::score).reversed())
                .toList();
        for (ObjectState state : ranked) {
            if (notable.size() >= renderTierSettings.notableTopK()) {
                break;
            }
            notable.add(state.id());
        }

        for (ObjectState state : sizeEligible) {
            tiers.put(state.id(), notable.contains(state.id()) ? RenderTier.T1 : RenderTier.T2);
        }
        return tiers;
    }

    private boolean isSubScale(ObjectState state) {
        if (state.kinematics() == null) {
            return false;
        }
        BoundingBox box = state.kinematics().box();
        return box.width() < renderTierSettings.subScaleFraction() && box.height() < renderTierSettings.subScaleFraction();
    }

    private boolean isMoving(ObjectState state) {
        if (state.kinematics() == null) {
            return false;
        }
        double dx = state.kinematics().displacementX();
        double dy = state.kinematics().displacementY();
        return Math.hypot(dx, dy) >= renderTierSettings.movingDisplacementThreshold();
    }

    private double score(ObjectState state) {
        if (state.kinematics() == null) {
            return 0.0;
        }
        double area = state.kinematics().box().width() * state.kinematics().box().height();
        double confidence = state.belief() == null ? 0.0 : state.belief().confidenceRaw();
        return area * confidence;
    }

    private void expireWorldObjects() {
        Instant staleBefore = latestObservedAt.minus(trackRetention);
        for (Iterator<Map.Entry<Long, WorldObject>> it = byWorldId.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Long, WorldObject> entry = it.next();
            WorldObject object = entry.getValue();
            Instant lastSeenAt = worldLastSeenAt.get(entry.getKey());
            boolean lost = object.state().lifecycle() == ObjectLifecycle.LOST;
            boolean stale = lastSeenAt == null || !lastSeenAt.isAfter(staleBefore);
            if (lost || stale) {
                it.remove();
                worldLastSeenAt.remove(entry.getKey());
            }
        }
    }

    // --- read models -----------------------------------------------------------------------------

    /** @see StreamPipeline#latestDetections() */
    synchronized List<Detection> latestDetections() {
        return latestDetections;
    }

    /** @see StreamPipeline#latestObjects() */
    synchronized List<ObjectState> latestObjects() {
        return latestObjects;
    }

    /**
     * @return every track currently booked for this stream, ordered by {@code trackId} ascending —
     *         byte-identical contract to {@code TrackBook#tracks()}.
     */
    synchronized List<TrackedObject> tracks() {
        List<TrackedObject> snapshot = new ArrayList<>(byTrackId.values());
        snapshot.sort(Comparator.comparingLong(TrackedObject::trackId));
        return List.copyOf(snapshot);
    }

    /**
     * @return the current state of whichever {@code FOLLOW} lock this stream's operator holds, or
     *         {@link Optional#empty()} if none has ever been issued or the most recent action was a
     *         release — byte-identical contract to {@code FollowTracker#status()}.
     */
    synchronized Optional<FollowStatus> followStatus() {
        return Optional.ofNullable(followStatus);
    }

    /**
     * @return every object this model currently owns, ordered by {@link ObjectState#id()}
     *         ascending — including a suppressed (label-denied) object, whose {@code render.tier}
     *         is {@link RenderTier#HIDDEN} (see this class's "Suppressed" section). Never {@code
     *         null}, empty before the first result arrives.
     */
    synchronized List<WorldObject> objects() {
        List<WorldObject> snapshot = new ArrayList<>(byWorldId.values());
        snapshot.sort(Comparator.comparingLong(w -> w.state().id()));
        return List.copyOf(snapshot);
    }

    /** @return {@link #objects()}, {@link #tracks()} and {@link #followStatus()}, read as one atomic snapshot. */
    synchronized WorldFold snapshot() {
        return new WorldFold(objects(), tracks(), followStatus());
    }

    /**
     * Empties every read model this class owns. Called by {@link StreamPipeline} on a model re-arm
     * or a detection-gate close, for the same reason {@code TrackBook#clear()}/{@code
     * FollowTracker#clear()} used to be: whatever this described was this pipeline's previous
     * state, not its next one.
     */
    synchronized void clear() {
        byTrackId.clear();
        latestObservedAt = null;
        followStatus = null;
        followRequestPending = false;
        followLockIsTrackIdForm = false;
        byWorldId.clear();
        worldLastSeenAt.clear();
        latestDetections = List.of();
        latestObjects = List.of();
    }
}
