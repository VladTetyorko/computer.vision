package com.drones.vision.perception.application.geo;

import com.drones.vision.kernel.StreamId;
import com.drones.vision.kernel.Telemetry;
import com.drones.vision.kernel.VisualFix;
import com.drones.vision.perception.domain.model.GeoSessionConfig;

import java.net.URI;
import java.util.Optional;
import java.util.concurrent.Flow;

/**
 * Perception's ownership of "the localization session" (docs/plans/active/VISUAL-GEO-V2-PLAN.md
 * D7) — at most one open session per stream, gated the same way {@code DefaultStreamService#start}
 * gates at most one active stream per device. One interface, one implementation: {@link
 * DefaultGeolocationSessionService}.
 *
 * <h2>Composition boundary (D7)</h2>
 * This service decides <em>whether a session may open</em> (the per-stream uniqueness gate) and
 * holds the latest telemetry handed to it; it does not decide <em>which streams need a session</em>
 * — that reconcile loop (which assets are flying, which of their streams should be corrected) is
 * {@code station/vision-app}'s {@code VisualGeoRunner}, the composition layer ("logic in the
 * context, composition in the legal composer"). Nor does it route {@link VisualFix} results anywhere
 * — the caller subscribes to the publisher {@link #start} returns and does that itself, so this
 * context never has to name a {@code vision-flight} type.
 */
public interface GeolocationSessionService {

    /**
     * Opens a localization session for {@code streamId}.
     *
     * @param streamId  the stream to localize
     * @param sourceUrl the mediamtx RTSP path to pull from
     * @param config    which region to search and at what cadence
     * @return a live publisher of {@link VisualFix}es for this session
     * @throws IllegalStateException if a session is already open for {@code streamId}
     */
    Flow.Publisher<VisualFix> start(StreamId streamId, URI sourceUrl, GeoSessionConfig config);

    /**
     * Restates the aircraft's current telemetry for an open session — latest-wins: this call
     * supersedes whatever was handed in before it, and the previous value is discarded rather than
     * queued. A no-op if no session is open for {@code streamId} (the {@code
     * PulledDetectionPort#attitude} contract).
     *
     * @param streamId  the session to update
     * @param telemetry the latest telemetry sample; never {@code null}
     */
    void telemetry(StreamId streamId, Telemetry telemetry);

    /**
     * The latest telemetry handed to an open session, if any has arrived yet.
     *
     * @param streamId the session to read
     * @return the latest sample; empty if the session is not open, or is open but has not yet
     *         received one
     */
    Optional<Telemetry> currentTelemetry(StreamId streamId);

    /**
     * Whether a session is currently open for {@code streamId}.
     *
     * @param streamId the stream to check
     * @return {@code true} iff {@link #start} was called for {@code streamId} and {@link #stop} has
     *         not since been called for it
     */
    boolean isOpen(StreamId streamId);

    /**
     * Closes the session for {@code streamId}, if one is open, and forgets its cached telemetry.
     * Idempotent.
     *
     * @param streamId the stream to stop localizing
     */
    void stop(StreamId streamId);
}
