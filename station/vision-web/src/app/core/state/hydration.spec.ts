import { INIT, UPDATE, type ActionReducer } from '@ngrx/store';
import { describe, expect, it } from 'vitest';
import { hydrationMetaReducer, type StateHydrator } from './hydration';

type RootState = Record<string, unknown>;

/** A reducer that always answers with the slice's own defaults, like a freshly registered feature. */
const defaults: ActionReducer<RootState> = (state) => state ?? { theme: { theme: 'light' } };

function hydrator(value: unknown | undefined): StateHydrator {
  return { featureKey: 'theme', read: () => value as Partial<unknown> | undefined };
}

describe('hydrationMetaReducer', () => {
  it('merges persisted values over the reducer defaults on INIT', () => {
    const reducer = hydrationMetaReducer([hydrator({ theme: 'dark' })])(defaults);
    expect(reducer(undefined, { type: INIT })).toEqual({ theme: { theme: 'dark' } });
  });

  it('also hydrates on UPDATE — a lazily registered feature only exists from that action', () => {
    const reducer = hydrationMetaReducer([hydrator({ theme: 'dark' })])(defaults);
    expect(reducer({ theme: { theme: 'light' } }, { type: UPDATE })).toEqual({ theme: { theme: 'dark' } });
  });

  it('leaves the defaults standing when the hydrator has nothing usable', () => {
    const reducer = hydrationMetaReducer([hydrator(undefined)])(defaults);
    expect(reducer(undefined, { type: INIT })).toEqual({ theme: { theme: 'light' } });
  });

  it('skips a feature that is not in the tree rather than inventing its state', () => {
    const reducer = hydrationMetaReducer([{ featureKey: 'absent', read: () => ({ x: 1 }) }])(defaults);
    expect(reducer(undefined, { type: INIT })).toEqual({ theme: { theme: 'light' } });
  });

  it('does not re-hydrate on an ordinary action — a later change must not be overwritten', () => {
    const reducer = hydrationMetaReducer([hydrator({ theme: 'dark' })])(defaults);
    const afterChange = { theme: { theme: 'light' } };
    expect(reducer(afterChange, { type: '[Theme Page] Theme Toggled' })).toEqual(afterChange);
  });
});
