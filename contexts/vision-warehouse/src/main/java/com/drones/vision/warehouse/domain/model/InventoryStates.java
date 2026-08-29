package com.drones.vision.warehouse.domain.model;

import java.util.Objects;

/**
 * Derives an {@link Asset}'s effective, user-facing {@link InventoryState} — the pure function
 * behind docs/plans/active/WAREHOUSE-UX-PLAN.md &sect;3.4's state diagram.
 *
 * <p>Kept as a standalone function rather than a method on {@link Asset}: the "does this asset have
 * an open usage right now" fact lives in {@code AssetUsageRepositoryPort}, which {@code Asset}
 * itself must never depend on, so the caller (the application layer, which already holds both) is
 * the one place that can answer the question.
 */
public final class InventoryStates {

    private InventoryStates() {
    }

    /**
     * The state a caller should show for {@code asset} right now.
     *
     * <p>An open usage always reads as {@link InventoryState#IN_FIELD}, even if {@code asset} also
     * has a custodian — a currently-flying aircraft is the more current fact, and CLAUDE.md's
     * "newest data wins" rule (&sect;9) says the live fact overrides the stale one. Failing that, a
     * named custodian reads as {@link InventoryState#ISSUED}. Otherwise the stored {@link
     * Asset#inventoryState()} ({@link InventoryState#IN_STOCK}, {@link InventoryState#MAINTENANCE}
     * or {@link InventoryState#RETIRED}) is the answer.
     *
     * @param asset        the asset
     * @param hasOpenUsage whether this asset currently has an open usage (a live flight/session)
     * @return the effective inventory state
     */
    public static InventoryState effective(Asset asset, boolean hasOpenUsage) {
        Objects.requireNonNull(asset, "asset must not be null");
        if (hasOpenUsage) {
            return InventoryState.IN_FIELD;
        }
        if (asset.custody().custodianId() != null) {
            return InventoryState.ISSUED;
        }
        return asset.inventoryState();
    }
}
