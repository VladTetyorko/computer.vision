import type { SeatHolderResponse, SeatKind, SeatsResponse } from '../api/models';

/**
 * Pure helpers for `core/seat/seat-store.ts` (docs/plans/active/CREW-CONTROL-PLAN.md §3.6, the
 * frozen wire contract). Angular-free, unit-tested directly — see `seat-logic.spec.ts`.
 */

/** A seat nobody holds — four explicit `null`s, matching the wire contract's own "free" shape
 * (`SeatsResponse`'s own doc comment: `@JsonInclude` is deliberately not applied server-side). */
const FREE_SEAT: SeatHolderResponse = Object.freeze({
  holderUserId: null,
  holderDisplayName: null,
  acquiredAt: null,
  expiresAt: null,
  mine: false,
});

/** Mirrors the backend's own `vision.crew.seat-ttl-ms` default (§3.6) — used only to fill the
 * fallback shape below; never substituted for a served `ttlMs` when deriving a renewal cadence. */
const DEFAULT_SEAT_TTL_MS = 15_000;

/**
 * The honest "no seat concept observed" shape — both seats free, every `may*` authority `true`.
 * Used by `SeatStore` before the first poll resolves, on any poll failure with no prior good
 * response, and by `reset()`. This is deliberately the *same* shape a real backend reports for
 * `vision.crew.enabled=false` (§3.8) — a deployment with no seat feature and a frontend that hasn't
 * heard from one yet are, correctly, indistinguishable: both mean "act like a single-operator
 * station," never "block until seats load."
 */
export function singleOperatorSeats(assetId: string): SeatsResponse {
  return {
    assetId,
    ttlMs: DEFAULT_SEAT_TTL_MS,
    flight: FREE_SEAT,
    camera: FREE_SEAT,
    mayTakeFlight: true,
    mayTakeCamera: true,
    mayForceSeat: false,
  };
}

/** Reads the seat for `kind` out of a `SeatsResponse` — the response's own field names are
 * lowercase (`flight`/`camera`) while `SeatKind` is uppercase, so every reader goes through this
 * rather than re-deriving the mapping inline. */
export function seatFor(seats: SeatsResponse, kind: SeatKind): SeatHolderResponse {
  return kind === 'FLIGHT' ? seats.flight : seats.camera;
}

/**
 * Derives the renewal heartbeat's period from the server-served `ttlMs` (§3.6: "the SPA derives its
 * renewal cadence (`ttlMs / 3`) instead of hard-coding one"). `PollScheduler` requires whole-second
 * multiples, so this rounds to the nearest second, floored at 1s so a short test-only TTL can never
 * produce a zero or negative period.
 */
export function renewalIntervalMs(ttlMs: number): number {
  const raw = ttlMs / 3;
  const roundedToSecond = Math.round(raw / 1000) * 1000;
  return Math.max(1_000, roundedToSecond);
}
