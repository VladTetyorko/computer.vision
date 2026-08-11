package com.drones.vision.perception.application.pipeline;

import com.drones.vision.perception.domain.model.FeedSpec;
import com.drones.vision.perception.domain.port.FeedTransmitterPort;

import java.util.List;
import java.util.Objects;

/**
 * Selects the {@link FeedTransmitterPort} adapter able to transmit a given {@link FeedSpec} — the
 * TX-side mirror of {@link VideoSourceRegistry}.
 *
 * <p>{@link com.drones.vision.simulation.application.DefaultSimulationService} originally held a single {@code FeedTransmitterPort}
 * dependency (docs/main/CYCLES-PLAN.md §3, {@code transport=RTSP} only); this registry is the §5
 * generalization once a second transmit protocol ({@code mjpeg}) exists alongside {@code rtsp}.
 * Holds the set of registered transmitter adapters (one per transmit protocol) and picks the
 * first one whose {@link FeedTransmitterPort#supports(FeedSpec)} returns {@code true}. Adding a
 * new transmit protocol means adding a new {@code FeedTransmitterPort} implementation to the
 * wiring, not changing this class or {@link com.drones.vision.simulation.application.DefaultSimulationService}.
 *
 * <p>Holds no framework dependency: adapters are supplied via the constructor (plain dependency
 * injection), and the wiring module decides which adapters are registered.
 *
 * <h2>Threading</h2>
 * The adapter list is defensively copied to an immutable list at construction time and never
 * mutated afterward, so {@link #transmitterFor(FeedSpec)} is safe to call concurrently.
 */
public final class FeedTransmitterRegistry {

    private final List<FeedTransmitterPort> transmitters;

    /**
     * @param transmitters the registered feed transmitter adapters; defensively copied
     */
    public FeedTransmitterRegistry(List<FeedTransmitterPort> transmitters) {
        Objects.requireNonNull(transmitters, "transmitters must not be null");
        this.transmitters = List.copyOf(transmitters);
    }

    /**
     * Finds the first registered adapter that supports the given spec.
     *
     * @param spec the spec to resolve an adapter for
     * @return the matching adapter
     * @throws IllegalArgumentException if no registered adapter supports {@code spec}
     */
    public FeedTransmitterPort transmitterFor(FeedSpec spec) {
        Objects.requireNonNull(spec, "spec must not be null");
        return transmitters.stream()
                .filter(transmitter -> transmitter.supports(spec))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "No FeedTransmitterPort registered for protocol: " + spec.protocol()));
    }
}
