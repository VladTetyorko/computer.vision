package com.drones.vision.perception.domain.port;

import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.StreamId;

/**
 * Driven port: whether anything is currently consuming a stream's <b>video</b>
 * (docs/plans/active/STREAM-STATE-PLAN.md &sect;3.2) &mdash; the signal the idle policy stops a stream on.
 *
 * <h2>Why this is not {@link DetectionDemandPort}</h2>
 * They answer different questions and reusing the older one here would be a bug, not a shortcut.
 * {@code DetectionDemandPort} answers "is anyone consuming this stream's <i>detections</i>", and
 * since docs/plans/active/CV-DEMAND-PLAN.md detection is <b>off by default</b> &mdash; a stream watched as
 * plain video, with no boxes, is the ordinary case in this deployment. A reaper driven by detection
 * demand would therefore stop precisely the streams the product is built to serve, while an operator
 * watched them.
 *
 * <h2>What counts as demand</h2>
 * Whatever the implementation can actually observe, OR-ed together. One term is not optional and is
 * the reason this port cannot be answered from inside the JVM alone: <b>WHEP/WebRTC viewers connect
 * straight to mediamtx and are invisible to this application</b>. Any implementation that ignores
 * mediamtx's own reader count will confidently report "nobody is watching" about a stream being
 * watched right now.
 *
 * <h2>Contract</h2>
 * Must not throw &mdash; the caller wraps every call anyway, since a periodically-scheduled task must
 * never let one failing evaluation stop every later one, but an implementation that answers instead
 * of throwing keeps that net reserved for genuine bugs.
 *
 * <p><b>Failure fails open: an implementation that cannot tell must answer {@code true}.</b> Same
 * direction {@code LiveAndPollDetectionDemand} already chose, and here the stakes are higher: the
 * consequence of a wrong {@code false} is not a gated detector but a <i>stopped stream</i>, in front
 * of a live operator, blamed on nobody. A wrong {@code true} costs CPU until the next tick and is
 * visible in the very place it is wrong.
 *
 * <h2>Threading</h2>
 * Called from the reaper's single scheduler thread on a fixed interval, never from the video or
 * detection hot path. Unlike {@link DetectionDemandPort} this one <b>may</b> perform network I/O
 * (mediamtx's Control API is an HTTP call), so it must apply its own timeout rather than block that
 * thread indefinitely &mdash; a hung call would freeze the sweep for every other stream too.
 */
public interface VideoDemandPort {

    /**
     * Whether anything is currently consuming this stream's video.
     *
     * @param streamId the stream being evaluated &mdash; also the mediamtx path name
     * @param assetId  the stream's owning asset, or {@code null} for a device-only stream with no
     *                 resolved asset; an implementation whose signals are asset-scoped (an SSE topic)
     *                 simply has nothing to check in that case
     * @return {@code true} if this stream's video is currently wanted, or if that could not be determined
     */
    boolean videoWanted(StreamId streamId, AssetId assetId);
}
