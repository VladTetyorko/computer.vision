import { DestroyRef, Injectable, computed, inject, signal } from '@angular/core';
import { VisionApi } from '../../core/api/vision-api';
import { AuthStore } from '../../core/auth/auth-store';
import { PollScheduler } from '../../core/poll-scheduler';
import { UiStore } from '../../core/ui/ui-store';
import { ToastService } from '../../core/toast.service';
import { describeHttpError } from '../../core/api-error';
import { canManageOrg } from '../../core/org/org-logic';
import {
  BLANK_REGION_INGEST_DRAFT,
  isVisualGeoDisabledError,
  regionIngestRequest,
  type RegionIngestDraft,
} from '../../core/geo/geo-logic';
import type { RegionProgressResponse, RegionResponse } from '../../core/api/models';

/** BUILDING regions' own progress is re-polled at this cadence — must be a multiple of `PollScheduler`'s 1s tick. */
const PROGRESS_POLL_INTERVAL_MS = 3_000;

/**
 * `RegionManagerPage`'s facade (docs/plans/active/VISUAL-GEO-V2-PLAN.md §3.8, wave H6) — `/manage/geo/regions`,
 * the reference-imagery region list + ingest form + live indexing progress. A single-consumer,
 * page-local read/write model, so this calls `VisionApi` directly rather than growing `GeoStore`
 * (per-asset correction tracking, unrelated) with region state — the same "a routed page's facade
 * may inject a service directly, not only a store" shape `RosterFacade`/`DatasetDetailFacade` already
 * use (see `core/training/training-store.ts`'s own doc comment for the identical single-consumer
 * reasoning).
 *
 * **Role gating mirrors `DatasetsFacade` exactly**: §3.3's own `POST`/`DELETE /api/geo/regions/**`
 * gate is `canAdminister`, not the `canManageOrg` this client can actually observe (`MeResponse` has
 * no finer-grained permission than `topRole` today) — {@link canManage} hides the ingest form and
 * every row's delete action from a PILOT client-side, same "never show a button that would only ever
 * 403" posture, and a genuine `canAdminister`/`canManageOrg` mismatch (if one exists) still degrades
 * honestly via {@link errorMessage} rather than a blocked page. **Dev parity**: `authEnabled=false`'s
 * dev principal resolves to `ADMIN`, so `canManage` is `true` and this page behaves exactly as it
 * does for a real admin.
 *
 * **Flag-off degrades to `disabled`, not an error** — mirrors `TrainingStore`'s identical D9-flavored
 * posture: the very first `refresh()` turning the D9 409 into {@link disabled} rather than a toast,
 * since an unflagged deployment is an expected, first-class outcome here, not a failure. Every *other*
 * failure (list refresh, ingest submit, delete) is one `ToastService` toast, exactly like
 * `TrainingStore.refresh`/`.run` — no page-local `errorMessage` signal, so this facade doesn't grow a
 * second, inconsistent error-reporting channel next to the one this app already has.
 * `region-manager.html` renders `vision-empty` on {@link disabled}, exactly like `DatasetsPage`'s own
 * precedent — `<vision-geo-chip>`/`<vision-tactical-map>`'s corrections layer are absent the same way
 * elsewhere in this wave, so the "every geo surface is absent, not empty" rule (§3.8's Off-state row)
 * is now satisfied consistently across all three surfaces this wave built.
 *
 * **`NEVER_ACCEPT` is a first-class, honestly-displayed state** (§3.8) — `region-manager.html` renders
 * it with `geo-logic.ts#regionStatusTone`'s own `'warn'` (never hidden, never folded into `FAILED`'s
 * `'danger'`) — see that function's own doc comment for why the two are deliberately distinct.
 */
@Injectable()
export class RegionManagerFacade {
  private readonly api = inject(VisionApi);
  private readonly auth = inject(AuthStore);
  private readonly scheduler = inject(PollScheduler);
  private readonly toasts = inject(ToastService);

  readonly canManage = computed(() => canManageOrg(this.auth.user()?.topRole));

  readonly regions = signal<readonly RegionResponse[]>([]);
  readonly loading = signal(true);
  /** `false` until the first `refresh()` settles — lets the page tell "still loading" from "genuinely no regions". */
  readonly loaded = signal(false);
  /** `true` once a `refresh()` has confirmed the D9 flag-off 409 — see class doc. */
  readonly disabled = signal(false);

