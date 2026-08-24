package com.drones.vision.api.dto;

import java.util.List;

/**
 * Server&rarr;client {@code /ws/manual-control} frame confirming an {@code engage} request
 * succeeded (docs/plans/done/RC-CONTROL-PHASE1-PLAN.md §4).
 *
 * @param type       always {@code "engaged"}
 * @param assetId    the engaged asset, as a canonical UUID string
 * @param rateHz     the engaged link's own keepalive cadence, in whole Hz, read live from the
 *                   adapter via {@code ManualControlSession#rateHz()} (docs/plans/active/
 *                   RC-LATENCY-PLAN.md §2 C). It is a <b>floor</b>, not a ceiling: the adapter
 *                   transmits sooner when new input arrives, so a client should treat this as how
 *                   often it must send an unchanged frame to keep the session alive, not as a cap
 *                   on how fast it may report a change
 * @param vehicleKind  what the vehicle most recently reported itself to be — {@code "COPTER"},
 *                     {@code "PLANE"}, {@code "ROVER"}, or {@code "UNKNOWN"}. {@code "UNKNOWN"} is
 *                     an honest answer, not an error: it means the vehicle has not identified
 *                     itself as anything this platform recognizes, and the map below is the
 *                     historical everything-centred one rather than a guess
 *                     (docs/plans/active/VEHICLE-CONTROL-PROFILES-CONTEXT.md §2 P8)
 * @param profileId    the engaged layout's id, as a canonical UUID string — the handle a client uses
 *                     to look the full layout (including its action bindings) up in
 *                     {@code GET /api/control-profiles}
 * @param profileSource {@code "SAVED"} when the operator's own active profile was engaged,
 *                     {@code "BUILT_IN"} when the platform's fallback for this vehicle kind was
 *                     (docs/plans/active/CONTROLLER-SETUP-CONTEXT.md decisions C6/C7). Worth
 *                     showing: an operator who configured a layout and is nonetheless flying the
 *                     built-in has an activation problem they cannot otherwise see
 * @param profileCode  the profile's short channel-order code, e.g. {@code "AETR"} for an aircraft,
 *                     {@code "S-T-"} for a ground vehicle ({@code -} where nothing is bound)
 * @param profileName  what to call this vehicle in front of an operator, e.g. {@code "Multirotor"}
 * @param channelMap   the channel map this session was engaged with, for display and for shaping the
 *                     operator's control surface
 */
public record ManualControlEngagedFrame(String type, String assetId, int rateHz, String vehicleKind,
                                         String profileId, String profileSource, String profileCode,
                                         String profileName,
                                         List<ManualControlChannelBindingResponse> channelMap) {

    /** Convenience constructor: fills in the fixed {@code type} literal. */
    public ManualControlEngagedFrame(String assetId, int rateHz, String vehicleKind, String profileId,
                                      String profileSource, String profileCode, String profileName,
                                      List<ManualControlChannelBindingResponse> channelMap) {
        this("engaged", assetId, rateHz, vehicleKind, profileId, profileSource, profileCode, profileName, channelMap);
    }
}
