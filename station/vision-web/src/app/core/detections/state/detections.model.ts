import type { DetectionResult, StreamTracksResponse } from '../../api/models';

/**
 * One tracked stream's detections + tracks state, keyed by `streamId` (docs/plans/active/
 * NGRX-MIGRATION-PLAN.md wave N5) — mirrors `SeatState`'s own by-key precedent: `DetectionsStore`
 * was `@Injectable()`, page/tile-provided, one instance per host, so this slice keys state by
 * `streamId` rather than a bare value.
 *
 * **Two independent lifecycles share one entry**, exactly like the old class held both concerns as
 * sibling fields on one instance: the **detections feed** ({@link feedActive}/{@link assetId}/
 * {@link pollResults}/{@link liveResults}, driven by `track()`/`reset()`) and the **tracks poll**
 * ({@link tracksWanted}/{@link tracksPollResponse}, driven by `trackTracks()`/`untrackTracks()`/
 * `followTracks()`). Every real call site tracks both under the *same* `streamId` (they name the
 * same stream), so one `Record` keyed by `streamId` lets the tracks lifecycle's transport decision
 * reuse whichever `assetId` the feed lifecycle most recently established — `DetectionsStore`'s own
 * class doc calls this out explicitly ("reuses this class's own `currentAssetIdSignal` … rather than
 * adding one to `followTracks`") — for free, via a plain `store.select` re-evaluating on *any* write
 * to this entry, with no extra sync action needed. The two lifecycles otherwise never touch each
 * other's fields: `reset()`'s reducer case clears only feed fields, `untrackTracks()`'s only tracks
 * fields — see `detections.reducer.ts`'s own `on(...)` handlers.
 *
 * `assetId` is `undefined` until a `track()` call supplies one — the live detections/world-objects/
 * tracks topics are all asset-scoped, never stream-scoped (`DetectionsStore`'s own class doc, "the
 * live `detections` topic is asset-scoped, not stream-scoped").
 */
export interface DetectionsSessionState {
  readonly feedActive: boolean;
  readonly assetId: string | undefined;
  /** Kept fresh by the 2s poll while the feed's transport selector resolves to `'poll'`. */
  readonly pollResults: readonly DetectionResult[];
  /** Accumulated (prepended, capped, deduped) from every live arrival, regardless of which transport
   *  is currently displayed — see `detections.effects.ts#liveResultAccumulator$`'s own doc comment. */
  readonly liveResults: readonly DetectionResult[];
  readonly tracksWanted: boolean;
  /** Kept fresh by the 2s tracks poll while wanted and not live; `null` before the first poll
   *  settles, before a tracks session was ever wanted, or on any poll failure (this field's own
   *  honesty posture — unlike {@link pollResults} — degrades to `null` on failure rather than
   *  keeping a stale value, mirroring `DetectionsStore#pollTracksOnce`'s own catch branch). */
  readonly tracksPollResponse: StreamTracksResponse | null;
}

export interface DetectionsState {
  readonly byStreamId: Readonly<Record<string, DetectionsSessionState | undefined>>;
}

export const initialDetectionsState: DetectionsState = { byStreamId: {} };

const emptySession: DetectionsSessionState = {
  feedActive: false,
  assetId: undefined,
  pollResults: [],
  liveResults: [],
  tracksWanted: false,
  tracksPollResponse: null,
};

/** The baseline every new entry (feed- or tracks-only) starts from — a plain constant, not a
 *  function, since it is only ever spread, never mutated. */
export function emptyDetectionsSession(): DetectionsSessionState {
  return emptySession;
}

/** Whether an entry has nothing left wanting it — used to prune `byStreamId` back down as sessions
 *  end, exactly as `SeatState#reset` deletes its own key outright, so a long-lived host (a Wall of
 *  many tiles cycling through many streams) never accumulates dead entries forever. */
export function detectionsSessionInactive(entry: DetectionsSessionState): boolean {
  return !entry.feedActive && !entry.tracksWanted;
}

/** How often a tracked stream's recent detections are re-read while the feed's poll fallback is active. */
export const DETECTIONS_POLL_INTERVAL_MS = 2_000;

/** How often `GET /api/streams/{id}/tracks` is re-read while the tracks poll fallback is active —
 *  mirrors {@link DETECTIONS_POLL_INTERVAL_MS} (docs/plans/done/TRACKING-PLAN.md §4.E). */
export const TRACKS_POLL_INTERVAL_MS = 2_000;

/** Matches `StreamController#detections`'s own default limit (docs/plans/done/MVP1-PLAN.md §C8) —
 *  also the cap on the live-accumulated list. */
export const DETECTIONS_LIMIT = 50;
