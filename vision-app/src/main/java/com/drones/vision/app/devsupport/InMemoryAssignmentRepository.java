package com.drones.vision.app.devsupport;

import com.drones.vision.domain.model.AssetId;
import com.drones.vision.domain.model.UserId;
import com.drones.vision.domain.port.out.AssignmentRepositoryPort;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * In-memory {@link AssignmentRepositoryPort}: dev fallback with no durability across restarts
 * (docs/plans/done/U-SCOPE-PLAN.md, U-e slice 2, feature 2).
 *
 * <p>Backed by a concurrent set of ({@code pilot}, {@code asset}) links, which gives {@code assign}
 * its idempotent no-duplicate semantics for free (adding an already-present link is a no-op) and
 * {@code unassign} its idempotent removal. Replaced by {@code adapter-persistence}'s {@code
 * JpaAssignmentRepository} when {@code vision.persistence.enabled=true}.
 */
public final class InMemoryAssignmentRepository implements AssignmentRepositoryPort {

    private record Link(UserId pilot, AssetId asset) {
    }

    private final Set<Link> links = java.util.concurrent.ConcurrentHashMap.newKeySet();

    @Override
    public void assign(UserId pilot, AssetId asset) {
        links.add(new Link(pilot, asset));
    }

    @Override
    public void unassign(UserId pilot, AssetId asset) {
        links.remove(new Link(pilot, asset));
    }

    @Override
    public Set<AssetId> assetsForPilot(UserId pilot) {
        return links.stream()
                .filter(link -> link.pilot().equals(pilot))
                .map(Link::asset)
                .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public Set<UserId> pilotsForAsset(AssetId asset) {
        return links.stream()
                .filter(link -> link.asset().equals(asset))
                .map(Link::pilot)
                .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public boolean isAssigned(UserId pilot, AssetId asset) {
        return links.contains(new Link(pilot, asset));
    }
}
