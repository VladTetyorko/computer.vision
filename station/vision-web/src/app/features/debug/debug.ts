import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { PageBar } from '../../shared/ui/page-bar/page-bar';
import { healthLabel, healthSeverity } from '../../core/system-status/system-status-logic';
import { SystemStatusStore } from '../../core/system-status/system-status-store';
import { DebugApiService, type RawResponse } from './debug-api.service';
import { DEBUG_ENDPOINTS, methodHasBody, prefillForEndpoint } from './debug-endpoints';
import { describeHealthProbe, formatResponseBody, isSuccessStatus, type FormattedBody } from './debug-response';
import { DEBUG_HISTORY_LIMIT, pushHistoryEntry, type DebugHistoryEntry } from './debug-history';

/** The shape this page cares about from Spring Boot Actuator's `/actuator/health` body. */
interface HealthDocument {
  readonly status?: string;
  readonly components?: Record<string, { status?: string } | undefined>;
}

interface HealthComponentStatus {
  readonly name: string;
  readonly status: string;
}

/** The HTTP methods a raw console should let you try — wider than any single endpoint needs. */
const METHODS = ['GET', 'POST', 'PUT', 'PATCH', 'DELETE'] as const;

/**
 * `/debug` — the raw API console (WEB-PLAN W5).
 *
 * **Page bar + health-probe correctness fix (docs/plans/done/NAV-IA-REDESIGN-PLAN.md §2.2, docs/extracts/design/18-debug.md,
 * wave 2).** The old three-line description restated what "Debug" plus the console's own copy already
 * say, so it's deleted outright — no `hint`. `METHOD` (a 6-value enum) and `PATH` (the console's own
 * request path — the literal "API console path" example the `--field-lg` token names) each carry the
 * new field-width buckets. **`healthProbe` is the correctness fix**: see `describeHealthProbe`'s own
 * doc comment (`debug-response.ts`) for why a missing probe (this deployment has no actuator
 * dependency at all) must never render as a red failure the way an actually-unhealthy system would.
 * **The response pane, request-history panel, and demoting Health to a header chip are out of this
 * wave's scope** (docs/plans/done/NAV-IA-REDESIGN-PLAN.md's own wave-2 brief) — the console/Health/Last-scan
 * three-card layout is otherwise unchanged.
 *
 * **Health card repointed at `GET /api/system/status` (docs/plans/done/SYSTEM-STATUS-PLAN.md §5.3, wave S3).**
 * The card's primary content is now the platform's own self-reported status (`SystemStatusStore`,
 * the same `providedIn: 'root'` singleton the shell rollup dot and `/manage/system` both read — no
 * second HTTP call, this page is simply a third reader), with a link to the full `/manage/system`
 * page for the live-transport card and system-event log this card has no room for. The raw
 * `/actuator/health` probe (`describeHealthProbe`/`healthComponents`/`healthRaw` below, **kept
 * byte-for-byte** per this wave's own instruction — "its verdict logic is sound and should be kept")
 * moves into a collapsed `<details>` as a secondary, lower-level probe — now that §4.4 wires Actuator
 * for real, this is the one place to check whether the two ever disagree, rather than the console's
 * one and only health source. **This page is not in `core/ui/architecture.spec.ts`'s `ROUTED_PAGES`
 * list** (it predates the facade sweep and stays exempt, matching its own pre-existing "injects
 * `DebugApiService` directly" shape) — `inject(SystemStatusStore)` below is therefore in-contract,
 * unlike a page the architecture guard does scan.
 */
