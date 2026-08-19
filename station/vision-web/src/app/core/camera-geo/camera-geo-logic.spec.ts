import { HttpErrorResponse } from '@angular/common/http';
import { describe, expect, it } from 'vitest';
import type { CalibrationResult, CameraPoseResponse, MapEventPayload, ProjectedTrackResponse } from '../api/models';
import {
  applyTrackEvent,
  armFrameClick,
  calibratedPoseRequest,
  calibrationResultTone,
  calibrationSummary,
  canAddCalibrationPoint,
  canRunCalibration,
  canSaveCalibration,
  detectionStateExplanation,
  detectionStateLabel,
  detectionStateTone,
  draftFromPose,
  isFixedCameraGeoDisabledError,
  manualPoseRequest,
  measuredPosition,
  pairMapClick,
  poseFactRows,
  poseSourceLabel,
  removeCalibrationPoint,
  toCalibrationRequest,
  trackChipLabel,
  trackErrorRadiusMeters,
  trackKey,
  trackTrailPoints,
  type CalibrationPointDraft,
} from './camera-geo-logic';

function errorOf(status: number, error: unknown): HttpErrorResponse {
  return new HttpErrorResponse({ status, error });
}

const MANUAL_POSE: CameraPoseResponse = {
  assetId: 'asset-1',
  latitude: 50.4501,
  longitude: 30.5234,
  aglMeters: 12,
  yawDegrees: 214,
  pitchDegrees: 8.5,
  hfovDegrees: 62,
  source: 'MANUAL',
  updatedAt: '2026-08-19T12:00:00Z',
};

const CALIBRATED_POSE: CameraPoseResponse = { ...MANUAL_POSE, source: 'CALIBRATED', rmsErrorPixels: 7.3 };

describe('isFixedCameraGeoDisabledError', () => {
  const DISABLED_TEXT = 'fixed-camera geolocation is disabled (vision.geo.fixed-camera.enabled)';

  it('matches the established {error,message} envelope', () => {
    expect(isFixedCameraGeoDisabledError(errorOf(409, { error: 'CONFLICT', message: DISABLED_TEXT }))).toBe(true);
  });

  it("also matches §5's own literal {detail} envelope, defensively", () => {
    expect(isFixedCameraGeoDisabledError(errorOf(409, { detail: DISABLED_TEXT }))).toBe(true);
  });

  it('matches a bare string body', () => {
    expect(isFixedCameraGeoDisabledError(errorOf(409, DISABLED_TEXT))).toBe(true);
  });

  it('is false for a different 409 (status alone is not enough)', () => {
    expect(isFixedCameraGeoDisabledError(errorOf(409, { error: 'CONFLICT', message: 'something else entirely' }))).toBe(
      false,
    );
  });

  it('is false for a non-409 status', () => {
    expect(isFixedCameraGeoDisabledError(errorOf(403, { error: 'FORBIDDEN', message: DISABLED_TEXT }))).toBe(false);
  });

  it('is false for a non-HttpErrorResponse', () => {
    expect(isFixedCameraGeoDisabledError(new Error('boom'))).toBe(false);
    expect(isFixedCameraGeoDisabledError(null)).toBe(false);
  });
});

describe('poseSourceLabel / poseFactRows', () => {
  it('labels a manual pose plainly', () => {
    expect(poseSourceLabel(MANUAL_POSE)).toBe('Manual entry');
  });

  it('labels a calibrated pose with its residual when present', () => {
    expect(poseSourceLabel(CALIBRATED_POSE)).toBe('Calibrated · 7.3px residual');
  });

  it('labels a calibrated pose with no reported residual plainly', () => {
    expect(poseSourceLabel({ source: 'CALIBRATED', rmsErrorPixels: undefined })).toBe('Calibrated');
  });

  it('formats every fact row from the pose', () => {
    const rows = poseFactRows(MANUAL_POSE);
    expect(rows).toEqual([
      { label: 'Position', value: '50.45010, 30.52340', mono: true },
      { label: 'Height AGL', value: '12.0 m' },
      { label: 'Yaw', value: '214.0°' },
      { label: 'Pitch', value: '8.5°' },
      { label: 'Horizontal FOV', value: '62.0°' },
      { label: 'Source', value: 'Manual entry' },
    ]);
  });
});

