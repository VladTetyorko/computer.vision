import { Injectable, computed, inject, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { VisionApi } from '../../core/api/vision-api';
import { describeHttpError } from '../../core/api-error';
import { ToastService } from '../../core/toast.service';
import { actorLabel } from '../../core/audit/summary-logic';
import type { AssetSummary, FleetMaintenanceRecord, MaintenanceKind, UserSummary } from '../../core/api/models';
import {
  groundableAssets,
  isLastOpenRecordForAsset,
  maintenanceKpis,
  openRecords,
  recentlyClosedRecords,
  type MaintenanceKpis,
} from '../../core/maintenance/maintenance-logic';

/**
 * `MaintenancePage`'s facade (docs/plans/done/UI-ARCHITECTURE-PLAN.md), a page-specific Facade with no
 * intermediate Store — same precedent as `ReportsFacade`/`RosterFacade`: this page's data isn't
 * shared across navigation, so there's nothing a Store would buy beyond an extra layer.
 *
 * **One fleet-wide call, not one per grounded asset** (docs/plans/active/WAREHOUSE-UX-CONTEXT.md
 * "W8 → W9 handoff", replacing the wave-W7 per-asset fan-out that same doc's W7 row had flagged as a
 * follow-up): `load()` fetches every asset (`listAssets`, still needed for the KPI tiles' `RETIRED`
 * count, the "Ground a vehicle" picker, and the table's category-name/custodian lookups — none of
 * which the maintenance endpoint itself carries), every user (`listUsers`, for name resolution), and
 * `VisionApi.fleetMaintenance('all')` — every maintenance record the caller's scope includes, open
 * or closed, in one call. Unlike the pre-W9 per-asset reads (which degraded silently, one asset at a
 * time, since they were enrichment on top of an already-shown `assets` list), the three fetches now
 * run in one `Promise.all`: a failed `fleetMaintenance` read is this page's *primary* content, not an
 * add-on, so it surfaces the same page-level error empty-state a failed `listAssets`/`listUsers`
 * already did, never a silently-empty table with no explanation (CLAUDE.md's "never a blocked page,
 * never a fabricated value" — an honest error state is neither).
 *
 * Every KPI/table/groundable-list derivation is pure (`core/maintenance/maintenance-logic.ts`) — this
 * class only owns the fetch, the "Ground a vehicle" form's view state, the three mutations
 * (`submitGround`/`closeRecord`/`release`, each reloading the full page afterward rather than
 * hand-patching local state — the fleet-wide read is now one call plus `listAssets`/`listUsers`, cheap
 * enough that a full reload is simpler and can't drift from the server's own state machine), and the
 * per-record category-name/custodian lookups the table needs (`FleetMaintenanceRecord` carries its
 * own `assetName`/`categoryId` slug already — see that type's own doc comment — but not the asset's
 * human category *name* or its custody, both of which still need the loaded `assets` list).
 */
@Injectable()
export class MaintenanceFacade {
  private readonly api = inject(VisionApi);
  private readonly toasts = inject(ToastService);

  readonly loading = signal(true);
  readonly error = signal<string | null>(null);

  private readonly assets = signal<readonly AssetSummary[]>([]);
  private readonly users = signal<readonly UserSummary[]>([]);
  private readonly records = signal<readonly FleetMaintenanceRecord[]>([]);

  private readonly assetsById = computed(() => new Map(this.assets().map((asset) => [asset.assetId, asset])));
  /** `userId → display name` — backs both {@link displayNameFor} ("Opened by") and {@link custodianLabelFor} ("Custodian"). */
  private readonly nameById = computed(() => new Map(this.users().map((user) => [user.userId, user.displayName])));

  readonly hasAnyAssets = computed(() => this.assets().length > 0);
  readonly kpis = computed<MaintenanceKpis>(() => maintenanceKpis(this.assets(), this.records()));
  readonly openRows = computed<readonly FleetMaintenanceRecord[]>(() => openRecords(this.records()));
  readonly closedRows = computed<readonly FleetMaintenanceRecord[]>(() => recentlyClosedRecords(this.records()));
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
      const [assets, users, records] = await Promise.all([
        this.api.listAssets(),
        this.api.listUsers(),
        this.api.fleetMaintenance('all'),
      ]);
      this.assets.set(assets);
      this.users.set(users);
      this.records.set(records);
    } catch (error) {
      this.error.set(describeHttpError(error));
    } finally {
      this.loading.set(false);
    }
  }

  /**
   * "Opened by" column — `core/audit/summary-logic.ts#actorLabel`, the same one-owner vocabulary the
   * Audit page already uses: the synthetic root/dev principal (`ROOT_ACTOR_ID`, `UUID(0,0)` — every
   * record opened with `vision.auth.enabled=false`, dev-parity's own unbounded ADMIN) reads
   * "Station" rather than the id's own unreadable "00000000…" prefix (W10 finding M1), a known org
   * user reads their display name, and anything else (deactivated/removed) degrades to the id's own
   * first 8 characters — never a blank cell, never a guess.
   */
  displayNameFor(userId: string): string {
    return actorLabel(userId, this.nameById());
  }

  /** "Category" column — the asset's human-readable category name when it's still loaded locally; falls back to the record's own `categoryId` slug otherwise (never a blank cell). */
  categoryNameFor(record: FleetMaintenanceRecord): string {
    return this.assetsById().get(record.assetId)?.categoryName ?? record.categoryId;
  }

  /** "Custodian" column (WAREHOUSE-UX-PLAN.md §3.2 D3) — `undefined` when the asset is in stock (no custodian) or no longer loaded, rendered as "—" by the template. */
  custodianLabelFor(record: FleetMaintenanceRecord): string | undefined {
    const custodianId = this.assetsById().get(record.assetId)?.custody?.custodianId;
    return custodianId ? this.displayNameFor(custodianId) : undefined;
  }

  /** Open-records table's Close/Release button treatment (W10 finding M1) — see `core/maintenance/maintenance-logic.ts#isLastOpenRecordForAsset`'s own doc comment for the reasoning. */
  isLastOpenRecord(record: FleetMaintenanceRecord): boolean {
    return isLastOpenRecordForAsset(record, this.records());
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

  async closeRecord(record: FleetMaintenanceRecord): Promise<void> {
    if (this.busyRecordId()) {
      return;
    }
    this.busyRecordId.set(record.id);
    try {
      await this.api.closeMaintenanceRecord(record.assetId, record.id);
      this.toasts.ok('Record closed.');
      await this.load();
    } catch (error) {
      this.toasts.error(this.describeMutationError(error, 'close a record on'));
    } finally {
      this.busyRecordId.set(null);
    }
  }

  async release(assetId: string): Promise<void> {
    if (this.busyAssetId()) {
      return;
    }
    this.busyAssetId.set(assetId);
    try {
      await this.api.setAssetInventory(assetId, { action: 'RELEASE' });
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
