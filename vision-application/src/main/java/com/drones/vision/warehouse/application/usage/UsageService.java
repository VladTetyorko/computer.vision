package com.drones.vision.warehouse.application.usage;

import com.drones.vision.identity.application.scope.VisibilityScope;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.warehouse.domain.port.AssetRepositoryPort;

import java.util.List;

/**
 * Lists finished (and still-open) {@link com.drones.vision.flight.domain.model.AssetUsage}s as
 * display-ready rows — the read side behind {@code GET /api/usages}, the "replay library"
 * (docs/plans/done/NAV-IA-REDESIGN-PLAN.md Wave 4, F8, docs/extracts/design/10-replay.md). {@link
 * com.drones.vision.events.application.ReplayService} (sibling package) is the detail view over
 * <em>one</em> usage's telemetry/detection timeline; this
 * is the cross-fleet list a caller picks a usage from before opening that detail view — a genuinely
 * different collaborator set ({@link AssetRepositoryPort} to resolve a display name and enforce
 * visibility, neither of which {@code ReplayService} needs), hence its own service area rather
 * than a method bolted onto {@code ReplayService}.
 */
public interface UsageService {

    /**
     * Lists the most recent usages the caller may see, newest first by {@code startedAt}.
     *
     * @param scope       what the caller may see; a non-{@link VisibilityScope#isUnbounded()} scope
     *                    silently excludes any usage whose owning asset is outside it (or no
     *                    longer resolvable — see {@code DefaultUsageService})
     * @param assetIdOrNull restricts the list to one asset's usages, or {@code null} for fleet-wide
     * @param limit       maximum number of usages to return; must be positive, silently clamped to
     *                    an internal ceiling if larger
     * @return an immutable, newest-first snapshot of the usages the caller may see
     * @throws IllegalArgumentException if {@code limit} is not positive
     */
    List<UsageSummary> recent(VisibilityScope scope, AssetId assetIdOrNull, int limit);
}
