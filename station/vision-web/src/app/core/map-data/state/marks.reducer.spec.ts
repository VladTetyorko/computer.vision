import { describe, expect, it } from 'vitest';
import type { MapMark } from '../../api/models';
import { LiveSocketActions } from '../../live/state/live.actions';
import { DEFAULT_MARK_PALETTE } from '../mark-logic';
import { MarksApiActions, MarksPageActions } from './marks.actions';
import { initialMarksState } from './marks.model';
import { marksFeature } from './marks.reducer';

const { reducer } = marksFeature;

function mark(overrides: Partial<MapMark> = {}): MapMark {
  return {
    markId: 'm-1',
    layerId: 'l-1',
    latitude: 1,
    longitude: 2,
    kind: 'TARGET',
    affiliation: 'HOSTILE',
    label: 'Tgt',
    createdByUserId: 'u-1',
    createdAt: '2026-09-01T00:00:00Z',
    status: 'ACTIVE',
    source: 'MANUAL',
    verification: 'UNVERIFIED',
    ...overrides,
  };
}

describe('marks reducer', () => {
  it('starts with no entities, no selection, the default palette, disarmed, no draft, no route yet', () => {
    expect(initialMarksState.ids).toEqual([]);
    expect(initialMarksState.loaded).toBe(false);
    expect(initialMarksState.activeConsumers).toBe(0);
    expect(initialMarksState.selectedMarkId).toBeUndefined();
    expect(initialMarksState.palette).toEqual(DEFAULT_MARK_PALETTE);
    expect(initialMarksState.armed).toBe(false);
    expect(initialMarksState.draft).toBeNull();
    expect(initialMarksState.lastRoutePath).toBeNull();
  });

  it('activated/released ref-count demand, floored at zero', () => {
    const first = reducer(initialMarksState, MarksPageActions.activated());
    expect(first.activeConsumers).toBe(1);
    const released = reducer(first, MarksPageActions.released());
    expect(released.activeConsumers).toBe(0);
    expect(reducer(released, MarksPageActions.released()).activeConsumers).toBe(0);
  });

  it('loaded setAlls the entities and flips loaded; loadFailed only flips loaded', () => {
    const loaded = reducer(initialMarksState, MarksApiActions.loaded({ marks: [mark()] }));
    expect(loaded.ids).toEqual(['m-1']);
    expect(loaded.loaded).toBe(true);
    expect(reducer(initialMarksState, MarksApiActions.loadFailed()).loaded).toBe(true);
  });

  it('selected toggles the same id off; a different id replaces it; deselected clears unconditionally', () => {
    const selected = reducer(initialMarksState, MarksPageActions.selected({ id: 'm-1' }));
    expect(selected.selectedMarkId).toBe('m-1');
    const toggledOff = reducer(selected, MarksPageActions.selected({ id: 'm-1' }));
    expect(toggledOff.selectedMarkId).toBeUndefined();
    const other = reducer(selected, MarksPageActions.selected({ id: 'm-2' }));
    expect(other.selectedMarkId).toBe('m-2');
    expect(reducer(other, MarksPageActions.deselected()).selectedMarkId).toBeUndefined();
  });

  it('kindSet/affiliationSet/layerSet each patch one palette field; paletteReconciled replaces the whole palette', () => {
    const kinded = reducer(initialMarksState, MarksPageActions.kindSet({ kind: 'HAZARD' }));
    expect(kinded.palette).toEqual({ ...DEFAULT_MARK_PALETTE, kind: 'HAZARD' });
    const affiliated = reducer(kinded, MarksPageActions.affiliationSet({ affiliation: 'FRIENDLY' }));
    expect(affiliated.palette).toEqual({ kind: 'HAZARD', affiliation: 'FRIENDLY' });
    const layered = reducer(affiliated, MarksPageActions.layerSet({ layerId: 'l-9' }));
    expect(layered.palette).toEqual({ kind: 'HAZARD', affiliation: 'FRIENDLY', layerId: 'l-9' });
    const reconciled = reducer(layered, MarksPageActions.paletteReconciled({ palette: DEFAULT_MARK_PALETTE }));
    expect(reconciled.palette).toEqual(DEFAULT_MARK_PALETTE);
  });

  it('armed clears any stale draft; a map click while armed captures a draft and disarms; draftCancelled clears it', () => {
    const armed = reducer(initialMarksState, MarksPageActions.armed());
    expect(armed.armed).toBe(true);
    expect(armed.draft).toBeNull();

    const clicked = reducer(armed, MarksPageActions.mapClicked({ position: { latitude: 5, longitude: 6 } }));
    expect(clicked.armed).toBe(false);
    expect(clicked.draft).toEqual({ palette: DEFAULT_MARK_PALETTE, position: { latitude: 5, longitude: 6 } });

    expect(reducer(clicked, MarksPageActions.draftCancelled()).draft).toBeNull();
  });

  it('a map click while not armed is a no-op', () => {
    const state = reducer(initialMarksState, MarksPageActions.mapClicked({ position: { latitude: 5, longitude: 6 } }));
    expect(state).toBe(initialMarksState);
  });

  it('disarmed only clears armed, leaving any in-flight draft alone', () => {
    const armed = reducer(initialMarksState, MarksPageActions.armed());
    const clicked = reducer(armed, MarksPageActions.mapClicked({ position: { latitude: 1, longitude: 1 } }));
    const disarmed = reducer(clicked, MarksPageActions.disarmed());
    expect(disarmed.armed).toBe(false);
    expect(disarmed.draft).toEqual(clicked.draft);
  });

  it('confirmDraftSucceeded/geolocateSucceeded adopt the mark, select it, and (confirm only) clear the draft', () => {
    const confirmed = reducer(initialMarksState, MarksApiActions.confirmDraftSucceeded({ mark: mark() }));
    expect(confirmed.entities['m-1']).toEqual(mark());
    expect(confirmed.selectedMarkId).toBe('m-1');
    expect(confirmed.draft).toBeNull();

    const geolocated = reducer(initialMarksState, MarksApiActions.geolocateSucceeded({ mark: mark({ markId: 'm-2' }) }));
    expect(geolocated.selectedMarkId).toBe('m-2');
  });

  it('patchSucceeded/verifySucceeded/promoteSucceeded adopt the mark without touching selection', () => {
    const seeded = reducer(initialMarksState, MarksApiActions.loaded({ marks: [mark()] }));
    const patched = reducer(seeded, MarksApiActions.patchSucceeded({ mark: mark({ label: 'Renamed' }) }));
    expect(patched.entities['m-1']?.label).toBe('Renamed');
    expect(patched.selectedMarkId).toBeUndefined();

    const verified = reducer(patched, MarksApiActions.verifySucceeded({ mark: mark({ label: 'Renamed', verification: 'CONFIRMED' }) }));
    expect(verified.entities['m-1']?.verification).toBe('CONFIRMED');

    const promoted = reducer(verified, MarksApiActions.promoteSucceeded({ mark: mark({ label: 'Renamed', verification: 'CONFIRMED', layerId: 'cop' }) }));
    expect(promoted.entities['m-1']?.layerId).toBe('cop');
  });

  it('adopting a mark whose status is CLEARED removes it outright (a status transition, not an update) and deselects it', () => {
    const seeded = reducer(initialMarksState, MarksApiActions.confirmDraftSucceeded({ mark: mark() }));
    expect(seeded.selectedMarkId).toBe('m-1');
    const cleared = reducer(seeded, MarksApiActions.verifySucceeded({ mark: mark({ status: 'CLEARED' }) }));
    expect(cleared.selectedMarkId).toBeUndefined();
    expect(cleared.entities['m-1']).toBeUndefined();
    expect(cleared.ids).toEqual([]);
  });

  it('removeSucceeded removes by id, deselecting only if that id was selected; removeFailed is a no-op', () => {
    const seeded = reducer(initialMarksState, MarksApiActions.confirmDraftSucceeded({ mark: mark() }));
    const removed = reducer(seeded, MarksApiActions.removeSucceeded({ id: 'm-1' }));
    expect(removed.ids).toEqual([]);
    expect(removed.selectedMarkId).toBeUndefined();

    const failed = reducer(removed, MarksApiActions.removeFailed({ error: 'boom' }));
    expect(failed).toBe(removed);
  });

  it('routeChanged is a no-op on the very first navigation (lastRoutePath was null), but resets on a genuine later transition', () => {
    const armed = reducer(initialMarksState, MarksPageActions.armed());
    const kinded = reducer(armed, MarksPageActions.kindSet({ kind: 'HAZARD' }));

    const firstNav = reducer(kinded, MarksPageActions.routeChanged({ path: '/command' }));
    expect(firstNav.armed).toBe(true); // unaffected — the very first navigation after boot never resets
    expect(firstNav.lastRoutePath).toBe('/command');

    const samePathAgain = reducer(firstNav, MarksPageActions.routeChanged({ path: '/command' }));
    expect(samePathAgain.armed).toBe(true); // same path (e.g. a query-param-only change) never resets

    const genuineNav = reducer(samePathAgain, MarksPageActions.routeChanged({ path: '/fly' }));
    expect(genuineNav.armed).toBe(false);
    expect(genuineNav.draft).toBeNull();
    expect(genuineNav.palette).toEqual(DEFAULT_MARK_PALETTE);
    expect(genuineNav.lastRoutePath).toBe('/fly');
  });

  it('a live map envelope for the mark entity upserts, unconditionally of activeConsumers', () => {
    const state = reducer(
      initialMarksState,
      LiveSocketActions.envelopeReceived({
        envelope: { seq: 1, type: 'map', payload: { entity: 'mark', action: 'created', layerId: 'l-1', mark: mark({ markId: 'm-3' }) } },
      }),
    );
    expect(state.entities['m-3']).toEqual(mark({ markId: 'm-3' }));
  });

  it('ignores a non-mark map event and any other topic', () => {
    const layerEvent = reducer(
      initialMarksState,
      LiveSocketActions.envelopeReceived({
        envelope: { seq: 1, type: 'map', payload: { entity: 'layer', action: 'created', layerId: 'l-1' } },
      }),
    );
    expect(layerEvent).toEqual(initialMarksState); // adapter.setAll always rebuilds the state object, even as a content no-op

    const otherTopic = reducer(
      initialMarksState,
      LiveSocketActions.envelopeReceived({ envelope: { seq: 2, type: 'fleet', payload: [] } }),
    );
    expect(otherTopic).toBe(initialMarksState);
  });

  it('extraSelectors derive the tactical view, the selected mark, and the pending palette', () => {
    const state = reducer(initialMarksState, MarksApiActions.loaded({ marks: [mark()] }));
    const allMarks = marksFeature.selectAllMarks.projector(state);
    expect(allMarks).toEqual([mark()]);
    expect(marksFeature.selectDisplayMarks.projector(allMarks)).toHaveLength(1);
    expect(marksFeature.selectPendingPalette.projector(state)).toBeNull(); // not armed

    const armed = reducer(state, MarksPageActions.armed());
    expect(marksFeature.selectPendingPalette.projector(armed)).toEqual(armed.palette);
  });
});
