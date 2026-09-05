import { DestroyRef, Injectable, computed, inject, signal } from '@angular/core';
import { VisionApi } from '../api/vision-api';
import type { SeatHolderResponse, SeatKind, SeatsResponse } from '../api/models';
import { PollScheduler } from '../poll-scheduler';
import { renewalIntervalMs, singleOperatorSeats } from './seat-logic';

/** How often the tracked asset's seat state is re-read — independent of the renewal heartbeat
 * below, which runs on the server-served `ttlMs / 3` instead. This is purely an observation cadence
 * (picking up a preemption or another crew member's take), so a fixed interval is honest here. */
const POLL_INTERVAL_MS = 3_000;

/**
 * Tracks one asset's two seats (docs/plans/active/CREW-CONTROL-PLAN.md §3.1/§3.6) — `features/
 * crew/crew-facade.ts`'s and (a later wave's) the pilot cockpit's shared source for "who holds
 * flight, who holds camera, what may I do." Polls `GET /api/assets/{id}/seats` on a fixed cadence
 * for observation, and independently renews-while-mine via `POST .../seats/{kind}` on the
 * server-served `ttlMs / 3` cadence (§3.6: "the SPA derives its renewal cadence instead of
 * hard-coding one") — the renewal is the heartbeat that keeps a held seat from lapsing while its
 * holder is only *reading* (watching detections, waiting), not issuing guarded writes of its own.
 *
 * **Component-provided, not `providedIn: 'root'`** — a page lists this in its own `providers`
 * array (mirrors `GeoStore`), so a fresh instance — and its polls — start/stop with the route.
 *
 * **Always a usable value.** `seats()` is never `undefined`: before the first poll resolves, on any
 * poll failure with no prior good response (a 404 — either a genuinely unknown asset, or, just as
 * likely during this feature's rollout, a deployment that hasn't shipped the seats endpoint at
 * all — and a `vision.crew.enabled=false` 200 both look the same from here), it reads as
 * {@link singleOperatorSeats} — both seats free, every `may*` true. A page never special-cases
 * "seats not loaded yet"; it renders exactly as it would with the feature off (§3.8). A failure
 * *after* a prior good response instead leaves the last-known state in place — the same
 * silent-degrade `GeoStore`/`ThresholdsStore` already follow — never blanked back to the fallback.
 *
 * **409 is an authoritative correction, not an error to retry past.** When a renewal 409s, someone
 * else now holds that seat (rule 3 preemption, or a manager's force) — the store does not attempt to
 * reconcile the conflict body's message into local state; it immediately re-reads `GET .../seats` so
 * every signal reflects the server's fresh truth.
 */
@Injectable()
export class SeatStore {
  private readonly api = inject(VisionApi);
  private readonly scheduler = inject(PollScheduler);

  private readonly seatsSignal = signal<SeatsResponse | undefined>(undefined);
  private readonly assetIdSignal = signal<string | undefined>(undefined);

  private stopPollFn: (() => void) | null = null;
  private stopRenewFn: (() => void) | null = null;
  private renewPeriodMs: number | undefined;
  /** Bumped on every `track`/`reset` so a stale in-flight call can tell it has been superseded. */
  private generation = 0;
  /** Defense in depth against a caller re-entering `track()` with an unchanged asset id — mirrors `GeoStore`'s identical field. */
  private lastTrackAssetId: string | undefined;

  /** Always defined — see class doc. */
  readonly seats = computed<SeatsResponse>(
    () => this.seatsSignal() ?? singleOperatorSeats(this.assetIdSignal() ?? ''),
  );

  readonly flight = computed<SeatHolderResponse>(() => this.seats().flight);
  readonly camera = computed<SeatHolderResponse>(() => this.seats().camera);
  readonly mayTakeFlight = computed<boolean>(() => this.seats().mayTakeFlight);
  readonly mayTakeCamera = computed<boolean>(() => this.seats().mayTakeCamera);
  readonly mayForceSeat = computed<boolean>(() => this.seats().mayForceSeat);

