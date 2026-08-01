package com.drones.vision.domain.port.out;

import com.drones.vision.domain.model.ModelRef;

import java.util.List;
import java.util.Optional;

/**
 * Driven port: query and promote CV models.
 *
 * <p>Models are versioned, immutable references ({@link ModelRef}); this
 * port is how the platform discovers which versions exist and how it
 * promotes one to production use. Hot-swapping a stream's model is done by
 * updating its {@code PipelineConfig} to point at a different {@link
 * ModelRef}, not through this port — this port only manages which model
 * references exist/are current, typically backed by the Python CV
 * service's model registry over gRPC.
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>{@link #models()} returns all known model references (e.g. across
 *       training runs and stages); order is implementation-defined.</li>
 *   <li>{@link #promote(ModelRef)} marks the given reference as the current
 *       one for its model id (e.g. moving it to a "production" stage), so
 *       that subsequently the version becomes discoverable as the default
 *       for that model id. Rollback is simply promoting an earlier
 *       version.</li>
 *   <li>{@link #active()} is the one reference currently promoted/live (the
 *       default the pipeline resolves an unqualified model id to), or empty
 *       when the registry has no model at all — the "which is live" flag a
 *       promote UI renders, kept off {@link ModelRef} itself since being the
 *       active default is a registry fact, not a property of a reference used
 *       throughout detection/pipeline config.</li>
 * </ul>
 *
 * <h2>Threading</h2>
 * Implementations must be safe for concurrent use; {@code promote} calls
 * are infrequent control-plane operations but may race with pipelines
 * concurrently reading the current model — implementations should make
 * promotion visible atomically (a reader never observes a partially applied
 * promotion).
 */
public interface ModelRegistryPort {

    /**
     * Lists all known model references.
     *
     * @return all known model references
     */
    List<ModelRef> models();

    /**
     * The model reference currently promoted/live (the registry's default), or empty when the
     * registry has no model.
     *
     * @return the active model reference, or {@link Optional#empty()} if none
     */
    Optional<ModelRef> active();

    /**
     * Promotes the given model reference (e.g. to production).
     *
     * @param ref the model reference to promote
     */
    void promote(ModelRef ref);
}
