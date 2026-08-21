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
 * @param channelMap the channel map this session was engaged with, for display
 */
public record ManualControlEngagedFrame(String type, String assetId, int rateHz,
                                         List<ManualControlChannelBindingResponse> channelMap) {

    /** Convenience constructor: fills in the fixed {@code type} literal. */
    public ManualControlEngagedFrame(String assetId, int rateHz, List<ManualControlChannelBindingResponse> channelMap) {
        this("engaged", assetId, rateHz, channelMap);
    }
}
