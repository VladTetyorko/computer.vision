package com.drones.vision.perception.application.profile;

import com.drones.vision.perception.domain.model.BindingScope;
import com.drones.vision.perception.domain.model.CvProfile;
import com.drones.vision.perception.domain.model.CvProfileBinding;
import com.drones.vision.perception.domain.model.CvProfileId;
import com.drones.vision.perception.domain.port.CvProfileRepositoryPort;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * A read-through, write-through cache in front of {@link CvProfileRepositoryPort} — the "usual read
 * path" that port's own javadoc anticipates ("a resolver reads them on every stream {@code
 * start}"). {@link CvProfileResolver} and {@code DefaultCvProfileService} both read through {@link
 * #snapshot()} rather than the repository directly (docs/plans/active/CV-SETTINGS-PLAN.md &sect;3.1).
 *
 * <h2>Write-through</h2>
 * Every mutating method ({@link #save}, {@link #delete}, {@link #saveBinding}, {@link
 * #deleteBinding}) writes to the repository first, then reloads the snapshot before returning — the
 * very next {@link #snapshot()} call in this process always reflects a mutation this process just
 * made, with no TTL delay. {@link #countBindingsFor} is a live pass-through, deliberately never
 * cached: the delete-profile 409 rule ({@link CvProfileRepositoryPort#countBindingsFor}'s own
 * javadoc) needs a live answer, not one that could be stale by up to {@link
 * CvProfileCacheSettings#ttl()}.
 *
 * <h2>Lazy TTL reload</h2>
 * A read-only {@link #snapshot()} call reloads from the repository whenever the held snapshot is
 * older than {@link CvProfileCacheSettings#ttl()} — the only way this process ever notices a write
 * made by another process (e.g. another node in a future multi-instance deployment) between its own
 * writes.
 *
 * <h2>Threading</h2>
 * A {@code volatile} single field is swapped atomically on every reload, so every reader always sees
 * a fully built, immutable {@link Snapshot} — never a partially updated one. Concurrent writers may
 * race (the last reload to complete wins); acceptable since writes come only from infrequent {@code
 * canManageOrg} admin actions, never a hot path — reads (every stream start) are.
 */
public final class CvProfileCache {

    private final CvProfileRepositoryPort repository;
    private final CvProfileCacheSettings settings;
    private final Supplier<Instant> clock;

    private volatile Snapshot snapshot;

    public CvProfileCache(CvProfileRepositoryPort repository, CvProfileCacheSettings settings) {
        this(repository, settings, Instant::now);
    }

    /**
     * Test seam: same as the 2-argument constructor, with an injectable clock so the TTL-reload
     * boundary is deterministic in tests instead of depending on wall-clock time.
     */
    CvProfileCache(CvProfileRepositoryPort repository, CvProfileCacheSettings settings, Supplier<Instant> clock) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
        this.settings = Objects.requireNonNull(settings, "settings must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.snapshot = load();
    }

    /**
     * The current snapshot of every profile and binding, reloading from the repository first if the
     * held snapshot is older than {@link CvProfileCacheSettings#ttl()}.
     *
     * @return an immutable snapshot, never stale by more than {@code ttl}
     */
    public Snapshot snapshot() {
        Snapshot current = snapshot;
        if (Duration.between(current.loadedAt(), clock.get()).compareTo(settings.ttl()) >= 0) {
            return refresh();
        }
        return current;
    }

    /**
     * Upserts a profile, then reloads the snapshot (write-through).
     *
     * @param profile the profile to persist
     * @return the persisted profile
     */
    public CvProfile save(CvProfile profile) {
        Objects.requireNonNull(profile, "profile must not be null");
        CvProfile saved = repository.save(profile);
        refresh();
        return saved;
    }

    /**
     * Deletes a profile, then reloads the snapshot (write-through).
     *
     * @param id the profile to delete; deleting an unknown id is a no-op
     */
    public void delete(CvProfileId id) {
        Objects.requireNonNull(id, "id must not be null");
        repository.delete(id);
        refresh();
    }

    /**
     * Upserts a binding, then reloads the snapshot (write-through).
     *
     * @param binding the binding to persist
     * @return the persisted binding
     */
    public CvProfileBinding saveBinding(CvProfileBinding binding) {
        Objects.requireNonNull(binding, "binding must not be null");
        CvProfileBinding saved = repository.saveBinding(binding);
        refresh();
        return saved;
    }

    /**
     * Removes a scope's binding, then reloads the snapshot (write-through).
     *
     * @param scopeKind which kind of scope {@code scopeId} identifies
     * @param scopeId   the scope's id; removing an already-unbound scope is a no-op
     */
    public void deleteBinding(BindingScope scopeKind, String scopeId) {
        Objects.requireNonNull(scopeKind, "scopeKind must not be null");
        Objects.requireNonNull(scopeId, "scopeId must not be null");
        repository.deleteBinding(scopeKind, scopeId);
        refresh();
    }

    /**
     * Counts how many scopes are bound to {@code profileId} — a live pass-through to {@link
     * CvProfileRepositoryPort#countBindingsFor}, never cached; see this class's own javadoc for why.
     *
     * @param profileId the profile to check
     * @return the number of bindings pointing at {@code profileId}
     */
    public int countBindingsFor(CvProfileId profileId) {
        return repository.countBindingsFor(Objects.requireNonNull(profileId, "profileId must not be null"));
    }

    private Snapshot refresh() {
        Snapshot fresh = load();
        snapshot = fresh;
        return fresh;
    }

    private Snapshot load() {
        return new Snapshot(repository.findAll(), repository.findAllBindings(), clock.get());
    }

    /**
     * An immutable point-in-time view of every profile and binding.
     *
     * @param profiles every profile, built-in and group-owned alike; defensively copied
     * @param bindings every binding across every scope; defensively copied
     * @param loadedAt when this snapshot was loaded from the repository
     */
    public record Snapshot(List<CvProfile> profiles, List<CvProfileBinding> bindings, Instant loadedAt) {

        public Snapshot {
            Objects.requireNonNull(profiles, "profiles must not be null");
            Objects.requireNonNull(bindings, "bindings must not be null");
            Objects.requireNonNull(loadedAt, "loadedAt must not be null");
            profiles = List.copyOf(profiles);
            bindings = List.copyOf(bindings);
        }

        /**
         * Finds a profile by id within this snapshot.
         *
         * @param id the profile id
         * @return the profile, or {@link Optional#empty()} if none exists in this snapshot
         */
        public Optional<CvProfile> findById(CvProfileId id) {
            return profiles.stream().filter(profile -> profile.id().equals(id)).findFirst();
        }

        /**
         * Finds the binding for one scope within this snapshot.
         *
         * @param scopeKind which kind of scope {@code scopeId} identifies
         * @param scopeId   the scope's id
         * @return the binding, or {@link Optional#empty()} if that scope has no bound profile
         */
        public Optional<CvProfileBinding> findBinding(BindingScope scopeKind, String scopeId) {
            return bindings.stream()
                    .filter(binding -> binding.scopeKind() == scopeKind && binding.scopeId().equals(scopeId))
                    .findFirst();
        }
    }
}
