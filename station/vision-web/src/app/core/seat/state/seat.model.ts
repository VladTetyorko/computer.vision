import type { SeatsResponse } from '../../api/models';

/**
 * Every tracked asset's last-known seat response, keyed by `assetId` (docs/plans/active/
 * NGRX-MIGRATION-PLAN.md wave N2's own correction to the plan: `SeatStore` was `@Injectable()`,
 * page-provided, one instance per host — not an app-wide singleton — so this slice keys state by
 * `assetId` rather than holding one bare value, guaranteeing two hosts tracking two different
 * assets structurally cannot read each other's seat (a `Record` lookup by a different key can never
 * resolve to another asset's entry). `SeatFacade` stays `@Injectable()` and page-provided exactly
 * like the old store — only the state storage itself moved into this one app-wide-registered slice
 * (NgRx feature state is global by name regardless of where a facade is provided).
 *
 * An entry is `undefined` before that asset's first poll resolves, on a poll failure with no prior
 * good response, and right after `track()`/`reset()` — {@link SeatFacade}'s own `seats` computed
 * falls back to `singleOperatorSeats` for exactly those cases, mirroring `SeatStore`'s original
 * "always a usable value" contract.
 */
export interface SeatState {
  readonly byAssetId: Readonly<Record<string, SeatsResponse | undefined>>;
}

export const initialSeatState: SeatState = { byAssetId: {} };

/** How often a tracked asset's seat state is re-read — independent of the renewal heartbeat, which
 *  runs on the server-served `ttlMs / 3` instead (see `seat.effects.ts`). */
export const SEAT_POLL_INTERVAL_MS = 3_000;
