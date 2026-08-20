import { HttpErrorResponse } from '@angular/common/http';
import { describe, expect, it } from 'vitest';
import type { CorrectionResponse, RegionProgressResponse } from '../api/models';
import {
  BLANK_REGION_INGEST_DRAFT,
  correctedTrailPoints,
  correctionRadiusMeters,
  correctionToneKey,
  divergenceBands,
  geoChipLabel,
  geoChipTone,
  geoDetailRows,
  hasCorrectionFix,
  isVisualGeoDisabledError,
  regionIngestRequest,
  regionPhaseLabel,
  regionProgressPercent,
  regionStatusLabel,
  regionStatusTone,
} from './geo-logic';

function correction(partial: Partial<CorrectionResponse> = {}): CorrectionResponse {
  return {
    assetId: 'asset-1',
    usageId: 'usage-1',
    frameAt: '2026-08-20T09:14:22.400Z',
    computedAt: '2026-08-20T09:14:22.910Z',
    status: 'CONFIRMED',
    source: 'VISUAL_HEAVY',
    divergent: false,
    ...partial,
  };
}

describe('isVisualGeoDisabledError', () => {
  it('matches the D9 flag-off envelope exactly', () => {
    const error = new HttpErrorResponse({
      status: 409,
      error: { error: 'CONFLICT', message: 'visual geolocation is disabled (vision.geo.visual.enabled)' },
    });
    expect(isVisualGeoDisabledError(error)).toBe(true);
  });

  it('is false for an unrelated 409', () => {
    const error = new HttpErrorResponse({ status: 409, error: { error: 'CONFLICT', message: 'stream already running' } });
    expect(isVisualGeoDisabledError(error)).toBe(false);
  });

  it('is false for a non-HttpErrorResponse', () => {
    expect(isVisualGeoDisabledError(new Error('boom'))).toBe(false);
  });
});

describe('geoChipLabel / geoChipTone', () => {
  it('reads "GEO —", dim, with no correction at all', () => {
    expect(geoChipLabel(undefined)).toBe('GEO —');
    expect(geoChipTone(undefined)).toBe('dim');
  });

  it('reads "GEO —", dim, on a NO_FIX row', () => {
    const c = correction({ status: 'NO_FIX', divergent: false });
    expect(geoChipLabel(c)).toBe('GEO —');
    expect(geoChipTone(c)).toBe('dim');
  });

  it('reads "GEO ok", ok tone, for a non-divergent CONFIRMED row', () => {
    const c = correction({ status: 'CONFIRMED', divergent: false });
    expect(geoChipLabel(c)).toBe('GEO ok');
    expect(geoChipTone(c)).toBe('ok');
  });

  it('never reads warn/red for a non-divergent PROBABLE row — the chip is ok, not alarmed', () => {
    const c = correction({ status: 'PROBABLE', divergent: false });
    expect(geoChipLabel(c)).toBe('GEO ok');
    expect(geoChipTone(c)).toBe('ok');
  });

  it('reads "GEO Δ 84 m", warn tone, when divergent with a separation', () => {
    const c = correction({ status: 'CONFIRMED', divergent: true, separationMeters: 84.4 });
    expect(geoChipLabel(c)).toBe('GEO Δ 84 m');
    expect(geoChipTone(c)).toBe('warn');
  });

  it('degrades to "GEO Δ —" (still warn) when divergent but separationMeters is absent', () => {
    const c = correction({ status: 'CONFIRMED', divergent: true, separationMeters: undefined });
    expect(geoChipLabel(c)).toBe('GEO Δ —');
    expect(geoChipTone(c)).toBe('warn');
  });
});

