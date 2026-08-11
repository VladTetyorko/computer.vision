package com.drones.vision.warehouse.application.fleet;

import com.drones.vision.warehouse.application.asset.AssetService;
import com.drones.vision.identity.application.scope.VisibilityScope;
/**
 * The manager dashboard's single aggregated read (docs/plans/done/MVP3-PLAN.md C-a) — a server-side join of
 * per-category counts and per-asset attention facts, so the browser issues one poll per cycle
 * instead of assembling the picture from several (assets, streams, telemetry, events).
 *
 * <p>One interface, one implementation ({@link DefaultFleetSummaryService}), composing {@link
 * AssetService}, {@link com.drones.vision.perception.application.stream.StreamService}, {@link
 * com.drones.vision.perception.application.pipeline.UsageTracker}, and {@link
 * com.drones.vision.events.domain.port.DetectionEventRepositoryPort} rather than reaching past them to
 * repositories directly — every rule those already enforce (soft-delete visibility, streaming
 * derivation) applies here without duplication.
 */
public interface FleetSummaryService {

    /**
     * Summarizes the fleet, unscoped (every asset). Used by internal/system callers.
     *
     * @param includeArchived whether to include soft-deleted ({@link
     *                        com.drones.vision.kernel.LifecycleState#DELETED}) assets —
     *                        mirrors {@link AssetService#assets(boolean)}'s {@code includeDeleted},
     *                        under the name docs/plans/done/MVP3-PLAN.md C-a's own spec gives the query
     *                        parameter it backs
     * @return the aggregated summary
     */
    FleetSummary summary(boolean includeArchived);

    /**
     * Summarizes only the fleet a given visibility scope may see (docs/plans/done/U-SCOPE-PLAN.md, U-e slice 2,
     * feature 1) — the same aggregation as {@link #summary(boolean)}, over the scoped asset set.
     *
     * <p>An {@link VisibilityScope#unbounded()} scope yields exactly {@link #summary(boolean)}.
     *
     * @param scope           what the acting user may see
     * @param includeArchived whether to include soft-deleted assets
     * @return the aggregated summary, over the assets {@code scope} includes
     */
    FleetSummary summary(VisibilityScope scope, boolean includeArchived);
}