  /** Live progress for every currently-`BUILDING` region — re-polled independently of the region list itself. */
  readonly progressByRegionId = signal<ReadonlyMap<string, RegionProgressResponse>>(new Map());

  // --- Ingest form (§3.8 — "an ingest form (bounds + zoom)") ---------------------------------------
  readonly draft = signal<RegionIngestDraft>(BLANK_REGION_INGEST_DRAFT);
  readonly submitting = signal(false);
  readonly canSubmit = computed(
    () => this.canManage() && !this.submitting() && regionIngestRequest(this.draft()) !== null,
  );

  /** Per-row delete confirm — one group, so at most one row's confirm is ever open at once (mirrors `DatasetsFacade.dialogs`). */
  readonly dialogs = new UiStore();
  readonly deletingRegionId = signal<string | undefined>(undefined);

  private progressStopFn: (() => void) | null = null;

  constructor() {
    void this.refresh();
    this.progressStopFn = this.scheduler.schedule(PROGRESS_POLL_INTERVAL_MS, () => this.refreshProgress());
    inject(DestroyRef).onDestroy(() => this.progressStopFn?.());
  }

  /** Re-reads the region list. A D9 409 sets {@link disabled} (an expected outcome, not an error); any other failure toasts while keeping the last-known list, same posture as `TrainingStore.refresh`. */
  async refresh(): Promise<void> {
    this.loading.set(true);
    try {
      const response = await this.api.listGeoRegions();
      this.regions.set(response.regions);
      this.disabled.set(false);
      await this.refreshProgress();
    } catch (error) {
      if (isVisualGeoDisabledError(error)) {
        this.regions.set([]);
        this.progressByRegionId.set(new Map());
        this.disabled.set(true);
      } else {
        this.toasts.error(describeHttpError(error));
      }
    } finally {
      this.loading.set(false);
      this.loaded.set(true);
    }
  }

  /** Re-polls `GET /api/geo/regions/{id}/progress` for every currently-`BUILDING` region — a best-effort refresh, never toasts (mirrors every other background poller in this app). */
  private async refreshProgress(): Promise<void> {
    if (this.disabled()) {
      return;
    }
    const building = this.regions().filter((region) => region.status === 'BUILDING');
    if (building.length === 0) {
      if (this.progressByRegionId().size > 0) {
        this.progressByRegionId.set(new Map());
      }
      return;
    }
    const entries = await Promise.all(
      building.map(async (region): Promise<readonly [string, RegionProgressResponse] | undefined> => {
        try {
          return [region.regionId, await this.api.geoRegionProgress(region.regionId)] as const;
        } catch {
          return undefined; // a single region's progress read failing never blocks the others
        }
      }),
    );
    const next = new Map<string, RegionProgressResponse>();
    for (const entry of entries) {
      if (entry) {
        next.set(entry[0], entry[1]);
      }
    }
    this.progressByRegionId.set(next);
  }

  updateDraft(patch: Partial<RegionIngestDraft>): void {
    this.draft.update((current) => ({ ...current, ...patch }));
  }

  /** `region-manager.html`'s own submit gate is {@link canSubmit}; the guard here just makes a stray call a safe no-op. */
  async submitIngest(): Promise<void> {
    const request = regionIngestRequest(this.draft());
    if (!request || this.submitting()) {
      return;
    }
    this.submitting.set(true);
    try {
      const region = await this.api.createGeoRegion(request);
      this.draft.set(BLANK_REGION_INGEST_DRAFT);
      await this.refresh();
      this.toasts.ok(`Ingesting "${region.name}" — watch its progress below.`);
    } catch (error) {
      this.toasts.error(describeHttpError(error));
    } finally {
      this.submitting.set(false);
    }
  }

  confirmDelete(regionId: string): void {
    this.dialogs.open(regionId);
  }

  cancelDelete(): void {
    this.dialogs.close();
  }

  async deleteConfirmed(regionId: string, name: string): Promise<void> {
    this.dialogs.close(regionId);
    this.deletingRegionId.set(regionId);
    try {
      await this.api.deleteGeoRegion(regionId);
      await this.refresh();
      this.toasts.ok(`Deleted "${name}".`);
    } catch (error) {
      this.toasts.error(describeHttpError(error));
    } finally {
      this.deletingRegionId.set(undefined);
    }
  }
}
