import { TestBed } from '@angular/core/testing';
import { provideMockActions } from '@ngrx/effects/testing';
import type { Action } from '@ngrx/store';
import { firstValueFrom, ReplaySubject } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import type { GroupSummary, UserSummary } from '../../api/models';
import { VisionApi } from '../../api/vision-api';
import { ToastService } from '../../toast.service';
import { OrgApiActions, OrgPageActions } from './org.actions';
import { createUser$, notifyFailure$, notifySuccess$, refresh$ } from './org.effects';

function user(): UserSummary {
  return {
    userId: 'u-1',
    username: 'pat',
    displayName: 'Pat Pilot',
    email: 'pat@example.com',
    enabled: true,
    memberships: [],
  };
}

function group(): GroupSummary {
  return { id: 'g-1', name: 'HQ' };
}

function setup(apiOverrides: Partial<VisionApi> = {}) {
  const actions = new ReplaySubject<Action>(1);
  const toasts = { ok: vi.fn(), error: vi.fn() };
  const api = {
    listUsers: vi.fn().mockResolvedValue([user()]),
    listGroups: vi.fn().mockResolvedValue([group()]),
    createUser: vi.fn().mockResolvedValue(user()),
    ...apiOverrides,
  };
  TestBed.configureTestingModule({
    providers: [
      provideMockActions(() => actions),
      { provide: VisionApi, useValue: api },
      { provide: ToastService, useValue: toasts },
    ],
  });
  return { actions, toasts, api };
}

describe('org effects', () => {
  it('refresh$ succeeds with both lists on a clean read', async () => {
    const { actions } = setup();
    const result = firstValueFrom(TestBed.runInInjectionContext(() => refresh$()));
    actions.next(OrgPageActions.refreshRequested({ quiet: false }));
    expect(await result).toEqual(OrgApiActions.refreshSucceeded({ users: [user()], groups: [group()] }));
  });

  it('refresh$ carries the error and the original quiet flag on failure', async () => {
    const { actions } = setup({ listUsers: vi.fn().mockRejectedValue(new Error('down')) });
    const result = firstValueFrom(TestBed.runInInjectionContext(() => refresh$()));
    actions.next(OrgPageActions.refreshRequested({ quiet: true }));
    const action = await result;
    expect(action.type).toBe(OrgApiActions.refreshFailed.type);
    expect((action as ReturnType<typeof OrgApiActions.refreshFailed>).quiet).toBe(true);
  });

  it('createUser$ succeeds and folds in the post-create refresh', async () => {
    const { actions } = setup();
    const result = firstValueFrom(TestBed.runInInjectionContext(() => createUser$()));
    actions.next(
      OrgPageActions.createUserRequested({
        request: { username: 'pat', displayName: 'Pat', email: 'p@x.com', password: 'pw' },
      }),
    );
    const action = await result;
    expect(action.type).toBe(OrgApiActions.createUserSucceeded.type);
    const succeeded = action as ReturnType<typeof OrgApiActions.createUserSucceeded>;
    expect(succeeded.user).toEqual(user());
    expect(succeeded.users).toEqual([user()]);
    expect(succeeded.message).toBe('Created Pat Pilot.');
  });

  it('createUser$ reports Failed when the create itself throws', async () => {
    const { actions } = setup({ createUser: vi.fn().mockRejectedValue(new Error('409')) });
    const result = firstValueFrom(TestBed.runInInjectionContext(() => createUser$()));
    actions.next(
      OrgPageActions.createUserRequested({
        request: { username: 'pat', displayName: 'Pat', email: 'p@x.com', password: 'pw' },
      }),
    );
    expect((await result).type).toBe(OrgApiActions.createUserFailed.type);
  });

  it('createUser$ reports Failed when the create succeeds but the follow-up refresh throws (matches the old OrgStore#run quirk: the whole chain fails as one unit)', async () => {
    const { actions } = setup({ listUsers: vi.fn().mockRejectedValue(new Error('down')) });
    const result = firstValueFrom(TestBed.runInInjectionContext(() => createUser$()));
    actions.next(
      OrgPageActions.createUserRequested({
        request: { username: 'pat', displayName: 'Pat', email: 'p@x.com', password: 'pw' },
      }),
    );
    expect((await result).type).toBe(OrgApiActions.createUserFailed.type);
  });

  it('notifySuccess$ toasts the pre-built message for every succeeded action', async () => {
    const { actions, toasts } = setup();
    TestBed.runInInjectionContext(() => notifySuccess$()).subscribe();
    actions.next(OrgApiActions.createUserSucceeded({ user: user(), users: [user()], groups: [], message: 'Created Pat.' }));
    expect(toasts.ok).toHaveBeenCalledWith('Created Pat.');
  });

  it('notifyFailure$ toasts a plain failure', async () => {
    const { actions, toasts } = setup();
    TestBed.runInInjectionContext(() => notifyFailure$()).subscribe();
    actions.next(OrgApiActions.createUserFailed({ error: 'nope' }));
    expect(toasts.error).toHaveBeenCalledWith('nope');
  });

  it('notifyFailure$ suppresses the toast for a quiet refreshFailed', async () => {
    const { actions, toasts } = setup();
    TestBed.runInInjectionContext(() => notifyFailure$()).subscribe();
    actions.next(OrgApiActions.refreshFailed({ error: 'nope', quiet: true }));
    expect(toasts.error).not.toHaveBeenCalled();
  });

  it('notifyFailure$ still toasts a non-quiet refreshFailed', async () => {
    const { actions, toasts } = setup();
    TestBed.runInInjectionContext(() => notifyFailure$()).subscribe();
    actions.next(OrgApiActions.refreshFailed({ error: 'nope', quiet: false }));
    expect(toasts.error).toHaveBeenCalledWith('nope');
  });
});
