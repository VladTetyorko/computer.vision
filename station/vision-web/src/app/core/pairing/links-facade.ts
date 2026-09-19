import { DestroyRef, Injectable, computed, effect, inject } from '@angular/core';
import { Actions, ofType } from '@ngrx/effects';
import type { Action, ActionCreator, Creator } from '@ngrx/store';
import { Store } from '@ngrx/store';
import { firstValueFrom } from 'rxjs';
import { filter, take } from 'rxjs/operators';
import type { LinkGroupResponse } from '../api/models';
import { LiveFacade } from '../live/live-facade';
import { resolveAssetScopedTransport } from '../live/live-fallback-logic';
import { LinksApiActions, LinksLiveActions, LinksPageActions } from './state/links.actions';
import { linksFeature } from './state/links.reducer';

/** Module-level, monotonically increasing — same "two live instances can never collide" guarantee as `WeatherFacade`/`GeoFacade`'s identical field. */
let nextHostSequence = 0;

/**
 * Replaces `LinksStore` (docs/plans/active/NGRX-MIGRATION-PLAN.md wave N7) — one asset's whole link
 * group (docs/plans/active/LINK-PAIRING-PLAN.md §3.4), the asset-detail Links panel's own source.
 * `@Injectable()`, **not** `providedIn: 'root'` — `AssetDetailPage` lists this in its own `providers`
 * (alongside `TelemetryStore`), so a fresh instance — and its poll/subscription — starts/stops with
 * the route. Keyed by a synthetic `hostId`, mirroring `WeatherFacade`/`GeoFacade` (see either's own
 * doc comment); unlike `GeoFacade`, this class keeps a true `liveResult` mirror in its own slice
 * rather than reading `LiveFacade.linksFor(assetId)` live — see `links.model.ts`'s own doc comment
 * for why `pin()`/`releasePin()` need a writable copy.
 *
 * Every poll-vs-live decision and the Defect-A seed trigger below are made **here**, in this
 * facade's own constructor `effect()`s — `links.effects.ts` never injects `LiveFacade` itself, only
 * reacting to this slice's own actions (the demand-gated-store rule this wave was warned about).
 */
@Injectable()
export class LinksFacade {
  private readonly store = inject(Store);
  private readonly actions$ = inject(Actions);
  private readonly live = inject(LiveFacade);
  private readonly hostId = `links-host-${++nextHostSequence}`;
  private readonly byHostId = this.store.selectSignal(linksFeature.selectByHostId);
  private readonly hostState = computed(() => this.byHostId()[this.hostId]);

  /** Defense in depth against a caller re-entering `track()` with an unchanged asset id — mirrors `LinksStore`'s identical `lastTrackAssetId` field. */
  private lastTrackAssetId: string | undefined;

  readonly group = computed<LinkGroupResponse | undefined>(() => {
    const host = this.hostState();
    if (!host) {
      return undefined;
    }
    return host.transport === 'live' ? host.liveResult : host.pollResult;
  });
  readonly loading = computed(() => this.hostState()?.loading ?? false);
  readonly disabled = computed(() => this.hostState()?.disabled ?? false);

  constructor() {
    // Bridges `LiveFacade.connectionState()` into this slice — see class doc.
    effect(() => {
      const host = this.hostState();
      if (!host || host.assetId === undefined) {
        return;
      }
      const transport = resolveAssetScopedTransport(this.live.connectionState(), host.assetId);
      if (transport !== host.transport) {
        this.store.dispatch(LinksLiveActions.transportResolved({ hostId: this.hostId, transport }));
      }
      // Defect-A: the SSE buffer is only ever seeded by a REST read, so a session that resolves
      // straight to `'live'` with nothing yet needs one one-shot fetch — see `LinksStore`'s own
      // class doc. Re-checked on every run, exactly like `LinksStore#applyTransport`'s own call
      // site; naturally stops firing once either side has data.
      if (transport === 'live' && host.pollResult === undefined && this.live.linksFor(host.assetId)() === undefined) {
        this.store.dispatch(LinksPageActions.seedRequested({ hostId: this.hostId, assetId: host.assetId }));
      }
    });

    // Mirrors the live snapshot straight through — no accumulation needed (§3.4's own "whole group,
    // never a diff" contract); runs regardless of `transport`'s current value so a poll→live switch
    // has continuity immediately, exactly like `LinksStore`'s own always-on mirror effect.
    effect(() => {
      const host = this.hostState();
      if (!host || host.assetId === undefined) {
        return;
      }
      const latest = this.live.linksFor(host.assetId)();
      if (latest === undefined || host.liveResult === latest) {
        return;
      }
      this.store.dispatch(LinksLiveActions.liveArrived({ hostId: this.hostId, group: latest }));
    });

    inject(DestroyRef).onDestroy(() => {
      this.releaseCurrentAsset();
      this.store.dispatch(LinksPageActions.hostReleased({ hostId: this.hostId }));
    });
  }

