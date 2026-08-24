package com.drones.vision.api.dto;

import com.drones.vision.flight.domain.model.ActionMap;
import com.drones.vision.flight.domain.model.ChannelMap;

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
 */
public record UpdateControlProfileRequest(String name, List<ControlBindingPayload> channelMap,
                                           List<ActionBindingPayload> actionMap) {

    /**
     * @return the channel bindings as a domain map
     * @throws IllegalArgumentException if any row fails {@link ControlBindingPayload#toBinding()}
     */
    public ChannelMap toChannelMap() {
        return new ChannelMap(channelMap == null ? List.of()
                : channelMap.stream().map(ControlBindingPayload::toBinding).toList());
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
