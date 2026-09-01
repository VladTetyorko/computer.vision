import { HttpErrorResponse } from '@angular/common/http';
import { describe, expect, it } from 'vitest';
import type { ReadinessRow } from '../api/models';
import {
  featureBlockers,
  featureLabel,
  featureStatusLabel,
  featureStatusTone,
  fleetRowAttention,
  groundedBannerText,
  groundingBlocker,
  hasBeenProbed,
  isProbeDisabledError,
  isRemediable,
  outcomeLabel,
  outcomeTone,
  parseGroundingBlocker,
  probeBlockedReason,
  readinessCounts,
  remedyLabel,
  sortReadinessRows,
  verdictLabel,
  verdictTone,
} from './readiness-logic';

describe('isProbeDisabledError', () => {
  it('is true for the exact flag-off 409 body', () => {
    const error = new HttpErrorResponse({
      status: 409,
      error: { error: 'Conflict', message: 'vehicle probing is disabled (vision.onboarding.probe.enabled)' },
    });
    expect(isProbeDisabledError(error)).toBe(true);
  });

  it('is false for a 409 with a different message (an unreachable candidate, not flag-off)', () => {
    const error = new HttpErrorResponse({ status: 409, error: { error: 'Conflict', message: 'candidate unreachable' } });
    expect(isProbeDisabledError(error)).toBe(false);
  });

  it('is false for a non-409 status', () => {
    const error = new HttpErrorResponse({
      status: 403,
      error: { error: 'Forbidden', message: 'vehicle probing is disabled (vision.onboarding.probe.enabled)' },
    });
    expect(isProbeDisabledError(error)).toBe(false);
  });

  it('is also true for a plain string body carrying the exact message (mirrors describeHttpError\'s own body-shape handling)', () => {
    const error = new HttpErrorResponse({ status: 409, error: 'vehicle probing is disabled (vision.onboarding.probe.enabled)' });
    expect(isProbeDisabledError(error)).toBe(true);
  });

  it('is false for a non-HttpErrorResponse', () => {
    expect(isProbeDisabledError(new Error('nope'))).toBe(false);
    expect(isProbeDisabledError(undefined)).toBe(false);
  });
});

describe('featureLabel', () => {
  it('labels every frozen v1 feature key', () => {
    expect(featureLabel('map-position')).toBe('Map position');
    expect(featureLabel('battery')).toBe('Battery');
    expect(featureLabel('command-tx')).toBe('Command TX');
  });

  it('falls back to the raw key for an unrecognised feature', () => {
    expect(featureLabel('future-feature')).toBe('future-feature');
  });
});

describe('verdict rendering', () => {
  it('tones GO/NO_GO/UNKNOWN by status meaning, never a categorical hue', () => {
    expect(verdictTone('GO')).toBe('ok');
    expect(verdictTone('NO_GO')).toBe('danger');
    expect(verdictTone('UNKNOWN')).toBe('muted');
  });

  it('labels every verdict', () => {
    expect(verdictLabel('GO')).toBe('Go');
    expect(verdictLabel('NO_GO')).toBe('No-go');
    expect(verdictLabel('UNKNOWN')).toBe('Unknown');
  });
});

describe('feature status rendering', () => {
  it('tones every FeatureStatus', () => {
    expect(featureStatusTone('READY')).toBe('ok');
    expect(featureStatusTone('DEGRADED')).toBe('warn');
    expect(featureStatusTone('MISSING')).toBe('danger');
    expect(featureStatusTone('UNKNOWN')).toBe('muted');
  });

  it('labels every FeatureStatus', () => {
    expect(featureStatusLabel('READY')).toBe('Ready');
    expect(featureStatusLabel('DEGRADED')).toBe('Degraded');
    expect(featureStatusLabel('MISSING')).toBe('Missing');
    expect(featureStatusLabel('UNKNOWN')).toBe('Unknown');
  });
});

describe('remedyLabel / isRemediable', () => {
  it('is null for a row with no remedy', () => {
    expect(remedyLabel(null)).toBeNull();
  });

  it('labels every RemedyKind', () => {
    expect(remedyLabel('MESSAGE_INTERVAL')).toContain('rate request');
    expect(remedyLabel('PARAM_WRITE')).toContain('parameter');
    expect(remedyLabel('CLI_SCRIPT')).toContain('CLI script');
    expect(remedyLabel('MANUAL')).toContain('manual action');
  });

  it('only MESSAGE_INTERVAL is remediable from a button', () => {
    expect(isRemediable('MESSAGE_INTERVAL')).toBe(true);
    expect(isRemediable('PARAM_WRITE')).toBe(false);
    expect(isRemediable('CLI_SCRIPT')).toBe(false);
    expect(isRemediable('MANUAL')).toBe(false);
    expect(isRemediable(null)).toBe(false);
  });
});

