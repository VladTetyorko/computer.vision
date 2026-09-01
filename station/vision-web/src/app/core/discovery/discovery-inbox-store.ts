import { Injectable, inject, signal } from '@angular/core';
import { VisionApi } from '../api/vision-api';
import { describeHttpError } from '../api-error';
import { PollScheduler } from '../poll-scheduler';
import { ToastService } from '../toast.service';
import type {
  DiscoveryCandidate,
  RegisterDeviceRequest,
  RegisterDiscoveryCandidateRequest,
  RegisterDiscoveryCandidateResponse,
} from '../api/models';

/** Matches the backend sweep's own cadence (`vision.discovery.inbox.sweep-seconds=30`,
 *  ZERO-CONFIG-ONBOARDING-CONTEXT.md §11 Z2c) — polling faster would never see anything newer. */
const POLL_INTERVAL_MS = 30_000;

/**
 * The discovery inbox's store (docs/plans/active/ZERO-CONFIG-ONBOARDING-CONTEXT.md §3 P2, §11,
 * wave Z2d) — one `GET /api/discovery/inbox` poll plus the three mutations (`register`/`dismiss`,
 * and the `attach` two-step: `registerDevice` + `assignDevice`), feeding `FoundDevices` (the
 * Inventory page's own section) and its cards.
 *
 * **`providedIn: 'root'`, activated/released like `EventsStore`** — a single consumer today
 * (`FoundDevices`, mounted once inside `InventoryPage`), but the app's own convention for a
 * poll-backed store is root-singleton + `activate()`/`release()` refcounting rather than a
 * component-scoped provider (`FleetStore`/`EventsStore`/`TelemetryStore` all follow this), so a
 * second future consumer (a Manage-page badge, say) shares the one poll rather than starting a
 * second one. The poll starts on the first `activate()` and stops on the matching `release()` —
 * "no polling when unmounted" falls out of that refcount reaching zero, not a bespoke teardown.
 *
 * **No optimistic state.** `register`/`dismiss`/`attach` never guess at a result before the
 * server confirms one. `dismiss` patches the one candidate's row with the server's own returned
 * (already-mutated) representation — not a guess, the real response. `register` re-fetches the
 * whole list on success instead of synthesizing a `REGISTERED` row locally, since
 * `RegisterDiscoveryCandidateResponse` doesn't carry the candidate's own new status. `attach`
 * patches nothing at all: the candidate's flip to `REGISTERED` happens on the backend's own next
 * sweep (its duplicate-match rule against the newly-registered device), not synchronously with
 * this call — faking that flip client-side would show a state the server hasn't reached yet, so
 * the next poll (≤30s) is left to show it for real (see `attach`'s own doc comment).
 *
 * **Errors are exactly one toast, via the same `run()` idiom `FleetStore` uses** — a background
 * poll failure is the one deliberate exception (silently kept-stale, like every other poller in
 * this app degrading to "last known good" rather than toast-spamming every 30s).
 */
@Injectable({ providedIn: 'root' })
export class DiscoveryInboxStore {
  private readonly api = inject(VisionApi);
  private readonly toasts = inject(ToastService);
  private readonly scheduler = inject(PollScheduler);

  private readonly candidatesSignal = signal<readonly DiscoveryCandidate[]>([]);
  readonly candidates = this.candidatesSignal.asReadonly();

  readonly loading = signal(false);
  /** The one candidate id currently mid-mutation (register/dismiss/attach) — disables that card's
   *  own buttons without freezing the rest of the list, mirrors `FleetStore#busyAssetId`. */
  readonly busyId = signal<string | null>(null);

  private activeConsumers = 0;
  private stopPollingFn: (() => void) | null = null;

  /** Registers interest — call once from a consumer's constructor. The first `activate()` since
   *  the last full `release()` triggers an immediate fetch and starts the shared 30s cadence. */
  activate(): void {
    this.activeConsumers++;
    if (this.activeConsumers === 1) {
      void this.refresh();
      this.stopPollingFn = this.scheduler.schedule(POLL_INTERVAL_MS, () => this.refresh());
    }
  }

