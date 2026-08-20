package com.drones.vision.perception.domain.port;

import com.drones.vision.kernel.StreamId;
import com.drones.vision.kernel.Telemetry;
import com.drones.vision.kernel.VisualFix;
import com.drones.vision.perception.domain.model.GeoSessionConfig;

import java.net.URI;
import java.util.concurrent.Flow;

/**
 * Driven port: cv-service's {@code Geolocation.LocalizeStream} RPC (docs/plans/active/
 * VISUAL-GEO-V2-PLAN.md §3.1, D2) — a worker-pull localization session, modelled method-for-method
 * on {@link PulledDetectionPort}. The worker dials {@code sourceUrl} itself (the mediamtx path for
 * a stream); no frame ever crosses this port.
 *
 * <h2>Mirrors {@code PulledDetectionPort} on purpose</h2>
 * Same shape, same reasons: {@link #open} is per-open/hot/live, {@link #telemetry} restates the hot
 * field of an already-open session decoupled from the frame rate (the pull equivalent of {@code
 * PulledDetectionPort#attitude}), {@link #close} is idempotent. This lets {@code
 * GrpcPulledGeolocationPort} (H3) share {@code CvChannelSupervisor} and the same {@code
 * ManagedChannel} {@code GrpcPulledDetectionPort} already uses (D3) — one channel to cv-service, not
 * two.
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>{@link #open(StreamId, URI, GeoSessionConfig)} starts a localization pull and returns a
 *       <b>per-open</b> publisher of {@link VisualFix}es; each call is an independent, freshly
 *       started session, never shared or cached across calls.</li>
 *   <li>The publisher is <b>hot and live</b>: a subscriber not currently requesting items does not
 *       "catch up" on fixes produced while it had no demand.</li>
 *   <li>On an unrecoverable failure the implementation must call {@link
 *       Flow.Subscriber#onError(Throwable)} and stop the pull. A single-frame refusal is carried as
 *       an ordinary {@link VisualFix} with a non-empty {@code refusal} — never as {@code onError}
 *       (VisualFix's own javadoc: "a refusal, not an error").</li>
 *   <li>{@link #telemetry(StreamId, Telemetry)} forwards the aircraft's current telemetry sample, to
 *       be restated on whatever {@code GeoControl} message the adapter next sends the worker (D2 —
 *       "restated on every message", never a one-shot fire). Calling it for a stream with no open
 *       session is a no-op.</li>
 *   <li>{@link #close(StreamId)} ends the session and releases adapter-side resources. Idempotent:
 *       closing an already-closed or never-opened stream is a no-op, not an error.</li>
 * </ul>
 *
 * <h2>Threading</h2>
 * Implementations typically drive delivery from their own I/O thread(s) (a gRPC receive loop);
 * {@link Flow.Subscriber} callbacks may be invoked from adapter-managed threads. {@code open},
 * {@code telemetry}, and {@code close} must be safe to call concurrently for different {@code
 * streamId}s.
 */
public interface PulledGeolocationPort {

    /**
     * Starts a localization session against {@code sourceUrl} and returns a live publisher of its
     * fixes.
     *
     * @param id        identity to associate with the opened session
     * @param sourceUrl where the worker should pull frames from (the mediamtx RTSP path for {@code
     *                  id})
     * @param config    which region to search and at what cadence
     * @return a per-open publisher of {@link VisualFix}es; see class javadoc for delivery semantics
     */
    Flow.Publisher<VisualFix> open(StreamId id, URI sourceUrl, GeoSessionConfig config);

    /**
     * Forwards the aircraft's current telemetry sample, restated on the worker's next {@code
     * GeoControl} message. A no-op if {@code id} has no open session.
     *
     * @param id        the session to update
     * @param telemetry the latest telemetry sample; never {@code null}
     */
    void telemetry(StreamId id, Telemetry telemetry);

    /**
     * Stops the localization session for the given stream and releases adapter resources.
     * Idempotent.
     *
     * @param id the stream to close
     */
    void close(StreamId id);
}