@Component({
  selector: 'vision-debug',
  imports: [FormsModule, PageBar, RouterLink],
  templateUrl: './debug.html',
  styleUrl: './debug.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class DebugPage {
  private readonly api = inject(DebugApiService);
  private readonly systemStatusStore = inject(SystemStatusStore);

  protected readonly endpoints = DEBUG_ENDPOINTS;
  protected readonly methods = METHODS;
  protected readonly historyLimit = DEBUG_HISTORY_LIMIT;

  // --- System status (docs/plans/done/SYSTEM-STATUS-PLAN.md §5.3) --------------------------------

  /** Already warm by the time this page mounts — `AppSidebar` (always mounted) injects the same
   *  singleton for its own rollup dot, so there is no extra fetch to kick off here (contrast
   *  `loadHealth()` below, this page's own one-shot actuator probe). */
  protected readonly systemStatus = this.systemStatusStore.status;
  protected readonly systemStatusLoading = this.systemStatusStore.loading;
  protected readonly systemStatusError = this.systemStatusStore.error;
  protected readonly healthLabel = healthLabel;
  protected readonly healthSeverity = healthSeverity;

  protected refreshSystemStatus(): Promise<void> {
    return this.systemStatusStore.refresh();
  }

  constructor() {
    // Best-effort convenience; a stale/absent result is still shown honestly (see template).
    void this.loadHealth();
  }

  // --- Raw API console -------------------------------------------------------

  protected readonly selectedEndpointId = signal('');
  protected readonly method = signal('GET');
  protected readonly path = signal('/api/devices');
  protected readonly body = signal('');
  protected readonly sending = signal(false);
  protected readonly response = signal<RawResponse | null>(null);
  protected readonly history = signal<readonly DebugHistoryEntry[]>([]);

  protected readonly showBody = computed(() => methodHasBody(this.method()));
  protected readonly formatted = computed<FormattedBody | null>(() => {
    const current = this.response();
    return current ? formatResponseBody(current.bodyText) : null;
  });

  protected selectEndpoint(id: string): void {
    this.selectedEndpointId.set(id);
    const prefill = prefillForEndpoint(id);
    if (!prefill) {
      return;
    }
    this.method.set(prefill.method);
    this.path.set(prefill.path);
    this.body.set(prefill.body);
  }

  protected async send(): Promise<void> {
    const method = this.method();
    const path = this.path();
    const body = this.showBody() ? this.body() : undefined;

    this.sending.set(true);
    try {
      const result = await this.api.send({ method, path, body });
      this.response.set(result);
      this.history.update((current) =>
        pushHistoryEntry(current, {
          method,
          path,
          body,
          status: result.status,
          statusText: result.statusText,
          ms: Math.round(result.ms),
        }),
      );
    } finally {
      this.sending.set(false);
    }
  }

  /** Re-fills the form from a history row; the user still presses Send. */
  protected replay(entry: DebugHistoryEntry): void {
    this.selectedEndpointId.set('');
    this.method.set(entry.method);
    this.path.set(entry.path);
    this.body.set(entry.body ?? '');
    this.response.set(null);
  }

  protected isOk(status: number): boolean {
    return isSuccessStatus(status);
  }

  protected headerEntries(headers: Record<string, string>): { key: string; value: string }[] {
    return Object.entries(headers).map(([key, value]) => ({ key, value }));
  }

  // --- Health ------------------------------------------------------------

  protected readonly healthLoading = signal(false);
  protected readonly healthResponse = signal<RawResponse | null>(null);

  private readonly parsedHealth = computed<HealthDocument | null>(() => {
    const current = this.healthResponse();
    if (!current?.bodyText) {
      return null;
    }
    try {
      return JSON.parse(current.bodyText) as HealthDocument;
    } catch {
      return null;
    }
  });

  /** The chip's kind/label/tone — `null` before the first check ever completes. See `describeHealthProbe`'s own doc comment (`debug-response.ts`) for the "missing probe" vs. "unhealthy system" distinction this drives. */
  protected readonly healthProbe = computed(() => describeHealthProbe(this.healthResponse()));

  protected readonly healthComponents = computed<readonly HealthComponentStatus[]>(() => {
    const components = this.parsedHealth()?.components;
    if (!components) {
      return [];
    }
    return Object.entries(components)
      .map(([name, value]) => ({ name, status: value?.status ?? 'UNKNOWN' }))
      .sort((a, b) => a.name.localeCompare(b.name));
  });

  protected readonly healthRaw = computed<FormattedBody | null>(() => {
    const current = this.healthResponse();
    return current ? formatResponseBody(current.bodyText) : null;
  });

  protected async loadHealth(): Promise<void> {
    this.healthLoading.set(true);
    try {
      this.healthResponse.set(await this.api.send({ method: 'GET', path: '/actuator/health' }));
    } finally {
      this.healthLoading.set(false);
    }
  }

  // --- Last scan -----------------------------------------------------------

  protected readonly scanLoading = signal(false);
  protected readonly scanResponse = signal<RawResponse | null>(null);

  protected readonly scanFormatted = computed<FormattedBody | null>(() => {
    const current = this.scanResponse();
    return current ? formatResponseBody(current.bodyText) : null;
  });

  protected async runScan(): Promise<void> {
    this.scanLoading.set(true);
    try {
      this.scanResponse.set(
        await this.api.send({ method: 'POST', path: '/api/discovery/scan', body: '{}' }),
      );
    } finally {
      this.scanLoading.set(false);
    }
  }
}
