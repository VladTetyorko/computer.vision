import { beforeEach, describe, expect, it } from 'vitest';
import type { FleetMarker } from '../../../core/map/map-logic';
import {
  BUILTIN_ASSETS_LAYER,
  BUILTIN_EVENTS_LAYER,
  BUILTIN_ZONES_LAYER,
  FALLBACK_MAP_COLORS,
  HIDDEN_LAYERS_KEY,
  affiliationCounts,
  appendVertex,
  arrowRotationDegrees,
  assetLegendCounts,
  builtinRows,
  completedDraft,
  copLayerIds,
  draftKindForMode,
  drawingColor,
  escapeHtml,
  followMarker,
  followMarkers,
  layerRows,
  markKindCounts,
  markSymbolClasses,
  minPointsFor,
  parseHiddenLayers,
  readHiddenLayers,
  resolveMapColors,
  toggleLayerHidden,
  visibleDrawings,
  visibleMarks,
  visibleTracks,
  writeHiddenLayers,
  zoneTooltipLabel,
  type LayerView,
  type MapColors,
  type MapDrawing,
  type TacticalMark,
  type TacticalTrack,
} from './tactical-map-logic';

function mark(overrides: Partial<TacticalMark> = {}): TacticalMark {
  return {
    id: 'v1',
    layerId: 'layer-a',
    position: { latitude: 50, longitude: 30 },
    kind: 'TARGET',
    affiliation: 'HOSTILE',
    label: 'Contact',
    verification: 'CONFIRMED',
    ...overrides,
  };
}

function asset(overrides: Partial<FleetMarker> = {}): FleetMarker {
  return {
    assetId: 'a1',
    displayName: 'Alpha',
    category: 'drone',
    categoryName: 'Drone',
    status: 'STREAMING',
    live: true,
    position: { latitude: 50, longitude: 30 },
    trail: [],
    ...overrides,
  };
}

describe('symbology class resolution', () => {
  it('resolves the affiliation frame class', () => {
    expect(markSymbolClasses(mark({ affiliation: 'FRIENDLY' }))).toContain('aff-friendly');
    expect(markSymbolClasses(mark({ affiliation: 'HOSTILE' }))).toContain('aff-hostile');
    expect(markSymbolClasses(mark({ affiliation: 'NEUTRAL' }))).toContain('aff-neutral');
    expect(markSymbolClasses(mark({ affiliation: 'UNKNOWN' }))).toContain('aff-unknown');
  });

  it('always carries the shared base class', () => {
    expect(markSymbolClasses(mark()).split(' ')[0]).toBe('mark-symbol');
  });

  it('adds `unverified` only for an unverified mark', () => {
    expect(markSymbolClasses(mark({ verification: 'UNVERIFIED' }))).toContain('unverified');
    expect(markSymbolClasses(mark({ verification: 'CONFIRMED' }))).not.toContain('unverified');
    expect(markSymbolClasses(mark({ verification: 'REJECTED' }))).not.toContain('unverified');
  });

  it('adds `selected` and `cop` only when asked', () => {
    expect(markSymbolClasses(mark(), { selected: true, cop: true })).toContain('selected');
    expect(markSymbolClasses(mark(), { selected: true, cop: true })).toContain('cop');
    const plain = markSymbolClasses(mark());
    expect(plain).not.toContain('selected');
    expect(plain).not.toContain('cop');
  });

  it('collects COP layer ids for the common-picture ring', () => {
    const layers: LayerView[] = [
      { layerId: 'cop', name: 'Common picture', kind: 'COP', myAccess: 'VIEW' },
      { layerId: 'team', name: 'Team', kind: 'TEAM', myAccess: 'CONTRIBUTE' },
    ];
    expect([...copLayerIds(layers)]).toEqual(['cop']);
  });
});

