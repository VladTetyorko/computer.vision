package com.drones.vision.perception.application.device;

import com.drones.vision.kernel.StreamDescriptor;

/**
 * Test-before-save connectivity probe (docs/plans/done/UX-REWORK-PLAN.md §U-d item 3, UX-DESIGN.md §5.1):
 * resolves the {@link VideoSourcePort} that would open a given {@link StreamDescriptor}, opens it,
 * waits for exactly one frame, then closes it again — proving a device can actually produce a
 * frame before anything about it is ever saved.
 *
 * <p>A protocol no video adapter claims is not automatically a bad request: if a telemetry adapter
 * claims it instead, the probe proves the link with one telemetry sample and returns a frameless
 * {@link ProbeResult} (docs/plans/active/TELEMETRY-ONLY-ONBOARDING-CONTEXT.md §3). {@code mavlink}
 * is the case that matters — a flight-controller link carries no video and never will.
 *
 * <p><b>Never registers anything.</b> No {@code Device}/{@code Asset} is created, no repository is
 * written to, no audit entry is recorded — this is a read-only connectivity check, the opposite of
 * {@link com.drones.vision.warehouse.application.device.DeviceService#register}/{@link
 * com.drones.vision.warehouse.application.asset.AssetService#create}.
 *
 * @see com.drones.vision.perception.domain.port.VideoSourcePort
 */
public interface ProbeService {

    /**
     * Probes {@code descriptor} for a live frame.
     *
     * @param descriptor the connection to test; {@code protocol} selects the adapter exactly like
     *                    {@link com.drones.vision.perception.application.pipeline.VideoSourceRegistry#sourceFor}
     * @return what the probe found — {@link ProbeResult#frame()} is {@code null} exactly when the
     *         link was proven by telemetry alone ({@link ProbeResult#telemetryOnly()})
     * @throws com.drones.vision.perception.application.stream.UnsupportedProtocolException if
     *         <em>neither</em> a video nor a telemetry adapter is registered for {@code
     *         descriptor}'s protocol — a malformed/unrecognized request, not a probe failure
     * @throws ProbeFailedException if an adapter recognizes the protocol but the connection
     *         itself fails, times out, or the source ends before producing a frame (or, on a
     *         telemetry-only link, a sample) — carries a specific, actionable message
     *         (UX-DESIGN §5.1)
     */
    ProbeResult probe(StreamDescriptor descriptor);
}
