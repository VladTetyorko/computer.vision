import { describe, expect, it } from 'vitest';
import { OBJECT_LIFECYCLES } from './models';

describe('ObjectLifecycle contract', () => {
  it("matches the Java enum's exact value list and order (see DetectionState's own contract spec: this drifted once already)", () => {
    // Fixture pinned by hand to `contexts/vision-perception/src/main/java/com/drones/vision/
    // perception/domain/model/ObjectLifecycle.java` (the enum's declaration order, `TENTATIVE`,
    // `CONFIRMED`, `COASTING`, `LOST`, `DORMANT`). A generated cross-language fixture is a later
    // wave's job (docs/plans/active/CV-ORCHESTRATION-PLAN.md §4.6), not this one's. Until then,
    // this hard-coded list is the only thing standing between a Java enum addition (or reorder) and
    // a silent fallthrough in every TS switch over `ObjectLifecycle` — the same failure mode
    // `DetectionState`'s own contract spec guards, called out at §7 D1.
    const javaObjectLifecycleValues = ['TENTATIVE', 'CONFIRMED', 'COASTING', 'LOST', 'DORMANT'];

    expect(OBJECT_LIFECYCLES).toEqual(javaObjectLifecycleValues);
  });
});
