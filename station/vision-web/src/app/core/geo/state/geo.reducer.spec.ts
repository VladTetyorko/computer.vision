import { describe, expect, it } from 'vitest';
import type { CorrectionResponse } from '../../api/models';
import { GeoApiActions, GeoLiveActions, GeoPageActions } from './geo.actions';
import { initialGeoState } from './geo.model';
import { geoFeature } from './geo.reducer';

const { reducer } = geoFeature;

function correction(assetId: string, partial: Partial<CorrectionResponse> = {}): CorrectionResponse {
  return {
    assetId,
    usageId: 'usage-1',
    frameAt: '2024-01-01T00:00:00Z',
    computedAt: '2024-01-01T00:00:00Z',
    status: 'CONFIRMED',
    source: 'VISUAL_HEAVY',
    divergent: false,
    ...partial,
  };
}

describe('geoFeature reducer', () => {
  it('starts with no hosts at all', () => {
    expect(initialGeoState.byHostId).toEqual({});
  });

  it('trackRequested seeds a fresh host entry at the given initial transport', () => {
    const state = reducer(
      initialGeoState,
      GeoPageActions.trackRequested({ hostId: 'host-a', assetId: 'a-1', initialTransport: 'poll' }),
    );
    expect(state.byHostId['host-a']).toEqual({
      assetId: 'a-1',
      transport: 'poll',
      pollResult: undefined,
      disabled: false,
    });
  });

  it('resetRequested removes the host entry entirely', () => {
    const tracked = reducer(
      initialGeoState,
      GeoPageActions.trackRequested({ hostId: 'host-a', assetId: 'a-1', initialTransport: 'poll' }),
    );
    const state = reducer(tracked, GeoPageActions.resetRequested({ hostId: 'host-a' }));
    expect(state.byHostId['host-a']).toBeUndefined();
  });

  it('hostReleased removes the host entry entirely', () => {
    const tracked = reducer(
      initialGeoState,
      GeoPageActions.trackRequested({ hostId: 'host-a', assetId: 'a-1', initialTransport: 'poll' }),
    );
    const state = reducer(tracked, GeoPageActions.hostReleased({ hostId: 'host-a' }));
    expect(state.byHostId['host-a']).toBeUndefined();
  });

  it('transportResolved updates the transport for an existing host only', () => {
    const tracked = reducer(
      initialGeoState,
      GeoPageActions.trackRequested({ hostId: 'host-a', assetId: 'a-1', initialTransport: 'poll' }),
    );
    const state = reducer(tracked, GeoLiveActions.transportResolved({ hostId: 'host-a', transport: 'live' }));
    expect(state.byHostId['host-a'].transport).toBe('live');
  });

  it('transportResolved for an unknown host is a no-op (a stale race after reset/release)', () => {
    const state = reducer(initialGeoState, GeoLiveActions.transportResolved({ hostId: 'ghost', transport: 'live' }));
    expect(state).toBe(initialGeoState);
  });

  it('pollSucceeded stores the correction (or undefined) for an existing host only', () => {
    const tracked = reducer(
      initialGeoState,
      GeoPageActions.trackRequested({ hostId: 'host-a', assetId: 'a-1', initialTransport: 'poll' }),
    );
    const mine = correction('a-1');
    const state = reducer(tracked, GeoApiActions.pollSucceeded({ hostId: 'host-a', correction: mine }));
    expect(state.byHostId['host-a'].pollResult).toEqual(mine);
  });

  it('pollSucceeded for an unknown host is a no-op (a stale race after reset/release)', () => {
    const state = reducer(
      initialGeoState,
      GeoApiActions.pollSucceeded({ hostId: 'ghost', correction: correction('a-1') }),
    );
    expect(state).toBe(initialGeoState);
  });

  it('pollDisabled sets disabled for an existing host only', () => {
    const tracked = reducer(
      initialGeoState,
      GeoPageActions.trackRequested({ hostId: 'host-a', assetId: 'a-1', initialTransport: 'poll' }),
    );
    const state = reducer(tracked, GeoApiActions.pollDisabled({ hostId: 'host-a' }));
    expect(state.byHostId['host-a'].disabled).toBe(true);
  });

  it('pollDisabled for an unknown host is a no-op (a stale race after reset/release)', () => {
    const state = reducer(initialGeoState, GeoApiActions.pollDisabled({ hostId: 'ghost' }));
    expect(state).toBe(initialGeoState);
  });

  describe('two hosts never clobber each other', () => {
    it('tracking two hosts keeps two entirely independent entries', () => {
      const afterA = reducer(
        initialGeoState,
        GeoPageActions.trackRequested({ hostId: 'command', assetId: 'a-1', initialTransport: 'poll' }),
      );
      const afterB = reducer(
        afterA,
        GeoPageActions.trackRequested({ hostId: 'fly', assetId: 'a-2', initialTransport: 'live' }),
      );

      expect(afterB.byHostId['command']).toMatchObject({ assetId: 'a-1', transport: 'poll' });
      expect(afterB.byHostId['fly']).toMatchObject({ assetId: 'a-2', transport: 'live' });
    });

    it("fly's poll succeeding never touches command's entry, and vice versa", () => {
      const tracked = [
        GeoPageActions.trackRequested({ hostId: 'command', assetId: 'a-1', initialTransport: 'poll' }),
        GeoPageActions.trackRequested({ hostId: 'fly', assetId: 'a-2', initialTransport: 'poll' }),
      ].reduce(reducer, initialGeoState);

      const flyCorrection = correction('a-2');
      const afterFly = reducer(tracked, GeoApiActions.pollSucceeded({ hostId: 'fly', correction: flyCorrection }));

      expect(afterFly.byHostId['command'].pollResult).toBeUndefined();
      expect(afterFly.byHostId['fly'].pollResult).toEqual(flyCorrection);

      const afterCommandDisabled = reducer(afterFly, GeoApiActions.pollDisabled({ hostId: 'command' }));
      expect(afterCommandDisabled.byHostId['fly'].disabled).toBe(false);
      expect(afterCommandDisabled.byHostId['command'].disabled).toBe(true);
    });

    it("releasing one host's entry leaves the other's completely intact", () => {
      const tracked = [
        GeoPageActions.trackRequested({ hostId: 'command', assetId: 'a-1', initialTransport: 'poll' }),
        GeoPageActions.trackRequested({ hostId: 'fly', assetId: 'a-2', initialTransport: 'poll' }),
      ].reduce(reducer, initialGeoState);

      const state = reducer(tracked, GeoPageActions.hostReleased({ hostId: 'command' }));

      expect(state.byHostId['command']).toBeUndefined();
      expect(state.byHostId['fly']).toBeDefined();
    });
  });
});
