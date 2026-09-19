import type { AssetScopedTransport } from '../../live/live-fallback-logic';
import type { LinkGroupResponse } from '../../api/models';

/**
 * One `LinksFacade` host's own session (docs/plans/done/NGRX-MIGRATION-PLAN.md wave N7, replacing
 * `LinksStore`) — keyed by a synthetic `hostId` in {@link LinksState.byHostId}, the same posture as
 * `WeatherState`/`GeoState`.
 *
 * Unlike `GeoState.byHostId`, this **does** keep its own `liveResult` mirror of
 * `LiveFacade.linksFor(assetId)`, rather than reading it live at render time — `pin()`/`releasePin()`
 * must overwrite *whichever* source `LinksFacade.group` currently reads from the instant the server
 * responds (`LinksStore#applyServerGroup`'s own behaviour), which only a locally-held, writable copy
 * can do; a plain `computed()` fallback over `LiveFacade`'s own signal (as `GeoState` uses — geo has
 * no mutation method to worry about) can't be overwritten by an unrelated write.
 */
export interface LinksHostState {
  /** The asset this host is currently tracking, or `undefined` while idle. */
  readonly assetId: string | undefined;
  /** Which source `LinksFacade.group` currently reads from — see `LinksStore.applyTransport`'s own doc comment. */
  readonly transport: AssetScopedTransport;
  /** Kept fresh by the 5s poll (and the one-shot Defect-A seed) while `transport === 'poll'`, or whenever either fires regardless of `transport` — mirrors `LinksStore#pollResultSignal`. */
  readonly pollResult: LinkGroupResponse | undefined;
  /** Mirrors `LiveFacade.linksFor(assetId)` — latest-wins, never accumulated (§3.4: "whole group snapshot"). */
  readonly liveResult: LinkGroupResponse | undefined;
  /** `true` while a poll/seed/refresh fetch is in flight — mirrors `LinksStore#loadingSignal`. */
  readonly loading: boolean;
  /** Sticky once a poll observes the "controller not mounted" 404 — see `LinksStore`'s own class doc for why this never resets except on a fresh `track()`/`reset()`. */
  readonly disabled: boolean;
}

export const initialLinksHostState: LinksHostState = {
  assetId: undefined,
  transport: 'poll',
  pollResult: undefined,
  liveResult: undefined,
  loading: false,
  disabled: false,
};

export interface LinksState {
  readonly byHostId: Readonly<Record<string, LinksHostState>>;
}

export const initialLinksState: LinksState = { byHostId: {} };
