import { beforeEach, describe, expect, it } from 'vitest';
import {
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

  it('picks CARTO Dark Matter for the dark theme', () => {
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

describe('isMapLayerExplicit / markMapLayerExplicit', () => {
  beforeEach(() => localStorage.clear());

  it('defaults to false with nothing persisted', () => {
    expect(isMapLayerExplicit()).toBe(false);
  });

  it('flips true once a layer pick is recorded, and stays true', () => {
    markMapLayerExplicit();
    expect(isMapLayerExplicit()).toBe(true);
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
