package com.drones.vision.perception.application.profile;

import com.drones.vision.kernel.GroupId;
import com.drones.vision.perception.domain.model.BindingScope;
import com.drones.vision.perception.domain.model.CvProfile;
import com.drones.vision.perception.domain.model.CvProfileBinding;
import com.drones.vision.perception.domain.model.CvProfileId;
import com.drones.vision.perception.domain.port.CvProfileRepositoryPort;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * An in-memory {@link CvProfileRepositoryPort} fake — this module's dominant hand-fake-port test
 * style. Starts empty; every method mirrors the port's own documented contract. Public (not a
 * nested test class) because both {@code application.profile} and {@code application.stream}
 * tests need it — the latter to build a real, empty-backed {@link CvProfileResolver} at every
 * {@code DefaultStreamService} construction site.
 */
public final class InMemoryCvProfileRepositoryPort implements CvProfileRepositoryPort {

    private final Map<CvProfileId, CvProfile> profiles = new ConcurrentHashMap<>();
    private final Map<String, CvProfileBinding> bindings = new ConcurrentHashMap<>();

    @Override
    public Optional<CvProfile> findById(CvProfileId id) {
        return Optional.ofNullable(profiles.get(id));
    }

    @Override
    public List<CvProfile> findAll() {
        return List.copyOf(profiles.values());
    }

    @Override
    public List<CvProfile> findAllByGroup(GroupId groupId) {
        return profiles.values().stream().filter(profile -> groupId.equals(profile.groupId())).toList();
    }

    @Override
    public CvProfile save(CvProfile profile) {
        profiles.put(profile.id(), profile);
        return profile;
    }

    @Override
    public void delete(CvProfileId id) {
        profiles.remove(id);
    }

    @Override
    public Optional<CvProfileBinding> findBinding(BindingScope scopeKind, String scopeId) {
        return Optional.ofNullable(bindings.get(key(scopeKind, scopeId)));
    }

    @Override
    public List<CvProfileBinding> findAllBindings() {
        return List.copyOf(bindings.values());
    }

    @Override
    public CvProfileBinding saveBinding(CvProfileBinding binding) {
        bindings.put(key(binding.scopeKind(), binding.scopeId()), binding);
        return binding;
    }

    @Override
    public void deleteBinding(BindingScope scopeKind, String scopeId) {
        bindings.remove(key(scopeKind, scopeId));
    }

    @Override
    public int countBindingsFor(CvProfileId profileId) {
        return (int) bindings.values().stream().filter(binding -> binding.profileId().equals(profileId)).count();
    }

    private static String key(BindingScope scopeKind, String scopeId) {
        return scopeKind + ":" + scopeId;
    }
}
