import { describe, expect, it } from 'vitest';
import type { LinkGroupResponse, LinkView } from '../../api/models';
import { LinksApiActions, LinksLiveActions, LinksPageActions } from './links.actions';
import { initialLinksState } from './links.model';
import { linksFeature } from './links.reducer';

const { reducer } = linksFeature;

function link(partial: Partial<LinkView> = {}): LinkView {
  return {
    id: 'link-1',
    carrier: 'UDP',
    serialRole: 'NONE',
    label: 'Wi-Fi',
    active: true,
    receiving: true,
    heartbeatAgeSeconds: 2,
    ...partial,
  };
}

function group(assetId: string, partial: Partial<LinkGroupResponse> = {}): LinkGroupResponse {
  return { assetId, links: [link()], activeLinkId: 'link-1', pinned: false, ...partial };
}

function track(hostId: string, assetId: string, initialTransport: 'poll' | 'live' = 'poll') {
  return LinksPageActions.trackRequested({ hostId, assetId, initialTransport });
}

describe('linksFeature reducer', () => {
  it('starts with no hosts at all', () => {
    expect(initialLinksState.byHostId).toEqual({});
  });

  it('trackRequested seeds a fresh host entry at the given initial transport', () => {
    const state = reducer(initialLinksState, track('host-a', 'a-1', 'poll'));
    expect(state.byHostId['host-a']).toEqual({
      assetId: 'a-1',
      transport: 'poll',
      pollResult: undefined,
      liveResult: undefined,
      loading: false,
      disabled: false,
    });
  });

  it('resetRequested and hostReleased both remove the host entry entirely', () => {
    const tracked = reducer(initialLinksState, track('host-a', 'a-1'));
    expect(reducer(tracked, LinksPageActions.resetRequested({ hostId: 'host-a' })).byHostId['host-a']).toBeUndefined();
    expect(reducer(tracked, LinksPageActions.hostReleased({ hostId: 'host-a' })).byHostId['host-a']).toBeUndefined();
  });

  it('pollStarted sets loading, and every terminal outcome clears it', () => {
    const tracked = reducer(initialLinksState, track('host-a', 'a-1'));
    const started = reducer(tracked, LinksApiActions.pollStarted({ hostId: 'host-a' }));
    expect(started.byHostId['host-a'].loading).toBe(true);

    expect(
      reducer(started, LinksApiActions.pollSucceeded({ hostId: 'host-a', group: group('a-1') })).byHostId['host-a']
        .loading,
    ).toBe(false);
    expect(reducer(started, LinksApiActions.pollFailed({ hostId: 'host-a' })).byHostId['host-a'].loading).toBe(false);
    expect(reducer(started, LinksApiActions.pollDisabled({ hostId: 'host-a' })).byHostId['host-a'].loading).toBe(
      false,
    );
  });

  it('pollSucceeded stores the group and clears disabled', () => {
    const tracked = reducer(initialLinksState, track('host-a', 'a-1'));
    const disabled = reducer(tracked, LinksApiActions.pollDisabled({ hostId: 'host-a' }));
    const mine = group('a-1', { pinned: true });
    const state = reducer(disabled, LinksApiActions.pollSucceeded({ hostId: 'host-a', group: mine }));
    expect(state.byHostId['host-a'].pollResult).toEqual(mine);
    expect(state.byHostId['host-a'].disabled).toBe(false);
  });

  it('pollFailed touches nothing but loading — a silent degrade', () => {
    const tracked = reducer(initialLinksState, track('host-a', 'a-1'));
    const seeded = reducer(tracked, LinksApiActions.pollSucceeded({ hostId: 'host-a', group: group('a-1') }));
    const state = reducer(seeded, LinksApiActions.pollFailed({ hostId: 'host-a' }));
    expect(state.byHostId['host-a'].pollResult).toEqual(group('a-1'));
    expect(state.byHostId['host-a'].disabled).toBe(false);
  });

  it('pollDisabled sets disabled without touching pollResult', () => {
    const tracked = reducer(initialLinksState, track('host-a', 'a-1'));
    const state = reducer(tracked, LinksApiActions.pollDisabled({ hostId: 'host-a' }));
    expect(state.byHostId['host-a'].disabled).toBe(true);
    expect(state.byHostId['host-a'].pollResult).toBeUndefined();
  });

  describe('transportResolved — never blank on the flip (mirrors LinksStore.applyTransport)', () => {
    it('flipping to live seeds liveResult from an existing pollResult', () => {
      const tracked = reducer(initialLinksState, track('host-a', 'a-1', 'poll'));
      const polled = reducer(tracked, LinksApiActions.pollSucceeded({ hostId: 'host-a', group: group('a-1') }));
      const state = reducer(polled, LinksLiveActions.transportResolved({ hostId: 'host-a', transport: 'live' }));
      expect(state.byHostId['host-a'].liveResult).toEqual(group('a-1'));
    });

    it('flipping to poll seeds pollResult from an existing liveResult', () => {
      const tracked = reducer(initialLinksState, track('host-a', 'a-1', 'live'));
      const arrived = reducer(tracked, LinksLiveActions.liveArrived({ hostId: 'host-a', group: group('a-1') }));
      const state = reducer(arrived, LinksLiveActions.transportResolved({ hostId: 'host-a', transport: 'poll' }));
      expect(state.byHostId['host-a'].pollResult).toEqual(group('a-1'));
    });

    it('never overwrites an existing value with undefined on either flip', () => {
      const tracked = reducer(initialLinksState, track('host-a', 'a-1', 'live'));
      const arrived = reducer(tracked, LinksLiveActions.liveArrived({ hostId: 'host-a', group: group('a-1') }));
      const backToLive = reducer(arrived, LinksLiveActions.transportResolved({ hostId: 'host-a', transport: 'live' }));
      expect(backToLive.byHostId['host-a'].liveResult).toEqual(group('a-1'));
    });
  });

  describe('liveArrived', () => {
    it('mirrors the live snapshot straight through and clears disabled', () => {
      const tracked = reducer(initialLinksState, track('host-a', 'a-1', 'live'));
      const disabled = reducer(tracked, LinksApiActions.pollDisabled({ hostId: 'host-a' }));
      const first = group('a-1', { activeLinkId: 'link-1' });
      const afterFirst = reducer(disabled, LinksLiveActions.liveArrived({ hostId: 'host-a', group: first }));
      expect(afterFirst.byHostId['host-a'].liveResult).toEqual(first);
      expect(afterFirst.byHostId['host-a'].disabled).toBe(false);

      const second = group('a-1', { activeLinkId: 'link-2' });
      const afterSecond = reducer(afterFirst, LinksLiveActions.liveArrived({ hostId: 'host-a', group: second }));
      expect(afterSecond.byHostId['host-a'].liveResult).toEqual(second); // replaced, not accumulated
    });
  });

  describe('the Defect-A seed (pollSucceeded while live with nothing yet)', () => {
    it('seeds liveResult when nothing has arrived on the live side yet', () => {
      const tracked = reducer(initialLinksState, track('host-a', 'a-1', 'live'));
      const seed = group('a-1', { pinned: true });
      const state = reducer(tracked, LinksApiActions.pollSucceeded({ hostId: 'host-a', group: seed }));
      expect(state.byHostId['host-a'].liveResult).toEqual(seed);
    });

    it('never clobbers a live push that already won the race', () => {
      const tracked = reducer(initialLinksState, track('host-a', 'a-1', 'live'));
      const pushed = group('a-1', { activeLinkId: 'live-first' });
      const arrived = reducer(tracked, LinksLiveActions.liveArrived({ hostId: 'host-a', group: pushed }));
      const lateSeed = group('a-1', { activeLinkId: 'seed-arrived-late' });
      const state = reducer(arrived, LinksApiActions.pollSucceeded({ hostId: 'host-a', group: lateSeed }));
      expect(state.byHostId['host-a'].liveResult).toEqual(pushed); // untouched by the late seed
      expect(state.byHostId['host-a'].pollResult).toEqual(lateSeed); // pollResult itself still updates
    });
  });

  describe('pin / release-pin — applyServerGroup', () => {
    it('pinSucceeded updates pollResult always, and liveResult only while live', () => {
      const trackedLive = reducer(initialLinksState, track('host-a', 'a-1', 'live'));
      const updated = group('a-1', { pinned: true });
      const state = reducer(trackedLive, LinksApiActions.pinSucceeded({ hostId: 'host-a', group: updated }));
      expect(state.byHostId['host-a'].pollResult).toEqual(updated);
      expect(state.byHostId['host-a'].liveResult).toEqual(updated);
    });

    it('pinSucceeded while poll leaves liveResult untouched', () => {
      const trackedPoll = reducer(initialLinksState, track('host-a', 'a-1', 'poll'));
      const updated = group('a-1', { pinned: true });
      const state = reducer(trackedPoll, LinksApiActions.pinSucceeded({ hostId: 'host-a', group: updated }));
      expect(state.byHostId['host-a'].pollResult).toEqual(updated);
      expect(state.byHostId['host-a'].liveResult).toBeUndefined();
    });

    it('releasePinSucceeded applies the same way', () => {
      const trackedLive = reducer(initialLinksState, track('host-a', 'a-1', 'live'));
      const updated = group('a-1', { pinned: false });
      const state = reducer(trackedLive, LinksApiActions.releasePinSucceeded({ hostId: 'host-a', group: updated }));
      expect(state.byHostId['host-a'].pollResult).toEqual(updated);
      expect(state.byHostId['host-a'].liveResult).toEqual(updated);
    });
  });

  describe('every mutation action is a no-op for an unknown host (a stale race after reset/release)', () => {
    it('transportResolved / liveArrived / pollStarted / pollSucceeded / pollFailed / pollDisabled / pinSucceeded / releasePinSucceeded', () => {
      const actions = [
        LinksLiveActions.transportResolved({ hostId: 'ghost', transport: 'live' }),
        LinksLiveActions.liveArrived({ hostId: 'ghost', group: group('a-1') }),
        LinksApiActions.pollStarted({ hostId: 'ghost' }),
        LinksApiActions.pollSucceeded({ hostId: 'ghost', group: group('a-1') }),
        LinksApiActions.pollFailed({ hostId: 'ghost' }),
        LinksApiActions.pollDisabled({ hostId: 'ghost' }),
        LinksApiActions.pinSucceeded({ hostId: 'ghost', group: group('a-1') }),
        LinksApiActions.releasePinSucceeded({ hostId: 'ghost', group: group('a-1') }),
      ];
      for (const action of actions) {
        expect(reducer(initialLinksState, action)).toBe(initialLinksState);
      }
    });
  });

  describe('two hosts never clobber each other', () => {
    it('tracking, polling, and pinning one host never touches another', () => {
      const tracked = [track('command', 'a-1', 'poll'), track('fly', 'a-2', 'poll')].reduce(reducer, initialLinksState);
      const afterFlyPoll = reducer(tracked, LinksApiActions.pollSucceeded({ hostId: 'fly', group: group('a-2') }));
      expect(afterFlyPoll.byHostId['command'].pollResult).toBeUndefined();
      expect(afterFlyPoll.byHostId['fly'].pollResult).toEqual(group('a-2'));

      const afterCommandPin = reducer(
        afterFlyPoll,
        LinksApiActions.pinSucceeded({ hostId: 'command', group: group('a-1', { pinned: true }) }),
      );
      expect(afterCommandPin.byHostId['fly'].pollResult).toEqual(group('a-2'));
      expect(afterCommandPin.byHostId['command'].pollResult).toEqual(group('a-1', { pinned: true }));

      const afterRelease = reducer(afterCommandPin, LinksPageActions.hostReleased({ hostId: 'command' }));
      expect(afterRelease.byHostId['command']).toBeUndefined();
      expect(afterRelease.byHostId['fly']).toBeDefined();
    });
  });
});