  constructor() {
    inject(DestroyRef).onDestroy(() => this.teardown());
  }

  /** Starts tracking `assetId`'s seats. A no-op when `assetId` is unchanged from the current session. */
  track(assetId: string): void {
    if (this.lastTrackAssetId === assetId) {
      return;
    }
    this.lastTrackAssetId = assetId;

    this.generation++;
    this.teardown();
    this.seatsSignal.set(undefined);
    this.assetIdSignal.set(assetId);

    const generation = this.generation;
    void this.pollOnce(assetId, generation);
    this.stopPollFn = this.scheduler.schedule(POLL_INTERVAL_MS, () => this.pollOnce(assetId, generation));
  }

  /** Stops polling and renewing, and clears seat state back to `undefined` (so `seats()` reads as
   * the single-operator fallback again for whichever asset is tracked next). */
  reset(): void {
    this.generation++;
    this.lastTrackAssetId = undefined;
    this.teardown();
    this.seatsSignal.set(undefined);
    this.assetIdSignal.set(undefined);
  }

  /** Forces an immediate re-read — used after a guarded write 409s so a page's stage recomputes
   * from fresh authority rather than a locally cached "mine" that just went stale. */
  refreshNow(): void {
    const assetId = this.assetIdSignal();
    if (assetId === undefined) {
      return;
    }
    void this.pollOnce(assetId, this.generation);
  }

  private async pollOnce(assetId: string, generation: number): Promise<void> {
    try {
      const response = await this.api.getAssetSeats(assetId);
      if (generation !== this.generation) {
        return;
      }
      this.seatsSignal.set(response);
      this.applyRenewalCadence(assetId, generation, response.ttlMs);
    } catch {
      // Silent-degrade: a missed poll just leaves `seats()` as it was (or, on the very first poll,
      // as the honest single-operator fallback) — see class doc.
    }
  }

  /** (Re)registers the renewal task only when the computed period actually changes — in practice
   * this fires once per session, since `ttlMs` is a deploy-time constant, but the mechanism honestly
   * supports it changing mid-session too. */
  private applyRenewalCadence(assetId: string, generation: number, ttlMs: number): void {
    const period = renewalIntervalMs(ttlMs);
    if (this.renewPeriodMs === period) {
      return;
    }
    this.stopRenew();
    this.renewPeriodMs = period;
    this.stopRenewFn = this.scheduler.schedule(period, () => this.renewTick(assetId, generation));
  }

  /** Renews every seat the last-known state says is mine — a lone operator never triggers this (both
   * seats read `mine:false` under the single-operator fallback), and it is a genuine no-op once
   * neither seat is held. */
  private async renewTick(assetId: string, generation: number): Promise<void> {
    const current = this.seatsSignal();
    if (generation !== this.generation || current === undefined) {
      return;
    }
    const mineKinds: SeatKind[] = [];
    if (current.flight.mine) {
      mineKinds.push('FLIGHT');
    }
    if (current.camera.mine) {
      mineKinds.push('CAMERA');
    }
    for (const kind of mineKinds) {
      await this.renewOne(assetId, generation, kind);
    }
  }

  private async renewOne(assetId: string, generation: number, kind: SeatKind): Promise<void> {
    try {
      const renewed = await this.api.takeAssetSeat(assetId, kind);
      if (generation === this.generation) {
        this.seatsSignal.set(renewed);
      }
    } catch {
      // 409 — preempted between two renewals. See class doc: re-read rather than reconcile.
      if (generation === this.generation) {
        await this.pollOnce(assetId, generation);
      }
    }
  }

  private stopPoll(): void {
    if (this.stopPollFn !== null) {
      this.stopPollFn();
      this.stopPollFn = null;
    }
  }

  private stopRenew(): void {
    if (this.stopRenewFn !== null) {
      this.stopRenewFn();
      this.stopRenewFn = null;
    }
    this.renewPeriodMs = undefined;
  }

  private teardown(): void {
    this.stopPoll();
    this.stopRenew();
  }
}
