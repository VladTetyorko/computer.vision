import { describe, expect, it } from 'vitest';
import type { MapDrawingResponse } from '../../api/models';
import { LiveSocketActions } from '../../live/state/live.actions';
import { DrawingsApiActions, DrawingsPageActions } from './drawings.actions';
import { initialDrawingsState } from './drawings.model';
import { drawingsFeature } from './drawings.reducer';

const { reducer } = drawingsFeature;

function drawing(overrides: Partial<MapDrawingResponse> = {}): MapDrawingResponse {
  return {
    drawingId: 'd-1',
    layerId: 'l-1',
    kind: 'LINE',
    points: [{ latitude: 1, longitude: 2 }],
    createdByUserId: 'u-1',
    createdAt: '2026-09-01T00:00:00Z',
    ...overrides,
  };
}

describe('drawings reducer', () => {
  it('starts with no entities, not drawing, the first color token, no selection, no route yet', () => {
    expect(initialDrawingsState.ids).toEqual([]);
    expect(initialDrawingsState.loaded).toBe(false);
    expect(initialDrawingsState.activeConsumers).toBe(0);
    expect(initialDrawingsState.mode).toBeNull();
    expect(initialDrawingsState.colorToken).toBe('accent');
    expect(initialDrawingsState.selectedDrawingId).toBeUndefined();
    expect(initialDrawingsState.lastRoutePath).toBeNull();
  });

  it('activated/released ref-count demand, floored at zero', () => {
    const first = reducer(initialDrawingsState, DrawingsPageActions.activated());
    expect(first.activeConsumers).toBe(1);
    const released = reducer(first, DrawingsPageActions.released());
    expect(released.activeConsumers).toBe(0);
    expect(reducer(released, DrawingsPageActions.released()).activeConsumers).toBe(0);
  });

  it('loaded setAlls the entities and flips loaded; loadFailed only flips loaded', () => {
    const loaded = reducer(initialDrawingsState, DrawingsApiActions.loaded({ drawings: [drawing()] }));
    expect(loaded.ids).toEqual(['d-1']);
    expect(loaded.loaded).toBe(true);
    expect(reducer(initialDrawingsState, DrawingsApiActions.loadFailed()).loaded).toBe(true);
  });

  it('modeSet toggles the same kind off, and switches straight to a different kind; drawingStopped clears unconditionally', () => {
    const armed = reducer(initialDrawingsState, DrawingsPageActions.modeSet({ kind: 'LINE' }));
    expect(armed.mode).toBe('LINE');
    const toggledOff = reducer(armed, DrawingsPageActions.modeSet({ kind: 'LINE' }));
    expect(toggledOff.mode).toBeNull();
    const switched = reducer(armed, DrawingsPageActions.modeSet({ kind: 'POLYGON' }));
    expect(switched.mode).toBe('POLYGON');
    expect(reducer(switched, DrawingsPageActions.drawingStopped()).mode).toBeNull();
  });

  it('colorTokenSet replaces the next-drawing color', () => {
    const state = reducer(initialDrawingsState, DrawingsPageActions.colorTokenSet({ token: 'danger' }));
    expect(state.colorToken).toBe('danger');
  });

  it('selected toggles the same id off; a different id replaces it; deselected clears unconditionally', () => {
    const selected = reducer(initialDrawingsState, DrawingsPageActions.selected({ id: 'd-1' }));
    expect(selected.selectedDrawingId).toBe('d-1');
    const toggledOff = reducer(selected, DrawingsPageActions.selected({ id: 'd-1' }));
    expect(toggledOff.selectedDrawingId).toBeUndefined();
    const other = reducer(selected, DrawingsPageActions.selected({ id: 'd-2' }));
    expect(other.selectedDrawingId).toBe('d-2');
    expect(reducer(other, DrawingsPageActions.deselected()).selectedDrawingId).toBeUndefined();
  });

  it('completeDraftSucceeded adopts the drawing and selects it', () => {
    const state = reducer(initialDrawingsState, DrawingsApiActions.completeDraftSucceeded({ drawing: drawing() }));
    expect(state.entities['d-1']).toEqual(drawing());
    expect(state.selectedDrawingId).toBe('d-1');
  });

  it('patchSucceeded adopts without touching selection', () => {
    const seeded = reducer(initialDrawingsState, DrawingsApiActions.loaded({ drawings: [drawing()] }));
    const patched = reducer(seeded, DrawingsApiActions.patchSucceeded({ drawing: drawing({ colorToken: 'danger' }) }));
    expect(patched.entities['d-1']?.colorToken).toBe('danger');
    expect(patched.selectedDrawingId).toBeUndefined();
  });

  it('removeSucceeded removes by id, deselecting only if that id was selected; removeFailed is a no-op', () => {
    const seeded = reducer(initialDrawingsState, DrawingsApiActions.completeDraftSucceeded({ drawing: drawing() }));
    const removed = reducer(seeded, DrawingsApiActions.removeSucceeded({ id: 'd-1' }));
    expect(removed.ids).toEqual([]);
    expect(removed.selectedDrawingId).toBeUndefined();

    const failed = reducer(removed, DrawingsApiActions.removeFailed({ error: 'boom' }));
    expect(failed).toBe(removed);
  });

  it('routeChanged is a no-op on the very first navigation, but stops drawing on a genuine later transition — data survives', () => {
    const drawing1 = reducer(initialDrawingsState, DrawingsApiActions.loaded({ drawings: [drawing()] }));
    const armed = reducer(drawing1, DrawingsPageActions.modeSet({ kind: 'LINE' }));

    const firstNav = reducer(armed, DrawingsPageActions.routeChanged({ path: '/command' }));
    expect(firstNav.mode).toBe('LINE'); // the very first navigation after boot never resets
    expect(firstNav.lastRoutePath).toBe('/command');

    const samePathAgain = reducer(firstNav, DrawingsPageActions.routeChanged({ path: '/command' }));
    expect(samePathAgain.mode).toBe('LINE');

    const genuineNav = reducer(samePathAgain, DrawingsPageActions.routeChanged({ path: '/fly' }));
    expect(genuineNav.mode).toBeNull();
    expect(genuineNav.lastRoutePath).toBe('/fly');
    expect(genuineNav.ids).toEqual(['d-1']); // drawing *data* is shared app-wide, not reset by navigation
  });

  it('a live map envelope for the drawing entity upserts, unconditionally of activeConsumers', () => {
    const state = reducer(
      initialDrawingsState,
      LiveSocketActions.envelopeReceived({
        envelope: { seq: 1, type: 'map', payload: { entity: 'drawing', action: 'created', layerId: 'l-1', drawing: drawing({ drawingId: 'd-3' }) } },
      }),
    );
    expect(state.entities['d-3']).toEqual(drawing({ drawingId: 'd-3' }));
  });

  it('ignores a non-drawing map event and any other topic', () => {
    const markEvent = reducer(
      initialDrawingsState,
      LiveSocketActions.envelopeReceived({
        envelope: { seq: 1, type: 'map', payload: { entity: 'mark', action: 'created', layerId: 'l-1' } },
      }),
    );
    expect(markEvent).toEqual(initialDrawingsState); // adapter.setAll always rebuilds the state object, even as a content no-op

    const otherTopic = reducer(
      initialDrawingsState,
      LiveSocketActions.envelopeReceived({ envelope: { seq: 2, type: 'fleet', payload: [] } }),
    );
    expect(otherTopic).toBe(initialDrawingsState);
  });

  it('extraSelectors derive the map view, the selected drawing, and the interaction mode', () => {
    const state = reducer(initialDrawingsState, DrawingsApiActions.loaded({ drawings: [drawing()] }));
    const allDrawings = drawingsFeature.selectAllDrawings.projector(state);
    expect(allDrawings).toEqual([drawing()]);
    expect(drawingsFeature.selectDisplayDrawings.projector(allDrawings)).toHaveLength(1);
    expect(drawingsFeature.selectInteractionMode.projector(state)).toBe('view'); // not drawing

    const armed = reducer(state, DrawingsPageActions.modeSet({ kind: 'LINE' }));
    expect(drawingsFeature.selectInteractionMode.projector(armed)).toBe('draw-line');
  });
});