  /** Starts tracking `assetId`'s link group. A no-op when `assetId` is unchanged from the current session — see `lastTrackAssetId`'s own doc comment. */
  track(assetId: string): void {
    if (this.lastTrackAssetId === assetId) {
      return;
    }
    this.releaseCurrentAsset();
    this.lastTrackAssetId = assetId;
    this.live.trackLinks(assetId); // ref-counted; lasts for this whole track()/reset() session
    const initialTransport = resolveAssetScopedTransport(this.live.connectionState(), assetId);
    this.store.dispatch(LinksPageActions.trackRequested({ hostId: this.hostId, assetId, initialTransport }));
  }

  /** Stops tracking (poll + live subscription alike) and clears the held group. */
  reset(): void {
    this.releaseCurrentAsset();
    this.lastTrackAssetId = undefined;
    this.store.dispatch(LinksPageActions.resetRequested({ hostId: this.hostId }));
  }

  /** Pins the election to `linkId` (§3.4 — return-to-AUTO is {@link releasePin}). Toasts on failure via `links.effects.ts#notifyFailure$` — never throws, mirroring `LinksStore#pin`'s own contract. */
  async pin(assetId: string, linkId: string): Promise<void> {
    await this.awaitHostAction<
      ReturnType<typeof LinksApiActions.pinSucceeded> | ReturnType<typeof LinksApiActions.pinFailed>
    >(LinksPageActions.pinRequested({ hostId: this.hostId, assetId, linkId }), [
      LinksApiActions.pinSucceeded,
      LinksApiActions.pinFailed,
    ]);
  }

  /** Releases a pin, returning the group to AUTO election. */
  async releasePin(assetId: string): Promise<void> {
    await this.awaitHostAction<
      ReturnType<typeof LinksApiActions.releasePinSucceeded> | ReturnType<typeof LinksApiActions.releasePinFailed>
    >(LinksPageActions.releasePinRequested({ hostId: this.hostId, assetId }), [
      LinksApiActions.releasePinSucceeded,
      LinksApiActions.releasePinFailed,
    ]);
  }

  /**
   * Forces an immediate re-read, independent of the poll's own 5s cadence — a no-op while `'live'`
   * is the active transport (mirrors `LinksStore#refreshNow`'s identical guard). Callers `await` this
   * so a busy-indicator they own doesn't clear before the panel actually reflects the refreshed data.
   */
  async refreshNow(): Promise<void> {
    const host = this.hostState();
    if (!host || host.assetId === undefined || host.transport === 'live') {
      return;
    }
    await this.awaitHostAction<
      | ReturnType<typeof LinksApiActions.pollSucceeded>
      | ReturnType<typeof LinksApiActions.pollFailed>
      | ReturnType<typeof LinksApiActions.pollDisabled>
    >(LinksPageActions.refreshNowRequested({ hostId: this.hostId, assetId: host.assetId }), [
      LinksApiActions.pollSucceeded,
      LinksApiActions.pollFailed,
      LinksApiActions.pollDisabled,
    ]);
  }

  private releaseCurrentAsset(): void {
    if (this.lastTrackAssetId !== undefined) {
      this.live.untrackLinks(this.lastTrackAssetId);
    }
  }

  /**
   * Dispatches `dispatched` and waits for the first of `types` carrying **this** instance's own
   * `hostId` — a hand-rolled cousin of `core/state/dispatch-bridge.ts#dispatchAndAwait` (deliberately
   * not that shared helper: it supports exactly one success + one failure type, but `refreshNow`
   * above waits on three, and every one of these outcomes carries a `hostId` that must be filtered
   * on — two `LinksFacade` instances (two asset-detail tabs) dispatching the same action *type* at
   * once must never resolve each other's promise).
   */
  private awaitHostAction<A extends { hostId: string }>(
    dispatched: Action,
    types: readonly ActionCreator<string, Creator<any[], A>>[],
  ): Promise<A> {
    const settled = firstValueFrom(
      this.actions$.pipe(
        ofType(...types),
        filter((action) => action.hostId === this.hostId),
        take(1),
      ),
    );
    this.store.dispatch(dispatched);
    return settled;
  }
}
