package com.drones.vision.application;

/**
 * The manager dashboard's single aggregated read (docs/MVP3-PLAN.md C-a) — a server-side join of
 * per-category counts and per-asset attention facts, so the browser issues one poll per cycle
 * instead of assembling the picture from several (assets, streams, telemetry, events).
 *
 * <p>One interface, one implementation ({@link DefaultFleetSummaryService}), composing {@link
 * AssetService}, {@link StreamService}, {@link UsageTracker}, and {@link
 * com.drones.vision.domain.port.out.DetectionEventRepositoryPort} rather than reaching past them to
 * repositories directly — every rule those already enforce (soft-delete visibility, streaming
 * derivation) applies here without duplication.
 */
public interface FleetSummaryService {

    /**
     * Summarizes the fleet, unscoped (every asset). Used by internal/system callers.
     *
     * @param includeArchived whether to include soft-deleted ({@link
     *                        com.drones.vision.domain.model.LifecycleState#DELETED}) assets —
     *                        mirrors {@link AssetService#assets(boolean)}'s {@code includeDeleted},
     *                        under the name docs/MVP3-PLAN.md C-a's own spec gives the query
     *                        parameter it backs
     * @return the aggregated summary
     */
    FleetSummary summary(boolean includeArchived);

    /**
     * Summarizes only the fleet a given visibility scope may see (docs/U-SCOPE-PLAN.md, U-e slice 2,
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
