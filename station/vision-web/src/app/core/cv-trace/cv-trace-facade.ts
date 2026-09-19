import { DestroyRef, Injectable, computed, inject, signal } from '@angular/core';
import { Store } from '@ngrx/store';
import type { FrameLedger, GateDecision, WorldObject } from '../api/models';
import { DEFAULT_CV_TRACE_LAST } from './cv-trace-logic';
import { CvTracePageActions } from './state/cv-trace.actions';
import { cvTraceFeature } from './state/cv-trace.reducer';

/**
 * The cv-trace slice's read/dispatch boundary (docs/plans/active/NGRX-MIGRATION-PLAN.md wave N5,
 * replacing `CvTraceStore`). `@Injectable()`, **not** `providedIn: 'root'` — the routed `/manage/cv`
 * page lists this in its own `providers:` array, exactly like the old store.
 *
 * **No poll-vs-live transport split** — see `cv-trace.model.ts`'s own class doc and
 * `cv-trace.effects.ts#session$`'s doc comment: unlike `TelemetryFacade`/`DetectionsFacade`, this
 * facade has no `resolveAssetScopedTransport` call anywhere, because the underlying slice never
 * switches sources — the poll always runs, live only ever merges into `frame` between ticks.
 */
@Injectable()
export class CvTraceFacade {
  private readonly store = inject(Store);
  private readonly byStreamId = this.store.selectSignal(cvTraceFeature.selectByStreamId);

  private readonly currentStreamId = signal<string | undefined>(undefined);
  private lastTrackKey: string | undefined;

  private readonly entry = computed(() => {
    const streamId = this.currentStreamId();
    return streamId === undefined ? undefined : this.byStreamId()[streamId];
  });

  /** Recent gate decisions, oldest first, coalesced server-side — refreshed only by the poll. */
  readonly gate = computed<readonly GateDecision[]>(() => this.entry()?.gate ?? []);
  /** The accumulated frame ring, oldest first, capped at the `last` this session was `track()`ed with. */
  readonly frame = computed<readonly FrameLedger[]>(() => this.entry()?.frame ?? []);
  /** The current world-object fold — refreshed only by the poll, never windowed. */
  readonly world = computed<readonly WorldObject[]>(() => this.entry()?.world ?? []);

  constructor() {
    // Defense in depth — see `TelemetryFacade`'s identical constructor doc comment: the old store's
    // own `DestroyRef` hook called `this.reset()` unconditionally, so this mirrors that even though
    // `CvInspectorFacade` already calls `reset()` on its own teardown.
    inject(DestroyRef).onDestroy(() => this.reset());
  }

  /**
   * Starts tracing `streamId` — a no-op when `(streamId, assetId, last)` is unchanged from the
   * current session, mirroring `CvTraceStore#track`'s own `lastTrackKey` guard. `assetId` opts into
   * the live `cv-trace:<assetId>` topic for sub-poll-interval ring updates; omit it to stay
   * poll-only. `last` matches the cap `GET .../cv/trace?last=N` is asked for and this session's own
   * ring capacity.
   */
  track(streamId: string, assetId?: string, last: number = DEFAULT_CV_TRACE_LAST): void {
    const key = `${streamId} ${assetId ?? ''} ${last}`;
    if (this.lastTrackKey === key) {
      return;
    }
    this.lastTrackKey = key;
    const previous = this.currentStreamId();
    this.currentStreamId.set(streamId);
    if (previous !== undefined && previous !== streamId) {
      this.store.dispatch(CvTracePageActions.reset({ streamId: previous }));
    }
    this.store.dispatch(CvTracePageActions.tracked({ streamId, assetId, last }));
  }

  /** Stops tracing (the demand rule's "closing flips it off" half) and clears every ledger. */
  reset(): void {
    const streamId = this.currentStreamId();
    this.lastTrackKey = undefined;
    this.currentStreamId.set(undefined);
    if (streamId !== undefined) {
      this.store.dispatch(CvTracePageActions.reset({ streamId }));
    }
  }
}
