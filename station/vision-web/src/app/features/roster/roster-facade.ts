import { Injectable, computed, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { VisionApi } from '../../core/api/vision-api';
import { describeHttpError } from '../../core/api-error';
import { ToastService } from '../../core/toast.service';
import type { AssetSummary, AssignedPilot, AssignmentRole, Category, UserSummary } from '../../core/api/models';
import {
  buildRosterRows,
  countAssetsWithoutPilot,
  custodyStatusFor,
  custodyStatusLabel,
  custodyStatusTitle,
  searchRosterRows,
  type CustodyStatus,
  type RosterRow,
} from './roster-logic';
import {
  buildPilotRows,
  countPilotsWithoutAssets,
  parseRosterPivot,
  searchPilotRows,
  type RosterPivot,
  type RosterPilotRow,
} from '../../core/roster/roster-pivot-logic';

/**
 * `RosterPage`'s facade (docs/plans/done/UI-ARCHITECTURE-PLAN.md) — the `/manage/roster` route is already
 * role-gated (`core/org/org-guard.ts`, same guard `/org` uses — only ADMIN/MANAGER reach this page
 * at all, so unlike `pilots-card.ts` this facade doesn't re-check `canManageOrg` itself, mirroring
 * `OrgSettingsFacade`'s own precedent for a route already behind that guard).
 *
 * Loads every asset (`VisionApi.listAssets`), the full user list (`listUsers`, once — for display
 * names), every category (`listCategories`, once — {@link connectedCategorySlugs}'s own source,
 * W10 finding C1(b)), and each asset's own assigned pilots (`listAssetPilots`, one call per asset,
 * in parallel — mirrors `AssetsFacade.refreshAssets`'s identical N-call shape for `getAsset`) — the
 * one shared `pilotsByAsset` map both `By asset` and `By pilot` pivots below read, pivoted in
 * opposite directions over the identical source data (`features/roster/roster-logic.ts#buildRosterRows` /
 * `core/roster/roster-pivot-logic.ts#buildPilotRows`).
 *
 * **Wave 3 (docs/plans/done/NAV-IA-REDESIGN-PLAN.md §2.4, docs/extracts/design/13-roster.md) — accordion deleted,
 * two-pane + pivot added:**
 * - `?by=asset|pilot` (`parseRosterPivot`) drives which list renders; switching pivot clears `?sel=`
 *   (the id namespaces differ — an asset id and a user id are never meant to be compared, so a
 *   selection from one pivot has no meaning carried into the other).
 * - `?sel=<id>` drives the two-pane detail — an asset id in the `asset` pivot, a user id in `pilot`.
 *   Resolved against the currently-loaded rows for the *active* pivot only; a `sel` that doesn't
 *   resolve (stale id, or one left over from the other pivot) degrades to "no selection", never a
 *   crash (docs/plans/done/NAV-IA-REDESIGN-PLAN.md §2.4's own rule).
 * - **One assignment path for both pivots**: `assignPilotToAsset`/`unassignPilotFromAsset` below are
 *   the only two places this facade calls `VisionApi.assignPilot`/`unassignPilot` on the "By pilot"
 *   side; the "By asset" side keeps reusing `<vision-pilots-card>` wholesale (unchanged since before
 *   this task), so there are still only two REST call sites total for this whole feature, never a
 *   third parallel implementation (the task brief's own explicit ask).
 */
@Injectable()
export class RosterFacade {
  private readonly api = inject(VisionApi);
  private readonly toasts = inject(ToastService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);

  readonly loading = signal(true);
  readonly error = signal<string | null>(null);
  readonly searchQuery = signal('');

  private readonly assets = signal<readonly AssetSummary[]>([]);
  private readonly users = signal<readonly UserSummary[]>([]);
  private readonly categories = signal<readonly Category[]>([]);
  private readonly pilotsByAsset = signal<ReadonlyMap<string, readonly AssignedPilot[]>>(new Map());

  /** `userId → display name` — reused by {@link custodyStatus} below; the roster already loads every user for pilot-name resolution, so custody resolution rides the same map rather than a second fetch. */
  private readonly nameById = computed(() => new Map(this.users().map((user) => [user.userId, user.displayName])));

  /** Every `Category#connected` category's own slug (mirrors `InventoryFacade`'s identical computed)
   *  — {@link assetsWithoutPilotCount}'s own scope (WAREHOUSE-UX-CONTEXT.md W10 finding C1(b)): a
   *  non-connected asset (a battery) has no pilot concept, so it must never inflate the gap tile. */
  private readonly connectedCategorySlugs = computed(
    () => new Set(this.categories().filter((category) => category.connected).map((category) => category.slug)),
  );

  private readonly queryParamMap = toSignal(this.route.queryParamMap, {
    initialValue: this.route.snapshot.queryParamMap,
  });

  readonly pivot = computed<RosterPivot>(() => parseRosterPivot(this.queryParamMap().get('by')));
  readonly selectedId = computed(() => this.queryParamMap().get('sel') ?? undefined);

  readonly hasAnyAssets = computed(() => this.assets().length > 0);

  /** "By asset" — unchanged from before this task, still the accordion's old row-building. */
  readonly rows = computed<readonly RosterRow[]>(() =>
    searchRosterRows(buildRosterRows(this.assets(), this.pilotsByAsset(), this.users()), this.searchQuery()),
  );

  /** "By pilot" — the new reverse pivot; see `core/roster/roster-pivot-logic.ts#buildPilotRows`'s own doc comment. */
  readonly pilotRows = computed<readonly RosterPilotRow[]>(() =>
    searchPilotRows(buildPilotRows(this.assets(), this.pilotsByAsset(), this.users()), this.searchQuery()),
  );

  /** `⚠ N assets have no pilot` (docs/extracts/design/13-roster.md) — against every loaded asset, not the
   *  search-filtered subset, and scoped to connected (flyable) categories only — see
   *  {@link connectedCategorySlugs}'s own doc comment. */
  readonly assetsWithoutPilotCount = computed(() =>
    countAssetsWithoutPilot(
      buildRosterRows(this.assets(), this.pilotsByAsset(), this.users()),
      this.connectedCategorySlugs(),
    ),
  );

  /**
   * The mirror gap (docs/plans/done/OPS-UX-PLAN.md §3 B3): how many users who hold a `PILOT`
   * membership somewhere are assigned to zero assets. Built the same way as `assetsWithoutPilotCount`
   * above — against every loaded pilot row, not the search-filtered subset — so both headline tiles
   * answer "as things stand", not "as currently filtered".
   */
  readonly pilotsWithoutAssignmentCount = computed(() =>
    countPilotsWithoutAssets(buildPilotRows(this.assets(), this.pilotsByAsset(), this.users()), this.users()),
  );

  readonly selectedAssetRow = computed(() => this.rows().find((row) => row.asset.assetId === this.selectedId()));
  readonly selectedPilotRow = computed(() => this.pilotRows().find((row) => row.userId === this.selectedId()));

  /** `vision-two-pane`'s own `[detailOpen]` — resolved against whichever pivot is active. */
  readonly hasSelection = computed(() =>
    this.pivot() === 'asset' ? this.selectedAssetRow() !== undefined : this.selectedPilotRow() !== undefined,
  );

  /** `vision-two-pane`'s own `[detailLabel]`. */
  readonly selectedLabel = computed(() =>
    this.pivot() === 'asset'
      ? (this.selectedAssetRow()?.asset.displayName ?? 'Details')
      : (this.selectedPilotRow()?.displayName ?? 'Details'),
  );

  /** Assets the selected pilot could still be added to — the assign picker's own options. */
  readonly assignableAssetsForSelectedPilot = computed(() => {
    const row = this.selectedPilotRow();
    if (!row) {
      return [];
    }
    const assigned = new Set(row.assignments.map((assignment) => assignment.assetId));
    return this.assets().filter((asset) => !assigned.has(asset.assetId));
  });

  readonly assignBusy = signal(false);

  constructor() {
    void this.load();
  }

  async load(): Promise<void> {
    this.loading.set(true);
    this.error.set(null);
    try {
      const [assets, users, categories] = await Promise.all([
        this.api.listAssets(),
        this.api.listUsers(),
        this.api.listCategories(),
      ]);
      this.assets.set(assets);
      this.users.set(users);
      this.categories.set(categories);
      const entries = await Promise.all(
        assets.map(async (asset): Promise<readonly [string, readonly AssignedPilot[]]> => {
          try {
            return [asset.assetId, await this.api.listAssetPilots(asset.assetId)];
          } catch {
            // Best-effort per-asset enrichment — one asset's pilots failing to load never blocks
            // the rest of the roster; that row just reads "no pilots assigned" until a retry.
            return [asset.assetId, []];
          }
        }),
      );
      this.pilotsByAsset.set(new Map(entries));
    } catch (error) {
      this.error.set(describeHttpError(error));
    } finally {
      this.loading.set(false);
    }
  }

  /** The `By asset | By pilot` toggle — clears `?sel=` too, since the two pivots' ids are never comparable. */
  setPivot(pivot: RosterPivot): void {
    void this.router.navigate([], {
      relativeTo: this.route,
      queryParams: { by: pivot, sel: null },
      queryParamsHandling: 'merge',
      replaceUrl: true,
    });
  }

  select(id: string): void {
    void this.router.navigate([], {
      relativeTo: this.route,
      queryParams: { sel: id },
      queryParamsHandling: 'merge',
      replaceUrl: true,
    });
  }

  /** `vision-two-pane`'s own `detailClose`. */
  clearSelection(): void {
    void this.router.navigate([], {
      relativeTo: this.route,
      queryParams: { sel: null },
      queryParamsHandling: 'merge',
      replaceUrl: true,
    });
  }

  /**
   * The "By asset" pivot's custody column (WAREHOUSE-UX-PLAN.md §3.2 D3, wave W7) — "may fly"
   * (`pilotNames` above) vs. "has it". Delegates to `roster-logic.ts#custodyStatusFor`, whose own
   * doc comment has the full priority order (WAREHOUSE-UX-CONTEXT.md W10 finding C1(c): the
   * asset's **effective** `inventoryState` — MAINTENANCE/RETIRED — always wins over a leftover
   * `custody.custodianId`, never the reverse).
   */
  custodyStatus(asset: AssetSummary): CustodyStatus {
    return custodyStatusFor(asset, this.nameById());
  }

  /** {@link custodyStatus}'s own row text — `roster.html`'s one muted custody string. */
  custodyLabel(asset: AssetSummary): string {
    return custodyStatusLabel(this.custodyStatus(asset));
  }

  /** {@link custodyStatus}'s own `title` text. */
  custodyTitle(asset: AssetSummary): string {
    return custodyStatusTitle(this.custodyStatus(asset));
  }

  /** Re-reads one asset's own pilots after `<vision-pilots-card>` reports a change — cheap, one call. */
  async refreshPilotsFor(assetId: string): Promise<void> {
    try {
      const pilots = await this.api.listAssetPilots(assetId);
      this.pilotsByAsset.update((map) => new Map(map).set(assetId, pilots));
    } catch {
      // Silent-degrade — the badge just stays as it was until the next full load(); the mutation
      // itself already succeeded (pilots-card only emits `changed` after a successful call).
    }
  }

  /**
   * The "By pilot" detail pane's Assign action — see this class's own "one assignment path" note.
   * `role` (docs/plans/active/AUTH-ROLES-PLAN.md wave W3) serves both a brand-new assignment and an
   * existing one's seat change — `assignPilot`'s own `PUT` is idempotent, so re-issuing it with a
   * different role is the update (`pilot-assignments-panel.ts`'s own doc comment for why there is no
   * separate "change role" call).
   */
  async assignPilotToAsset(userId: string, assetId: string, role?: AssignmentRole): Promise<void> {
    await this.mutateAssignment(() => this.api.assignPilot(assetId, userId, role), assetId, 'Assigned pilot.');
  }

  /** The "By pilot" detail pane's Unassign action. */
  async unassignPilotFromAsset(userId: string, assetId: string): Promise<void> {
    await this.mutateAssignment(() => this.api.unassignPilot(assetId, userId), assetId, 'Removed pilot.');
  }

  private async mutateAssignment(action: () => Promise<void>, assetId: string, okMessage: string): Promise<void> {
    if (this.assignBusy()) {
      return;
    }
    this.assignBusy.set(true);
    try {
      await action();
      await this.refreshPilotsFor(assetId);
      this.toasts.ok(okMessage);
    } catch (error) {
      // Mirrors `pilots-card.ts#mutate`'s own 403/404 handling verbatim — the same two backend
      // rejections mean the same two things here, regardless of which pivot triggered the call.
      if (error instanceof HttpErrorResponse && error.status === 403) {
        this.toasts.error('That asset is outside your scope — you can only assign pilots to your own assets.');
      } else if (error instanceof HttpErrorResponse && error.status === 404) {
        this.toasts.error('That asset no longer exists — it may have been removed.');
      } else {
        this.toasts.error(describeHttpError(error));
      }
    } finally {
      this.assignBusy.set(false);
    }
  }
}
