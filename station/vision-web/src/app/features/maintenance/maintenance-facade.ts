import { Injectable, computed, inject, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { VisionApi } from '../../core/api/vision-api';
import { describeHttpError } from '../../core/api-error';
import { ToastService } from '../../core/toast.service';
import type { AssetSummary, MaintenanceKind, MaintenanceRecord, UserSummary } from '../../core/api/models';
import {
  groundableAssets,
  maintenanceKpis,
  openRecordRows,
  recentlyClosedRecordRows,
  type AssetMaintenanceRow,
  type MaintenanceKpis,
} from '../../core/maintenance/maintenance-logic';

/**
 * `MaintenancePage`'s facade (docs/plans/done/UI-ARCHITECTURE-PLAN.md), a page-specific Facade with no
 * intermediate Store — same precedent as `ReportsFacade`/`RosterFacade`: this page's data isn't
 * shared across navigation, so there's nothing a Store would buy beyond an extra layer.
 *
 * **No fleet-wide maintenance endpoint exists** (docs/plans/active/WAREHOUSE-UX-CONTEXT.md's "W3 → W7
 * handoff", flagged again in `docs/plans/active/WAREHOUSE-UX-CONTEXT.md`'s W7 row as a follow-up for W3):
 * `load()` fetches every asset (`listAssets`, carries `inventoryState`/`custody`) and every user
 * (`listUsers`, for name resolution), then — only for assets whose `inventoryState` is already
 * `MAINTENANCE` — fetches that one asset's own maintenance history in parallel
 * (`listAssetMaintenance`, one call per grounded asset; mirrors `RosterFacade.load`'s identical
 * per-asset-in-parallel shape for `listAssetPilots`). A single asset's history failing to load is
 * swallowed to an empty list — that asset just drops out of the KPI/table counts until a retry,
 * never a fabricated row (the same "degrade honestly" rule `RosterFacade` already follows for
 * `listAssetPilots`).
 *
 * Every KPI/table/groundable-list derivation is pure (`core/maintenance/maintenance-logic.ts`) — this
 * class only owns the fetch, the "Ground a vehicle" form's view state, and the three mutations
 * (`submitGround`/`closeRecord`/`release`), each reloading the full page afterward rather than
 * hand-patching local state — the fleet-wide read is cheap enough (one `listAssets` + N grounded-asset
 * reads) that a full reload is simpler and can't drift from the server's own state machine.
 */
@Injectable()
export class MaintenanceFacade {
  private readonly api = inject(VisionApi);
  private readonly toasts = inject(ToastService);

  readonly loading = signal(true);
  readonly error = signal<string | null>(null);

  private readonly assets = signal<readonly AssetSummary[]>([]);
  private readonly users = signal<readonly UserSummary[]>([]);
  private readonly recordsByAssetId = signal<ReadonlyMap<string, readonly MaintenanceRecord[]>>(new Map());

  /** `userId → display name` — backs both {@link displayNameFor} ("Opened by") and {@link custodianLabelFor} ("Custodian"). */
  private readonly nameById = computed(() => new Map(this.users().map((user) => [user.userId, user.displayName])));

  readonly hasAnyAssets = computed(() => this.assets().length > 0);
  readonly kpis = computed<MaintenanceKpis>(() => maintenanceKpis(this.assets(), this.recordsByAssetId()));
  readonly openRows = computed<readonly AssetMaintenanceRow[]>(() =>
    openRecordRows(this.assets(), this.recordsByAssetId()),
  );
  readonly closedRows = computed<readonly AssetMaintenanceRow[]>(() =>
    recentlyClosedRecordRows(this.assets(), this.recordsByAssetId()),
  );
  readonly groundable = computed<readonly AssetSummary[]>(() => groundableAssets(this.assets()));

  /** "Nothing grounded · every vehicle is in stock or in the field" — the page's own empty state (WAREHOUSE-UX-PLAN.md §4 W7 exact copy). */
  readonly nothingGrounded = computed(() => this.hasAnyAssets() && this.openRows().length === 0);

  // --- "Ground a vehicle" disclosure form — mirrors `OrgSettingsFacade`'s own
  // create-on-demand `userFormOpen` idiom (plain, non-persisted, closed by Cancel or a successful submit).
  readonly groundFormOpen = signal(false);
  readonly groundAssetId = signal('');
  readonly groundKind = signal<MaintenanceKind>('GROUNDING');
  readonly groundSummary = signal('');
  readonly grounding = signal(false);

  readonly busyRecordId = signal<string | null>(null);
  readonly busyAssetId = signal<string | null>(null);

  constructor() {
    void this.load();
  }

  async load(): Promise<void> {
    this.loading.set(true);
    this.error.set(null);
    try {
      const [assets, users] = await Promise.all([this.api.listAssets(), this.api.listUsers()]);
      this.assets.set(assets);
      this.users.set(users);
      const grounded = assets.filter((asset) => asset.inventoryState === 'MAINTENANCE');
      const entries = await Promise.all(
        grounded.map(async (asset): Promise<readonly [string, readonly MaintenanceRecord[]]> => {
          try {
            return [asset.assetId, await this.api.listAssetMaintenance(asset.assetId)];
          } catch {
            // Best-effort per-asset enrichment (see this class's own doc comment) — one asset's
            // history failing to load never blocks the rest of the page.
            return [asset.assetId, []];
          }
        }),
      );
      this.recordsByAssetId.set(new Map(entries));
    } catch (error) {
      this.error.set(describeHttpError(error));
    } finally {
      this.loading.set(false);
    }
  }

  /** "Opened by" column — degrades to the id's own first 8 characters when the user isn't found (deactivated/removed), never a blank cell. */
  displayNameFor(userId: string): string {
    return this.nameById().get(userId) ?? userId.slice(0, 8);
  }

  /** "Custodian" column (WAREHOUSE-UX-PLAN.md §3.2 D3) — `undefined` when the asset is in stock (no custodian), rendered as "—" by the template. */
  custodianLabelFor(asset: AssetSummary): string | undefined {
    const custodianId = asset.custody?.custodianId;
    return custodianId ? this.displayNameFor(custodianId) : undefined;
  }

  openGroundForm(): void {
    this.groundAssetId.set('');
    this.groundKind.set('GROUNDING');
    this.groundSummary.set('');
    this.groundFormOpen.set(true);
  }

  closeGroundForm(): void {
    this.groundFormOpen.set(false);
  }

  async submitGround(): Promise<void> {
    const assetId = this.groundAssetId();
    const summary = this.groundSummary().trim();
    if (!assetId || !summary || this.grounding()) {
      return;
    }
    this.grounding.set(true);
    try {
      await this.api.setAssetInventory(assetId, { action: 'GROUND', kind: this.groundKind(), summary });
      this.toasts.ok('Grounded.');
      this.groundFormOpen.set(false);
      await this.load();
    } catch (error) {
      this.toasts.error(this.describeMutationError(error, 'ground'));
    } finally {
      this.grounding.set(false);
    }
  }

  async closeRecord(row: AssetMaintenanceRow): Promise<void> {
    if (this.busyRecordId()) {
      return;
    }
    this.busyRecordId.set(row.record.id);
    try {
      await this.api.closeMaintenanceRecord(row.asset.assetId, row.record.id);
      this.toasts.ok('Record closed.');
      await this.load();
    } catch (error) {
      this.toasts.error(this.describeMutationError(error, 'close a record on'));
    } finally {
      this.busyRecordId.set(null);
    }
  }

  async release(asset: AssetSummary): Promise<void> {
    if (this.busyAssetId()) {
      return;
    }
    this.busyAssetId.set(asset.assetId);
    try {
      await this.api.setAssetInventory(asset.assetId, { action: 'RELEASE' });
      this.toasts.ok('Released back to stock.');
      await this.load();
    } catch (error) {
      this.toasts.error(this.describeMutationError(error, 'release'));
    } finally {
      this.busyAssetId.set(null);
    }
  }

  /** Mirrors `RosterFacade#mutateAssignment`'s own 403/404 handling verbatim — the same two backend rejections mean the same two things here. */
  private describeMutationError(error: unknown, verbPhrase: string): string {
    if (error instanceof HttpErrorResponse && error.status === 403) {
      return `That asset is outside your scope — you can only ${verbPhrase} your own assets.`;
    }
    if (error instanceof HttpErrorResponse && error.status === 404) {
      return 'That asset no longer exists — it may have been removed.';
    }
    return describeHttpError(error);
  }
}
