import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { DebugApiService, type RawResponse } from './debug-api.service';
import { DEBUG_ENDPOINTS, methodHasBody, prefillForEndpoint } from './debug-endpoints';
import { formatResponseBody, isSuccessStatus, type FormattedBody } from './debug-response';
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

@Component({
  selector: 'vision-debug',
  imports: [FormsModule],
  templateUrl: './debug.html',
  styleUrl: './debug.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class DebugPage {
  private readonly api = inject(DebugApiService);

  protected readonly endpoints = DEBUG_ENDPOINTS;
  protected readonly methods = METHODS;
  protected readonly historyLimit = DEBUG_HISTORY_LIMIT;

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

  protected readonly healthOverall = computed(() => this.parsedHealth()?.status ?? null);

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