describe('detection-state presentation (D9)', () => {
  it('covers RUNNING/IDLE_NO_VIEWERS/OFF/undefined distinctly', () => {
    expect(detectionStateLabel('RUNNING')).toBe('Detecting');
    expect(detectionStateLabel('IDLE_NO_VIEWERS')).toBe('Detection idle');
    expect(detectionStateLabel('OFF')).toBe('Detection off');
    expect(detectionStateLabel(undefined)).toBe('Detection unknown');
  });

  it('tones RUNNING ok, IDLE warn, OFF/undefined muted', () => {
    expect(detectionStateTone('RUNNING')).toBe('ok');
    expect(detectionStateTone('IDLE_NO_VIEWERS')).toBe('warn');
    expect(detectionStateTone('OFF')).toBe('muted');
    expect(detectionStateTone(undefined)).toBe('muted');
  });

  it('explains OFF so a dead map reads as explained, not broken', () => {
    expect(detectionStateExplanation('OFF')).toMatch(/turned off/);
    expect(detectionStateExplanation('RUNNING')).toMatch(/project onto the map/);
    expect(detectionStateExplanation(undefined)).toMatch(/hasn't reported/);
  });
});

describe('measuredPosition (the wizard’s own camera-position sub-form)', () => {
  it('parses a fully-filled draft', () => {
    expect(measuredPosition({ latitude: '50.4501', longitude: '30.5234', aglMeters: '12' })).toEqual({
      latitude: 50.4501,
      longitude: 30.5234,
      aglMeters: 12,
    });
  });

  it('rejects a blank field rather than treating it as 0 (the Number(\'\') gotcha)', () => {
    expect(measuredPosition({ latitude: '50.4501', longitude: '30.5234', aglMeters: '' })).toBeNull();
  });

  it('rejects a non-numeric field', () => {
    expect(measuredPosition({ latitude: 'north', longitude: '30.5234', aglMeters: '12' })).toBeNull();
  });
});

describe('calibration point pairing (D5)', () => {
  it('arms a pending frame click', () => {
    expect(armFrameClick(0.4, 0.8)).toEqual({ u: 0.4, v: 0.8 });
  });

  it('a map click with no pending frame click is a no-op', () => {
    const result = pairMapClick(null, [], 50, 30);
    expect(result.points).toEqual([]);
    expect(result.pending).toBeNull();
  });

  it('a map click completes the pending pair and clears it', () => {
    const pending = armFrameClick(0.4, 0.8);
    const result = pairMapClick(pending, [], 50.001, 30.002);
    expect(result.points).toEqual([{ u: 0.4, v: 0.8, latitude: 50.001, longitude: 30.002 }]);
    expect(result.pending).toBeNull();
  });

  it('appends to existing points rather than replacing them', () => {
    const existing: readonly CalibrationPointDraft[] = [{ u: 0.1, v: 0.1, latitude: 1, longitude: 1 }];
    const result = pairMapClick(armFrameClick(0.2, 0.2), existing, 2, 2);
    expect(result.points).toHaveLength(2);
    expect(result.points[0]).toEqual(existing[0]);
  });

  it('a second frame click before its pair replaces the pending half, not stacks it', () => {
    const first = armFrameClick(0.1, 0.1);
    const second = armFrameClick(0.9, 0.9);
    expect(second).not.toEqual(first);
    const result = pairMapClick(second, [], 5, 5);
    expect(result.points).toEqual([{ u: 0.9, v: 0.9, latitude: 5, longitude: 5 }]);
  });

  it('removes a point by index', () => {
    const points: readonly CalibrationPointDraft[] = [
      { u: 0.1, v: 0.1, latitude: 1, longitude: 1 },
      { u: 0.2, v: 0.2, latitude: 2, longitude: 2 },
    ];
    expect(removeCalibrationPoint(points, 0)).toEqual([points[1]]);
  });

  it('bounds N to [2,8] (D5)', () => {
    const pointAt = (n: number): CalibrationPointDraft => ({ u: 0, v: 0, latitude: n, longitude: n });
    expect(canRunCalibration([])).toBe(false);
    expect(canRunCalibration([pointAt(1)])).toBe(false);
    expect(canRunCalibration([pointAt(1), pointAt(2)])).toBe(true);
    expect(canRunCalibration(Array.from({ length: 8 }, (_, i) => pointAt(i)))).toBe(true);
    expect(canRunCalibration(Array.from({ length: 9 }, (_, i) => pointAt(i)))).toBe(false);
  });

  it('stops accepting new points at the 8-point ceiling', () => {
    const pointAt = (n: number): CalibrationPointDraft => ({ u: 0, v: 0, latitude: n, longitude: n });
    expect(canAddCalibrationPoint(Array.from({ length: 7 }, (_, i) => pointAt(i)))).toBe(true);
    expect(canAddCalibrationPoint(Array.from({ length: 8 }, (_, i) => pointAt(i)))).toBe(false);
  });

  it('assembles the wire request from measured position + points', () => {
    const points: readonly CalibrationPointDraft[] = [{ u: 0.41, v: 0.83, latitude: 50.45031, longitude: 30.5231 }];
    const request = toCalibrationRequest(50.4501, 30.5234, 12, 1920, 1080, points);
    expect(request).toEqual({
      latitude: 50.4501,
      longitude: 30.5234,
      aglMeters: 12,
      imageWidth: 1920,
      imageHeight: 1080,
      points: [{ u: 0.41, v: 0.83, latitude: 50.45031, longitude: 30.5231 }],
    });
  });
});

describe('calibration result honesty (D5 — solved:false offers no save)', () => {
  const solvedGood: CalibrationResult = {
    solved: true,
    pose: CALIBRATED_POSE,
    rmsErrorPixels: 7.3,
    quality: 'GOOD',
    reason: null,
  };
  const solvedUndetermined: CalibrationResult = { ...solvedGood, quality: 'UNDETERMINED' };
  const refused: CalibrationResult = {
    solved: false,
    pose: null,
    rmsErrorPixels: 41.0,
    quality: null,
    reason: 'residual 41.0px exceeds 25.0px',
  };

  it('allows saving only when solved', () => {
    expect(canSaveCalibration(solvedGood)).toBe(true);
    expect(canSaveCalibration(refused)).toBe(false);
  });

  it('tones GOOD ok, UNDETERMINED warn, refused danger', () => {
    expect(calibrationResultTone(solvedGood)).toBe('ok');
    expect(calibrationResultTone(solvedUndetermined)).toBe('warn');
    expect(calibrationResultTone(refused)).toBe('danger');
  });

  it('renders the refusal reason verbatim, never paraphrased', () => {
    expect(calibrationSummary(refused)).toBe('residual 41.0px exceeds 25.0px');
  });

  it.each([
    'landmarks span only 6° of bearing',
    'landmark 2 is 1.4m from the camera',
  ])('passes through every D5 refusal example verbatim: %s', (reason) => {
    expect(calibrationSummary({ ...refused, reason })).toBe(reason);
  });

  it('explains an UNDETERMINED 2-point fit rather than reporting a falsely-precise residual', () => {
    expect(calibrationSummary(solvedUndetermined)).toMatch(/third point/);
  });

  it('summarises a GOOD solve with its residual', () => {
    expect(calibrationSummary(solvedGood)).toBe('Solved — 7.3px residual.');
  });

  it('turns a solved result into a CALIBRATED PUT body', () => {
    expect(canSaveCalibration(solvedGood)).toBe(true);
    if (canSaveCalibration(solvedGood)) {
      expect(calibratedPoseRequest(solvedGood)).toEqual({
        latitude: CALIBRATED_POSE.latitude,
        longitude: CALIBRATED_POSE.longitude,
        aglMeters: CALIBRATED_POSE.aglMeters,
        yawDegrees: CALIBRATED_POSE.yawDegrees,
        pitchDegrees: CALIBRATED_POSE.pitchDegrees,
        hfovDegrees: CALIBRATED_POSE.hfovDegrees,
        targetLayerId: null,
        source: 'CALIBRATED',
        rmsErrorPixels: 7.3,
      });
    }
  });
});

describe('manual pose entry', () => {
  it('seeds a blank draft with no pose', () => {
    expect(draftFromPose(null)).toEqual({
      latitude: '',
      longitude: '',
      aglMeters: '',
      yawDegrees: '',
      pitchDegrees: '',
      hfovDegrees: '',
    });
  });

  it('seeds the draft from an existing pose', () => {
    expect(draftFromPose(MANUAL_POSE).latitude).toBe('50.4501');
  });

  it('parses a valid draft into a MANUAL PUT body', () => {
    const draft = draftFromPose(MANUAL_POSE);
    expect(manualPoseRequest(draft, null)).toEqual({
      latitude: 50.4501,
      longitude: 30.5234,
      aglMeters: 12,
      yawDegrees: 214,
      pitchDegrees: 8.5,
      hfovDegrees: 62,
      targetLayerId: null,
      source: 'MANUAL',
      rmsErrorPixels: null,
    });
  });

  it('refuses to parse a blank/non-numeric field rather than sending garbage', () => {
    const draft = { ...draftFromPose(MANUAL_POSE), aglMeters: '' };
    expect(manualPoseRequest(draft, null)).toBeNull();
  });
});

describe('applyTrackEvent (D3, D11 — the live TRACK reducer)', () => {
  const liveTrack: ProjectedTrackResponse = {
    assetId: 'asset-1',
    trackId: 17,
    label: 'car',
    layerId: 'layer-1',
    latitude: 50.45044,
    longitude: 30.52371,
    rangeMeters: 84.2,
    errorRadiusMeters: 6.8,
    updatedAt: '2026-08-19T12:03:04.500Z',
    trail: [{ latitude: 50.4504, longitude: 30.5237, at: '2026-08-19T12:03:00Z' }],
  };

  function createdPayload(overrides: Partial<ProjectedTrackResponse> = {}): MapEventPayload {
    const { trail: _trail, ...withoutTrail } = { ...liveTrack, ...overrides };
    return { entity: 'track', action: 'created', layerId: liveTrack.layerId, track: withoutTrail };
  }

  it('ignores a non-TRACK entity', () => {
    const payload: MapEventPayload = { entity: 'mark', action: 'created', layerId: 'layer-1' };
    expect(applyTrackEvent([], payload)).toEqual([]);
  });

  it('CREATED inserts a new track with an empty trail (trail arrives via GET only)', () => {
    const result = applyTrackEvent([], createdPayload());
    expect(result).toHaveLength(1);
    expect(result[0].trackId).toBe(17);
    expect(result[0].trail).toEqual([]);
  });

  it('UPDATED upserts scalar fields while preserving the existing trail', () => {
    const existing: ProjectedTrackResponse = { ...liveTrack };
    const updated: MapEventPayload = {
      entity: 'track',
      action: 'updated',
      layerId: liveTrack.layerId,
      track: { assetId: 'asset-1', trackId: 17, latitude: 50.451, longitude: 30.524 },
    };
    const result = applyTrackEvent([existing], updated);
    expect(result).toHaveLength(1);
    expect(result[0].latitude).toBe(50.451);
    expect(result[0].longitude).toBe(30.524);
    expect(result[0].trail).toEqual(existing.trail); // preserved, not wiped by the trail-less live event
    expect(result[0].label).toBe(existing.label); // untouched fields fall back to the prior value
  });

  it('CLEARED removes the track outright — nothing lingers', () => {
    const existing: ProjectedTrackResponse = { ...liveTrack };
    const cleared: MapEventPayload = {
      entity: 'track',
      action: 'cleared',
      layerId: liveTrack.layerId,
      track: { assetId: 'asset-1', trackId: 17 },
    };
    expect(applyTrackEvent([existing], cleared)).toEqual([]);
  });

  it('CLEARED only removes the matching (assetId, trackId), leaving other tracks untouched', () => {
    const other: ProjectedTrackResponse = { ...liveTrack, assetId: 'asset-2', trackId: 3 };
    const existing: ProjectedTrackResponse = { ...liveTrack };
    const cleared: MapEventPayload = {
      entity: 'track',
      action: 'cleared',
      layerId: liveTrack.layerId,
      track: { assetId: 'asset-1', trackId: 17 },
    };
    const result = applyTrackEvent([existing, other], cleared);
    expect(result).toEqual([other]);
  });

  it('trackKey composes the (assetId, trackId) identity used throughout', () => {
    expect(trackKey('asset-1', 17)).toBe('asset-1:17');
  });
});

describe('track trail/error-circle/label derivation (D6, D7)', () => {
  const track: ProjectedTrackResponse = {
    assetId: 'asset-1',
    trackId: 17,
    label: 'car',
    layerId: 'layer-1',
    latitude: 50.451,
    longitude: 30.524,
    rangeMeters: 84.2,
    errorRadiusMeters: 6.8,
    updatedAt: '2026-08-19T12:03:04.500Z',
    trail: [{ latitude: 50.45, longitude: 30.523, at: '2026-08-19T12:03:00Z' }],
  };

  it('appends the current head position to the stored trail', () => {
    expect(trackTrailPoints(track)).toEqual([
      { latitude: 50.45, longitude: 30.523 },
      { latitude: 50.451, longitude: 30.524 },
    ]);
  });

  it('does not duplicate the head position when it already matches the last trail point', () => {
    const atRest: ProjectedTrackResponse = { ...track, trail: [{ latitude: 50.451, longitude: 30.524, at: track.updatedAt }] };
    expect(trackTrailPoints(atRest)).toHaveLength(1);
  });

  it('is a single point for a brand-new track with no stored trail yet', () => {
    const brandNew: ProjectedTrackResponse = { ...track, trail: [] };
    expect(trackTrailPoints(brandNew)).toEqual([{ latitude: 50.451, longitude: 30.524 }]);
  });

  it('the error radius is always a non-negative number — drawn unconditionally (D6)', () => {
    expect(trackErrorRadiusMeters(track)).toBe(6.8);
    expect(trackErrorRadiusMeters({ errorRadiusMeters: -1 })).toBe(0);
  });

  it('the chip label pairs the stable id with the object kind', () => {
    expect(trackChipLabel(track)).toBe('#17 car');
    expect(trackChipLabel({ trackId: 4, label: '' })).toBe('#4');
  });
});
