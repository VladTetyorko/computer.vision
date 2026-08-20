package com.drones.vision.perception.application.geo;

import com.drones.vision.kernel.StreamId;
import com.drones.vision.kernel.Telemetry;
import com.drones.vision.kernel.VisualFix;
import com.drones.vision.perception.domain.model.GeoSessionConfig;
import com.drones.vision.perception.domain.port.PulledGeolocationPort;

import java.net.URI;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Flow;

/**
 * {@link GeolocationSessionService} backed by {@link PulledGeolocationPort}. See the interface
 * javadoc for the composition boundary this class stops at.
 *
 * <h2>Two collections, not one map with a sentinel</h2>
 * {@link #openSessions} (which streams have a session at all) and {@link #latestTelemetry} (what the
 * newest sample for an open one is) are separate — a {@code ConcurrentHashMap} cannot hold a
 * {@code null} value to mean "open, nothing reported yet", and a synthetic sentinel {@code
 * Telemetry} would be one more way to leak a fake reading into a caller's hands by accident. The
 * same "several small concurrent collections beat one overloaded one" shape {@code
 * DefaultStreamService}'s {@code activeStreams}/{@code streamByDevice} already uses.
 */
public final class DefaultGeolocationSessionService implements GeolocationSessionService {

    private final PulledGeolocationPort port;
    private final Set<StreamId> openSessions = ConcurrentHashMap.newKeySet();
    private final Map<StreamId, Telemetry> latestTelemetry = new ConcurrentHashMap<>();

    public DefaultGeolocationSessionService(PulledGeolocationPort port) {
        this.port = Objects.requireNonNull(port, "port must not be null");
    }

    @Override
    public Flow.Publisher<VisualFix> start(StreamId streamId, URI sourceUrl, GeoSessionConfig config) {
        Objects.requireNonNull(streamId, "streamId must not be null");
        Objects.requireNonNull(sourceUrl, "sourceUrl must not be null");
        Objects.requireNonNull(config, "config must not be null");
        if (!openSessions.add(streamId)) {
            throw new IllegalStateException(
                    "a geolocation session is already open for stream " + streamId.value());
        }
        return port.open(streamId, sourceUrl, config);
    }

    @Override
    public void telemetry(StreamId streamId, Telemetry telemetry) {
        Objects.requireNonNull(streamId, "streamId must not be null");
        Objects.requireNonNull(telemetry, "telemetry must not be null");
        if (!openSessions.contains(streamId)) {
            return;
        }
        latestTelemetry.put(streamId, telemetry);
        port.telemetry(streamId, telemetry);
    }

    @Override
    public Optional<Telemetry> currentTelemetry(StreamId streamId) {
        Objects.requireNonNull(streamId, "streamId must not be null");
        return Optional.ofNullable(latestTelemetry.get(streamId));
    }

    @Override
    public boolean isOpen(StreamId streamId) {
        Objects.requireNonNull(streamId, "streamId must not be null");
        return openSessions.contains(streamId);
    }

    @Override
    public void stop(StreamId streamId) {
        Objects.requireNonNull(streamId, "streamId must not be null");
        if (!openSessions.remove(streamId)) {
            return;
        }
        latestTelemetry.remove(streamId);
        port.close(streamId);
    }
}