describe('geoDetailRows', () => {
  it('renders every §3.8 fact, "—" for every absent optional', () => {
    const rows = geoDetailRows(correction({ status: 'CONFIRMED' }));
    expect(rows).toEqual([
      { label: 'Status', value: 'CONFIRMED' },
      { label: 'Separation', value: '—', mono: true },
      { label: 'Radius', value: '—', mono: true },
      { label: 'Inliers', value: '—', mono: true },
      { label: 'Inlier ratio', value: '—', mono: true },
      { label: 'Sequence spread', value: '—', mono: true },
      { label: 'Region', value: '—' },
    ]);
  });

  it('formats present values and appends refusal verbatim only on NO_FIX', () => {
    const rows = geoDetailRows(
      correction({
        status: 'NO_FIX',
        separationMeters: 16.24,
        radiusMeters: 18.4,
        inlierCount: 131,
        inlierRatio: 0.753,
        sequenceSpreadMeters: 38.0,
        regionId: 'kyiv-pozniaky',
        refusal: 'residual 41.0px exceeds 25.0px',
      }),
    );
    expect(rows).toContainEqual({ label: 'Separation', value: '16.2 m', mono: true });
    expect(rows).toContainEqual({ label: 'Inlier ratio', value: '75%', mono: true });
    expect(rows).toContainEqual({ label: 'Refusal', value: 'residual 41.0px exceeds 25.0px' });
  });

  it('never appends a Refusal row when status is not NO_FIX, even if refusal happened to be present', () => {
    const rows = geoDetailRows(correction({ status: 'CONFIRMED', refusal: 'should not render' }));
    expect(rows.some((row) => row.label === 'Refusal')).toBe(false);
  });
});

describe('correctionRadiusMeters / hasCorrectionFix / correctionToneKey', () => {
  it('floors at 0 and defaults to 0 when radiusMeters is absent', () => {
    expect(correctionRadiusMeters({ radiusMeters: 18.4 })).toBe(18.4);
    expect(correctionRadiusMeters({ radiusMeters: undefined })).toBe(0);
    expect(correctionRadiusMeters({ radiusMeters: -5 })).toBe(0);
  });

  it('hasCorrectionFix is true only when both coordinates are present', () => {
    expect(hasCorrectionFix({ latitude: 50.1, longitude: 30.1 })).toBe(true);
    expect(hasCorrectionFix({ latitude: undefined, longitude: 30.1 })).toBe(false);
    expect(hasCorrectionFix({ latitude: 50.1, longitude: undefined })).toBe(false);
  });

  it('correctionToneKey is warn only while divergent, never danger', () => {
    expect(correctionToneKey({ divergent: true })).toBe('warn');
    expect(correctionToneKey({ divergent: false })).toBe('info');
  });
});

describe('correctedTrailPoints', () => {
  it('includes only fixed rows at or before atMs, oldest→newest as given', () => {
    const corrections = [
      correction({ frameAt: '2026-08-20T09:00:00Z', latitude: 50.1, longitude: 30.1 }),
      correction({ frameAt: '2026-08-20T09:01:00Z', status: 'NO_FIX', latitude: undefined, longitude: undefined }),
      correction({ frameAt: '2026-08-20T09:02:00Z', latitude: 50.2, longitude: 30.2 }),
    ];
    const atMs = Date.parse('2026-08-20T09:01:30Z');
    expect(correctedTrailPoints(corrections, atMs)).toEqual([{ latitude: 50.1, longitude: 30.1 }]);
  });

  it('is empty before the first fixed frame', () => {
    const corrections = [correction({ frameAt: '2026-08-20T09:05:00Z', latitude: 50.1, longitude: 30.1 })];
    expect(correctedTrailPoints(corrections, Date.parse('2026-08-20T09:00:00Z'))).toEqual([]);
  });
});

