package com.drones.vision.api.dto;

import com.drones.vision.flight.domain.model.ActionMap;
import com.drones.vision.flight.domain.model.ChannelMap;
import com.drones.vision.flight.domain.model.TransmitterView;

import java.util.List;

/**
 * Request body for {@code PUT /api/control-profiles/{id}} (docs/plans/active/
 * CONTROLLER-SETUP-CONTEXT.md §4.4) — the operator's whole layout, replaced in one write.
 *
 * <p>Whole-document rather than per-binding PATCH because the two maps have a cross-binding
 * invariant: one physical control may appear in exactly one of them, and one RC channel may be
 * driven by exactly one binding. A per-row endpoint would have to reject an edit that is only
 * invalid in combination with a row the client has not sent yet — the operator would be told "no"
 * halfway through a rearrangement that is perfectly valid once finished.
 *
 * @param name       the layout's name; must not be blank
 * @param channelMap the controls that stream into RC channels; {@code null} is read as empty
 * @param actionMap  the controls whose positions fire commands; {@code null} is read as empty
 * @param stickMode  the owner's transmitter mode, 1-4; {@code null} keeps the platform default
 * @param forwardIsUp whether pushing a stick forward reads positive; {@code null} keeps the default
 */
public record UpdateControlProfileRequest(String name, List<ControlBindingPayload> channelMap,
                                           List<ActionBindingPayload> actionMap, Integer stickMode,
                                           Boolean forwardIsUp) {

    /**
     * @return the channel bindings as a domain map
     * @throws IllegalArgumentException if any row fails {@link ControlBindingPayload#toBinding()}
     */
    public ChannelMap toChannelMap() {
        return new ChannelMap(channelMap == null ? List.of()
                : channelMap.stream().map(ControlBindingPayload::toBinding).toList());
    }

    /**
     * How the caller's transmitter is arranged, or the platform default when they said nothing.
     *
     * <p>Absent rather than invalid is the normal case here: an older client, or one that has never
     * shown the operator the picture, has no opinion to send, and the default is what it was already
     * drawing with.
     *
     * @return the view to store
     * @throws IllegalArgumentException if {@code stickMode} is outside 1-4
     */
    public TransmitterView toView() {
        if (stickMode == null && forwardIsUp == null) {
            return TransmitterView.DEFAULT;
        }
        return new TransmitterView(stickMode == null ? TransmitterView.DEFAULT.stickMode() : stickMode,
                forwardIsUp == null ? TransmitterView.DEFAULT.forwardIsUp() : forwardIsUp);
    }

    /**
     * @return the action bindings as a domain map
     * @throws IllegalArgumentException if any row fails {@link ActionBindingPayload#toBinding()}
     */
    public ActionMap toActionMap() {
        return new ActionMap(actionMap == null ? List.of()
                : actionMap.stream().map(ActionBindingPayload::toBinding).toList());
    }
}
