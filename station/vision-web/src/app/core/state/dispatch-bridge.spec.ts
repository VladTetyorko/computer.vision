import { TestBed } from '@angular/core/testing';
import { Actions } from '@ngrx/effects';
import { createAction, props, Store, provideStore } from '@ngrx/store';
import { describe, expect, it } from 'vitest';
import { dispatchAndAwait } from './dispatch-bridge';

const requested = createAction('[Test] Requested');
const succeeded = createAction('[Test] Succeeded', props<{ value: number }>());
const failed = createAction('[Test] Failed', props<{ reason: string }>());

describe('dispatchAndAwait', () => {
  function setup() {
    TestBed.configureTestingModule({ providers: [provideStore({})] });
    return { store: TestBed.inject(Store), actions$: TestBed.inject(Actions) };
  }

  it('resolves via onSuccess when the success action fires', async () => {
    const { store, actions$ } = setup();
    const promise = dispatchAndAwait(
      store,
      actions$,
      requested(),
      succeeded,
      failed,
      (action) => `ok:${action.value}`,
      () => 'never',
    );
    store.dispatch(succeeded({ value: 42 }));
    expect(await promise).toBe('ok:42');
  });

  it('resolves via onFailure when the failure action fires instead', async () => {
    const { store, actions$ } = setup();
    const promise = dispatchAndAwait(
      store,
      actions$,
      requested(),
      succeeded,
      failed,
      () => 'never',
      (action) => `err:${action.reason}`,
    );
    store.dispatch(failed({ reason: 'boom' }));
    expect(await promise).toBe('err:boom');
  });

  it('ignores an unrelated action and only settles on success/failure', async () => {
    const { store, actions$ } = setup();
    const other = createAction('[Test] Unrelated');
    const promise = dispatchAndAwait(
      store,
      actions$,
      requested(),
      succeeded,
      failed,
      (action) => `ok:${action.value}`,
      () => 'never',
    );
    store.dispatch(other());
    store.dispatch(succeeded({ value: 7 }));
    expect(await promise).toBe('ok:7');
  });

  it('dispatches the requested action itself (the bridge is not a passive listener)', async () => {
    const { store, actions$ } = setup();
    const seen: string[] = [];
    actions$.subscribe((action) => seen.push(action.type));

    const promise = dispatchAndAwait(store, actions$, requested(), succeeded, failed, () => 'ok', () => 'err');
    store.dispatch(succeeded({ value: 1 }));
    await promise;

    expect(seen).toContain('[Test] Requested');
  });
});
