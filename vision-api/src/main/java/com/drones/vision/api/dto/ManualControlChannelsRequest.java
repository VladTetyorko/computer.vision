package com.drones.vision.api.dto;

import java.util.List;

/**
 * Client&rarr;server {@code /ws/manual-control} frame streaming one gamepad sample
 * (docs/plans/done/RC-CONTROL-PHASE1-PLAN.md §4) — sent at whatever rate the browser's Gamepad API poll
 * delivers (~20-30Hz), decoupled from the adapter's fixed wire cadence by {@code
 * ManualControlSession#onChannels}'s latest-wins forwarding.
 *
 * @param type    {@code "channels"}
 * @param axes    raw gamepad axis readings, {@code [-1,1]} each; a missing/short list reads as
 *                center for the bindings it doesn't cover ({@code ChannelMap#apply}'s own rule)
 * @param buttons raw gamepad button readings, {@code [0,1]} each; same missing/short handling
 * @param seq     a client-assigned, monotonically increasing sequence number, echoed verbatim in
 *                the resulting {@code ack}
 * @param tSent   the client's send timestamp (epoch millis), echoed verbatim in the {@code ack}
 *                for round-trip latency measurement
 */
public record ManualControlChannelsRequest(String type, List<Double> axes, List<Double> buttons, long seq,
                                            long tSent) {

    public ManualControlChannelsRequest {
        axes = axes == null ? List.of() : axes;
        buttons = buttons == null ? List.of() : buttons;
    }
}
