import { describe, expect, it } from 'vitest';
import { myAssignedAssets } from './drone-picker-logic';

interface FakeAsset {
  readonly assetId: string;
}

function asset(assetId: string): FakeAsset {
  return { assetId };
}

describe('myAssignedAssets', () => {
  it('returns an empty list when there are no assignments — same flat list as today', () => {
    const assets = [asset('a-1'), asset('a-2')];
    expect(myAssignedAssets(assets, new Set())).toEqual([]);
  });

  it('returns only the assets present in the assigned set, preserving input order', () => {
    const assets = [asset('a-1'), asset('a-2'), asset('a-3')];
    const assigned = myAssignedAssets(assets, new Set(['a-3', 'a-1']));
    expect(assigned.map((a) => a.assetId)).toEqual(['a-1', 'a-3']);
  });

  it('ignores an assigned id not present in the given asset list', () => {
    const assets = [asset('a-1')];
    expect(myAssignedAssets(assets, new Set(['a-1', 'a-404']))).toEqual([asset('a-1')]);
  });

  it('returns an empty list for an empty asset list regardless of assignments', () => {
    expect(myAssignedAssets([], new Set(['a-1']))).toEqual([]);
  });
});
