import { describe, expect, it } from 'vitest';
import { DETECTION_STATES } from './models';

describe('DetectionState contract', () => {
  it('matches the Java enum\'s exact value list and order (D1: this drifted once already)', () => {
    // Fixture pinned by hand to `contexts/vision-perception/src/main/java/com/drones/vision/
    // perception/domain/model/DetectionState.java` (the enum's declaration order, `OFF`,
    // `IDLE_NO_VIEWERS`, `RUNNING_UNWATCHED`, `RUNNING`) — a generated fixture exported by the API
    // is wave W1's job (docs/plans/active/CV-ORCHESTRATION-PLAN.md §4.6), not this one. Until then,
    // this hard-coded list is the only thing standing between a Java enum addition and a silent
    // fallthrough in every TS switch over `DetectionState` (§7 D1's exact failure mode).
    const javaDetectionStateValues = ['OFF', 'IDLE_NO_VIEWERS', 'RUNNING_UNWATCHED', 'RUNNING'];

    expect(DETECTION_STATES).toEqual(javaDetectionStateValues);
  });
});
