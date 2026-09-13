import { describe, expect, it } from 'vitest';
import fixture from './__fixtures__/cv-trace.wire.json';
import {
  CvTrace,
  FrameLedger,
  GATE_OUTCOMES,
  GATE_REASONS,
  GateDecision,
  LEDGER_OUTCOMES,
  LedgerEntry,
  ObjectEvidence,
  TracedDetection,
} from './models';

// This spec is the TypeScript half of CV-ORCHESTRATION wave W5.0/W5.1's "CvTrace reaches the wire"
// acceptance test (docs/plans/active/CV-ORCHESTRATION-PLAN.md §4.4/§4.8). The Java half
// (station/vision-api's CvTraceResponseWireContractTest) builds a CvTraceResponse with every
// GateReason represented, a RAN/SKIPPED/FAILED ledger entry triad, one ObjectEvidence claim and one
// WorldObject, serializes it with the app's real Jackson config, and commits the result as
// `./__fixtures__/cv-trace.wire.json`. This file loads that exact fixture and proves it satisfies
// the `CvTrace` TypeScript type below -- key for key, no extra keys either way -- the same
// `Record<keyof T, true>` technique `world-object.wire.contract.spec.ts` uses.
//
// `full.frame` carries two ledgers as of wave W5b (decision E23): frame[0] with the detector's own
// raw `detections` at a non-zero frame size, frame[1] with the "not carried" shape (`detections: []`,
// `frameWidth`/`frameHeight: 0`) -- both halves of the new fields' contract in one fixture.

const cvTraceKeys: Record<keyof CvTrace, true> = {
  streamId: true,
  gate: true,
  frame: true,
  world: true,
};

const gateDecisionKeys: Record<keyof GateDecision, true> = {
  frameSequence: true,
  atMillis: true,
  outcome: true,
  reason: true,
  demand: true,
};

const frameLedgerKeys: Record<keyof FrameLedger, true> = {
  streamId: true,
  sequence: true,
  capturedAtMillis: true,
  levelServed: true,
  detectorReason: true,
  eligible: true,
  entries: true,
  objects: true,
  dropsSinceLast: true,
  gateWaitMillis: true,
  totalMillis: true,
  halted: true,
  detections: true,
  frameWidth: true,
  frameHeight: true,
};

const tracedDetectionKeys: Record<keyof TracedDetection, true> = {
  label: true,
  confidence: true,
  box: true,
};

const ledgerEntryKeys: Record<keyof LedgerEntry, true> = {
  contributorId: true,
  outcome: true,
  reason: true,
  costMillis: true,
  summary: true,
};

const objectEvidenceKeys: Record<keyof ObjectEvidence, true> = {
  contributorId: true,
  claim: true,
};

function keysOf(map: Record<string, true>): string[] {
  return Object.keys(map).sort();
}

function actualKeysOf(value: object): string[] {
  return Object.keys(value).sort();
}

describe('CvTrace wire contract (fixture: __fixtures__/cv-trace.wire.json)', () => {
  const full = fixture.full;
  const minimal = fixture.minimal;

  it('full: top-level keys match CvTrace exactly', () => {
    expect(actualKeysOf(full)).toEqual(keysOf(cvTraceKeys));
  });

  it('full: gate carries one decision per GateReason, plus a SENT and a PROBE', () => {
    expect(full.gate).toHaveLength(GATE_REASONS.length + 2);
    for (const decision of full.gate) {
      expect(actualKeysOf(decision)).toEqual(
        'reason' in decision ? keysOf(gateDecisionKeys) : keysOf(gateDecisionKeys).filter((k) => k !== 'reason'),
      );
      expect(GATE_OUTCOMES).toContain(decision.outcome);
    }
    const reasons = full.gate.map((d) => ('reason' in d ? d.reason : undefined)).filter((r) => r !== undefined);
    expect(new Set(reasons)).toEqual(new Set(GATE_REASONS));
  });

  it('full: a SENT/PROBE decision has no reason key -- absence of a reason, not a dummy value', () => {
    const sent = full.gate.find((d) => d.outcome === 'SENT');
    const probe = full.gate.find((d) => d.outcome === 'PROBE');
    expect(sent && 'reason' in sent).toBe(false);
    expect(probe && 'reason' in probe).toBe(false);
  });

  it('full: frame keys match FrameLedger exactly, with a RAN/SKIPPED/FAILED entry triad', () => {
    // Two ledgers (CV-ORCHESTRATION wave W5b): frame[0] carries the detector's raw boxes, frame[1]
    // is the "not carried" shape (untraced, or a pre-W5b response) -- see the next test.
    expect(full.frame).toHaveLength(2);
    const ledger = full.frame[0];
    expect(actualKeysOf(ledger)).toEqual(keysOf(frameLedgerKeys));
    expect(new Set(ledger.entries.map((e) => e.outcome))).toEqual(new Set(LEDGER_OUTCOMES));
    for (const entry of ledger.entries) {
      expect(actualKeysOf(entry)).toEqual(keysOf(ledgerEntryKeys));
    }
  });

  it('full: frame[0].detections carries the detector\'s raw boxes at a non-zero frame size', () => {
    const ledger = full.frame[0] as unknown as FrameLedger;
    expect(ledger.detections.length).toBeGreaterThan(0);
    for (const detection of ledger.detections) {
      expect(actualKeysOf(detection)).toEqual(keysOf(tracedDetectionKeys));
      expect(actualKeysOf(detection.box)).toEqual(['height', 'width', 'x', 'y']);
    }
    expect(ledger.frameWidth).toBeGreaterThan(0);
    expect(ledger.frameHeight).toBeGreaterThan(0);
  });

  it('full: frame[1].detections is the "not carried" shape -- empty list, zero frame size', () => {
    const ledger = full.frame[1] as unknown as FrameLedger;
    expect(actualKeysOf(ledger)).toEqual(keysOf(frameLedgerKeys));
    expect(ledger.detections).toEqual([]);
    expect(ledger.frameWidth).toBe(0);
    expect(ledger.frameHeight).toBe(0);
  });

  it('full: objects carries a per-track evidence list, keyed by track id as a string', () => {
    const ledger = full.frame[0] as unknown as FrameLedger;
    const trackIds = Object.keys(ledger.objects);
    expect(trackIds.length).toBeGreaterThan(0);
    const evidence = ledger.objects[trackIds[0]];
    expect(evidence.length).toBeGreaterThan(0);
    expect(actualKeysOf(evidence[0])).toEqual(keysOf(objectEvidenceKeys));
    // predict.cv's real free-form claim shape (cv_service/orchestration/contributors/predict.py) --
    // this spec does not assume "held" exists in general (ObjectEvidence.claim is genuinely
    // free-form), only that THIS fixture's predict.cv row carries it, since the Java test built it
    // that way on purpose.
    const predictClaim = evidence.find((e) => e.contributorId === 'predict.cv')?.claim;
    expect(predictClaim?.['held']).toEqual(expect.any(String));
  });

  it('full: world carries at least one WorldObject fold entry', () => {
    expect(full.world.length).toBeGreaterThan(0);
    expect(full.world[0].state.streamId).toBe(full.streamId);
  });

  it('minimal: never-traced-this-stream shape -- every list empty, never absent (CvTraceResponse never errors)', () => {
    expect(actualKeysOf(minimal)).toEqual(keysOf(cvTraceKeys));
    expect(minimal.gate).toEqual([]);
    expect(minimal.frame).toEqual([]);
    expect(minimal.world).toEqual([]);
  });
});
