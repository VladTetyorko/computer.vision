import type { TelemetrySample } from '../../api/models';

/**
 * One tracked device's telemetry session, keyed by `deviceId` (docs/plans/active/
 * NGRX-MIGRATION-PLAN.md wave N5) — mirrors `SeatState`'s own by-key precedent (`core/seat/state/
 * seat.model.ts`): `TelemetryStore` was `@Injectable()`, page/tile-provided, one instance per host,
 * so this slice keys state by `deviceId` rather than holding a bare value — two hosts tracking two
 * different devices structurally cannot read each other's samples. `TelemetryFacade` stays
 * `@Injectable()` and host-provided exactly like the old store; only the state storage itself moved
 * into this one app-wide-registered slice (NgRx feature state is global by name regardless of where
 * a facade is provided).
 *
 * `assetId` is stored (even though it never changes for the lifetime of one entry — see
 * `telemetry.effects.ts#session$`'s own doc comment) so the poll-vs-live transport can be derived by
 * a plain selector reading this slice plus `liveFeature.selectConnectionState`
 * (`telemetry.reducer.ts#transportSelectorFor`) — the concrete answer to NGRX-MIGRATION-PLAN.md §3's
 * convention 9, never by injecting `LiveFacade` into an effect. `backfill` is the one-time history
 * fetch on `track()`, untouched again until the next `track()`; `pollSamples` is kept fresh by the 2s
 * poll while that selector resolves to `'poll'`, stale/unused while `'live'` — `TelemetryFacade.
 * samples` merges `backfill` with `LiveFacade.telemetryFor(assetId)` only in that branch, exactly
 * like `TelemetryStore#samples`'s own computed did.
 */
export interface TelemetrySessionState {
  readonly assetId: string | undefined;
  readonly backfill: readonly TelemetrySample[];
  readonly pollSamples: readonly TelemetrySample[];
}

export interface TelemetryState {
  readonly byDeviceId: Readonly<Record<string, TelemetrySessionState | undefined>>;
}

export const initialTelemetryState: TelemetryState = { byDeviceId: {} };

/** How often a tracked device's telemetry is re-read while polling is the active transport. */
export const TELEMETRY_POLL_INTERVAL_MS = 2_000;

/** Matches `AssetController#telemetry`'s own default-overriding call site (docs/main/CYCLES-PLAN.md §2). */
export const TELEMETRY_BACKFILL_LIMIT = 200;
