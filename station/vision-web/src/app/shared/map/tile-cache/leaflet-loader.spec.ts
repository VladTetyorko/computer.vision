import { computed } from '@angular/core';
import { beforeEach, describe, expect, it } from 'vitest';
import {
  MAP_LAYERS,
  defaultMapLayerIdForTheme,
  effectiveMapLayerId,
  isMapLayerExplicit,
  mapLayerDef,
  markMapLayerExplicit,
} from './leaflet-loader';

describe('defaultMapLayerIdForTheme', () => {
  it('picks the plain OSM raster (already a genuinely light basemap) for the light theme', () => {
    expect(defaultMapLayerIdForTheme('light')).toBe('standard');
  });

  it('picks the filtered-dark OSM layer for the dark theme', () => {
    expect(defaultMapLayerIdForTheme('dark')).toBe('night');
  });
});

describe('effectiveMapLayerId', () => {
  it('ignores the stored choice and follows the theme default while nothing was ever explicitly picked', () => {
    expect(effectiveMapLayerId('light', 'night', false)).toBe('standard');
    expect(effectiveMapLayerId('dark', 'standard', false)).toBe('night');
  });

  it('an explicit pick always wins, regardless of theme', () => {
    expect(effectiveMapLayerId('light', 'satellite', true)).toBe('satellite');
    expect(effectiveMapLayerId('dark', 'relief', true)).toBe('relief');
    // Even when the explicit pick happens to equal what the theme default would have been anyway.
    expect(effectiveMapLayerId('dark', 'standard', true)).toBe('standard');
  });
});

// This module seeds one reactive signal from `localStorage` once per file load (see
// `leaflet-loader.ts#explicitMapLayer`'s own doc comment) — `markMapLayerExplicit()` only ever
// moves it false→true, mirroring the real product invariant ("an explicit pick always wins,
// forever"), so — unlike a bare `localStorage` read — `localStorage.clear()` alone cannot roll it
// back mid-file. The cases below are therefore intentionally ordered: the "defaults to false" and
// "is reactive" cases must run before anything else in this describe block flips the flag.
describe('isMapLayerExplicit / markMapLayerExplicit', () => {
  beforeEach(() => localStorage.clear());

  it('defaults to false with nothing persisted', () => {
    expect(isMapLayerExplicit()).toBe(false);
  });

  it('BUG 1 regression guard: is a genuine reactive signal, not a plain localStorage read — a computed() over it re-notifies the instant it flips, not only after a fresh read', () => {
    const derived = computed(() => isMapLayerExplicit());
    expect(derived()).toBe(false);
    markMapLayerExplicit();
    expect(derived()).toBe(true);
  });

  it('flips true once a layer pick is recorded, and stays true', () => {
    markMapLayerExplicit();
    expect(isMapLayerExplicit()).toBe(true);
  });

  it('persists the flip to localStorage as well as the in-memory signal', () => {
    markMapLayerExplicit();
    expect(localStorage.getItem('vision.map.layerExplicit')).toBe('true');
  });
});

describe('mapLayerDef', () => {
  it('falls back to the first (standard) layer for an unrecognized id', () => {
    expect(mapLayerDef('not-a-real-layer' as never).id).toBe('standard');
  });

  it('looks up a real id', () => {
    expect(mapLayerDef('night').id).toBe('night');
  });
});

// docs/plans/active/OPERATOR-UX-6-PLAN.md M1: `night` used to be CARTO Dark Matter, which now
// returns "API KEY REQUIRED" tiles — every dark-theme map was dead. `night` renders OSM raster
// (same source as `standard`) through a CSS filter on the tile pane instead, with no API key.
describe('night basemap (M1 fix)', () => {
  it('carries a tileFilter, unlike every other layer', () => {
    expect(mapLayerDef('night').tileFilter).toBe(
      'invert(1) hue-rotate(180deg) brightness(0.85) contrast(0.9) saturate(0.6)',
    );
    for (const layer of MAP_LAYERS) {
      if (layer.id !== 'night') {
        expect(layer.tileFilter).toBeUndefined();
      }
    }
  });

  it('shares its tile source with standard (OSM raster, no CARTO/API-key dependency)', () => {
    expect(mapLayerDef('night').url).toBe(mapLayerDef('standard').url);
    expect(mapLayerDef('night').attribution).toBe(mapLayerDef('standard').attribution);
  });

  it('attributes OSM only — no CARTO credit for tiles CARTO no longer serves', () => {
    expect(mapLayerDef('night').attribution).not.toMatch(/carto/i);
  });
});