describe('remediation outcome rendering', () => {
  it('tones every outcome', () => {
    expect(outcomeTone('ACCEPTED')).toBe('ok');
    expect(outcomeTone('DENIED')).toBe('danger');
    expect(outcomeTone('NO_ACK')).toBe('warn');
    expect(outcomeTone('UNSUPPORTED')).toBe('muted');
  });

  it('labels every outcome', () => {
    expect(outcomeLabel('ACCEPTED')).toBe('Accepted');
    expect(outcomeLabel('DENIED')).toBe('Denied');
    expect(outcomeLabel('NO_ACK')).toBe('No response');
    expect(outcomeLabel('UNSUPPORTED')).toBe('Not supported');
  });
});

function row(partial: Partial<ReadinessRow> = {}): ReadinessRow {
  return { assetId: 'a-1', displayName: 'Drone 1', verdict: 'GO', features: {}, ...partial };
}

describe('fleetRowAttention', () => {
  it('is null when every evaluated feature is READY', () => {
    expect(fleetRowAttention(row({ features: { battery: 'READY', 'map-position': 'READY' } }))).toBeNull();
  });

  it('is null for a row with zero evaluated features (unrecognised firmware) — as honest as an all-READY row', () => {
    expect(fleetRowAttention(row({ features: {} }))).toBeNull();
  });

  it('lists every non-READY feature label when under the limit, in frozen feature-key order', () => {
    expect(fleetRowAttention(row({ features: { battery: 'MISSING', 'map-position': 'DEGRADED' } }))).toBe(
      'Map position, Battery',
    );
  });

  it('caps at the limit with a "+N more" tail, in frozen feature-key order', () => {
    const result = fleetRowAttention(
      row({
        features: {
          'visual-geolocation': 'MISSING',
          battery: 'MISSING',
          'map-position': 'DEGRADED',
          'ground-speed': 'UNKNOWN',
        },
      }),
      2,
    );
    // frozen order: map-position, ground-speed, battery, visual-geolocation
    expect(result).toBe('Map position, Ground speed +2 more');
  });

  it('falls back to the raw key for an unrecognised feature in the rollup', () => {
    expect(fleetRowAttention(row({ features: { 'future-feature': 'MISSING' } }))).toBe('future-feature');
  });
});

describe('sortReadinessRows', () => {
  it('orders NO_GO, then UNKNOWN, then GO', () => {
    const rows = [row({ assetId: 'g', displayName: 'Go drone', verdict: 'GO' }), row({ assetId: 'n', displayName: 'No-go drone', verdict: 'NO_GO' }), row({ assetId: 'u', displayName: 'Unknown drone', verdict: 'UNKNOWN' })];
    expect(sortReadinessRows(rows).map((r) => r.assetId)).toEqual(['n', 'u', 'g']);
  });

  it('breaks ties alphabetically by display name, case-insensitive', () => {
    const rows = [row({ assetId: 'b', displayName: 'bravo', verdict: 'NO_GO' }), row({ assetId: 'a', displayName: 'Alpha', verdict: 'NO_GO' })];
    expect(sortReadinessRows(rows).map((r) => r.assetId)).toEqual(['a', 'b']);
  });

  it('never mutates the input array', () => {
    const rows = [row({ assetId: 'a', verdict: 'GO' }), row({ assetId: 'b', verdict: 'NO_GO' })];
    const sorted = sortReadinessRows(rows);
    expect(sorted).not.toBe(rows);
    expect(rows.map((r) => r.assetId)).toEqual(['a', 'b']);
  });
});

describe('readinessCounts', () => {
  it('counts every verdict bucket, including zero fleets', () => {
    expect(readinessCounts([])).toEqual({ go: 0, noGo: 0, unknown: 0 });
  });

  it('tallies a mixed fleet', () => {
    const rows = [row({ verdict: 'GO' }), row({ verdict: 'GO' }), row({ verdict: 'NO_GO' }), row({ verdict: 'UNKNOWN' })];
    expect(readinessCounts(rows)).toEqual({ go: 2, noGo: 1, unknown: 1 });
  });
});

