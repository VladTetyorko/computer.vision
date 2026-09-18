import { createActionGroup, props } from '@ngrx/store';
import type { CvTrace, FrameLedger } from '../../api/models';

/** Every command a `CvTraceFacade` host issues. */
export const CvTracePageActions = createActionGroup({
  source: 'CvTrace Page',
  events: {
    Tracked: props<{ streamId: string; assetId: string | undefined; last: number }>(),
    Reset: props<{ streamId: string }>(),
  },
});

/**
 * Every outcome. `Poll Received` carries the whole `CvTrace` — the poll's own authoritative resync
 * (`CvTraceStore#pollOnce`'s own doc comment: "each tick replaces this store's ring outright, never
 * merges"); `Live Frame Received` is the between-polls low-latency merge instead
 * (`cv-trace.effects.ts#liveFrameAccumulator$`).
 */
export const CvTraceApiActions = createActionGroup({
  source: 'CvTrace API',
  events: {
    'Poll Received': props<{ streamId: string; trace: CvTrace }>(),
    'Poll Failed': props<{ streamId: string }>(),
    'Live Frame Received': props<{ streamId: string; frame: FrameLedger }>(),
  },
});