  /** The matching teardown — call from `DestroyRef.onDestroy`. Stops polling once nothing is left. */
  release(): void {
    if (this.activeConsumers === 0) {
      return; // defensive — a mismatched release should never go negative
    }
    this.activeConsumers--;
    if (this.activeConsumers === 0) {
      this.stopPollingFn?.();
      this.stopPollingFn = null;
    }
  }

  /** Silent-degrade poll fetch — a failed read keeps the last-known list rather than toasting on
   *  every 30s tick or blanking a section that was working a moment ago (CLAUDE.md "degrade
   *  honestly": stale data, never a fabricated value or a blocked page). */
  async refresh(): Promise<void> {
    this.loading.set(true);
    try {
      this.candidatesSignal.set(await this.api.listDiscoveryInboxCandidates());
    } catch (error) {
      console.warn('[discovery-inbox] refresh failed', { error });
    } finally {
      this.loading.set(false);
    }
  }

  /** One-click Add (§11 Z2d) — registers the candidate as a new asset. On success, re-fetches the
   *  inbox (the response has no candidate-status field of its own to patch with — see class doc)
   *  and returns the pointer at the created asset for the dialog to route to. */
  async register(
    id: string,
    request: RegisterDiscoveryCandidateRequest,
  ): Promise<RegisterDiscoveryCandidateResponse | null> {
    this.busyId.set(id);
    try {
      const result = await this.run(() => this.api.registerDiscoveryCandidate(id, request));
      if (result) {
        this.toasts.ok(`Added "${result.displayName}" to inventory.`);
        await this.refresh();
      }
      return result;
    } finally {
      this.busyId.set(null);
    }
  }

  /** "Attach to existing asset" (§11 Z2d) — registers an unowned `Device` from the candidate's own
   *  suggested stream, then assigns it onto `assetId` via the *existing* `POST
   *  /api/assets/{id}/devices` endpoint (`VisionApi#assignDevice`, unchanged). Two REST calls, one
   *  `run()` — a failure partway (e.g. `registerDevice` succeeds, `assignDevice` 409s because the
   *  asset already has that device) still surfaces as the one toast this idiom promises; the
   *  now-unowned device this leaves behind is visible/reusable from the Links tab, not silently
   *  lost. See `buildDeviceSpecFromCandidate`'s own doc comment for why nothing here patches the
   *  candidate's status — that is the backend sweep's job, not this call's.
   */
  async attach(id: string, deviceSpec: RegisterDeviceRequest, assetId: string): Promise<boolean> {
    this.busyId.set(id);
    try {
      const result = await this.run(async () => {
        const device = await this.api.registerDevice(deviceSpec);
        return this.api.assignDevice(assetId, device.id);
      });
      if (result) {
        this.toasts.ok(`Attached "${deviceSpec.name}" to "${result.displayName}".`);
      }
      return result !== null;
    } finally {
      this.busyId.set(null);
    }
  }

  /** No confirm — reversible in spirit (the "show dismissed" toggle still shows it). Patches with
   *  the server's own returned candidate (real, not guessed). */
  async dismiss(id: string): Promise<void> {
    this.busyId.set(id);
    try {
      const result = await this.run(() => this.api.dismissDiscoveryCandidate(id));
      if (result) {
        this.candidatesSignal.update((list) => list.map((candidate) => (candidate.id === id ? result : candidate)));
      }
    } finally {
      this.busyId.set(null);
    }
  }

  /** Exactly one toast on failure, `null` on failure — the same shape `FleetStore#run` uses. */
  private async run<T>(action: () => Promise<T>): Promise<T | null> {
    try {
      return await action();
    } catch (error) {
      console.warn('[discovery-inbox] action failed', { error });
      this.toasts.error(describeHttpError(error));
      return null;
    }
  }
}
