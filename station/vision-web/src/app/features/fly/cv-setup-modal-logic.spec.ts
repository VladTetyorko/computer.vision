import { describe, expect, it } from 'vitest';
import type { CvKnobSources, EffectiveCvProfile } from '../../core/api/models';
import { resolvedSourceLine } from './cv-setup-modal-logic';

function knobSources(partial: Partial<CvKnobSources> = {}): CvKnobSources {
  return {
    model: 'PLATFORM',
    confidenceThreshold: 'PLATFORM',
    inferenceFps: 'PLATFORM',
    labelFilter: 'PLATFORM',
    labelDenyFilter: 'PLATFORM',
    detectionEnabled: 'PLATFORM',
    tracking: 'PLATFORM',
    eventRule: 'PLATFORM',
    ...partial,
  };
}

function effective(partial: Partial<EffectiveCvProfile> = {}): EffectiveCvProfile {
  return {
    assetId: 'a-1',
    profile: {
      id: 'p-1',
      name: 'people-vehicles',
      description: '',
      builtIn: true,
      createdAt: '2026-01-01T00:00:00Z',
      updatedAt: '2026-01-01T00:00:00Z',
      sources: {},
    },
    source: 'ASSET',
    sources: knobSources(),
    ...partial,
  };
}

describe('resolvedSourceLine (docs/plans/active/CV-ORCHESTRATION-PLAN.md §4.7, waves W3.4 + W7)', () => {
  describe('priority 1 — this session\'s own most recent hot-knob PATCH (lastConfigSources)', () => {
    it('reports intent resolution for the model knob when lastConfigSources.model is INTENT', () => {
      expect(resolvedSourceLine({ model: 'INTENT' }, undefined, 'model')).toBe('Resolved from your intent pick');
    });

    it('reports intent resolution for the labelFilter (Classes) knob when lastConfigSources.labelFilter is INTENT', () => {
      expect(resolvedSourceLine({ labelFilter: 'INTENT' }, undefined, 'labelFilter')).toBe('Resolved from your intent pick');
    });

    it('does not cross-report — a model-only INTENT source says nothing for the labelFilter knob', () => {
      expect(resolvedSourceLine({ model: 'INTENT' }, undefined, 'labelFilter')).toBeNull();
    });

    it('lastConfigSources has no field for confidenceThreshold — never reports INTENT from it alone', () => {
      expect(resolvedSourceLine({ model: 'INTENT', labelFilter: 'INTENT' }, undefined, 'confidenceThreshold')).toBeNull();
    });

    it('names the specific intent once the effective read carries one, even when the live PATCH fact wins', () => {
      expect(resolvedSourceLine({ model: 'INTENT' }, effective({ intent: 'VEHICLES' }), 'model')).toBe(
        'Resolved from your Vehicles intent',
      );
    });

    it('falls back to the generic phrasing when no intent value is known at all', () => {
      expect(resolvedSourceLine({ model: 'INTENT' }, undefined, 'model')).toBe('Resolved from your intent pick');
    });

    it('prefers the live PATCH fact over a present effective-profile tier — same-session is the more immediate fact', () => {
      expect(resolvedSourceLine({ labelFilter: 'INTENT' }, effective({ sources: knobSources({ labelFilter: 'ASSET' }) }), 'labelFilter')).toBe(
        'Resolved from your intent pick',
      );
    });
  });

  describe('priority 2 — the effective profile read\'s own real per-knob provenance (wave W7)', () => {
    it('reports every real tier by name — ASSET/CATEGORY/ORGANIZATION', () => {
      expect(resolvedSourceLine(undefined, effective({ sources: knobSources({ labelFilter: 'ASSET' }) }), 'labelFilter')).toBe(
        'From your asset profile',
      );
      expect(resolvedSourceLine(undefined, effective({ sources: knobSources({ confidenceThreshold: 'CATEGORY' }) }), 'confidenceThreshold')).toBe(
        'From your category profile',
      );
      expect(resolvedSourceLine(undefined, effective({ sources: knobSources({ model: 'ORGANIZATION' }) }), 'model')).toBe(
        'From your organization profile',
      );
    });

    it('renders "Platform default" for the PLATFORM tier, never "From your platform profile"', () => {
      expect(resolvedSourceLine(undefined, effective({ sources: knobSources({ confidenceThreshold: 'PLATFORM' }) }), 'confidenceThreshold')).toBe(
        'Platform default',
      );
    });

    it('now reports INTENT for confidenceThreshold too — wave W7.1 seeds it from an intent at fold time', () => {
      expect(
        resolvedSourceLine(undefined, effective({ intent: 'PEOPLE', sources: knobSources({ confidenceThreshold: 'INTENT' }) }), 'confidenceThreshold'),
      ).toBe('Resolved from your People intent');
    });

    it('lets a live-PATCH INTENT fact for one knob coexist with an effective-profile ASSET fact for another', () => {
      const profile = effective({ sources: knobSources({ model: 'INTENT', labelFilter: 'ASSET' }) });
      expect(resolvedSourceLine({ model: 'INTENT' }, profile, 'model')).toBe('Resolved from your intent pick');
      expect(resolvedSourceLine(undefined, profile, 'labelFilter')).toBe('From your asset profile');
    });
  });

  describe('priority 3 — nothing to say', () => {
    it('returns null — never a fabricated source — when neither fact is present', () => {
      expect(resolvedSourceLine(undefined, undefined, 'confidenceThreshold')).toBeNull();
      expect(resolvedSourceLine({}, undefined, 'labelFilter')).toBeNull();
    });
  });

  describe('survives a reload — the whole point of wave W7 (§4.7/§8, decision E22: "sources reported on every read, not just on save")', () => {
    // `CockpitFacade#lastConfigSourcesSignal` is reset to `undefined` on every asset switch
    // (`cockpit-facade.ts#selectAsset`) and starts `undefined` on a fresh page load — it only ever
    // carries a fact after a hot-knob PATCH sent *this session*. `CockpitFacade#effectiveProfile`,
    // in contrast, is a plain `GET .../effective-profile` keyed on the active asset — refreshed on
    // every asset switch and after any profile save, independent of whether this session has ever
    // PATCHed anything. A source line that only worked right after a save (the pre-W7 contract) would
    // go blank/wrong the moment `lastConfigSources` resets — these two calls are exactly that: no
    // `lastConfigSources` fact at all, only a fresh effective-profile read, and the correct line still
    // comes back for every one of the three Tuning knobs.
    it('reports the correct source with no lastConfigSources at all — a fresh GET, not a fresh PATCH', () => {
      const profile = effective({
        intent: 'CUSTOM',
        sources: knobSources({ model: 'INTENT', labelFilter: 'ASSET', confidenceThreshold: 'ORGANIZATION' }),
      });
      expect(resolvedSourceLine(undefined, profile, 'model')).toBe('Resolved from your Custom intent');
      expect(resolvedSourceLine(undefined, profile, 'labelFilter')).toBe('From your asset profile');
      expect(resolvedSourceLine(undefined, profile, 'confidenceThreshold')).toBe('From your organization profile');
    });
  });
});
