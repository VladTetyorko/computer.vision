import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { describe, expect, it, vi } from 'vitest';
import { IdentityChip } from './identity-chip';
import { AuthStore } from '../../core/auth/auth-store';
import { VisionApi } from '../../core/api/vision-api';
import type { MeResponse } from '../../core/api/models';

function meResponse(overrides: Partial<MeResponse> = {}): MeResponse {
  return {
    userId: 'u-1',
    username: 'pilot',
    displayName: 'Pat Pilot',
    email: 'pilot@example.com',
    memberships: [{ groupId: 'g-1', groupName: 'HQ', role: 'PILOT' }],
    topRole: 'PILOT',
    authEnabled: true,
    ...overrides,
  };
}

async function render(me: MeResponse | null) {
  const api = {
    authMe: vi.fn().mockResolvedValue(me),
    authLogin: vi.fn(),
    authLogout: vi.fn().mockResolvedValue(undefined),
  };
  TestBed.configureTestingModule({
    providers: [provideRouter([]), AuthStore, { provide: VisionApi, useValue: api }],
  });
  const store = TestBed.inject(AuthStore);
  await store.ready;
  const fixture = TestBed.createComponent(IdentityChip);
  fixture.detectChanges();
  return fixture;
}

function menuHrefs(fixture: { nativeElement: HTMLElement }): (string | null)[] {
  return Array.from(fixture.nativeElement.querySelectorAll('.identity-links a')).map((a) => a.getAttribute('href'));
}

/**
 * Wave 1's own profile-menu extension (docs/UI-REDESIGN-PLAN.md, F4 "(shell) Account settings →
 * `/settings` via profile menu") — added alongside the pre-existing My activity/Organization/Log
 * out, per `identity-chip.ts`'s own updated class doc comment.
 */
describe('IdentityChip — profile menu', () => {
  it('renders My activity + Account settings for every signed-in user, no Organization for a PILOT', async () => {
    const fixture = await render(meResponse({ topRole: 'PILOT' }));

    expect(menuHrefs(fixture)).toEqual(['/activity', '/settings']);
  });

  it('adds Organization for a MANAGER/ADMIN, alongside the ungated My activity/Account settings', async () => {
    const fixture = await render(meResponse({ topRole: 'MANAGER' }));

    expect(menuHrefs(fixture)).toEqual(['/activity', '/settings', '/org']);
  });

  it('renders nothing while there is no session (no placeholder swapped in)', async () => {
    const fixture = await render(null);

    expect(fixture.nativeElement.querySelector('.identity-chip')).toBeNull();
  });
});
