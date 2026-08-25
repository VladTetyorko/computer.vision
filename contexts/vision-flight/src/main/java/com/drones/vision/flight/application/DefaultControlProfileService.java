package com.drones.vision.flight.application;

import com.drones.vision.flight.domain.model.ActionMap;
import com.drones.vision.flight.domain.model.ChannelMap;
import com.drones.vision.flight.domain.model.ControlProfile;
import com.drones.vision.flight.domain.model.ControlProfileId;
import com.drones.vision.flight.domain.model.OwnedControlProfile;
import com.drones.vision.flight.domain.model.TransmitterView;
import com.drones.vision.flight.domain.model.VehicleKind;
import com.drones.vision.flight.domain.port.ControlProfileRepositoryPort;
import com.drones.vision.kernel.UserId;
import com.drones.vision.platform.AccessDeniedException;

import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
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
                                      ActionMap actionMap, TransmitterView view) {
        OwnedControlProfile existing = requireOwned(owner, id);
        ControlProfile updated = existing.profile()
                .withBindings(channelMap, actionMap)
                .copyAs(id, requireName(name));
        OwnedControlProfile saved = new OwnedControlProfile(owner, updated, existing.active(), clock.instant(),
                Objects.requireNonNullElse(view, TransmitterView.DEFAULT));
        repository.save(saved);
        return saved;
    }

    @Override
    public void activate(UserId owner, ControlProfileId id) {
        Objects.requireNonNull(owner, "owner must not be null");
        Objects.requireNonNull(id, "id must not be null");
        Optional<VehicleKind> builtIn = id.builtInKind();
        if (builtIn.isPresent()) {
            fallBackToBuiltIn(owner, builtIn.get());
            return;
        }
        requireOwned(owner, id);
        repository.activate(owner, id);
    }

    /**
     * "Activate the built-in" is not a write to the built-in — it is the <em>absence</em> of an
     * active saved profile for that kind, which is exactly what {@link #activeFor} already falls
     * back to. So this clears the owner's active flag for the kind and stops; nothing is copied,
     * because a copy would be an editable duplicate the operator never asked for, free to drift
     * from the built-in it was cut from.
     *
     * <p>Idempotent: with nothing active for that kind there is nothing to clear, and saying so
     * would be an error message about a state the operator was trying to reach anyway.
     */
    private void fallBackToBuiltIn(UserId owner, VehicleKind kind) {
        repository.findActive(owner, kind).ifPresent(active ->
                repository.save(new OwnedControlProfile(owner, active.profile(), false, clock.instant(), active.view())));
    }

    @Override
    public void delete(UserId owner, ControlProfileId id) {
        requireOwned(owner, id);
        repository.delete(id);
    }

    /**
     * Loads a stored profile and refuses it unless the caller owns it. Built-in ids never reach
     * storage at all, so they are rejected up front — there is no saved profile by that id, and the
     * built-in behind it is not a thing that can be edited or deleted (C7).
     *
     * <p>{@link #activate} deliberately does <em>not</em> come through here: activating a built-in
     * is a legitimate request meaning "stop using my saved layout for this kind", and it is handled
     * before this guard runs.
     */
    private OwnedControlProfile requireOwned(UserId owner, ControlProfileId id) {
        Objects.requireNonNull(owner, "owner must not be null");
        Objects.requireNonNull(id, "id must not be null");
        if (id.isBuiltIn()) {
            throw new IllegalArgumentException("Built-in control profiles cannot be edited or deleted: " + id.value()
                    + " -- create a copy of it instead. (Activating one is allowed, and means \"use the"
                    + " built-in\" -- see activate.)");
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
