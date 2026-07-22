package com.drones.vision.application;

import com.drones.vision.domain.model.StreamDescriptor;
import com.drones.vision.domain.port.out.VideoSourcePort;

import java.util.List;
import java.util.Objects;

/**
 * Selects the {@link VideoSourcePort} adapter able to open a given
 * {@link StreamDescriptor}.
 *
 * <p>Holds the set of registered adapters (one per ingest protocol) and picks
 * the first one whose {@link VideoSourcePort#supports(StreamDescriptor)}
 * returns {@code true}. This is the application-layer half of the
 * open/closed extension point described in the architecture: adding a new
 * protocol means adding a new {@code VideoSourcePort} implementation to the
 * wiring, not changing this class.
 *
 * <p>Holds no framework dependency: adapters are supplied via the
 * constructor (plain dependency injection), and the wiring module decides
 * which adapters are registered.
 *
 * <h2>Threading</h2>
 * The adapter list is defensively copied to an immutable list at
 * construction time and never mutated afterward, so {@link
 * #sourceFor(StreamDescriptor)} is safe to call concurrently.
 */
public final class VideoSourceRegistry {

    private final List<VideoSourcePort> sources;

    /**
     * @param sources the registered video source adapters; defensively copied
     */
    public VideoSourceRegistry(List<VideoSourcePort> sources) {
        Objects.requireNonNull(sources, "sources must not be null");
        this.sources = List.copyOf(sources);
    }

    /**
     * Finds the first registered adapter that supports the given descriptor.
     *
     * @param descriptor the descriptor to resolve an adapter for
     * @return the matching adapter
     * @throws UnsupportedProtocolException if no registered adapter supports {@code descriptor}
     */
    public VideoSourcePort sourceFor(StreamDescriptor descriptor) {
        Objects.requireNonNull(descriptor, "descriptor must not be null");
        return sources.stream()
                .filter(source -> source.supports(descriptor))
                .findFirst()
                .orElseThrow(() -> new UnsupportedProtocolException(descriptor.protocol()));
    }
}
