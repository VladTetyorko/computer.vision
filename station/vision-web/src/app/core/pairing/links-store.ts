import { DestroyRef, Injectable, computed, effect, inject, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { VisionApi } from '../api/vision-api';
import { ToastService } from '../toast.service';
import { describeHttpError } from '../api-error';
import { PollScheduler } from '../poll-scheduler';
import { LiveStore } from '../live/live-store';
import { type AssetScopedTransport, resolveAssetScopedTransport } from '../live/live-fallback-logic';
import type { LinkGroupResponse } from '../api/models';

/** How often one asset's link group is re-read while **polling** (the fallback) is active — a
 *  failover/pin change matters within a few seconds, not `GeoStore`'s sub-2s cadence. */
const POLL_INTERVAL_MS = 5_000;

/**
 * Tracks one asset's whole link group (docs/plans/active/LINK-PAIRING-PLAN.md §3.4, wave L4) — the
 * asset-detail Links panel's own source. Polls `GET /api/assets/{id}/links` every 5s while visible,
 * or, when `LiveStore` is open, subscribes to that asset's live `links:<assetId>` topic instead — the
 * payload there is always the *whole* group snapshot, never a diff (`LinkGroupResponse`'s own doc
 * comment), so this store simply replaces its held value on every arrival exactly like
 * `GeoStore`/`DetectionsStore`'s own ring-capacity-1 topics. Modeled directly on
 * `core/geo/geo-store.ts#GeoStore` — same dual-transport shape, same component-provided lifecycle —
 * simplified for the one difference that matters here: §3.4 *does* have a per-asset REST route, so
 * there is no fleet-wide "latest per asset" list to filter client-side.
 *
 * **Component-provided, not `providedIn: 'root'`** — `AssetDetailPage` lists this in its own
 * `providers` (alongside `TelemetryStore`/`DetectionsStore`), so a fresh instance — and its
 * poll/subscription — starts/stops with the route.
 *
 * **Degrades honestly when the backend is absent.** L2/L3 (the Java side of this same plan) had not
 * landed in this worktree as of this wave: `GET /api/assets/{id}/links` 404s today on every asset,
 * unconditionally — `training-store.ts#TrainingStore`'s own precedent for turning a
 * feature-not-here 404 into a first-class {@link disabled} state applies verbatim here (a 404 on
 * *this specific* route can only mean "the controller isn't mounted", never "unknown asset id" — an
 * unknown asset never reaches this store at all, `AssetDetailFacade#load` already 404s on the asset
 * fetch itself first). The panel reads {@link disabled} to render `vision-empty` with an honest
 * reason, never a blocked page or a fabricated link list. Every *other* failure (network down, a
 * genuine 5xx) silent-degrades the same way `GeoStore#pollOnce` does — a missed poll just leaves
 * {@link group} at its last-known value, no toast (a link-health panel re-polling every 5s does not
 * need to interrupt the operator over one missed beat); `pin`/`releasePin` below, being an operator-
 * initiated action rather than a background poll, do toast on failure.
 */
@Injectable()
export class LinksStore {
  private readonly api = inject(VisionApi);
  private readonly toasts = inject(ToastService);
  private readonly scheduler = inject(PollScheduler);
  private readonly live = inject(LiveStore);

  /** Kept fresh by the 5s poll while `transportSignal() === 'poll'`; stale/unused while `'live'`. */
  private readonly pollResultSignal = signal<LinkGroupResponse | undefined>(undefined);
  /** Mirrors `LiveStore.linksFor(assetId)` while `transportSignal() === 'live'`. */
  private readonly liveResultSignal = signal<LinkGroupResponse | undefined>(undefined);
  /** The `assetId` passed to the current `track()` call, or `undefined` — drives the transport decision. */
  private readonly currentAssetIdSignal = signal<string | undefined>(undefined);
  /** Which source `group` currently reads from — see `applyTransport`. */
  private readonly transportSignal = signal<AssetScopedTransport>('poll');
  private readonly loadingSignal = signal(false);
  /** `true` once a poll has actually observed the "controller not mounted" 404 — see class doc. */
  private readonly disabledSignal = signal(false);

  private stopPollingFn: (() => void) | null = null;
  /** Bumped on every `track`/`reset` so a stale in-flight poll can tell it has been superseded. */
  private generation = 0;
  /** Whether a `track()` call is currently in effect — guards the transport effect. */
  private tracking = false;
  /** Defense in depth against a caller re-entering `track()` with an unchanged asset id — mirrors `GeoStore`'s identical `lastTrackAssetId` field. */
  private lastTrackAssetId: string | undefined;

  /** Whichever source is currently active — `undefined` until this asset's first group has arrived. */
  readonly group = computed<LinkGroupResponse | undefined>(() =>
    this.transportSignal() === 'live' ? this.liveResultSignal() : this.pollResultSignal(),
  );
  readonly loading = this.loadingSignal.asReadonly();
  readonly disabled = this.disabledSignal.asReadonly();

  constructor() {
    // Mirrors `GeoStore`'s identical effect — see that class's own doc comment for the full
    // "subscription lifecycle vs. transport" split this relies on.
    effect(() => {
      const connectionState = this.live.connectionState();
      const assetId = this.currentAssetIdSignal();
      if (!this.tracking) {
        return;
      }
      this.applyTransport(resolveAssetScopedTransport(connectionState, assetId));
    });

    // Mirrors the live snapshot straight through — no accumulation needed (§3.4's own "whole group,
    // never a diff" contract) — runs regardless of `transportSignal`'s current value so a poll→live
    // switch has continuity immediately, exactly like `GeoStore`'s own always-on mirror effect.
    effect(() => {
      const assetId = this.currentAssetIdSignal();
      if (assetId === undefined) {
        return;
      }
      const latest = this.live.linksFor(assetId)();
      if (latest === undefined || this.liveResultSignal() === latest) {
        return;
      }
      this.liveResultSignal.set(latest);
      // A live snapshot arriving is direct proof the backend has links for this asset after all —
      // clears a `disabled` a stale poll may have set before live took over.
      this.disabledSignal.set(false);
    });

    inject(DestroyRef).onDestroy(() => this.teardownTracking());
  }

  /**
   * Starts tracking `assetId`'s link group. A no-op when `assetId` is unchanged from the current
   * session — see `lastTrackAssetId`'s own doc comment.
   */
  track(assetId: string): void {
    if (this.lastTrackAssetId === assetId) {
      return;
    }
    this.lastTrackAssetId = assetId;

    this.generation++;
    this.tracking = false;
    this.teardownTracking(); // tears down the *previous* session's subscription/poll, if any
    this.pollResultSignal.set(undefined);
    this.liveResultSignal.set(undefined);
    this.disabledSignal.set(false);

    this.tracking = true;
    this.currentAssetIdSignal.set(assetId);
    this.live.trackLinks(assetId); // ref-counted; lasts for this whole track()/reset() session
    this.applyTransport(resolveAssetScopedTransport(this.live.connectionState(), assetId));
  }

  /** Stops tracking (poll + live subscription alike) and clears the held group. */
  reset(): void {
    this.generation++;
    this.tracking = false;
    this.lastTrackAssetId = undefined;
    this.teardownTracking();
    this.currentAssetIdSignal.set(undefined);
    this.pollResultSignal.set(undefined);
    this.liveResultSignal.set(undefined);
    this.disabledSignal.set(false);
    this.loadingSignal.set(false);
    this.transportSignal.set('poll');
  }

  /** Pins the election to `linkId` (§3.4 — return-to-AUTO is {@link releasePin}). Toasts on failure — an operator-initiated write, unlike the background poll's own silent-degrade. */
  async pin(assetId: string, linkId: string): Promise<void> {
    try {
      this.applyServerGroup(await this.api.pinAssetLink(assetId, linkId));
    } catch (error) {
      this.toasts.error(describeHttpError(error));
    }
  }

  /** Releases a pin, returning the group to AUTO election. */
  async releasePin(assetId: string): Promise<void> {
    try {
      this.applyServerGroup(await this.api.releaseAssetLinkPin(assetId));
    } catch (error) {
      this.toasts.error(describeHttpError(error));
    }
  }

  /**
   * Forces an immediate re-read, independent of the poll's own 5s cadence — called right after a
   * pairing recovery action (`AssetDetailFacade#replaceLinkHardware`/`forgetLinkPairing`) so the
   * panel doesn't wait out the poll interval to reflect what the operator just did. A no-op while
   * `live` is the active transport (the SSE snapshot already arrives on its own, and there is no
   * single-shot "re-fetch now" primitive for a subscription).
   */
  async refreshNow(): Promise<void> {
    const assetId = this.currentAssetIdSignal();
    if (assetId === undefined || this.transportSignal() === 'live') {
      return;
    }
    await this.pollOnce(assetId, this.generation);
  }

  private applyServerGroup(group: LinkGroupResponse): void {
    this.pollResultSignal.set(group);
    if (this.transportSignal() === 'live') {
      this.liveResultSignal.set(group);
    }
    this.disabledSignal.set(false);
  }

  /**
   * Switches which source `group` reads from and, correspondingly, whether the local poll is
   * running — **not** whether the live subscription itself exists (that's `track()`/`reset()`'s
   * job). Seeds the *other* signal from whatever was last visible so the panel never blanks for a
   * beat on the flip. Mirrors `GeoStore.applyTransport`.
   */
  private applyTransport(next: AssetScopedTransport): void {
    if (next === 'live') {
      if (this.liveResultSignal() === undefined && this.pollResultSignal() !== undefined) {
        this.liveResultSignal.set(this.pollResultSignal());
      }
      this.transportSignal.set(next);
      this.stopPolling();
      return;
    }
    if (this.pollResultSignal() === undefined && this.liveResultSignal() !== undefined) {
      this.pollResultSignal.set(this.liveResultSignal());
    }
    this.transportSignal.set(next);
    const assetId = this.currentAssetIdSignal();
    if (assetId === undefined || this.stopPollingFn !== null) {
      return; // nothing to poll, or already polling
    }
    const generation = this.generation;
    void this.pollOnce(assetId, generation);
    this.stopPollingFn = this.scheduler.schedule(POLL_INTERVAL_MS, () => this.pollOnce(assetId, generation));
  }

  private async pollOnce(assetId: string, generation: number): Promise<void> {
    this.loadingSignal.set(true);
    try {
      const response = await this.api.getAssetLinks(assetId);
      if (generation === this.generation) {
        this.pollResultSignal.set(response);
        this.disabledSignal.set(false);
      }
    } catch (error) {
      if (generation === this.generation && error instanceof HttpErrorResponse && error.status === 404) {
        this.disabledSignal.set(true);
      }
      // Every other failure silent-degrades — see class doc.
    } finally {
      if (generation === this.generation) {
        this.loadingSignal.set(false);
      }
    }
  }

  private stopPolling(): void {
    if (this.stopPollingFn !== null) {
      this.stopPollingFn();
      this.stopPollingFn = null;
    }
  }

  /** Stops the local poll and releases the live subscription for whatever asset the *previous* session tracked. */
  private teardownTracking(): void {
    this.stopPolling();
    const assetId = this.currentAssetIdSignal();
    if (assetId !== undefined) {
      this.live.untrackLinks(assetId);
    }
  }
}
