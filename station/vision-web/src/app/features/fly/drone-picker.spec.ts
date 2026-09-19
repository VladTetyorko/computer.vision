import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { DronePickerPage } from './drone-picker';
import { VisionApi } from '../../core/api/vision-api';
import { AuthFacade } from '../../core/auth/auth-facade';
import { PollScheduler } from '../../core/poll-scheduler';
import { LiveFacade, type LiveConnectionState } from '../../core/live/live-facade';
import type { AssetSummary, AuthCapability, MeResponse, Role, ScopeKind } from '../../core/api/models';

/**
 * `DronePickerPage` TestBed specs (docs/plans/active/OPERATOR-UX-3-PLAN.md wave T1) — the wiring a
 * pure spec can't reach: `facade.groups()` actually splitting into the template's two sections, the
 * "Hide simulated" checkbox actually collapsing a grid and persisting to `localStorage`, and the
 * "Your vehicles" empty leg rendering only when that one group (not the whole picker) is empty.
 * Grouping/sorting/label *content* is already covered by `drone-picker-logic.spec.ts`; this file
 * only asserts the DOM reacts to it, mirroring `preflight.spec.ts`'s own "thin TestBed layer over an
 * already-tested pure logic module" shape.
 */

function asset(partial: Partial<AssetSummary>): AssetSummary {
  return {
    assetId: 'a-0',
    displayName: 'Asset',
    category: 'drone',
    categoryName: 'Drone',
    owner: 'owner-0',
    status: 'OFFLINE',
    attributes: {},
    ...partial,
  };
}

/** A duck-typed `VisionApi` stand-in — the only call this facade makes on its own account. */
function stubApi(assets: readonly AssetSummary[]) {
  return { listAssets: vi.fn().mockResolvedValue(assets) };
}

/** Mirrors the real `RoleAuthority`/`DefaultScopeResolver` policy table closely enough for a
 *  fixture — see `core/auth/auth-logic.spec.ts`'s identical helper for the full reasoning. */
const ROLE_CAPABILITIES: Record<Role, readonly AuthCapability[]> = {
  VIEWER: [],
  PILOT: ['OPERATE_PAYLOAD', 'COMMAND_FLIGHT'],
  MANAGER: ['OPERATE_PAYLOAD', 'COMMAND_FLIGHT', 'MANAGE_FLEET', 'MANAGE_ORG'],
  ADMIN: ['OPERATE_PAYLOAD', 'COMMAND_FLIGHT', 'MANAGE_FLEET', 'MANAGE_ORG'],
};
const ROLE_SCOPE_KIND: Record<Role, ScopeKind> = {
  VIEWER: 'GROUPS',
  PILOT: 'ASSIGNED_ASSETS',
  MANAGER: 'GROUPS',
  ADMIN: 'UNBOUNDED',
};

/** Mirrors `landing-guard.spec.ts#fakeAuthFacade` — `user()`, plus `capabilities()`/`scopeKind()`
 *  (docs/plans/active/AUTH-ROLES-PLAN.md §3.2, wave W2), the fields `emptyState`
 *  (`drone-picker-facade.ts`) now reads instead of `user()?.topRole`. */
function fakeAuthFacade(user: Pick<MeResponse, 'topRole' | 'memberships'> | null = { topRole: 'ADMIN', memberships: [] }) {
  return {
    user: () => user,
    capabilities: () => (user ? ROLE_CAPABILITIES[user.topRole] : []),
    scopeKind: () => (user ? ROLE_SCOPE_KIND[user.topRole] : undefined),
  };
}

/** Mirrors `fleet-store.spec.ts#stubScheduler`, narrowed to what this facade needs — a no-op
 * `schedule()` so the constructor's poll registration doesn't leave a real timer running. */
function stubScheduler() {
  return { schedule: vi.fn(() => vi.fn()) };
}

/** Mirrors `fleet-store.spec.ts#stubLiveFacade` — real signals, closed/undefined by default (the
 * same state the real `LiveFacade` reports under jsdom, per that file's own doc comment), so the
 * facade's poll-vs-live effect stays on the poll path this stubbed `listAssets()` backs. */
function stubLiveFacade() {
  return {
    connectionState: signal<LiveConnectionState>('closed').asReadonly(),
    fleet: signal<readonly AssetSummary[] | undefined>(undefined).asReadonly(),
  };
}

async function flushMicrotasks(): Promise<void> {
  await Promise.resolve();
  await Promise.resolve();
}

async function render(assets: readonly AssetSummary[], user?: Pick<MeResponse, 'topRole' | 'memberships'> | null) {
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({
    providers: [
      provideRouter([]),
      { provide: VisionApi, useValue: stubApi(assets) as unknown as VisionApi },
      { provide: AuthFacade, useValue: fakeAuthFacade(user) as unknown as AuthFacade },
      { provide: PollScheduler, useValue: stubScheduler() as unknown as PollScheduler },
      { provide: LiveFacade, useValue: stubLiveFacade() as unknown as LiveFacade },
    ],
  });
  const fixture = TestBed.createComponent(DronePickerPage);
  fixture.detectChanges();
  await flushMicrotasks();
  fixture.detectChanges();
  return fixture;
}

