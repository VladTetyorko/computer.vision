package com.drones.vision.perception.domain.model;

/**
 * One tracked or dormant identity, as this platform currently sees it (docs/plans/active/
 * CV-ORCHESTRATION-PLAN.md §4.6). {@link #state()} is the wire mirror, carried
 * <strong>verbatim</strong>; {@link #operator()}, {@link #event()} and {@link #render()} are
 * relations this platform owns on top of it — cv-service must never be told about them
 * (docs/extracts/TRACKING-ORCHESTRATION.md §6 rule 3 / invariant P3: no consumer-shaped field
 * goes back into the pipeline).
 *
 * @param state    the domain mirror of cv-service's own view of this object; must not be
 *                 {@code null}
 * @param operator operator-owned relations to this object (follow, deny); must not be {@code
 *                 null}
 * @param event    which {@link DetectionEvent}, if any, is currently open for this object's
 *                 label; must not be {@code null}
 * @param render   how a viewer should render this object; must not be {@code null}
 */
public record WorldObject(ObjectState state, Operator operator, EventLink event, Render render) {

    public WorldObject {
        if (state == null) {
            throw new IllegalArgumentException("WorldObject state must not be null");
        }
        if (operator == null) {
            throw new IllegalArgumentException("WorldObject operator must not be null");
        }
        if (event == null) {
            throw new IllegalArgumentException("WorldObject event must not be null");
        }
        if (render == null) {
            throw new IllegalArgumentException("WorldObject render must not be null");
        }
    }

    /**
     * The operator's own relationship to this object — never a cv-service concern.
     *
     * @param followed whether the operator currently follows this object
     * @param denied   whether this object's label is deny-filtered
     * @param follow   this lock's {@link FollowState}, or {@code null} — {@code null} is an
     *                 <strong>absence of a relation</strong>: this object is not the follow
     *                 target, not a disabled feature (CLAUDE.md rule 10)
     */
    public record Operator(boolean followed, boolean denied, FollowState follow) {
    }

    /**
     * Which debounced occurrence, if any, this object's label currently belongs to.
     *
     * @param openEventId the open {@link DetectionEvent}'s id, or {@code null} — {@code null} is
     *                     an <strong>absence of a relation</strong>: no event is currently open
     *                     for this object's label, not a disabled feature (CLAUDE.md rule 10)
     */
    public record EventLink(DetectionEventId openEventId) {
    }

    /**
     * How a viewer should render this object.
     *
     * @param tier the render tier; must not be {@code null}
     */
    public record Render(RenderTier tier) {
        public Render {
            if (tier == null) {
                throw new IllegalArgumentException("Render tier must not be null");
            }
        }
    }
}
