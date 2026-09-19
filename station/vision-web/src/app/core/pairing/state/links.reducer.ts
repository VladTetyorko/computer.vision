import { createFeature, createReducer, on } from '@ngrx/store';
import type { LinkGroupResponse } from '../../api/models';
import { LinksApiActions, LinksLiveActions, LinksPageActions } from './links.actions';
import { initialLinksHostState, initialLinksState } from './links.model';
import type { LinksHostState, LinksState } from './links.model';

/** Returns `state` itself (same reference) for an unknown `hostId` — a stale race after reset/release
 *  must be a true no-op, mirroring `geo.reducer.ts`'s identical `if (!host) return state;` guard. */
function patchHost(state: LinksState, hostId: string, patch: (host: LinksHostState) => LinksHostState): LinksState {
  const host = state.byHostId[hostId];
  if (!host) {
    return state;
  }
  return { byHostId: { ...state.byHostId, [hostId]: patch(host) } };
}

export const linksFeature = createFeature({
  name: 'links',
  reducer: createReducer(
    initialLinksState,
    on(LinksPageActions.trackRequested, (state, { hostId, assetId, initialTransport }) => ({
      byHostId: { ...state.byHostId, [hostId]: { ...initialLinksHostState, assetId, transport: initialTransport } },
    })),
    on(LinksPageActions.resetRequested, (state, { hostId }) => {
      const { [hostId]: _removed, ...rest } = state.byHostId;
      return { byHostId: rest };
    }),
    on(LinksPageActions.hostReleased, (state, { hostId }) => {
      const { [hostId]: _removed, ...rest } = state.byHostId;
      return { byHostId: rest };
    }),
    // Mirrors `LinksStore.applyTransport`'s own "seed the other signal so the panel never blanks for
    // a beat on the flip" behaviour, in both directions.
    on(LinksLiveActions.transportResolved, (state, { hostId, transport }) =>
      patchHost(state, hostId, (host) => {
        if (transport === 'live') {
          return {
            ...host,
            transport,
            liveResult: host.liveResult ?? host.pollResult,
          };
        }
        return {
          ...host,
          transport,
          pollResult: host.pollResult ?? host.liveResult,
        };
      }),
    ),
    on(LinksLiveActions.liveArrived, (state, { hostId, group }) =>
      patchHost(state, hostId, (host) => ({ ...host, liveResult: group, disabled: false })),
    ),
    on(LinksApiActions.pollStarted, (state, { hostId }) =>
      patchHost(state, hostId, (host) => ({ ...host, loading: true })),
    ),
    on(LinksApiActions.pollSucceeded, (state, { hostId, group }) =>
      patchHost(state, hostId, (host) => ({
        ...host,
        pollResult: group,
        // The Defect-A seed: only when nothing has ever arrived on the live side yet — a live push
        // that already won the race (see `LinksLiveActions.liveArrived`) is never clobbered by a
        // slower REST read that started before it.
        liveResult: host.transport === 'live' && host.liveResult === undefined ? group : host.liveResult,
        disabled: false,
        loading: false,
      })),
    ),
    on(LinksApiActions.pollFailed, (state, { hostId }) =>
      patchHost(state, hostId, (host) => ({ ...host, loading: false })),
    ),
    on(LinksApiActions.pollDisabled, (state, { hostId }) =>
      patchHost(state, hostId, (host) => ({ ...host, disabled: true, loading: false })),
    ),
    on(LinksApiActions.pinSucceeded, (state, { hostId, group }) => applyServerGroup(state, hostId, group)),
    on(LinksApiActions.releasePinSucceeded, (state, { hostId, group }) => applyServerGroup(state, hostId, group)),
  ),
});

/** Mirrors `LinksStore#applyServerGroup` — an operator-initiated write always updates `pollResult`,
 *  and also `liveResult` when that's the source currently on screen, so the panel reflects the
 *  write immediately regardless of which transport is active. */
function applyServerGroup(state: LinksState, hostId: string, group: LinkGroupResponse): LinksState {
  return patchHost(state, hostId, (host) => ({
    ...host,
    pollResult: group,
    liveResult: host.transport === 'live' ? group : host.liveResult,
    disabled: false,
  }));
}
