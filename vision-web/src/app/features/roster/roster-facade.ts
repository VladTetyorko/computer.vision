import { Injectable, computed, inject, signal } from '@angular/core';
import { VisionApi } from '../../core/api/vision-api';
import { describeHttpError } from '../../core/api-error';
import type { AssetSummary, AssignedPilot, UserSummary } from '../../core/api/models';
import { buildRosterRows, searchRosterRows, type RosterRow } from './roster-logic';

/**
 * `RosterPage`'s facade (docs/UI-ARCHITECTURE-PLAN.md) — the `/manage/roster` route is already
 * role-gated (`core/org/org-guard.ts`, same guard `/org` uses — only ADMIN/MANAGER reach this page
 * at all, so unlike `pilots-card.ts` this facade doesn't re-check `canManageOrg` itself, mirroring
 * `OrgSettingsFacade`'s own precedent for a route already behind that guard).
 *
 * Loads every asset (`VisionApi.listAssets`), the full user list (`listUsers`, once — for display
 * names), and each asset's own assigned pilots (`listAssetPilots`, one call per asset, in parallel —
 * mirrors `AssetsFacade.refreshAssets`'s identical N-call shape for `getAsset`) so the collapsed
 * roster row can show *who* is assigned without the manager needing to expand every row first. The
 * heavier add/remove UI (`<vision-pilots-card>`, unchanged, reused as-is) only mounts once a row is
 * expanded (`expanded`, a plain id set) — its own `(changed)` output (docs/UI-REDESIGN-PLAN.md Wave
 * 4, new) is what keeps this page's own pilot-name badges in sync after a mutation, via
 * {@link refreshPilotsFor}, without re-fetching the whole roster.
 */
@Injectable()
export class RosterFacade {
  private readonly api = inject(VisionApi);

  readonly loading = signal(true);
  readonly error = signal<string | null>(null);
  readonly searchQuery = signal('');

  private readonly assets = signal<readonly AssetSummary[]>([]);
  private readonly users = signal<readonly UserSummary[]>([]);
  private readonly pilotsByAsset = signal<ReadonlyMap<string, readonly AssignedPilot[]>>(new Map());
  private readonly expandedIds = signal<ReadonlySet<string>>(new Set());

  readonly hasAnyAssets = computed(() => this.assets().length > 0);
  readonly rows = computed<readonly RosterRow[]>(() =>
    searchRosterRows(buildRosterRows(this.assets(), this.pilotsByAsset(), this.users()), this.searchQuery()),
  );

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

  isExpanded(assetId: string): boolean {
    return this.expandedIds().has(assetId);
  }

  setExpanded(assetId: string, open: boolean): void {
    this.expandedIds.update((current) => {
      const next = new Set(current);
      if (open) {
        next.add(assetId);
      } else {
        next.delete(assetId);
      }
      return next;
    });
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
}
