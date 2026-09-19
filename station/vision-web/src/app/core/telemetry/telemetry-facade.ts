import { DestroyRef, Injectable, computed, inject, signal } from '@angular/core';
import { Store } from '@ngrx/store';
import type { TelemetrySample } from '../api/models';
import { LiveFacade } from '../live/live-facade';
import { mergeTelemetrySamples, resolveAssetScopedTransport, trackSessionKey } from '../live/live-fallback-logic';
import { PollScheduler } from '../poll-scheduler';
import { ageSeconds, deriveTrail, isStale } from './telemetry-logic';
import { TelemetryPageActions } from './state/telemetry.actions';
import { telemetryFeature } from './state/telemetry.reducer';

/** How often the "n seconds ago" readout ticks, independent of when a poll last landed. */
const CLOCK_TICK_MS = 1_000;

/**
 * The telemetry slice's read/dispatch boundary (docs/plans/active/NGRX-MIGRATION-PLAN.md wave N5,
 * replacing `TelemetryStore`). `@Injectable()`, **not** `providedIn: 'root'` — component-provided
 * exactly like the old store (`LivePage`/`WallTile`/`AssetDetailPage`/`CockpitPage`/`CrewPage` list
 * this in their own `providers:` array), so a fresh instance — and, via {@link track}/{@link reset},
 * its own tracked device — starts and stops with that host. The underlying `byDeviceId` state and
 * its effects are registered once, app-wide, in `provideAppState()`; this class only reads/dispatches
 * against whichever entry is keyed by {@link track}'s own `deviceId` (host-local bookkeeping, exactly
 * like `SeatFacade`'s own `currentAssetId` — mirrors `TelemetryStore`'s old `lastTrackKey`).
 *
 * **Errors silent-degrade.** Telemetry is best-effort context, not a user-initiated action, so a
 * failed lookup or a missed poll never raises a toast — callers just see `hasTelemetry()` stay
 * `false` and render a "no telemetry" state instead. See `telemetry.effects.ts` for where every
 * failure still becomes a modeled action rather than a swallowed catch.
 *
 * **Poll-vs-live for display** is derived right here from `LiveFacade` + `resolveAssetScopedTransport`
 * — the same pure decision `telemetry.effects.ts#telemetryPollGate` makes via a cross-slice selector
 * to control the poll timer, recomputed here to decide what {@link samples} reads from. A facade
 * reading another facade directly is not the boot-order hazard NGRX-MIGRATION-PLAN.md §9 documents —
 * that only applies to a cross-slice facade eagerly injected as a `createEffect` factory default.
 */
@Injectable()
export class TelemetryFacade {
  private readonly store = inject(Store);
  private readonly live = inject(LiveFacade);
  private readonly byDeviceId = this.store.selectSignal(telemetryFeature.selectByDeviceId);

  private readonly currentDeviceId = signal<string | undefined>(undefined);
  private lastTrackKey: string | undefined;

  private readonly nowSignal = signal(Date.now());

  /**
   * Reads live (`LiveFacade.telemetryFor`, merged with the one-time backfill) or the poll fallback,
   * per the current transport — every other computed below derives from this, not either source
   * directly, so the rest of this class's public API never needs to know which is active.
   */
  readonly samples = computed<readonly TelemetrySample[]>(() => {
    const deviceId = this.currentDeviceId();
    if (deviceId === undefined) {
      return [];
    }
    const entry = this.byDeviceId()[deviceId];
    if (entry === undefined) {
      return [];
    }
    const transport = resolveAssetScopedTransport(this.live.connectionState(), entry.assetId);
    if (transport === 'live' && entry.assetId !== undefined) {
      return mergeTelemetrySamples(entry.backfill, this.live.telemetryFor(entry.assetId)());
    }
    return entry.pollSamples;
  });

  /** Whether any telemetry has arrived yet for the currently tracked device. */
  readonly hasTelemetry = computed(() => this.samples().length > 0);

  readonly latest = computed<TelemetrySample | undefined>(() => {
    const samples = this.samples();
    return samples.length > 0 ? samples[samples.length - 1] : undefined;
  });

  /** Chronological lat/lon points for the map's breadcrumb trail. */
  readonly trail = computed(() => deriveTrail(this.samples()));

  /** Seconds since the latest sample; ticks every second independent of the 2s poll cadence. */
  readonly sampleAgeSeconds = computed(() => ageSeconds(this.latest()?.at, this.nowSignal()));

  /** Whether the latest sample is old enough to be a safety concern — the OSD highlights this. */
  readonly stale = computed(() => isStale(this.sampleAgeSeconds()));

  constructor() {
    const scheduler = inject(PollScheduler);
    const stopClock = scheduler.schedule(CLOCK_TICK_MS, () => this.nowSignal.set(Date.now()));
    // Defense in depth mirroring `TelemetryStore`'s own constructor-time `DestroyRef` hook: every
    // known host already calls `reset()` explicitly from its own destroy path (e.g.
    // `asset-detail-facade.ts`, `live-facade.ts`, `wall-tile.ts`), but the underlying session now
    // lives in app-wide-registered effects rather than this instance's own fields, so a host that
    // ever forgot to call `reset()` would otherwise leak a permanently-running poll/live subscription.
    inject(DestroyRef).onDestroy(() => {
      stopClock();
      this.reset();
    });
  }

  /**
   * Starts tracking `deviceId`: resolves its owning asset's open usage, backfills once, then
   * subscribes live (if `assetId` is given) or polls — whichever `LiveFacade.connectionState()`
   * currently supports (see `telemetry.effects.ts#session$`).
   *
   * **A no-op when `(deviceId, assetId)` is unchanged from the current/in-flight session** — mirrors
   * `TelemetryStore#track`'s own `lastTrackKey` guard, defense in depth against a caller effect that
   * re-enters `track()` with unchanged primitives.
   */
  track(deviceId: string, assetId?: string): void {
    const key = trackSessionKey(deviceId, assetId);
    if (this.lastTrackKey === key) {
      return;
    }
    this.lastTrackKey = key;
    const previous = this.currentDeviceId();
    this.currentDeviceId.set(deviceId);
    if (previous !== undefined && previous !== deviceId) {
      this.store.dispatch(TelemetryPageActions.reset({ deviceId: previous }));
    }
    this.store.dispatch(TelemetryPageActions.tracked({ deviceId, assetId }));
  }

  /** Stops tracking (poll + live subscription alike) and clears samples. */
  reset(): void {
    const deviceId = this.currentDeviceId();
    this.lastTrackKey = undefined;
    this.currentDeviceId.set(undefined);
    if (deviceId !== undefined) {
      this.store.dispatch(TelemetryPageActions.reset({ deviceId }));
    }
  }
}
