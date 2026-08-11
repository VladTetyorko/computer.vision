package com.drones.vision.application.device;

import com.drones.vision.domain.model.StreamDescriptor;
import com.drones.vision.application.asset.AssetService;
import com.drones.vision.application.stream.UnsupportedProtocolException;
import com.drones.vision.application.pipeline.VideoSourceRegistry;

/**
 * Test-before-save connectivity probe (docs/plans/done/UX-REWORK-PLAN.md §U-d item 3, UX-DESIGN.md §5.1):
 * resolves the {@link VideoSourcePort} that would open a given {@link StreamDescriptor}, opens it,
 * waits for exactly one frame, then closes it again — proving a device can actually produce a
 * frame before anything about it is ever saved.
 *
 * <p><b>Never registers anything.</b> No {@code Device}/{@code Asset} is created, no repository is
 * written to, no audit entry is recorded — this is a read-only connectivity check, the opposite of
 * {@link DeviceService#register}/{@link AssetService#create}.
 *
 * @see com.drones.vision.domain.port.out.VideoSourcePort
 */
public interface ProbeService {

    /**
     * Probes {@code descriptor} for a live frame.
     *
     * @param descriptor the connection to test; {@code protocol} selects the adapter exactly like
     *                    {@link VideoSourceRegistry#sourceFor}
     * @return what the probe found
     * @throws com.drones.vision.application.UnsupportedProtocolException if no adapter is
     *         registered for {@code descriptor}'s protocol — a malformed/unrecognized request, not
     *         a probe failure
     * @throws ProbeFailedException if the adapter recognizes the protocol but the connection
     *         itself fails, times out, or the source ends before producing a frame — carries a
     *         specific, actionable message (UX-DESIGN §5.1)
     */
    ProbeResult probe(StreamDescriptor descriptor);
}
