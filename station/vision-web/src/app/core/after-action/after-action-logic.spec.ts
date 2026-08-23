import { describe, expect, it } from 'vitest';
import type { AfterActionManifest, AfterActionPartStatus } from '../api/models';
import {
  AFTER_ACTION_PART_ORDER,
  buildAfterActionView,
  buildPartRows,
  deriveCaveats,
  openFlightNotice,
  partLabel,
  partStateLabel,
  partTone,
  showPartCount,
} from './after-action-logic';

/**
 * §3.1's own worked example (docs/plans/done/AFTER-ACTION-PLAN.md), typed verbatim as fixture data
 * per this wave's own brief ("the backend is not running, so build your specs against §3.1's example
 * payload treated as fixture data").
 *
 * **This wave found a defect in that example and it has since been corrected in the plan**: the
 * original omitted `audit`'s `FORBIDDEN` note from `caveats` and paraphrased `telemetry`'s instead
 * of quoting it. Settling it also widened the rule — a `note` now counts regardless of `state`, so
 * marks' standing "not bound to a flight" caveat surfaces even though marks are `PRESENT`. The
 * fixture below carries the corrected example.
 */
const EXAMPLE_MANIFEST: AfterActionManifest = {
  assetId: 'd971a335-0000-0000-0000-000000000000',
  assetName: 'Rover-1',
  usageId: '6b1f0000-0000-0000-0000-000000000000',
  startedAt: '2026-08-19T09:14:02Z',
  endedAt: '2026-08-19T09:48:31Z',
  open: false,
  generatedAt: '2026-08-19T15:02:11Z',
  scopedTo: 'referee@example.org',
  parts: [
    {
      part: 'telemetry',
      state: 'TRUNCATED',
      count: 2000,
      note: 'thinned to the 2000-point ceiling; the source series is larger',
    },
    { part: 'detections', state: 'PRESENT', count: 214, note: null },
    {
      part: 'marks',
      state: 'PRESENT',
      count: 6,
      note: 'marks created inside the flight window and visible to you; a mark is not bound to a flight',
    },
    { part: 'recording', state: 'ABSENT', count: 0, note: 'no recording is configured for this stream' },
    { part: 'passport', state: 'PRESENT', count: 1, note: null },
    { part: 'audit', state: 'FORBIDDEN', count: 0, note: 'your role cannot read the audit trail' },
  ],
  complete: false,
  caveats: [
    'thinned to the 2000-point ceiling; the source series is larger',
    'marks created inside the flight window and visible to you; a mark is not bound to a flight',
    'no recording is configured for this stream',
    'your role cannot read the audit trail',
  ],
};