describe('layer visibility (the eye toggles)', () => {
  beforeEach(() => localStorage.clear());

  it('survives every shape localStorage can actually hold', () => {
    expect(parseHiddenLayers(null)).toEqual([]);
    expect(parseHiddenLayers('')).toEqual([]);
    expect(parseHiddenLayers('{"not":"an array"}')).toEqual([]);
    expect(parseHiddenLayers('[not json')).toEqual([]);
    expect(parseHiddenLayers('["a",1,"b"]')).toEqual(['a', 'b']);
  });

  it('round-trips through localStorage under the plans own key', () => {
    writeHiddenLayers(['a', BUILTIN_ZONES_LAYER]);
    expect(localStorage.getItem(HIDDEN_LAYERS_KEY)).toBe('["a","builtin:zones"]');
    expect(readHiddenLayers()).toEqual(['a', BUILTIN_ZONES_LAYER]);
  });

  it('toggles an id in and out', () => {
    expect(toggleLayerHidden([], 'a')).toEqual(['a']);
    expect(toggleLayerHidden(['a', 'b'], 'a')).toEqual(['b']);
  });

  it('filters marks and drawings by their own layer', () => {
    const marks = [mark({ id: '1', layerId: 'a' }), mark({ id: '2', layerId: 'b' })];
    const drawings: MapDrawing[] = [
      { id: 'd1', layerId: 'a', kind: 'LINE', points: [] },
      { id: 'd2', layerId: 'b', kind: 'LINE', points: [] },
    ];
    expect(visibleMarks(marks, ['a']).map((m) => m.id)).toEqual(['2']);
    expect(visibleDrawings(drawings, ['b']).map((d) => d.id)).toEqual(['d1']);
    expect(visibleMarks(marks, []).length).toBe(2);
  });

  it('filters projected tracks by their own target layer too (docs/plans/active/FIXED-CAMERA-GEO-PLAN.md D10, wave G5)', () => {
    const tracks: TacticalTrack[] = [
      {
        assetId: 'a1',
        trackId: 1,
        label: 'car',
        layerId: 'a',
        latitude: 1,
        longitude: 1,
        rangeMeters: 10,
        errorRadiusMeters: 5,
        updatedAt: '2026-08-19T00:00:00Z',
        trail: [],
      },
      {
        assetId: 'a1',
        trackId: 2,
        label: 'van',
        layerId: 'b',
        latitude: 2,
        longitude: 2,
        rangeMeters: 20,
        errorRadiusMeters: 8,
        updatedAt: '2026-08-19T00:00:00Z',
        trail: [],
      },
    ];
    expect(visibleTracks(tracks, ['a']).map((t) => t.trackId)).toEqual([2]);
    expect(visibleTracks(tracks, []).length).toBe(2);
  });
});

describe('data-layer panel rows', () => {
  it('counts the marks and drawings actually on the map, per layer', () => {
    const layers: LayerView[] = [{ layerId: 'a', name: 'Alpha', kind: 'TEAM', myAccess: 'CONTRIBUTE' }];
    const rows = layerRows(
      layers,
      [mark({ id: '1', layerId: 'a' }), mark({ id: '2', layerId: 'a' })],
      [{ id: 'd1', layerId: 'a', kind: 'POLYGON', points: [] }],
      [],
    );
    expect(rows).toEqual([{ id: 'a', name: 'Alpha', kind: 'TEAM', markCount: 2, drawingCount: 1, hidden: false }]);
  });

  it('falls back to the servers own counts for a layer with nothing rendered', () => {
    const layers: LayerView[] = [
      { layerId: 'a', name: 'Alpha', kind: 'COP', myAccess: 'VIEW', markCount: 7, drawingCount: 2 },
    ];
    expect(layerRows(layers, [], [], [])[0]).toMatchObject({ markCount: 7, drawingCount: 2 });
  });

  it('synthesizes an id-named row for a layer nobody described (a mark whose layer event has not landed yet)', () => {
    const rows = layerRows([], [mark({ id: 'x', layerId: 'layer-unknown' })], [], []);
    expect(rows).toEqual([
      { id: 'layer-unknown', name: 'layer-unknown', markCount: 1, drawingCount: 0, hidden: false },
    ]);
  });

  it('reports the hidden flag from the persisted set', () => {
    const rows = layerRows([], [mark({ layerId: 'layer-unknown' })], [], ['layer-unknown']);
    expect(rows[0].hidden).toBe(true);
  });

  it('renders a built-in row only for an overlay that has something in it', () => {
    const rows = builtinRows({ assets: 3, zones: 0, events: 2 }, [BUILTIN_EVENTS_LAYER]);
    expect(rows).toEqual([
      { id: BUILTIN_ASSETS_LAYER, name: 'Assets', count: 3, hidden: false },
      { id: BUILTIN_EVENTS_LAYER, name: 'Events', count: 2, hidden: true },
    ]);
  });
});

