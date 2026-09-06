import { Injectable, effect, inject, signal } from '@angular/core';
import { VisionApi } from '../api/vision-api';
import { describeHttpError } from '../api-error';
import { PollScheduler } from '../poll-scheduler';
import { ToastService } from '../toast.service';
import { LiveStore } from '../live/live-store';
import { isLiveAvailable } from '../live/live-fallback-logic';
import { applyDiscoveryEvents } from './discovery-inbox-logic';
import type {
  DiscoveryCandidate,
  DiscoverySource,
  RegisterDeviceRequest,
  RegisterDiscoveryCandidateRequest,
  RegisterDiscoveryCandidateResponse,
} from '../api/models';

/** Matches the backend sweep's own cadence (`vision.discovery.inbox.sweep-seconds=30`,
 *  ZERO-CONFIG-ONBOARDING-CONTEXT.md §11 Z2c) — polling faster would never see anything newer.
 *
 *  **Gated on live, not unconditional** (docs/plans/active/LIVE-POLL-RETIREMENT-PLAN.md §3 D1) —
 *  runs **only** while `activeConsumers > 0` **and** `LiveStore` is not `'open'`; see
 *  `applyTransport`'s own doc comment for the state table (the same one `MarksStore`/`FleetStore`/
 *  `EventsStore` each implement). **`sources` is the one thing this poll alone still populates** —
 *  the `discovery` topic carries only candidates, never sources — so the reconcile this gate
 *  performs on every genuine reconnect is not a nicety here, it is the only thing that keeps
 *  `sources` from going stale forever once live is up. */
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
 *
 * **`sources` (A3, docs/plans/active/ASSET-FLOWS-PLAN.md §2)** — since this wave, `GET
 * /api/discovery/inbox` answers `{candidates, sources}` rather than a bare array; `sources` is each
 * discovery mechanism's own reachability, feeding `core/discovery/discovery-inbox-logic.ts#sourceUnreachableWarnings`
 * so `FoundDevices` can say "mediamtx unreachable" instead of leaving an operator staring at an
 * ambiguous empty list. Degrades the same way `candidates` does — a failed poll leaves both
 * signals exactly as they were.
 *
 * **The `discovery` SSE topic (W1, docs/plans/active/SOURCE-ONBOARDING-2-PLAN.md §3.2 C4)** now
 * layers on top of that poll, exactly like `MarksStore` layers `map` on top of its own `GET
 * /api/map/marks` — the poll is now the fallback rather than an unconditional safety net (see
 * `POLL_INTERVAL_MS`'s own doc comment on the D1 gate and the `sources` gap it exists to cover),
 * while the always-on, delta-only feed (`LiveStore.discoveryEvents`) folds in near-instant
 * `REPORTED`/`REGISTERED`/`DISMISSED`/`RESTORED` *candidate* changes via
 * `discovery-inbox-logic.ts#applyDiscoveryEvents`. This fold runs unconditionally from construction
 * (not gated by `activate`/`release`) — it costs nothing but an array upsert against a signal the
 * shared `/api/live` connection already carries regardless of whether this store has an active
 * consumer right now, and keeps `candidates` warm for the next `activate()` instead of every mount
 * starting from a stale poll.
 *
 * **Polling is gated on live too now (docs/plans/active/LIVE-POLL-RETIREMENT-PLAN.md §3 D1, L2)** —
 * `activeConsumers > 0 && !isLiveAvailable(...)` is the only state that runs the 30s poll; see
 * {@link applyTransport} for the exact table (identical shape to `MarksStore`/`FleetStore`/
 * `EventsStore`, copied rather than shared per that plan's own D1 reasoning). Note this store keeps
 * its pre-existing `=== 1`/`stopPollingFn` naming rather than the `core/map-data/**` stores'
 * `> 1`/`stopPollFn` — a pre-existing, harmless divergence, left as found.
 */
@Injectable({ providedIn: 'root' })
export class DiscoveryInboxStore {
  private readonly api = inject(VisionApi);
  private readonly toasts = inject(ToastService);
  private readonly scheduler = inject(PollScheduler);
  private readonly live = inject(LiveStore);

  private readonly candidatesSignal = signal<readonly DiscoveryCandidate[]>([]);
  readonly candidates = this.candidatesSignal.asReadonly();

  private readonly sourcesSignal = signal<readonly DiscoverySource[]>([]);
  readonly sources = this.sourcesSignal.asReadonly();

  readonly loading = signal(false);
  /** The one candidate id currently mid-mutation (register/dismiss/attach/restore) — disables that
   *  card's own buttons without freezing the rest of the list, mirrors `FleetStore#busyAssetId`. */
  readonly busyId = signal<string | null>(null);

  private activeConsumers = 0;
  private stopPollingFn: (() => void) | null = null;

  /**
   * `false` while this store is (or should be) relying on the poll rather than live — see
   * {@link applyTransport}'s own doc comment for the full state table this tracks. Starts `false`
   * so this store's very first `applyTransport` call — whichever way `liveAvailable` resolves — is
   * always treated as a genuine transition, never a spurious no-op.
   */
  private liveGated = false;

  /** How many live `discovery` deltas this store has already folded in — see `MarksStore`'s identical cursor. */
  private processedLiveEventCount = 0;

  constructor() {
    effect(() => {
      const events = this.live.discoveryEvents();
      if (events.length <= this.processedLiveEventCount) {
        return;
      }
      const newEvents = events.slice(this.processedLiveEventCount);
      this.processedLiveEventCount = events.length;
      this.candidatesSignal.update((candidates) => applyDiscoveryEvents(candidates, newEvents));
    });

    // Re-evaluates poll-vs-live whenever `LiveStore` (re)connects or drops
    // (docs/plans/active/LIVE-POLL-RETIREMENT-PLAN.md §3 D1) — mirrors `FleetStore`/
    // `EventsStore`'s identical reconnect-driven effect.
    effect(() => {
      this.applyTransport(isLiveAvailable(this.live.connectionState()));
    });
  }

  /** Registers interest — call once from a consumer's constructor. The first `activate()` since
   *  the last full `release()` routes through {@link applyTransport} with the current transport. */
  activate(): void {
    this.activeConsumers++;
    if (this.activeConsumers === 1) {
      this.applyTransport(isLiveAvailable(this.live.connectionState()));
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

  /**
   * D1's frozen gate (docs/plans/active/LIVE-POLL-RETIREMENT-PLAN.md §3) — see
   * `MarksStore.applyTransport`'s own doc comment for the full state table; this is the identical
   * shape. Called both by the reconnect-driven `effect()` above and by `activate()` itself. The
   * refresh this performs on every genuine reconnect (poll → live) is what keeps `sources` from
   * going stale forever, since the `discovery` topic never carries it — see this class's own
   * `POLL_INTERVAL_MS` doc comment.
   */
  private applyTransport(liveAvailable: boolean): void {
    if (this.activeConsumers === 0) {
      this.stopPolling();
      return;
    }
    if (liveAvailable) {
      if (!this.liveGated) {
        this.stopPolling();
        void this.refresh();
        this.liveGated = true;
      }
      return;
    }
    this.liveGated = false;
    if (this.stopPollingFn !== null) {
      return; // already polling
    }
    void this.refresh();
    this.stopPollingFn = this.scheduler.schedule(POLL_INTERVAL_MS, () => this.refresh());
  }

  private stopPolling(): void {
    this.stopPollingFn?.();
    this.stopPollingFn = null;
  }

  /** Silent-degrade poll fetch — a failed read keeps the last-known list rather than toasting on
   *  every 30s tick or blanking a section that was working a moment ago (CLAUDE.md "degrade
   *  honestly": stale data, never a fabricated value or a blocked page). */
  async refresh(): Promise<void> {
    this.loading.set(true);
    try {
      const response = await this.api.listDiscoveryInboxCandidates();
      this.candidatesSignal.set(response.candidates);
      this.sourcesSignal.set(response.sources);
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

  /**
   * Atomic "this candidate *is* that asset" (W3, docs/plans/active/SOURCE-ONBOARDING-2-PLAN.md
   * C1) — one call, `POST /api/discovery/inbox/{id}/attach`, replacing {@link attach}'s two-step
   * register-then-assign dance for the common "it's my rover, I already registered it, this is
   * just its camera" case. **Left `attach()` above untouched on purpose** — existing callers
   * (`FoundDevices`/`AttachCandidateDialog`, pre-dating this wave) keep working unchanged until W3
   * repoints them; this is a new, additive method. Unlike `attach()`, the server flips the
   * candidate to `REGISTERED` synchronously with this call (see `DiscoveryInboxService#attach`'s
   * own contract) — no sweep-lag wait, so the response is adopted directly instead of triggering a
   * re-fetch.
   */
  async attachCandidate(id: string, assetId: string): Promise<boolean> {
    this.busyId.set(id);
    try {
      const result = await this.run(() => this.api.attachDiscoveryCandidate(id, { assetId }));
      if (result) {
        this.candidatesSignal.update((list) => list.map((candidate) => (candidate.id === id ? result : candidate)));
        this.toasts.ok(`Attached "${result.name}" to the asset.`);
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

  /** Undoes a `dismiss` — `POST /api/discovery/inbox/{id}/restore` puts a `DISMISSED` candidate
   *  back to `NEW` (W3). Patches with the server's own returned candidate, same shape as `dismiss`. */
  async restore(id: string): Promise<void> {
    this.busyId.set(id);
    try {
      const result = await this.run(() => this.api.restoreDiscoveryCandidate(id));
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
