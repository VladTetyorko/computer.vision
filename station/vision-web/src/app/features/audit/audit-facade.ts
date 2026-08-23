import { Injectable, computed, inject, signal } from '@angular/core';
import { VisionApi } from '../../core/api/vision-api';
import { describeHttpError } from '../../core/api-error';
import type { AssetSummary, AuditEntry, UserSummary } from '../../core/api/models';
import {
  buildAuditRows,
  distinctActions,
  distinctActors,
  filterAuditRows,
  type AuditOption,
  type AuditRow,
} from '../../core/audit/audit-logic';

/** How many recent entries to request — a fleet-wide trail is busier than one user's own (`ActivityFacade`'s 100), so this page asks for twice that. No backend cap is documented (unlike `myActivity`'s 500) — `AuditController` simply bounds the read to whatever `limit` says. */
const AUDIT_FETCH_LIMIT = 200;

/**
 * `AuditPage`'s facade (docs/plans/done/OPS-UX-PLAN.md §3 B1, docs/plans/done/UI-ARCHITECTURE-PLAN.md layering) —
 * mirrors `features/activity/activity-facade.ts`'s one-shot-load shape almost exactly, fleet-wide
 * instead of "just me": `VisionApi.listAudit` is the primary call, whose failure (most notably a
 * `403` for anyone who isn't ADMIN/MANAGER, or a stale session whose role changed mid-session even
 * though the route itself is already `orgGuard`-gated) is the page's own error state — never
 * silently swallowed into an empty-looking table (docs/conclusions/OPS-UX-REVIEW.md §U3's own framing).
 *
 * `listUsers`/`listAssets` are loaded alongside it purely for **name resolution** (actor/target
 * display names) — a best-effort enrichment read, degrading to a short id fragment per row rather
 * than failing the whole page, matching this app's "a failed enrichment read shows a fallback, never
 * blocks the page" convention (`RosterFacade.load`'s identical per-asset `catch` for the same
 * reason).
 */
@Injectable()
export class AuditFacade {
  private readonly api = inject(VisionApi);

  readonly loading = signal(true);
  readonly error = signal<string | null>(null);
  readonly actorFilter = signal('');
  readonly actionFilter = signal('');

  private readonly entries = signal<readonly AuditEntry[]>([]);
  private readonly users = signal<readonly UserSummary[]>([]);
  private readonly assets = signal<readonly AssetSummary[]>([]);
  private readonly nowMs = signal(Date.now());

  /** Every loaded entry, enriched — unfiltered (the count chip and the filter option lists both read from this, not the filtered subset, so switching a filter never shrinks its own option list). */
  readonly rows = computed<readonly AuditRow[]>(() => buildAuditRows(this.entries(), this.users(), this.assets(), this.nowMs()));

  readonly filteredRows = computed<readonly AuditRow[]>(() =>
    filterAuditRows(this.rows(), {
      actorId: this.actorFilter() || undefined,
      action: this.actionFilter() || undefined,
    }),
  );

  readonly actorOptions = computed<readonly AuditOption[]>(() => distinctActors(this.rows()));
  readonly actionOptions = computed<readonly AuditOption[]>(() => distinctActions(this.rows()));

  readonly hasActiveFilters = computed(() => this.actorFilter() !== '' || this.actionFilter() !== '');

  constructor() {
    void this.load();
  }

  async load(): Promise<void> {
    this.loading.set(true);
    this.error.set(null);
    try {
      const [entries, users, assets] = await Promise.all([
        this.api.listAudit({ limit: AUDIT_FETCH_LIMIT }),
        this.api.listUsers().catch(() => [] as UserSummary[]),
        this.api.listAssets().catch(() => [] as AssetSummary[]),
      ]);
      this.nowMs.set(Date.now());
      this.entries.set(entries);
      this.users.set(users);
      this.assets.set(assets);
    } catch (error) {
      this.error.set(describeHttpError(error));
    } finally {
      this.loading.set(false);
    }
  }

  clearFilters(): void {
    this.actorFilter.set('');
    this.actionFilter.set('');
  }
}
