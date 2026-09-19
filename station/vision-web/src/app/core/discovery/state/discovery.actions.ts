import { createActionGroup, emptyProps, props } from '@ngrx/store';
import type {
  DiscoveryCandidate,
  DiscoverySource,
  RegisterDeviceRequest,
  RegisterDiscoveryCandidateRequest,
  RegisterDiscoveryCandidateResponse,
} from '../../api/models';

/** `DiscoveryInboxFacade` itself — `activate()`/`release()` are the ref-count; the four mutations
 *  are the Inventory "Found devices" section's own card actions. */
export const DiscoveryPageActions = createActionGroup({
  source: 'Discovery Page',
  events: {
    Activated: emptyProps(),
    Released: emptyProps(),
    /** `DiscoveryInboxFacade#refresh`'s forced immediate re-read — independent of `activate()`'s own
     *  ref-count (mirrors `DiscoveryInboxStore#refresh`, callable with or without an active consumer). */
    'Refresh Requested': emptyProps(),
    'Register Requested': props<{ id: string; request: RegisterDiscoveryCandidateRequest }>(),
    /** The legacy two-step register-device-then-assign path — superseded by `Attach Candidate
     *  Requested` (W3) for every current caller, but kept for API-compat (`DiscoveryInboxStore`'s own
     *  class doc calls it out as intentionally left in place). */
    'Attach Requested': props<{ id: string; deviceSpec: RegisterDeviceRequest; assetId: string }>(),
    'Attach Candidate Requested': props<{ id: string; assetId: string }>(),
    'Dismiss Requested': props<{ id: string }>(),
    'Restore Requested': props<{ id: string }>(),
  },
});

export const DiscoveryApiActions = createActionGroup({
  source: 'Discovery API',
  events: {
    'Poll Started': emptyProps(),
    'Poll Succeeded': props<{ candidates: readonly DiscoveryCandidate[]; sources: readonly DiscoverySource[] }>(),
    /** Silent-degrade — only clears `loading`, touches neither `candidates` nor `sources` (CLAUDE.md "degrade honestly"). */
    'Poll Failed': emptyProps(),
    'Register Succeeded': props<{ id: string; result: RegisterDiscoveryCandidateResponse }>(),
    'Register Failed': props<{ id: string; error: string }>(),
    'Attach Succeeded': props<{ id: string; deviceName: string; displayName: string }>(),
    'Attach Failed': props<{ id: string; error: string }>(),
    'Attach Candidate Succeeded': props<{ id: string; result: DiscoveryCandidate }>(),
    'Attach Candidate Failed': props<{ id: string; error: string }>(),
    'Dismiss Succeeded': props<{ id: string; result: DiscoveryCandidate }>(),
    'Dismiss Failed': props<{ id: string; error: string }>(),
    'Restore Succeeded': props<{ id: string; result: DiscoveryCandidate }>(),
    'Restore Failed': props<{ id: string; error: string }>(),
  },
});
