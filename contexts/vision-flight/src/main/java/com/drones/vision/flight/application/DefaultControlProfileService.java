package com.drones.vision.flight.application;

import com.drones.vision.flight.domain.model.ActionMap;
import com.drones.vision.flight.domain.model.ChannelMap;
import com.drones.vision.flight.domain.model.ControlProfile;
import com.drones.vision.flight.domain.model.ControlProfileId;
import com.drones.vision.flight.domain.model.OwnedControlProfile;
import com.drones.vision.flight.domain.model.VehicleKind;
import com.drones.vision.flight.domain.port.ControlProfileRepositoryPort;
import com.drones.vision.kernel.UserId;
import com.drones.vision.platform.AccessDeniedException;

import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * The one implementation of {@link ControlProfileService}.
 *
 * <h2>The fallback is total, and that is the point</h2>
 * {@link #activeFor} never returns {@code null} and never throws: an operator with no saved profile,
 * a vehicle kind nobody has configured, an empty database — all resolve to {@link
 * ControlProfile#forKind}. A manual-control session must always have a layout, because the
 * alternative is a session engaging with no map at all, and there is no safe way to fail open on a
 * throttle (VEHICLE-CONTROL-PROFILES-CONTEXT.md P2).
 *
 * <h2>Threading</h2>
 * Holds no mutable state; every read and write goes through the injected port.
 */
public final class DefaultControlProfileService implements ControlProfileService {

    private final ControlProfileRepositoryPort repository;
    private final Clock clock;

    /** Production convenience ctor: {@link Clock#systemUTC()}. */
    public DefaultControlProfileService(ControlProfileRepositoryPort repository) {
        this(repository, Clock.systemUTC());
    }

    /** Test seam: an explicit {@link Clock}, so {@code updatedAt} is assertable. */
    public DefaultControlProfileService(ControlProfileRepositoryPort repository, Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public List<ControlProfile> builtIns() {
        return Arrays.stream(VehicleKind.values()).map(ControlProfile::forKind).toList();
    }

    @Override
    public List<OwnedControlProfile> saved(UserId owner) {
        Objects.requireNonNull(owner, "owner must not be null");
        return repository.findAllByOwner(owner);
    }

    @Override
    public ControlProfile activeFor(UserId owner, VehicleKind kind) {
        Objects.requireNonNull(owner, "owner must not be null");
        Objects.requireNonNull(kind, "kind must not be null");
        return repository.findActive(owner, kind)
                .map(OwnedControlProfile::profile)
                .orElseGet(() -> ControlProfile.forKind(kind));
    }

    @Override
    public OwnedControlProfile create(UserId owner, VehicleKind kind, String name) {
        Objects.requireNonNull(owner, "owner must not be null");
        Objects.requireNonNull(kind, "kind must not be null");
        ControlProfile copy = ControlProfile.forKind(kind).copyAs(ControlProfileId.random(), requireName(name));
        OwnedControlProfile saved = new OwnedControlProfile(owner, copy, false, clock.instant());
        repository.save(saved);
        return saved;
    }

    @Override
    public OwnedControlProfile update(UserId owner, ControlProfileId id, String name, ChannelMap channelMap,
                                      ActionMap actionMap) {
        OwnedControlProfile existing = requireOwned(owner, id);
        ControlProfile updated = existing.profile()
                .withBindings(channelMap, actionMap)
                .copyAs(id, requireName(name));
        OwnedControlProfile saved = new OwnedControlProfile(owner, updated, existing.active(), clock.instant());
        repository.save(saved);
        return saved;
    }

    @Override
    public void activate(UserId owner, ControlProfileId id) {
        requireOwned(owner, id);
        repository.activate(owner, id);
    }

    @Override
    public void delete(UserId owner, ControlProfileId id) {
        requireOwned(owner, id);
        repository.delete(id);
    }

    /**
     * Loads a stored profile and refuses it unless the caller owns it. Built-in ids never reach
     * storage at all, so they fail here as "no such profile" — which is the truth: there is no saved
     * profile by that id, and the built-in behind it is not a thing that can be edited (C7).
     */
    private OwnedControlProfile requireOwned(UserId owner, ControlProfileId id) {
        Objects.requireNonNull(owner, "owner must not be null");
        Objects.requireNonNull(id, "id must not be null");
        if (id.isBuiltIn()) {
            throw new IllegalArgumentException("Built-in control profiles cannot be edited or deleted: " + id.value()
                    + " -- create a copy of it instead");
        }
        OwnedControlProfile profile = repository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("No control profile " + id.value()));
        if (!profile.owner().equals(owner)) {
            throw new AccessDeniedException("Control profile " + id.value() + " belongs to another operator");
        }
        return profile;
    }

    private static String requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Control profile name must not be blank");
        }
        return name.trim();
    }
}