function groupLabels(fixture: { nativeElement: HTMLElement }): string[] {
  return Array.from(fixture.nativeElement.querySelectorAll('.picker-group-label')).map(
    (el) => (el as HTMLElement).textContent?.trim() ?? '',
  );
}

const REAL = asset({ assetId: 'rover-1', displayName: 'Rover One', category: 'rover', categoryName: 'Rover' });
const SIM = asset({ assetId: 'sim-1', displayName: 'Sim One', category: 'simulated', categoryName: 'Simulated aircraft' });

describe('DronePickerPage', () => {
  beforeEach(() => {
    localStorage.clear();
  });

  it('renders both group headers with their own counts', async () => {
    const fixture = await render([REAL, SIM]);

    expect(groupLabels(fixture)).toEqual(['Your vehicles (1)', 'Simulated (1)']);
  });

  it('puts a real-category asset under Your vehicles and a simulated one under Simulated', async () => {
    const fixture = await render([REAL, SIM]);

    const groups = fixture.nativeElement.querySelectorAll('.picker-group');
    expect(groups[0].textContent).toContain('Rover One');
    expect(groups[0].textContent).not.toContain('Sim One');
    expect(groups[1].textContent).toContain('Sim One');
    expect(groups[1].textContent).not.toContain('Rover One');
  });

  it('the "Hide simulated" checkbox collapses the Simulated grid but keeps its header', async () => {
    const fixture = await render([REAL, SIM]);

    expect(fixture.nativeElement.textContent).toContain('Sim One');

    const checkbox = fixture.nativeElement.querySelector('input[name="hideSimulated"]') as HTMLInputElement;
    expect(checkbox).not.toBeNull();
    checkbox.checked = true;
    checkbox.dispatchEvent(new Event('change'));
    fixture.detectChanges();

    expect(groupLabels(fixture)).toEqual(['Your vehicles (1)', 'Simulated (1)']); // header survives
    expect(fixture.nativeElement.textContent).not.toContain('Sim One'); // card grid gone
  });

  it('persists the "Hide simulated" toggle to localStorage under a namespaced key', async () => {
    const fixture = await render([REAL, SIM]);
    const checkbox = fixture.nativeElement.querySelector('input[name="hideSimulated"]') as HTMLInputElement;

    checkbox.checked = true;
    checkbox.dispatchEvent(new Event('change'));
    fixture.detectChanges();

    expect(localStorage.getItem('vision.fly.hideSimulated')).toBe('true');
  });

  it('reads a persisted "Hide simulated" preference back on the next visit', async () => {
    localStorage.setItem('vision.fly.hideSimulated', 'true');

    const fixture = await render([REAL, SIM]);

    expect(fixture.nativeElement.textContent).not.toContain('Sim One');
    const checkbox = fixture.nativeElement.querySelector('input[name="hideSimulated"]') as HTMLInputElement;
    expect(checkbox.checked).toBe(true);
  });

  it('omits the "Hide simulated" toggle entirely when there is nothing simulated to hide', async () => {
    const fixture = await render([REAL]);

    expect(fixture.nativeElement.querySelector('input[name="hideSimulated"]')).toBeNull();
    expect(groupLabels(fixture)).toEqual(['Your vehicles (1)']);
  });

  it('shows the "No real vehicles yet" line when only simulated assets exist', async () => {
    const fixture = await render([SIM]);

    expect(fixture.nativeElement.textContent).toContain('No real vehicles yet');
    const addSource = fixture.nativeElement.querySelector('.picker-group-empty a');
    expect(addSource?.getAttribute('href')).toBe('/add-source');
  });

  it('D7 (docs/plans/active/SOURCE-ONBOARDING-2-PLAN.md): a PILOT never sees the "Your vehicles" empty leg\'s Add-source link, since /add-source is orgGuard-gated and would bounce them', async () => {
    const fixture = await render([SIM], { topRole: 'PILOT', memberships: [{ groupId: 'g-1', groupName: 'Alpha', role: 'PILOT' }] });

    expect(fixture.nativeElement.textContent).toContain('No real vehicles yet');
    expect(fixture.nativeElement.querySelector('.picker-group-empty a')).toBeNull();
    expect(fixture.nativeElement.textContent).toContain('ask a fleet manager');
  });

  it('falls back to the full-page empty state when there are no assets at all', async () => {
    const fixture = await render([], { topRole: 'ADMIN', memberships: [] });

    expect(fixture.nativeElement.querySelector('.picker-group')).toBeNull();
    expect(fixture.nativeElement.querySelector('.card.empty')).not.toBeNull();
    expect(fixture.nativeElement.textContent).toContain('No drones registered yet');
  });

  it('a streaming card keeps the plain "Streaming" chip; an offline one reads its age', async () => {
    const streaming = asset({ assetId: 'live-1', displayName: 'Live One', status: 'STREAMING' });
    const neverSeen = asset({ assetId: 'never-1', displayName: 'Never One', status: 'OFFLINE', lastUsedAt: undefined });
    const fixture = await render([streaming, neverSeen]);

    const chips = Array.from(fixture.nativeElement.querySelectorAll('.picker-card .chip')).map(
      (el) => (el as HTMLElement).textContent?.trim(),
    );
    expect(chips).toContain('Streaming');
    expect(chips).toContain('Never seen');
  });
});
