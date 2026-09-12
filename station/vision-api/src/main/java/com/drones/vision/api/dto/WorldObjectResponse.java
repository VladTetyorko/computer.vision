package com.drones.vision.api.dto;

import com.drones.vision.perception.domain.model.FollowState;
import com.drones.vision.perception.domain.model.RenderTier;
import com.drones.vision.perception.domain.model.WorldObject;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Wire mirror of {@link WorldObject} (docs/plans/active/CV-ORCHESTRATION-PLAN.md §4.6, wave W2.8)
 * — {@link #state()} plus the operator/event/render relations this platform owns on top of it.
 * Backs the two live TRACKS surfaces ({@code GET /api/streams/{streamId}/tracks}'s {@code objects}
 * and the {@code tracks:<assetId>} SSE topic) and {@code CvTraceResponse#world}; deliberately
 * <b>not</b> used for {@code DetectionResultResponse#objects}/the {@code detections:<assetId>}
 * topic/the durable path — those stay the flat {@link ObjectStateResponse} mirror W1 shipped,
 * because a class W3 can move server-side (the tier decision) belongs on the surfaces an operator's
 * viewer actually renders from, not on the surface cv-service's own contract is measured against.
 *
 * @param state    the wire mirror of cv-service's own view of this object, carried verbatim
 * @param operator operator-owned relations to this object (follow, deny)
 * @param event    which detection event, if any, is currently open for this object's label
 * @param render   how a viewer should render this object
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WorldObjectResponse(ObjectStateResponse state, Operator operator, EventLink event, Render render) {

    /**
     * Maps a domain {@link WorldObject} to its wire representation.
     *
     * @param object the object to map
     * @return the response body for {@code object}
     */
    public static WorldObjectResponse from(WorldObject object) {
        return new WorldObjectResponse(ObjectStateResponse.from(object.state()), Operator.from(object.operator()),
                EventLink.from(object.event()), Render.from(object.render()));
    }

    /**
     * Wire mirror of {@link WorldObject.Operator} (JSON key {@code operator}) — never a cv-service
     * concern.
     *
     * @param followed whether the operator currently follows this object
     * @param denied   whether this object's label is deny-filtered
     * @param follow   this lock's {@link FollowState}, or absent — absent is an <b>absence of a
     *                 relation</b> (CLAUDE.md rule 10): this object is not the follow target, not a
     *                 disabled feature
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Operator(boolean followed, boolean denied, FollowState follow) {
        public static Operator from(WorldObject.Operator operator) {
            return new Operator(operator.followed(), operator.denied(), operator.follow());
        }
    }

    /**
     * Wire mirror of {@link WorldObject.EventLink} (JSON key {@code event}) — which debounced
     * occurrence, if any, this object's label currently belongs to.
     *
     * @param openEventId the open detection event's id, canonical UUID string, or absent — absent is
     *                     an <b>absence of a relation</b> (CLAUDE.md rule 10): no event is currently
     *                     open for this object's label, not a disabled feature
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record EventLink(String openEventId) {
        public static EventLink from(WorldObject.EventLink event) {
            return new EventLink(event.openEventId() == null ? null : event.openEventId().value().toString());
        }
    }

    /**
     * Wire mirror of {@link WorldObject.Render} (JSON key {@code render}) — how a viewer should
     * render this object.
     *
     * @param tier the render tier
     */
    public record Render(RenderTier tier) {
        public static Render from(WorldObject.Render render) {
            return new Render(render.tier());
        }
    }
}
