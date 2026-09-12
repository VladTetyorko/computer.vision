import { describe, expect, it } from 'vitest';
import fixture from './__fixtures__/object-state.wire.json';
import {
  BoundingBox,
  EVIDENCE_SOURCES,
  ObjectBelief,
  ObjectIdentity,
  ObjectKinematics,
  ObjectLabelCandidate,
  ObjectLockFacts,
  ObjectMemoryFacts,
  ObjectProvenance,
  ObjectState,
  ObjectTiming,
  OBJECT_LIFECYCLES,
} from './models';

// This spec is the TypeScript half of CV-ORCHESTRATION wave W1's "wire mirror" acceptance test
// (docs/plans/active/CV-ORCHESTRATION-PLAN.md §4.5). The Java half
// (station/vision-app's ObjectStateRoundTripTest) builds a proto ObjectState with every field set,
// decodes it, serializes it with the app's real Jackson config, and commits the result as
// `./__fixtures__/object-state.wire.json`. This file loads that exact fixture and proves it
// satisfies the `ObjectState` TypeScript type below -- key for key, no extra keys either way.
//
// "No extra keys" is the part a plain `const x: ObjectState = fixture.full` assignment would NOT
// catch: TypeScript's structural typing only rejects a MISSING required property on an imported
// (non-literal) value, never an EXTRA one. So this is the one spec that actually fails when a
// Java DTO field is renamed (or a group is added/removed) and this file's `models.ts` mirror is
// not updated to match -- which is the exact drift this whole wave exists to close.
//
// The technique: for every wire type below, declare `const somethingKeys: Record<keyof T, true>`.
// The TypeScript compiler enforces that this object literal has EXACTLY T's own keys -- no fewer
// (a missing property is a compile error), no more (an invented property is an "object literal
// may only specify known properties" compile error). `Object.keys(...)` on that compiler-verified
// map is then the runtime ground truth this spec checks the fixture's actual JSON key set against.

const objectStateKeys: Record<keyof ObjectState, true> = {
  id: true,
  lifecycle: true,
  streamId: true,
  identity: true,
  kinematics: true,
  belief: true,
  provenance: true,
  memory: true,
  lock: true,
  timing: true,
};

const objectIdentityKeys: Record<keyof ObjectIdentity, true> = {
  label: true,
  labelRaw: true,
  candidates: true,
  stability: true,
};

const objectLabelCandidateKeys: Record<keyof ObjectLabelCandidate, true> = {
  label: true,
  weight: true,
};

const objectKinematicsKeys: Record<keyof ObjectKinematics, true> = {
  box: true,
  detectorBox: true,
  trackerBox: true,
  predictedBox: true,
  horizonMillis: true,
  velocityX: true,
  velocityY: true,
  displacementX: true,
  displacementY: true,
  motionCompensated: true,
};

const boundingBoxKeys: Record<keyof BoundingBox, true> = {
  x: true,
  y: true,
  width: true,
  height: true,
};

const objectBeliefKeys: Record<keyof ObjectBelief, true> = {
  confidenceRaw: true,
  confidenceSmoothed: true,
  existence: true,
  sinceConfirmedMillis: true,
};

const objectProvenanceKeys: Record<keyof ObjectProvenance, true> = {
  source: true,
  contributors: true,
  assocCost: true,
  reupdated: true,
};

const objectMemoryFactsKeys: Record<keyof ObjectMemoryFacts, true> = {
  recovered: true,
  identityConfidence: true,
  dormantMillis: true,
  galleryMatches: true,
  matchDistance: true,
};

const objectLockFactsKeys: Record<keyof ObjectLockFacts, true> = {
  locked: true,
  lockSeqApplied: true,
};

const objectTimingKeys: Record<keyof ObjectTiming, true> = {
  firstSeenMillis: true,
  lastSeenMillis: true,
  lastConfirmedMillis: true,
  ageFrames: true,
  hits: true,
  misses: true,
};

const OPTIONAL_GROUP_KEYS = ['identity', 'kinematics', 'belief', 'provenance', 'memory', 'lock', 'timing'] as const;
const BOX_KEYS = ['box', 'detectorBox', 'trackerBox', 'predictedBox'] as const;

/** Sorted key list of a compiler-verified `Record<keyof T, true>` map -- the expected side of every
 *  "does the fixture's actual key set match T exactly" assertion below. */
function keysOf(map: Record<string, true>): string[] {
  return Object.keys(map).sort();
}

function actualKeysOf(value: object): string[] {
  return Object.keys(value).sort();
}

describe('ObjectState wire contract (fixture: __fixtures__/object-state.wire.json)', () => {
  const full = fixture.full;
  const minimal = fixture.minimal;

  it('full: top-level keys match ObjectState exactly', () => {
    expect(actualKeysOf(full)).toEqual(keysOf(objectStateKeys));
  });

  it('full: lifecycle is a real ObjectLifecycle member', () => {
    expect(OBJECT_LIFECYCLES).toContain(full.lifecycle);
  });

  it('full: identity keys match ObjectIdentity exactly, with at least two label candidates', () => {
    expect(actualKeysOf(full.identity)).toEqual(keysOf(objectIdentityKeys));
    expect(full.identity.candidates.length).toBeGreaterThanOrEqual(2);
    for (const candidate of full.identity.candidates) {
      expect(actualKeysOf(candidate)).toEqual(keysOf(objectLabelCandidateKeys));
    }
  });

  it('full: kinematics keys match ObjectKinematics exactly, with all four boxes present', () => {
    expect(actualKeysOf(full.kinematics)).toEqual(keysOf(objectKinematicsKeys));
    for (const boxKey of BOX_KEYS) {
      expect(actualKeysOf(full.kinematics[boxKey])).toEqual(keysOf(boundingBoxKeys));
    }
  });

  it('full: belief keys match ObjectBelief exactly', () => {
    expect(actualKeysOf(full.belief)).toEqual(keysOf(objectBeliefKeys));
  });

  it('full: provenance keys match ObjectProvenance exactly, with source REUPDATE', () => {
    expect(actualKeysOf(full.provenance)).toEqual(keysOf(objectProvenanceKeys));
    expect(EVIDENCE_SOURCES).toContain(full.provenance.source);
  });

  it('full: memory keys match ObjectMemoryFacts exactly', () => {
    expect(actualKeysOf(full.memory)).toEqual(keysOf(objectMemoryFactsKeys));
  });

  it('full: lock keys match ObjectLockFacts exactly', () => {
    expect(actualKeysOf(full.lock)).toEqual(keysOf(objectLockFactsKeys));
  });

  it('full: timing keys match ObjectTiming exactly', () => {
    expect(actualKeysOf(full.timing)).toEqual(keysOf(objectTimingKeys));
  });

  it('minimal: top-level keys are exactly the three required fields', () => {
    expect(actualKeysOf(minimal)).toEqual(['id', 'lifecycle', 'streamId']);
  });

  it('minimal: lifecycle is a real ObjectLifecycle member', () => {
    expect(OBJECT_LIFECYCLES).toContain(minimal.lifecycle);
  });

  it('minimal: none of the seven optional groups are present -- an absent group is a missing key, never a zeroed object', () => {
    for (const group of OPTIONAL_GROUP_KEYS) {
      expect(group in minimal).toBe(false);
    }
  });
});
