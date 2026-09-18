import type { Actions } from '@ngrx/effects';
import { ofType } from '@ngrx/effects';
import type { Action, ActionCreator, Creator, Store } from '@ngrx/store';
import { firstValueFrom } from 'rxjs';
import { take } from 'rxjs/operators';

/**
 * The bridge every facade method that must keep returning a value (a created record, a boolean,
 * `void`) uses to sit on top of NgRx's fire-and-forget dispatch (docs/plans/active/
 * NGRX-MIGRATION-PLAN.md §5 step 7) — `OrgStore#createUser`/`#setMemberships`/etc. and the old
 * `AuthStore#login`/`#bootstrap`/`#changePassword` all `await`ed their own outcome and handed a
 * value back to the caller; an action dispatch alone can't do that.
 *
 * Subscribes to `actions$` *before* dispatching (never after — an effect's HTTP call is always
 * asynchronous in practice, but subscribing first removes any doubt), then resolves with whichever
 * of `successType`/`failureType` comes back first, projected through `onSuccess`/`onFailure`.
 *
 * Correct-ordering guarantee: NgRx runs every reducer against a dispatched action synchronously,
 * strictly before any effect observes that same action (the store's own reducer subscription to
 * `ActionsSubject` is registered before any custom effect's). So by the time this Promise resolves,
 * the reducer has already applied the corresponding state change — a caller that reads a store
 * signal right after `await`ing this always sees the settled value, never a stale one.
 */
export function dispatchAndAwait<T, S extends Action, F extends Action>(
  store: Store,
  actions$: Actions,
  dispatched: Action,
  // `any[]` matches NgRx's own `Creator<P extends any[] = any[], ...>` default — `unknown[]` rejects
  // a real props-carrying creator here (contravariant parameter check).
  successType: ActionCreator<string, Creator<any[], S>>,
  failureType: ActionCreator<string, Creator<any[], F>>,
  onSuccess: (action: S) => T,
  onFailure: (action: F) => T,
): Promise<T> {
  const settled = firstValueFrom(actions$.pipe(ofType(successType, failureType), take(1)));
  store.dispatch(dispatched);
  return settled.then((action) => (action.type === successType.type ? onSuccess(action as S) : onFailure(action as F)));
}
