import { describe, expect, it } from 'vitest';
import { resolvedSourceLine } from './cv-setup-modal-logic';

describe('resolvedSourceLine (docs/plans/active/CV-ORCHESTRATION-PLAN.md §4.7, wave W3.4)', () => {
  it('reports intent resolution for the model knob when sources.model is INTENT', () => {
    expect(resolvedSourceLine({ model: 'INTENT' }, undefined, 'model')).toBe('Resolved from your intent pick');
  });

  it('reports intent resolution for the labelFilter (Classes) knob when sources.labelFilter is INTENT', () => {
    expect(resolvedSourceLine({ labelFilter: 'INTENT' }, undefined, 'labelFilter')).toBe('Resolved from your intent pick');
  });

  it('never reports intent resolution for confidence — CvProfileSources has no such field', () => {
    // Even a maximally-populated sources object must not leak into confidenceThreshold's line —
    // there is no wire field for it, so the honest answer is to fall through to the profile tier.
    expect(resolvedSourceLine({ model: 'INTENT', labelFilter: 'INTENT' }, undefined, 'confidenceThreshold')).toBeNull();
  });

  it('does not cross-report — a model-only INTENT source says nothing for the labelFilter knob', () => {
    expect(resolvedSourceLine({ model: 'INTENT' }, undefined, 'labelFilter')).toBeNull();
  });

  it('falls back to the profile tier once sources carries no INTENT for that knob', () => {
    expect(resolvedSourceLine({}, 'ASSET', 'labelFilter')).toBe('From your asset profile');
    expect(resolvedSourceLine(undefined, 'CATEGORY', 'confidenceThreshold')).toBe('From your category profile');
    expect(resolvedSourceLine(undefined, 'ORGANIZATION', 'model')).toBe('From your organization profile');
  });

  it('renders "Platform default" for the PLATFORM tier, never "From your platform profile"', () => {
    expect(resolvedSourceLine(undefined, 'PLATFORM', 'confidenceThreshold')).toBe('Platform default');
  });

  it('prefers INTENT over a present profile tier — request-time resolution is the more specific fact', () => {
    expect(resolvedSourceLine({ labelFilter: 'INTENT' }, 'ASSET', 'labelFilter')).toBe('Resolved from your intent pick');
  });

  it('returns null — never a fabricated source — when neither fact is present', () => {
    expect(resolvedSourceLine(undefined, undefined, 'confidenceThreshold')).toBeNull();
    expect(resolvedSourceLine({}, undefined, 'labelFilter')).toBeNull();
  });
});
