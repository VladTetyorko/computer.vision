import type { DiscoveryCandidate, DiscoverySource } from '../../api/models';

/**
 * `DiscoveryInboxFacade`'s own slice (docs/plans/done/NGRX-MIGRATION-PLAN.md wave N7, replacing
 * `DiscoveryInboxStore`) — a root singleton, unlike `WeatherState`/`GeoState`/`LinksState`'s
 * `byHostId` keying: exactly one inbox exists app-wide, so `activeConsumers` is a plain counter
 * rather than a per-host map. See `discovery.effects.ts#poll$`'s own doc comment for how
 * `activeConsumers` (ref-count, here as state) and the live-vs-poll decision (an effect reading
 * `core/live`'s own `liveFeature` slice via `store.select`, never `LiveFacade` itself) combine into
 * one derived "phase".
 */
export interface DiscoveryInboxState {
  readonly candidates: readonly DiscoveryCandidate[];
  readonly sources: readonly DiscoverySource[];
  /** `true` while a poll/reconcile fetch is in flight — mirrors `DiscoveryInboxStore#loading`. */
  readonly loading: boolean;
  /** The one candidate id currently mid-mutation (register/dismiss/restore/attach) — mirrors `DiscoveryInboxStore#busyId`. */
  readonly busyId: string | null;
  /** How many mounted consumers currently want this inbox live — mirrors `DiscoveryInboxStore`'s own private `activeConsumers` field, promoted to state per this wave's own "ref-count is state" rule. */
  readonly activeConsumers: number;
}

export const initialDiscoveryInboxState: DiscoveryInboxState = {
  candidates: [],
  sources: [],
  loading: false,
  busyId: null,
  activeConsumers: 0,
};
