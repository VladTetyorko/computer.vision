package com.drones.vision.api.dto;

import java.util.List;

/**
 * Server&rarr;client {@code /ws/manual-control} frame confirming an {@code engage} request
 * succeeded (docs/RC-CONTROL-PHASE1-PLAN.md §4).
 *
 * @param type       always {@code "engaged"}
 * @param assetId    the engaged asset, as a canonical UUID string
 * @param rateHz     the informational fixed override send rate the client may throttle its own
 *                   {@code channels} send rate toward; matches {@code
 *                   com.drones.vision.adapter.mavlink.MavlinkManualControlSender}'s own default
 *                   ({@value #DEFAULT_RATE_HZ}Hz) but is <b>not</b> read live from that adapter's
 *                   {@code VISION_RC_OVERRIDE_HZ} env knob (vision-api may not depend on
 *                   adapter-mavlink) — a documented rough edge, see {@code
 *                   ManualControlWebSocketHandler}'s own javadoc
 * @param channelMap the channel map this session was engaged with, for display
 */
public record ManualControlEngagedFrame(String type, String assetId, int rateHz,
                                         List<ManualControlChannelBindingResponse> channelMap) {

    /** {@code MavlinkManualControlSender.DEFAULT_OVERRIDE_HZ}'s value, mirrored here (see {@link #rateHz}). */
    public static final int DEFAULT_RATE_HZ = 33;

    /** Convenience constructor: fills in the fixed {@code type} literal. */
    public ManualControlEngagedFrame(String assetId, int rateHz, List<ManualControlChannelBindingResponse> channelMap) {
        this("engaged", assetId, rateHz, channelMap);
    }
}