describe('legend counts', () => {
  it('splits plotted assets by state and takes the unplottable count from the host', () => {
    const counts = assetLegendCounts(
      [asset({ assetId: 'a1', live: true }), asset({ assetId: 'a2', live: false }), asset({ assetId: 'a3', live: false })],
      new Set(['a2']),
      4,
    );
    expect(counts).toEqual({ streaming: 1, offline: 2, attention: 1, noPosition: 4 });
  });

  it('never invents a no-position count the host did not give it', () => {
    expect(assetLegendCounts([asset()], new Set(), 0).noPosition).toBe(0);
  });

  it('counts marks per affiliation and per kind', () => {
    const marks = [
      mark({ id: '1', affiliation: 'HOSTILE', kind: 'TARGET' }),
      mark({ id: '2', affiliation: 'HOSTILE', kind: 'HAZARD' }),
      mark({ id: '3', affiliation: 'FRIENDLY', kind: 'UNIT' }),
    ];
    expect(affiliationCounts(marks)).toEqual({ FRIENDLY: 1, HOSTILE: 2, NEUTRAL: 0, UNKNOWN: 0 });
    expect(markKindCounts(marks)).toEqual({ UNIT: 1, EQUIPMENT: 0, HAZARD: 1, POI: 0, TARGET: 1 });
  });
});

describe('drawing drafts', () => {
  it('maps a drawing mode to its kind, and the two non-drawing modes to nothing', () => {
    expect(draftKindForMode('draw-line')).toBe('LINE');
    expect(draftKindForMode('draw-polygon')).toBe('POLYGON');
    expect(draftKindForMode('draw-arrow')).toBe('ARROW');
    expect(draftKindForMode('draw-text')).toBe('TEXT');
    expect(draftKindForMode('view')).toBeUndefined();
    expect(draftKindForMode('mark')).toBeUndefined();
  });

  it('mirrors Drawings own minimum-vertex rules', () => {
    expect(minPointsFor('LINE')).toBe(2);
    expect(minPointsFor('ARROW')).toBe(2);
    expect(minPointsFor('POLYGON')).toBe(3);
    expect(minPointsFor('TEXT')).toBe(1);
  });

  it('appends vertices without mutating the draft', () => {
    const draft = { kind: 'LINE' as const, points: [{ latitude: 1, longitude: 1 }] };
    const next = appendVertex(draft, { latitude: 2, longitude: 2 });
    expect(draft.points.length).toBe(1);
    expect(next.points.map((p) => p.latitude)).toEqual([1, 2]);
  });

  it('refuses to complete a degenerate shape', () => {
    expect(completedDraft({ kind: 'LINE', points: [{ latitude: 1, longitude: 1 }] })).toBeUndefined();
    expect(
      completedDraft({ kind: 'POLYGON', points: [{ latitude: 1, longitude: 1 }, { latitude: 2, longitude: 2 }] }),
    ).toBeUndefined();
    const line = { kind: 'LINE' as const, points: [{ latitude: 1, longitude: 1 }, { latitude: 2, longitude: 2 }] };
    expect(completedDraft(line)).toBe(line);
  });

  it('points an arrowhead along its last segment', () => {
    const north = arrowRotationDegrees([{ latitude: 0, longitude: 0 }, { latitude: 1, longitude: 0 }]);
    const east = arrowRotationDegrees([{ latitude: 0, longitude: 0 }, { latitude: 0, longitude: 1 }]);
    const south = arrowRotationDegrees([{ latitude: 1, longitude: 0 }, { latitude: 0, longitude: 0 }]);
    expect(north).toBeCloseTo(0, 5);
    expect(east).toBeCloseTo(90, 5);
    expect(south).toBeCloseTo(180, 5);
    expect(arrowRotationDegrees([{ latitude: 0, longitude: 0 }])).toBe(0);
  });
});

