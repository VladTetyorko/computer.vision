import { describe, expect, it } from 'vitest';
import type { MapDrawingResponse, MapEventPayload } from '../api/models';
import { drawingColor } from '../../shared/map/tactical-map/tactical-map-logic';
import {
  DRAWING_COLOR_TOKENS,
  DRAW_KINDS,
  applyDrawingEvents,
  createDrawingRequest,
  drawKindLabel,
  interactionModeForDrawKind,
  patchDrawingRequest,
  remainingPoints,
  resolveInteractionMode,
  toMapDrawing,
} from './drawings-logic';

function drawing(overrides: Partial<MapDrawingResponse> = {}): MapDrawingResponse {
  return {
    drawingId: 'd1',
    layerId: 'layer-a',
    kind: 'LINE',
    points: [
      { latitude: 50, longitude: 30 },
      { latitude: 51, longitude: 31 },
    ],
    createdByUserId: 'u1',
    createdAt: '2026-08-05T10:00:00Z',
    ...overrides,
  };
}

describe('wire → display projection', () => {
  it('re-keys drawingId as id and drops ownership/timestamps', () => {
    expect(toMapDrawing(drawing({ label: 'Route', colorToken: 'warn' }))).toEqual({
      id: 'd1',
      layerId: 'layer-a',
      kind: 'LINE',
      points: [
        { latitude: 50, longitude: 30 },
        { latitude: 51, longitude: 31 },
      ],
      label: 'Route',
      colorToken: 'warn',
    });
  });
});

describe('applyDrawingEvents', () => {
  function drawingEvent(overrides: Partial<MapEventPayload> = {}): MapEventPayload {
    return { entity: 'drawing', action: 'created', layerId: 'layer-a', drawing: drawing(), ...overrides };
  }

  it('upserts created/updated and drops deleted', () => {
    const created = applyDrawingEvents([], [drawingEvent()]);
    expect(created.map((d) => d.drawingId)).toEqual(['d1']);
    expect(applyDrawingEvents(created, [drawingEvent({ action: 'deleted' })])).toEqual([]);
  });

  it('ignores mark deltas riding the same arrival log', () => {
    const before = [drawing()];
    expect(applyDrawingEvents(before, [{ entity: 'mark', action: 'created', layerId: 'layer-a' }])).toBe(before);
  });

  it('drops every drawing on a deleted layer', () => {
    const before = [drawing({ drawingId: 'a', layerId: 'gone' }), drawing({ drawingId: 'b' })];
    expect(applyDrawingEvents(before, [{ entity: 'layer', action: 'deleted', layerId: 'gone' }]).map((d) => d.drawingId)).toEqual(['b']);
  });
});

describe('toolbar vocabulary', () => {
  it('names what the operator is drawing, not the geometry primitive', () => {
    expect(DRAW_KINDS).toEqual(['LINE', 'POLYGON', 'ARROW', 'TEXT']);
    expect(drawKindLabel('POLYGON')).toBe('Area');
    expect(drawKindLabel('TEXT')).toBe('Label');
  });

  it('counts the clicks still needed before a completion gesture does anything', () => {
    expect(remainingPoints('POLYGON', 0)).toBe(3);
    expect(remainingPoints('POLYGON', 2)).toBe(1);
    expect(remainingPoints('LINE', 5)).toBe(0);
    expect(remainingPoints('TEXT', 0)).toBe(1);
  });

  it('offers only colour tokens the map can actually resolve — every one is a real, distinct stroke', () => {
    // `accent` is deliberately first *because* it is `drawingColor`'s own fallback for an absent or
    // unknown token — so it is the one entry that cannot be told apart from a miss, by design.
    expect(DRAWING_COLOR_TOKENS[0].token).toBe('accent');
    const fallback = drawingColor('definitely-not-a-token');
    for (const { token } of DRAWING_COLOR_TOKENS.slice(1)) {
      expect(drawingColor(token)).not.toBe(fallback);
    }
    // …and every token maps to a distinct colour, so two drawings can never look identical.
    const colors = DRAWING_COLOR_TOKENS.map((option) => drawingColor(option.token));
    expect(new Set(colors).size).toBe(colors.length);
  });
});

describe('interaction mode', () => {
  it('maps each draw kind to its map mode, and null to view', () => {
    expect(interactionModeForDrawKind('LINE')).toBe('draw-line');
    expect(interactionModeForDrawKind('POLYGON')).toBe('draw-polygon');
    expect(interactionModeForDrawKind('ARROW')).toBe('draw-arrow');
    expect(interactionModeForDrawKind('TEXT')).toBe('draw-text');
    expect(interactionModeForDrawKind(null)).toBe('view');
  });

  it('an armed mark palette wins over a draw mode, and nothing armed is view', () => {
    expect(resolveInteractionMode(true, null)).toBe('mark');
    expect(resolveInteractionMode(true, 'LINE')).toBe('mark');
    expect(resolveInteractionMode(false, 'ARROW')).toBe('draw-arrow');
    expect(resolveInteractionMode(false, null)).toBe('view');
  });
});

describe('request builders', () => {
  it('carries the draft geometry through and omits absent options', () => {
    const request = createDrawingRequest({ kind: 'ARROW', points: [{ latitude: 1, longitude: 2 }] }, { layerId: 'l1' });
    expect(request).toEqual({
      layerId: 'l1',
      kind: 'ARROW',
      label: undefined,
      colorToken: undefined,
      points: [{ latitude: 1, longitude: 2 }],
    });
  });

  it('the patch body is exactly what the caller named', () => {
    expect(patchDrawingRequest({ label: 'Route' })).toEqual({ label: 'Route' });
  });
});
