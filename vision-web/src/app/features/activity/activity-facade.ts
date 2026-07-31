import { Injectable, computed, inject, signal } from '@angular/core';
import { VisionApi } from '../../core/api/vision-api';
import { describeHttpError } from '../../core/api-error';
import { formatActivity } from '../../core/org/org-logic';
import type { AuditEntry } from '../../core/api/models';

/** How many recent entries to request — the backend caps at 500; 100 is plenty for a "recent activity" read. */
const ACTIVITY_LIMIT = 100;

/**
 * `ActivityPage`'s facade (docs/UI-ARCHITECTURE-PLAN.md). Calls `VisionApi.myActivity` directly
 * rather than through a store — mirrors `features/fly/flight-command-panel.ts`'s own precedent for
 * a one-shot, page-scoped read with no shared/reusable state a store would sensibly model (no other
 * surface in this app reads "my activity"). Every read-model/command below is byte-for-byte what
 * `ActivityPage` owned before this refactor.
 */
@Injectable()
export class ActivityFacade {
  private readonly api = inject(VisionApi);

  private readonly entries = signal<readonly AuditEntry[]>([]);
  private readonly nowMs = signal(Date.now());

  readonly loading = signal(true);
  readonly error = signal<string | null>(null);

  /** The rows, pre-formatted — recomputed only if the entries or the reference clock change. */
  readonly rows = computed(() => this.entries().map((entry) => formatActivity(entry, this.nowMs())));

  constructor() {
    void this.load();
  }

  async load(): Promise<void> {
    this.loading.set(true);
    this.error.set(null);
    try {
      const entries = await this.api.myActivity(ACTIVITY_LIMIT);
      this.nowMs.set(Date.now());
      this.entries.set(entries);
    } catch (error) {
      this.error.set(describeHttpError(error));
    } finally {
      this.loading.set(false);
    }
  }
}
