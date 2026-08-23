package com.drones.vision.adapter.publishhls;

import com.drones.vision.kernel.StreamId;

import java.net.URI;
import java.util.Objects;

/**
 * Asks mediamtx whether anyone is currently reading a stream's path
 * (docs/plans/done/STREAM-STATE-PLAN.md &sect;3.2) &mdash; the one video-demand term that cannot be
 * answered from inside this JVM.
 *
 * <p><b>Why this exists as its own class.</b> The idle policy needs a single yes/no about a path;
 * {@link MediamtxProxyPublisher} needs to create, poll and delete paths and is only wired at all in
 * proxy mode. Reader count is wanted in <i>every</i> mode (push-mode streams publish to mediamtx paths
 * too, and their WHEP viewers are just as invisible), so it is a separate, independently wireable
 * object over the same package-private {@link MediamtxControlApi} rather than one more method on a
 * publisher that may not be present.
 *
 * <p>Throws {@link MediamtxControlApiException} when it cannot reach or parse mediamtx. That is the
 * point: its caller ({@code LiveHlsAndReaderVideoDemand}) turns a failure into "assume watched", and
 * it can only do that if this class refuses to guess.
 */
public final class MediamtxReaderProbe {

    private final MediamtxControlApi controlApi;

    /**
     * @param apiBase     mediamtx's Control API base, e.g. {@code http://127.0.0.1:19997} — the same
     *                    loopback-bound endpoint {@code MediamtxProxyPublisher} already uses
     * @param apiUser     optional Basic-auth username; {@code null}/blank sends no header
     * @param apiPassword password paired with {@code apiUser}
     */
    public MediamtxReaderProbe(URI apiBase, String apiUser, String apiPassword) {
        this.controlApi = new MediamtxControlApi(Objects.requireNonNull(apiBase, "apiBase must not be null"),
                apiUser, apiPassword);
    }

    /**
     * @param streamId the stream, whose id is also its mediamtx path name
     * @return whether at least one reader (HLS, RTSP, WHEP, ...) is connected to that path
     * @throws MediamtxControlApiException if mediamtx could not be reached, rejected the call, or
     *                                     answered something this client cannot read
     */
    public boolean hasReaders(StreamId streamId) {
        Objects.requireNonNull(streamId, "streamId must not be null");
        return controlApi.hasReaders(streamId.value().toString());
    }
}