describe('after-action-logic', () => {
  describe('buildPartRows', () => {
    it('orders all six parts by the canonical §3.1 order regardless of wire order', () => {
      const shuffled = [...EXAMPLE_MANIFEST.parts].reverse();
      const rows = buildPartRows(shuffled);
      expect(rows.map((r) => r.part)).toEqual(AFTER_ACTION_PART_ORDER);
    });

    it('carries state/count/note through untouched, plus a plain-language label/stateLabel/tone', () => {
      const rows = buildPartRows(EXAMPLE_MANIFEST.parts);
      const telemetry = rows.find((r) => r.part === 'telemetry')!;
      expect(telemetry.state).toBe('TRUNCATED');
      expect(telemetry.count).toBe(2000);
      expect(telemetry.note).toBe('thinned to the 2000-point ceiling; the source series is larger');
      expect(telemetry.label).toBe('Telemetry');
      expect(telemetry.stateLabel).toBe('Thinned');
      expect(telemetry.tone).toBe('neutral');
      expect(telemetry.showCount).toBe(true);
    });

    it('never fabricates a row for a part missing from the wire array', () => {
      const withoutAudit = EXAMPLE_MANIFEST.parts.filter((p) => p.part !== 'audit');
      const rows = buildPartRows(withoutAudit);
      expect(rows.map((r) => r.part)).toEqual(['telemetry', 'detections', 'marks', 'recording', 'passport']);
    });

    it('a PRESENT part with a null note renders no explanatory text (rule 5 — no invented reassuring copy)', () => {
      const rows = buildPartRows(EXAMPLE_MANIFEST.parts);
      const passport = rows.find((r) => r.part === 'passport')!;
      expect(passport.state).toBe('PRESENT');
      expect(passport.note).toBeNull();
    });

    it('a PRESENT part may still carry a qualifying note (marks\' "not bound to a flight" disclaimer)', () => {
      const rows = buildPartRows(EXAMPLE_MANIFEST.parts);
      const marks = rows.find((r) => r.part === 'marks')!;
      expect(marks.state).toBe('PRESENT');
      expect(marks.note).toContain('not bound to a flight');
    });
  });

  describe('partTone — the calm-statement-of-fact requirement', () => {
    it('is "ok" only for PRESENT; ABSENT/TRUNCATED/FORBIDDEN are identically neutral, never an alarm colour', () => {
      expect(partTone('PRESENT')).toBe('ok');
      expect(partTone('ABSENT')).toBe('neutral');
      expect(partTone('TRUNCATED')).toBe('neutral');
      expect(partTone('FORBIDDEN')).toBe('neutral');
    });
  });

  describe('showPartCount', () => {
    it('shows a count for PRESENT/TRUNCATED, hides the always-0 count for ABSENT/FORBIDDEN', () => {
      expect(showPartCount('PRESENT')).toBe(true);
      expect(showPartCount('TRUNCATED')).toBe(true);
      expect(showPartCount('ABSENT')).toBe(false);
      expect(showPartCount('FORBIDDEN')).toBe(false);
    });
  });

  describe('deriveCaveats', () => {
    it('collects every non-null note verbatim, in canonical order', () => {
      expect(deriveCaveats(EXAMPLE_MANIFEST.parts)).toEqual([
        'thinned to the 2000-point ceiling; the source series is larger',
        'marks created inside the flight window and visible to you; a mark is not bound to a flight',
        'no recording is configured for this stream',
        'your role cannot read the audit trail',
      ]);
    });

    it("includes a PRESENT part's note — marks' approximation is always true and must not be hidden by its own success", () => {
      const marksNote = EXAMPLE_MANIFEST.parts.find((p) => p.part === 'marks')!.note!;
      expect(deriveCaveats(EXAMPLE_MANIFEST.parts)).toContain(marksNote);
    });

    it('re-derives rather than trusting the wire, so a backend that under-reports cannot make this app under-report', () => {
      const underReported: AfterActionManifest = { ...EXAMPLE_MANIFEST, caveats: [] };
      expect(buildAfterActionView(underReported).caveats).toEqual(deriveCaveats(EXAMPLE_MANIFEST.parts));
    });

    it('is order-independent against the wire array (same canonical order regardless of input order)', () => {
      const shuffled = [...EXAMPLE_MANIFEST.parts].reverse();
      expect(deriveCaveats(shuffled)).toEqual(deriveCaveats(EXAMPLE_MANIFEST.parts));
    });

    it('is empty when every part is PRESENT', () => {
      const allPresent: AfterActionPartStatus[] = AFTER_ACTION_PART_ORDER.map((part) => ({
        part,
        state: 'PRESENT' as const,
        count: 1,
        note: null,
      }));
      expect(deriveCaveats(allPresent)).toEqual([]);
    });
  });

  describe('openFlightNotice', () => {
    it('is null for a finished flight', () => {
      expect(openFlightNotice(false)).toBeNull();
    });

    it('is an honest, non-null sentence for a still-running flight', () => {
      expect(openFlightNotice(true)).toContain('still in progress');
    });
  });

  describe('buildAfterActionView', () => {
    it('composes rows + recomputed caveats + complete + openNotice off the manifest', () => {
      const view = buildAfterActionView(EXAMPLE_MANIFEST);
      expect(view.parts).toHaveLength(6);
      expect(view.complete).toBe(false);
      expect(view.openNotice).toBeNull();
      // One per non-null note, marks' PRESENT note included.
      expect(view.caveats).toHaveLength(4);
    });

    it('reflects an open usage honestly', () => {
      const view = buildAfterActionView({ ...EXAMPLE_MANIFEST, open: true, endedAt: null });
      expect(view.openNotice).toContain('still in progress');
    });

    it('is complete with an empty caveat list when every part is PRESENT', () => {
      const complete: AfterActionManifest = {
        ...EXAMPLE_MANIFEST,
        complete: true,
        caveats: [],
        parts: AFTER_ACTION_PART_ORDER.map((part) => ({
          part,
          state: 'PRESENT' as const,
          count: 1,
          note: null,
        })),
      };
      const view = buildAfterActionView(complete);
      expect(view.complete).toBe(true);
      expect(view.caveats).toEqual([]);
      expect(view.parts.every((row) => row.tone === 'ok' && row.note === null)).toBe(true);
    });
  });

  describe('partLabel / partStateLabel', () => {
    it('names all six parts and all four states in non-empty plain language', () => {
      for (const part of AFTER_ACTION_PART_ORDER) {
        expect(partLabel(part).length).toBeGreaterThan(0);
      }
      for (const state of ['PRESENT', 'ABSENT', 'TRUNCATED', 'FORBIDDEN'] as const) {
        expect(partStateLabel(state).length).toBeGreaterThan(0);
      }
    });
  });
});
