import { describe, expect, it } from 'vitest';
import type { MapLayer } from '../../api/models';
import { LiveSocketActions } from '../../live/state/live.actions';
import { LayersApiActions, LayersPageActions } from './layers.actions';
import { initialLayersState } from './layers.model';
import { layersFeature } from './layers.reducer';

const { reducer } = layersFeature;

function layer(overrides: Partial<MapLayer> = {}): MapLayer {
  return {
    layerId: 'l-1',
    name: 'Team Alpha',
    kind: 'TEAM',
    myAccess: 'CONTRIBUTE',
    markCount: 0,
    drawingCount: 0,
    createdAt: '2026-09-01T00:00:00Z',
    ...overrides,
  };
}

describe('layers reducer', () => {
  it('starts with no entities, not loaded, no active consumers', () => {
    expect(initialLayersState.ids).toEqual([]);
    expect(initialLayersState.loaded).toBe(false);
    expect(initialLayersState.activeConsumers).toBe(0);
  });

  it('activated/released ref-count demand, floored at zero', () => {
    const first = reducer(initialLayersState, LayersPageActions.activated());
    expect(first.activeConsumers).toBe(1);
    const released = reducer(first, LayersPageActions.released());
    expect(released.activeConsumers).toBe(0);
    const releasedAgain = reducer(released, LayersPageActions.released());
    expect(releasedAgain.activeConsumers).toBe(0);
  });

  it('loaded setAlls the entities and flips loaded; loadFailed only flips loaded', () => {
    const loaded = reducer(initialLayersState, LayersApiActions.loaded({ layers: [layer()] }));
    expect(loaded.ids).toEqual(['l-1']);
    expect(loaded.loaded).toBe(true);

    const failed = reducer(initialLayersState, LayersApiActions.loadFailed());
    expect(failed.ids).toEqual([]);
    expect(failed.loaded).toBe(true);
  });

  it('createSucceeded/renameSucceeded/setGrantsSucceeded upsert by layerId; the *Failed siblings are no-ops', () => {
    const created = reducer(initialLayersState, LayersApiActions.createSucceeded({ layer: layer() }));
    expect(created.entities['l-1']).toEqual(layer());
    expect(reducer(created, LayersApiActions.createFailed({ error: 'boom' }))).toBe(created);

    const renamed = reducer(created, LayersApiActions.renameSucceeded({ layer: layer({ name: 'Renamed' }) }));
    expect(renamed.entities['l-1']?.name).toBe('Renamed');
    expect(reducer(renamed, LayersApiActions.renameFailed({ error: 'boom' }))).toBe(renamed);

    const granted = reducer(renamed, LayersApiActions.setGrantsSucceeded({ layer: layer({ name: 'Renamed', myAccess: 'MANAGE' }) }));
    expect(granted.entities['l-1']?.myAccess).toBe('MANAGE');
    expect(reducer(granted, LayersApiActions.setGrantsFailed({ error: 'boom' }))).toBe(granted);
  });

  it('removeSucceeded removes by layerId; removeFailed is a no-op', () => {
    const seeded = reducer(initialLayersState, LayersApiActions.loaded({ layers: [layer(), layer({ layerId: 'l-2' })] }));
    const removed = reducer(seeded, LayersApiActions.removeSucceeded({ layerId: 'l-1' }));
    expect(removed.ids).toEqual(['l-2']);

    const failed = reducer(removed, LayersApiActions.removeFailed({ error: 'boom' }));
    expect(failed).toBe(removed);
  });

  it('a live map envelope for the layer entity upserts, unconditionally of activeConsumers', () => {
    const state = reducer(
      initialLayersState,
      LiveSocketActions.envelopeReceived({
        envelope: {
          seq: 1,
          type: 'map',
          payload: { entity: 'layer', action: 'created', layerId: 'l-3', layer: layer({ layerId: 'l-3', name: 'Live-created' }) },
        },
      }),
    );
    expect(state.entities['l-3']?.name).toBe('Live-created');
  });

  it('a layer arriving over SSE with no grants keeps the previously-known grants', () => {
    const seeded = reducer(
      initialLayersState,
      LayersApiActions.loaded({ layers: [layer({ grants: [{ subjectType: 'GROUP', subjectId: 'g-1', level: 'VIEW' }] })] }),
    );
    const updated = reducer(
      seeded,
      LiveSocketActions.envelopeReceived({
        envelope: { seq: 2, type: 'map', payload: { entity: 'layer', action: 'updated', layerId: 'l-1', layer: layer() } },
      }),
    );
    expect(updated.entities['l-1']?.grants).toEqual([{ subjectType: 'GROUP', subjectId: 'g-1', level: 'VIEW' }]);
  });

  it('ignores a non-layer map event and any other topic', () => {
    const markEvent = reducer(
      initialLayersState,
      LiveSocketActions.envelopeReceived({
        envelope: { seq: 1, type: 'map', payload: { entity: 'mark', action: 'created', layerId: 'l-1' } },
      }),
    );
    expect(markEvent).toEqual(initialLayersState); // adapter.setAll always rebuilds the state object, even as a content no-op

    const otherTopic = reducer(
      initialLayersState,
      LiveSocketActions.envelopeReceived({ envelope: { seq: 2, type: 'fleet', payload: [] } }),
    );
    expect(otherTopic).toBe(initialLayersState);
  });

  it('extraSelectors derive contributable/manageable/COP/default-layer views off the entity list', () => {
    const cop = layer({ layerId: 'cop', kind: 'COP', myAccess: 'VIEW' });
    const team = layer({ layerId: 'team', kind: 'TEAM', myAccess: 'CONTRIBUTE' });
    const state = reducer(initialLayersState, LayersApiActions.loaded({ layers: [cop, team] }));

    expect(layersFeature.selectAllLayers.projector(state)).toEqual([cop, team]);
    expect(layersFeature.selectContributable.projector([cop, team])).toEqual([team]);
    expect(layersFeature.selectManageable.projector([cop, team])).toEqual([]);
    expect(layersFeature.selectCop.projector([cop, team])).toEqual(cop);
    expect(layersFeature.selectDefaultLayerId.projector([cop, team])).toBe('team');
  });
});
