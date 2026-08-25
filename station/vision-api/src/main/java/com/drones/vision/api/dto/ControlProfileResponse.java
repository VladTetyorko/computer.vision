package com.drones.vision.api.dto;

import com.drones.vision.flight.domain.model.ControlProfile;
import com.drones.vision.flight.domain.model.OwnedControlProfile;
import com.drones.vision.flight.domain.model.TransmitterView;

import java.time.Instant;
import java.util.List;

/**
 * One controller layout on the {@code /api/control-profiles} wire (docs/plans/active/
 * CONTROLLER-SETUP-CONTEXT.md §4.4) — either one the operator saved or one of the platform's
 * built-ins, told apart by {@link #source()} rather than by which array they arrived in.
 *
 * <p>Built-ins are listed alongside saved profiles on purpose: they are what an operator flies with
 * until they configure something, so hiding them would make the list say "you have no controller
 * layout" when in fact a complete one is already in force (decision C7). A built-in is read-only —
 * every write endpoint refuses its id — and is copied, not edited.
 *
 * @param id         the profile id, as a canonical UUID string; a built-in's is derived from its
 *                   vehicle kind, so it is stable across restarts and across deployments
 * @param source     {@code "BUILT_IN"} or {@code "SAVED"}
 * @param kind       the vehicle kind this layout is for: {@code "COPTER"}, {@code "PLANE"},
 *                   {@code "ROVER"} or {@code "UNKNOWN"}
 * @param code       the short channel-order code, e.g. {@code "AETR"} or {@code "S-T-"}
 * @param name       what to call it in front of an operator
 * @param active     whether this is the layout a session on that vehicle kind will actually engage
 *                   with — true for a saved profile the operator activated, and for a built-in
 *                   exactly when they have activated none for its kind
 * @param updatedAt  when it was last saved; {@code null} for a built-in, which was never saved
 * @param channelMap the controls that stream into RC channels
 * @param actionMap  the controls whose positions fire commands
 * @param stickMode  the owner's transmitter mode, 1-4 — how the layout is <em>drawn</em>, never what
 *                   it sends. A built-in reports the platform default, having no owner to ask
 * @param forwardIsUp whether pushing a stick forward makes its axis read positive on this operator's
 *                   radio; drawing only, like {@code stickMode}
 */
public record ControlProfileResponse(String id, String source, String kind, String code, String name, boolean active,
                                      Instant updatedAt, List<ControlBindingPayload> channelMap,
                                      List<ActionBindingPayload> actionMap, int stickMode, boolean forwardIsUp) {

    /** Wire value of {@link #source()} for a profile the operator saved. */
    public static final String SOURCE_SAVED = "SAVED";

    /** Wire value of {@link #source()} for one of the platform's own layouts. */
    public static final String SOURCE_BUILT_IN = "BUILT_IN";

    /**
     * Serializes one of the platform's built-in layouts.
     *
     * @param profile the built-in
     * @param active  whether it is what the caller would currently fly with — i.e. whether they have
     *                no active saved profile for its vehicle kind
     * @return the wire entry, with a {@code null} {@code updatedAt}
     */
    public static ControlProfileResponse builtIn(ControlProfile profile, boolean active) {
        return from(profile, SOURCE_BUILT_IN, active, null, TransmitterView.DEFAULT);
    }

    /**
     * Serializes one saved layout.
     *
     * @param profile the stored profile with its ownership record
     * @return the wire entry
     */
    public static ControlProfileResponse saved(OwnedControlProfile profile) {
        return from(profile.profile(), SOURCE_SAVED, profile.active(), profile.updatedAt(), profile.view());
    }

    private static ControlProfileResponse from(ControlProfile profile, String source, boolean active,
                                               Instant updatedAt, TransmitterView view) {
        return new ControlProfileResponse(profile.id().value().toString(), source, profile.kind().name(),
                profile.code(), profile.displayName(), active, updatedAt,
                profile.channelMap().bindings().stream().map(ControlBindingPayload::from).toList(),
                profile.actionMap().bindings().stream().map(ActionBindingPayload::from).toList(),
                view.stickMode(), view.forwardIsUp());
    }
}