describe('divergenceBands', () => {
  it('is empty when nothing ever diverged', () => {
    const corrections = [correction({ frameAt: '2026-08-20T09:00:00Z', divergent: false })];
    expect(divergenceBands(corrections)).toEqual([]);
  });

  it('closes a band at the first non-divergent correction after it', () => {
    const corrections = [
      correction({ frameAt: '2026-08-20T09:00:00Z', divergent: false }),
      correction({ frameAt: '2026-08-20T09:01:00Z', divergent: true }),
      correction({ frameAt: '2026-08-20T09:02:00Z', divergent: true }),
      correction({ frameAt: '2026-08-20T09:03:00Z', divergent: false }),
    ];
    expect(divergenceBands(corrections)).toEqual([
      { fromMs: Date.parse('2026-08-20T09:01:00Z'), toMs: Date.parse('2026-08-20T09:03:00Z') },
    ]);
  });

  it('leaves a band open through the last correction if it never clears', () => {
    const corrections = [
      correction({ frameAt: '2026-08-20T09:00:00Z', divergent: false }),
      correction({ frameAt: '2026-08-20T09:01:00Z', divergent: true }),
    ];
    expect(divergenceBands(corrections)).toEqual([
      { fromMs: Date.parse('2026-08-20T09:01:00Z'), toMs: Date.parse('2026-08-20T09:01:00Z') },
    ]);
  });

  it('supports two separate bands', () => {
    const corrections = [
      correction({ frameAt: '2026-08-20T09:00:00Z', divergent: true }),
      correction({ frameAt: '2026-08-20T09:01:00Z', divergent: false }),
      correction({ frameAt: '2026-08-20T09:02:00Z', divergent: true }),
      correction({ frameAt: '2026-08-20T09:03:00Z', divergent: false }),
    ];
    expect(divergenceBands(corrections)).toEqual([
      { fromMs: Date.parse('2026-08-20T09:00:00Z'), toMs: Date.parse('2026-08-20T09:01:00Z') },
      { fromMs: Date.parse('2026-08-20T09:02:00Z'), toMs: Date.parse('2026-08-20T09:03:00Z') },
    ]);
  });
});

describe('region status/phase labels', () => {
  it('gives NEVER_ACCEPT its own warn tone, distinct from FAILED danger', () => {
    expect(regionStatusLabel('NEVER_ACCEPT')).toBe('Never accept');
    expect(regionStatusTone('NEVER_ACCEPT')).toBe('warn');
    expect(regionStatusTone('FAILED')).toBe('danger');
    expect(regionStatusTone('READY')).toBe('ok');
    expect(regionStatusTone('BUILDING')).toBe('muted');
  });

  it('labels every frozen phase', () => {
    expect(regionPhaseLabel('receiving')).toBe('Receiving imagery');
    expect(regionPhaseLabel('done')).toBe('Done');
  });
});

describe('regionProgressPercent', () => {
  it('is undefined (indeterminate) when total is 0 — "no countable unit"', () => {
    const progress: Pick<RegionProgressResponse, 'done' | 'total'> = { done: 0, total: 0 };
    expect(regionProgressPercent(progress)).toBeUndefined();
  });

  it('rounds to the nearest percent, capped at 100', () => {
    expect(regionProgressPercent({ done: 140, total: 312 })).toBe(45);
    expect(regionProgressPercent({ done: 312, total: 312 })).toBe(100);
  });
});

describe('regionIngestRequest', () => {
  it('returns null for a blank draft', () => {
    expect(regionIngestRequest(BLANK_REGION_INGEST_DRAFT)).toBeNull();
  });

  it('parses a complete draft into the §3.3 POST body', () => {
    const request = regionIngestRequest({
      name: 'kyiv-pozniaky',
      zoom: '17',
      north: '50.4020',
      south: '50.3860',
      east: '30.6400',
      west: '30.6120',
    });
    expect(request).toEqual({ name: 'kyiv-pozniaky', zoom: 17, north: 50.402, south: 50.386, east: 30.64, west: 30.612 });
  });

  it('returns null when any bound is unparseable', () => {
    expect(regionIngestRequest({ ...BLANK_REGION_INGEST_DRAFT, name: 'x', north: 'nope' })).toBeNull();
  });
});
