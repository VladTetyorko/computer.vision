import { describe, expect, it } from 'vitest';
import fixture from './__fixtures__/world-object.wire.json';
import { RENDER_TIERS, WorldObject, WorldObjectEventLink, WorldObjectOperator, WorldObjectRender } from './models';

// This spec is the TypeScript half of CV-ORCHESTRATION wave W2.8's "WorldObject reaches the wire"
// acceptance test (docs/plans/active/CV-ORCHESTRATION-PLAN.md §4.6). The Java half
// (station/vision-api's WorldObjectResponseWireContractTest) builds a WorldObject with every facet
// set, serializes it with WorldObjectResponse.from(...) and the app's real Jackson config, and
// commits the result as `./__fixtures__/world-object.wire.json`. This file loads that exact
// fixture and proves it satisfies the `WorldObject` TypeScript type below -- key for key, no extra
// keys either way -- the same `Record<keyof T, true>` technique `object-state.wire.contract.spec.ts`
// uses, and for the same reason: it is the one check that fails when the Java DTO drifts (a field
// renamed, `RenderTier`'s vocabulary changed) and this file's mirror is not updated to match.

const worldObjectKeys: Record<keyof WorldObject, true> = {
  state: true,
  operator: true,
  event: true,
  render: true,
};

const worldObjectOperatorKeys: Record<keyof WorldObjectOperator, true> = {
  followed: true,
  denied: true,
  follow: true,
};

const worldObjectEventLinkKeys: Record<keyof WorldObjectEventLink, true> = {
  openEventId: true,
};

const worldObjectRenderKeys: Record<keyof WorldObjectRender, true> = {
  tier: true,
};

/** Sorted key list of a compiler-verified `Record<keyof T, true>` map -- the expected side of every
 *  "does the fixture's actual key set match T exactly" assertion below. */
function keysOf(map: Record<string, true>): string[] {
  return Object.keys(map).sort();
}

function actualKeysOf(value: object): string[] {
  return Object.keys(value).sort();
}

describe('WorldObject wire contract (fixture: __fixtures__/world-object.wire.json)', () => {
  const full = fixture.full;
  const minimal = fixture.minimal;

  it('full: top-level keys match WorldObject exactly', () => {
    expect(actualKeysOf(full)).toEqual(keysOf(worldObjectKeys));
  });

  it('full: operator keys match WorldObjectOperator exactly, with a real follow lock', () => {
    expect(actualKeysOf(full.operator)).toEqual(keysOf(worldObjectOperatorKeys));
    expect(full.operator.follow).toBe('HOLDING');
  });

  it('full: event carries an openEventId', () => {
    expect(actualKeysOf(full.event)).toEqual(keysOf(worldObjectEventLinkKeys));
    expect(typeof full.event.openEventId).toBe('string');
  });

  it('full: render keys match WorldObjectRender exactly, with a real RenderTier member', () => {
    expect(actualKeysOf(full.render)).toEqual(keysOf(worldObjectRenderKeys));
    expect(RENDER_TIERS).toContain(full.render.tier);
  });

  it('minimal: top-level keys match WorldObject exactly -- operator/event/render are always present groups', () => {
    expect(actualKeysOf(minimal)).toEqual(keysOf(worldObjectKeys));
  });

  it('minimal: operator has no follow key -- no FOLLOW lock is a missing key, never null/zeroed', () => {
    expect(actualKeysOf(minimal.operator)).toEqual(['denied', 'followed']);
    expect('follow' in minimal.operator).toBe(false);
  });

  it('minimal: event has no openEventId key -- no open event is a missing key, never a zeroed id', () => {
    expect(actualKeysOf(minimal.event)).toEqual([]);
    expect('openEventId' in minimal.event).toBe(false);
  });

  it('minimal: render.tier is HIDDEN -- never absent, even when there is nothing to render', () => {
    expect(actualKeysOf(minimal.render)).toEqual(keysOf(worldObjectRenderKeys));
    expect(minimal.render.tier).toBe('HIDDEN');
  });
});
