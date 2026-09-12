package com.drones.vision.perception.application.pipeline;

import com.drones.vision.perception.domain.model.FollowStatus;
import com.drones.vision.perception.domain.model.TrackedObject;
import com.drones.vision.perception.domain.model.WorldObject;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One atomic snapshot of everything {@link WorldModel} currently knows about a stream — {@link
 * #objects()}, {@link #tracks()} and {@link #follow()} taken together under one lock, rather than
 * three separate {@code synchronized} calls that could each observe a different {@link
 * WorldModel#accept} landing in between them. Package-private: nothing outside this package needs
 * a torn-free view of all three at once today, but a future read model (the {@code cv-trace} SSE
 * topic, docs/plans/active/CV-ORCHESTRATION-PLAN.md &sect;4.6) will, and {@link WorldModel#snapshot()}
 * is the seam that already exists for it rather than one more three-call race to fix later.
 *
 * @param objects every object {@link WorldModel} currently owns, in {@link WorldModel#objects()}'s
 *                own order; defensively copied, never {@code null}
 * @param tracks  {@link WorldModel#tracks()}'s own value at the same instant; defensively copied,
 *                never {@code null}
 * @param follow  {@link WorldModel#followStatus()}'s own value at the same instant; never {@code
 *                null} (an absent lock is {@link Optional#empty()}, not a {@code null} record)
 */
record WorldFold(List<WorldObject> objects, List<TrackedObject> tracks, Optional<FollowStatus> follow) {

    WorldFold {
        Objects.requireNonNull(objects, "objects must not be null");
        Objects.requireNonNull(tracks, "tracks must not be null");
        Objects.requireNonNull(follow, "follow must not be null");
        objects = List.copyOf(objects);
        tracks = List.copyOf(tracks);
    }
}
