package com.drones.vision.warehouse.domain.port;

import com.drones.vision.warehouse.domain.model.DiscoveryCandidate;
import com.drones.vision.warehouse.domain.model.DiscoveryCandidateId;

import java.util.List;
import java.util.Optional;

/**
 * Driven port: persists the discovery inbox's candidates (docs/plans/active/
 * ZERO-CONFIG-ONBOARDING-CONTEXT.md &sect;11 Z2a) — the "found devices" rows that survive across
 * scans and across a station restart.
 *
 * <h2>Threading</h2>
 * {@link com.drones.vision.warehouse.application.discovery.DefaultDiscoveryInboxService} serializes
 * every call this port sees against a single lock, so implementations need not themselves guarantee
 * an atomic find-then-save upsert — see that class's own javadoc for why the read-then-write cannot
 * simply be pushed onto this port instead (there is no natural SQL "upsert by identity key" verb
 * that also composes with this port's plain {@code save}/{@code findByIdentityKey} shape without a
 * dialect-specific {@code ON CONFLICT} clause this port deliberately stays agnostic of).
 */
public interface DiscoveryCandidateRepositoryPort {

    /**
     * Inserts or replaces a candidate by its own {@link DiscoveryCandidateId}.
     *
     * @param candidate the candidate to persist
     * @return the persisted candidate
     */
    DiscoveryCandidate save(DiscoveryCandidate candidate);

    /**
     * Finds a candidate by its id.
     *
     * @param id the candidate's id
     * @return the candidate, or empty if unknown
     */
    Optional<DiscoveryCandidate> findById(DiscoveryCandidateId id);

    /**
     * Finds the candidate for a given identity key — the upsert lookup {@link
     * com.drones.vision.warehouse.application.discovery.DiscoveryInboxService#report} uses.
     *
     * @param identityKey the dedup key, see {@link DiscoveryCandidate#identityKeyFor}
     * @return the candidate, or empty if this identity has never been reported
     */
    Optional<DiscoveryCandidate> findByIdentityKey(String identityKey);

    /**
     * Lists every candidate, in no particular guaranteed order.
     *
     * @return an immutable snapshot
     */
    List<DiscoveryCandidate> findAll();
}