describe('parseGroundingBlocker', () => {
  it('parses a well-formed MAINTENANCE_GROUNDED blocker', () => {
    expect(parseGroundingBlocker('MAINTENANCE_GROUNDED:GROUNDING:Prop strike on landing')).toEqual({
      kind: 'GROUNDING',
      summary: 'Prop strike on landing',
    });
  });

  it('keeps every colon in the summary after the first — free text a manager typed', () => {
    expect(parseGroundingBlocker('MAINTENANCE_GROUNDED:INSPECTION_DUE:Annual due: overdue since 08:00')).toEqual({
      kind: 'INSPECTION_DUE',
      summary: 'Annual due: overdue since 08:00',
    });
  });

  it('is null for a plain feature-key blocker (no prefix)', () => {
    expect(parseGroundingBlocker('battery')).toBeNull();
  });

  it('is null for a prefixed entry with no summary separator', () => {
    expect(parseGroundingBlocker('MAINTENANCE_GROUNDED:GROUNDING')).toBeNull();
  });

  it('is null for an unrecognised MaintenanceKind — degrades honestly instead of guessing', () => {
    expect(parseGroundingBlocker('MAINTENANCE_GROUNDED:FUTURE_KIND:something new')).toBeNull();
  });
});

describe('featureBlockers', () => {
  it('drops MAINTENANCE_GROUNDED entries, keeping plain feature keys', () => {
    expect(featureBlockers(['battery', 'MAINTENANCE_GROUNDED:GROUNDING:Prop strike', 'link-quality'])).toEqual([
      'battery',
      'link-quality',
    ]);
  });

  it('is the identity on a list with no grounding blocker', () => {
    expect(featureBlockers(['battery', 'link-quality'])).toEqual(['battery', 'link-quality']);
  });
});

describe('groundingBlocker', () => {
  it('is undefined when the list carries no grounding entry', () => {
    expect(groundingBlocker(['battery', 'link-quality'])).toBeUndefined();
  });

  it('finds the grounding entry among plain feature-key blockers', () => {
    expect(groundingBlocker(['battery', 'MAINTENANCE_GROUNDED:GROUNDING:Prop strike'])).toEqual({
      kind: 'GROUNDING',
      summary: 'Prop strike',
    });
  });

  it('picks the first grounding entry when more than one is present', () => {
    expect(
      groundingBlocker([
        'MAINTENANCE_GROUNDED:GROUNDING:First',
        'MAINTENANCE_GROUNDED:INSPECTION_DUE:Second',
      ]),
    ).toEqual({ kind: 'GROUNDING', summary: 'First' });
  });
});

describe('groundedBannerText', () => {
  it('renders the kind label and summary, em-dash separated', () => {
    expect(groundedBannerText({ kind: 'GROUNDING', summary: 'Prop strike on landing' })).toBe(
      'Grounded — Prop strike on landing',
    );
  });

  it('labels every blocking MaintenanceKind', () => {
    expect(groundedBannerText({ kind: 'INSPECTION_DUE', summary: 'Annual overdue' })).toBe(
      'Inspection due — Annual overdue',
    );
  });
});

describe('hasBeenProbed', () => {
  it('is false when profileObservedAt is null (never probed)', () => {
    expect(hasBeenProbed({ profileObservedAt: null })).toBe(false);
  });

  it('is true once a profile has ever been observed', () => {
    expect(hasBeenProbed({ profileObservedAt: '2026-08-29T13:44:31Z' })).toBe(true);
  });
});

describe('probeBlockedReason', () => {
  const now = Date.parse('2026-08-29T18:00:00Z');

  it('is null (button enabled) while the asset is streaming', () => {
    expect(probeBlockedReason(true, '2026-08-28T00:00:00Z', now)).toBeNull();
    expect(probeBlockedReason(true, undefined, now)).toBeNull();
  });

  it('names the honest offline age via humanAge, matching the exact R1 finding text', () => {
    // 18h before `now`
    const lastUsedAt = new Date(now - 18 * 60 * 60 * 1000).toISOString();
    expect(probeBlockedReason(false, lastUsedAt, now)).toBe('Needs a live link — vehicle is offline (18h)');
  });

  it('degrades to an honest "never been online" when there is no lastUsedAt at all', () => {
    expect(probeBlockedReason(false, undefined, now)).toBe('Needs a live link — this vehicle has never been online');
  });

  it('clamps a future-dated lastUsedAt to a zero age rather than a negative one', () => {
    const future = new Date(now + 60_000).toISOString();
    expect(probeBlockedReason(false, future, now)).toBe('Needs a live link — vehicle is offline (0s)');
  });
});
