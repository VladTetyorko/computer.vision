package com.drones.vision.adapter.cvgrpc;

import com.drones.vision.kernel.StreamId;
import com.drones.vision.kernel.Telemetry;
import com.drones.vision.kernel.VisualFix;
import com.drones.vision.perception.domain.model.GeoSessionConfig;
import com.drones.vision.perception.domain.port.PulledGeolocationPort;
import com.drones.vision.proto.v1.GeolocationGrpc;
import io.grpc.ManagedChannel;

import java.net.URI;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Flow;

/**
 * {@link PulledGeolocationPort} over the generated {@code Geolocation/LocalizeStream} bidi stub —
 * the Java client half of the pull-only geolocation contract (docs/plans/active/
 * VISUAL-GEO-V2-PLAN.md §3.1, D2/D3, H3).
 *
 * <h2>Channel sharing (D3)</h2>
 * Takes a pre-built {@link ManagedChannel} — the same one {@link GrpcDetectionPort}/{@link
 * GrpcPulledDetectionPort} already use — rather than opening a second TCP connection to cv-service.
 * {@code Geolocation} is a second gRPC service multiplexed over that one HTTP/2 channel, exactly
 * like {@code Training}/{@code Inference} already are. Channel lifecycle stays wherever the channel
 * was built; this class never closes it.
 *
 * <h2>The one difference from {@link GrpcPulledDetectionPort}: a required supervisor gate</h2>
 * Unlike detection's ports (where {@link CvChannelSupervisor} is an optional, nullable constructor
 * argument), this class's {@link CvChannelSupervisor} is <b>required</b>. A closed gate makes {@link
 * #open} throw {@link CvUnavailableException} <em>synchronously</em>, before a session or bidi call
 * is ever created — the same fail-fast reasoning {@link GrpcDetectionPort#detect} already applies,
 * but checked eagerly here (once per {@code open()}, not once per frame) since a geolocation session
 * is long-lived rather than per-frame. Opening a session against a channel already known to be
 * unreachable would only wedge on the first {@code onNext} and fail asynchronously moments later;
 * failing fast here means a caller (H5's {@code VisualGeoRunner}) never has to wait out that round
 * trip to learn cv-service is down.
 *
 * <h2>Reconnect/backoff is deliberately not built here</h2>
 * Same as {@link GrpcPulledDetectionPort}: a transport failure surfaces honestly via {@link
 * Flow.Subscriber#onError} and nothing more. Reopening with backoff belongs to whatever supervises
 * this port's publisher (a later wiring wave), not this adapter.
 */
public final class GrpcPulledGeolocationPort implements PulledGeolocationPort {

    private final GeolocationGrpc.GeolocationStub asyncStub;
    private final CvChannelSupervisor supervisor;
    private final double mountPitchDegrees;
    private final ConcurrentHashMap<StreamId, GeolocationSession> sessions = new ConcurrentHashMap<>();

    /**
     * @param channel           a channel already open to cv-service — typically the same one {@link
     *                          GrpcDetectionPort} built; never closed by this class
     * @param supervisor        gates {@link #open} fail-fast while cv-service is known unreachable;
     *                          unlike detection's ports, required (never {@code null}) — see class javadoc
     * @param mountPitchDegrees this deployment's fixed camera-mount pitch relative to the airframe,
     *                          positive-up ({@code vision.geo.visual.mount-pitch-degrees}, default
     *                          {@code 0.0}); used by {@link GeoFixCodec} only when an aircraft
     *                          reports no gimbal pitch at all — see that class's own javadoc, "H8 —
     *                          the gimbal-less fallback"
     * @throws NullPointerException if {@code channel} or {@code supervisor} is {@code null}
     */
    public GrpcPulledGeolocationPort(ManagedChannel channel, CvChannelSupervisor supervisor,
                                      double mountPitchDegrees) {
        Objects.requireNonNull(channel, "channel must not be null");
        this.supervisor = Objects.requireNonNull(supervisor, "supervisor must not be null");
        this.mountPitchDegrees = mountPitchDegrees;
        this.asyncStub = GeolocationGrpc.newStub(channel);
    }

    @Override
    public Flow.Publisher<VisualFix> open(StreamId id, URI sourceUrl, GeoSessionConfig config) {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(sourceUrl, "sourceUrl must not be null");
        Objects.requireNonNull(config, "config must not be null");
        if (!supervisor.available()) {
            throw new CvUnavailableException(supervisor.describe());
        }

        GeolocationSession session = new GeolocationSession(id, asyncStub, sessions, config, mountPitchDegrees);
        GeolocationSession previous = sessions.put(id, session);
        if (previous != null) {
            previous.endAndClose(); // defensive: an id must not have two live sessions
        }
        session.start(sourceUrl);
        return session.publisher();
    }

    @Override
    public void telemetry(StreamId id, Telemetry telemetry) {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(telemetry, "telemetry must not be null");
        GeolocationSession session = sessions.get(id);
        if (session != null) {
            session.telemetry(telemetry);
        }
    }

    @Override
    public void close(StreamId id) {
        Objects.requireNonNull(id, "id must not be null");
        GeolocationSession session = sessions.get(id);
        if (session != null) {
            session.endAndClose();
        }
    }
}