describe('follow-mode marker', () => {
  it('plots the freshest fix', () => {
    const marker = followMarker({
      assetId: 'a1',
      displayName: 'Alpha',
      trail: [{ latitude: 1, longitude: 1 }],
      latest: { deviceId: 'd1', at: '2026-08-05T10:00:00Z', latitude: 2, longitude: 3, headingDegrees: 90, batteryPercent: 55 },
    });
    expect(marker?.position).toEqual({ latitude: 2, longitude: 3, altitudeMeters: undefined });
    expect(marker?.headingDegrees).toBe(90);
    expect(marker?.batteryPercent).toBe(55);
    expect(marker?.live).toBe(true);
    expect(marker?.trail.length).toBe(1);
  });

  it('falls back to the last trail point when the freshest sample carries no fix', () => {
    const marker = followMarker({
      assetId: 'a1',
      displayName: 'Alpha',
      trail: [{ latitude: 1, longitude: 1 }, { latitude: 4, longitude: 5 }],
      latest: { deviceId: 'd1', at: '2026-08-05T10:00:00Z', batteryPercent: 20 },
    });
    expect(marker?.position).toEqual({ latitude: 4, longitude: 5 });
  });

  it('plots nothing at all when there is no position anywhere', () => {
    expect(followMarker({ assetId: 'a1', displayName: 'Alpha', trail: [], latest: undefined })).toBeUndefined();
    expect(followMarkers({ assetId: 'a1', displayName: 'Alpha', trail: [], latest: undefined })).toEqual([]);
  });
});

describe('shared helpers lifted from the two deleted map components', () => {
  it('escapes every character that could break out of popup HTML', () => {
    expect(escapeHtml(`<img src=x onerror="a">&'`)).toBe('&lt;img src=x onerror=&quot;a&quot;&gt;&amp;&#39;');
  });

  it('labels a zone by kind, name, and whether it is enforced', () => {
    expect(zoneTooltipLabel({ kind: 'KEEP_OUT', name: 'North', enabled: true })).toBe('KEEP-OUT: North');
    expect(zoneTooltipLabel({ kind: 'KEEP_IN', name: 'Field', enabled: false })).toBe('KEEP-IN: Field (disabled)');
  });

  it('resolves a drawing colour token, falling back to the accent', () => {
    expect(drawingColor('danger')).not.toBe(drawingColor('accent'));
    expect(drawingColor(undefined)).toBe(drawingColor('accent'));
    expect(drawingColor('not-a-token')).toBe(drawingColor('accent'));
  });

  it('drawingColor resolves against the passed-in live colours, not the fallback, once given one', () => {
    const live: MapColors = { trail: '#111111', danger: '#222222', warn: '#333333', success: '#444444', neutral: '#555555' };
    expect(drawingColor('accent', live)).toBe('#111111');
    expect(drawingColor('info', live)).toBe('#111111');
    expect(drawingColor('danger', live)).toBe('#222222');
    expect(drawingColor('warn', live)).toBe('#333333');
    expect(drawingColor('success', live)).toBe('#444444');
    expect(drawingColor('neutral', live)).toBe('#555555');
    expect(drawingColor(undefined, live)).toBe('#111111');
    expect(drawingColor('not-a-token', live)).toBe('#111111');
  });
});

describe('resolveMapColors', () => {
  it('reads every role from its own token, trimming whitespace a real getComputedStyle read leaves in', () => {
    const values: Record<string, string> = {
      '--color-info': '  #2e6bcf  ',
      '--color-danger': '#c9333f',
      '--color-warn': '#b26a00',
      '--color-success': '#1f7f4c',
      '--text-faint': '#8a94a2',
    };
    const colors = resolveMapColors((name) => values[name]);
    expect(colors).toEqual({
      trail: '#2e6bcf',
      danger: '#c9333f',
      warn: '#b26a00',
      success: '#1f7f4c',
      neutral: '#8a94a2',
    });
  });

  it('falls back per-token when a variable resolves empty or is missing entirely', () => {
    const colors = resolveMapColors((name) => (name === '--color-danger' ? '' : undefined));
    expect(colors).toEqual(FALLBACK_MAP_COLORS);
  });

  it('never returns the same object reference as FALLBACK_MAP_COLORS even when every value matches it', () => {
    // A caller (`TacticalMap`) sets a signal from this return value on every theme flip — asserting a
    // fresh object here is what guards against ever "optimizing" this into returning the shared
    // fallback constant directly, which would make the signal's `Object.is` check silently no-op.
    const colors = resolveMapColors(() => undefined);
    expect(colors).toEqual(FALLBACK_MAP_COLORS);
    expect(colors).not.toBe(FALLBACK_MAP_COLORS);
  });
});
