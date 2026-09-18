import type { FrameLedger, GateDecision, WorldObject } from '../../api/models';
import { DEFAULT_CV_TRACE_LAST } from '../cv-trace-logic';

/**
 * One tracked stream's engineer-inspector trace, keyed by `streamId` (docs/plans/active/
 * NGRX-MIGRATION-PLAN.md wave N5, replacing `CvTraceStore`) — mirrors `TelemetryState`'s own
 * by-key precedent (`CvTraceStore` was `@Injectable()`, page-provided, one instance per host).
 *
 * **Poll and live run concurrently, never exclusively** — this is a plan inaccuracy worth flagging
 * explicitly: NGRX-MIGRATION-PLAN.md §3's poll-vs-live convention (an `AssetScopedTransport` that is
 * `'live'` XOR `'poll'`) does **not** describe this slice. `CvTraceStore`'s own class doc lays out
 * why: `gate`/`world` have no live topic at all (`cv-trace:<assetId>` carries only
 * {@link FrameLedger}), so the poll below is their *only* freshness source, live or not; `frame` is
 * the accumulated ring the poll authoritatively resyncs every tick (replacing it outright, never
 * merging), while a live arrival merges into that same ring *between* polls purely for lower
 * latency. There is no `AssetScopedTransport` selector for this slice — see `cv-trace.effects.ts`'s
 * own doc comment.
 */
export interface CvTraceSessionState {
  readonly assetId: string | undefined;
  /** The `last` this session was `track()`ed with — both the `GET .../cv/trace?last=N` query param
   *  and this state's own `frame` ring capacity, kept in lockstep (`cv-trace-logic.ts
   *  #DEFAULT_CV_TRACE_LAST`'s own doc comment). */
  readonly cap: number;
  /** Recent gate decisions, oldest first, coalesced server-side — refreshed only by the poll. */
  readonly gate: readonly GateDecision[];
  /** The accumulated frame ring, oldest first, capped at {@link cap} — replaced outright by every
   *  poll tick, merged into between ticks by a live arrival (`cv-trace.effects.ts
   *  #liveFrameAccumulator$`). */
  readonly frame: readonly FrameLedger[];
  /** The current world-object fold — refreshed only by the poll, never windowed (mirrors
   *  `CvTrace.world` itself). */
  readonly world: readonly WorldObject[];
}

export interface CvTraceState {
  readonly byStreamId: Readonly<Record<string, CvTraceSessionState | undefined>>;
}

export const initialCvTraceState: CvTraceState = { byStreamId: {} };

/** How often the tracked stream's full trace is re-read — see `CvTraceStore`'s own
 *  `POLL_INTERVAL_MS` doc comment for why this is an engineer-inspector cadence, not a
 *  server-buffer-capacity mirror like `DetectionsStore`'s poll interval. */
export const CV_TRACE_POLL_INTERVAL_MS = 3_000;

export { DEFAULT_CV_TRACE_LAST };
