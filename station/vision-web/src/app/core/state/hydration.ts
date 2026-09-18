import { INIT, UPDATE, type MetaReducer } from '@ngrx/store';

/**
 * One slice's read side of `localStorage` (docs/plans/active/NGRX-MIGRATION-PLAN.md §3 rule 5).
 *
 * Reducers must stay pure, so a slice that restores a persisted preference cannot read storage in
 * its own `initialState`. It registers a hydrator instead: {@link hydrationMetaReducer} asks each
 * one for a partial state exactly once, at store creation, and merges it over the reducer's own
 * defaults. Writes go the other way — through that slice's effects, which is where side effects live.
 *
 * `read()` returns `undefined` for "nothing usable persisted" (absent key, or a garbage value a
 * hand-edited `localStorage` produced), which leaves the reducer's default standing rather than
 * writing a half-parsed shape into state.
 */
export interface StateHydrator<S = unknown> {
  readonly featureKey: string;
  read(): Partial<S> | undefined;
}

/**
 * Merges every registered hydrator's persisted values into state on `INIT` and `UPDATE`.
 *
 * `UPDATE` matters as much as `INIT`: a lazily-registered feature slice (one provided on a route)
 * only exists from the `UPDATE` that added its reducer, so hydrating on `INIT` alone would restore
 * app-wide slices and silently skip page-scoped ones.
 *
 * A hydrator whose feature is not in the tree yet is skipped, not defaulted — writing its key into
 * root state before its reducer exists would hand that slice a state object it never produced.
 */
export function hydrationMetaReducer(
  hydrators: readonly StateHydrator[],
): MetaReducer<Record<string, unknown>> {
  return (reducer) => (state, action) => {
    const reduced = reducer(state, action);
    if (action.type !== INIT && action.type !== UPDATE) {
      return reduced;
    }
    let hydrated = reduced;
    for (const hydrator of hydrators) {
      const slice = hydrated[hydrator.featureKey];
      if (slice === undefined || slice === null) {
        continue;
      }
      const persisted = hydrator.read();
      if (persisted === undefined) {
        continue;
      }
      hydrated = { ...hydrated, [hydrator.featureKey]: { ...(slice as object), ...persisted } };
    }
    return hydrated;
  };
}
