import { describe, expect, it } from 'vitest';
import { ROOT_ACTOR_ID, actorLabel, buildNameMap, humanizeSummary } from './summary-logic';

const ASSET_ID = '40dd46d8-be99-451f-b8e2-c03a291aea33';
const USER_ID = 'a1b2c3d4-1111-2222-3333-444455556666';
const UNKNOWN_ID = 'ffffffff-0000-1111-2222-333344445555';

describe('buildNameMap', () => {
  it('maps asset ids to their display name', () => {
    const names = buildNameMap([{ assetId: ASSET_ID, displayName: 'Falcon-1' }]);
    expect(names.get(ASSET_ID)).toBe('Falcon-1');
  });

  it('maps user ids to their display name when a users list is supplied', () => {
    const names = buildNameMap([], [{ userId: USER_ID, displayName: 'Jane Pilot' }]);
    expect(names.get(USER_ID)).toBe('Jane Pilot');
  });

  it('defaults users to none — a caller with only assets loaded need not pass an empty array', () => {
    const names = buildNameMap([{ assetId: ASSET_ID, displayName: 'Falcon-1' }]);
    expect(names.size).toBe(1);
  });

  it('merges both lists into one map', () => {
    const names = buildNameMap(
      [{ assetId: ASSET_ID, displayName: 'Falcon-1' }],
      [{ userId: USER_ID, displayName: 'Jane Pilot' }],
    );
    expect(names.get(ASSET_ID)).toBe('Falcon-1');
    expect(names.get(USER_ID)).toBe('Jane Pilot');
  });
});

describe('humanizeSummary', () => {
  it('replaces a UUID that resolves in the names map with the display name', () => {
    const names = buildNameMap([{ assetId: ASSET_ID, displayName: 'Falcon-1' }]);
    expect(humanizeSummary(`Flight command 'ARM' for asset ${ASSET_ID}`, names)).toBe(
      "Flight command 'ARM' for asset Falcon-1",
    );
  });

  it('shortens an unresolvable UUID to its first 8 characters', () => {
    const names = buildNameMap([]);
    expect(humanizeSummary(`Flight command 'ARM' for asset ${UNKNOWN_ID}`, names)).toBe(
      `Flight command 'ARM' for asset ${UNKNOWN_ID.slice(0, 8)}`,
    );
  });

  it('replaces every UUID in the string, resolved and unresolved independently', () => {
    const names = buildNameMap([{ assetId: ASSET_ID, displayName: 'Falcon-1' }]);
    const summary = `Assigned ${ASSET_ID} away from ${UNKNOWN_ID}`;
    expect(humanizeSummary(summary, names)).toBe(`Assigned Falcon-1 away from ${UNKNOWN_ID.slice(0, 8)}`);
  });

  it('changes nothing else in the string — punctuation and surrounding text are untouched', () => {
    const names = buildNameMap([]);
    expect(humanizeSummary('Created dataset "night-drive-v3"', names)).toBe('Created dataset "night-drive-v3"');
  });

  it('leaves a string with no UUID-shaped substring untouched', () => {
    const names = buildNameMap([{ assetId: ASSET_ID, displayName: 'Falcon-1' }]);
    expect(humanizeSummary('Updated organization settings', names)).toBe('Updated organization settings');
  });

  it('resolves an uppercase-cased UUID against a lowercase-keyed names map — same id, either case', () => {
    const upper = ASSET_ID.toUpperCase();
    const names = buildNameMap([{ assetId: ASSET_ID, displayName: 'Falcon-1' }]);
    expect(humanizeSummary(`for asset ${upper}`, names)).toBe('for asset Falcon-1');
  });
});

describe('actorLabel', () => {
  it('reads the root/system principal as "Station"', () => {
    expect(actorLabel(ROOT_ACTOR_ID, buildNameMap([]))).toBe('Station');
  });

  it('resolves a known user to their display name', () => {
    const names = buildNameMap([], [{ userId: USER_ID, displayName: 'Jane Pilot' }]);
    expect(actorLabel(USER_ID, names)).toBe('Jane Pilot');
  });

  it('shortens an unknown actor to its first 8 characters', () => {
    expect(actorLabel(UNKNOWN_ID, buildNameMap([]))).toBe(UNKNOWN_ID.slice(0, 8));
  });
});
