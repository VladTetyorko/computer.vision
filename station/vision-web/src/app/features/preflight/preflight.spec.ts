import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { describe, expect, it } from 'vitest';
import { PreflightPage } from './preflight';
import { VisionApi } from '../../core/api/vision-api';
import type { AssetDetails, FleetReadiness, ReadinessRow } from '../../core/api/models';

/** The two `VisionApi` calls this page's facade makes (`fleetReadiness`, plus a per-row `getAsset`
 * fan-out for triage/telemetry-device enrichment — docs/plans/active/OPERATOR-UX-7-PLAN.md finding P1,
 * §2 P1, wave W1) — a duck-typed stand-in, same pattern `rc-monitor.spec.ts`'s own `FakeVisionApi`
 * uses for its narrower `assetReadiness`. `getAsset` rejects for any assetId with no fixture
 * registered (mirrors a real, out-of-scope/unknown asset) — `PreflightFacade#loadVehicleDetails`
 * degrades that silently, same as a network failure, so most of the pre-existing tests below never
 * bother registering one at all and still get the documented safe defaults. */
class FakeVisionApi {
  private response: FleetReadiness = { assets: [] };
  private readonly details = new Map<string, AssetDetails>();

  setRows(rows: readonly ReadinessRow[]): void {
    this.response = { assets: rows };
  }

  /** Registers the `AssetDetails` `getAsset(assetId)` resolves to — every field but `assetId`/the
   * given overrides defaults to an ordinary, non-simulated, offline asset with no devices at all. */
  setDetails(assetId: string, overrides: Partial<AssetDetails> = {}): void {
    this.details.set(assetId, {
      assetId,
      displayName: 'Drone',
      category: 'drones',
      categoryName: 'Drones',
      owner: 'owner-1',
      status: 'OFFLINE',
      attributes: {},
      devices: [],
      recentUsages: [],
      ...overrides,
    });
  }

  fleetReadiness(): Promise<FleetReadiness> {
    return Promise.resolve(this.response);
  }

  getAsset(assetId: string): Promise<AssetDetails> {
    const details = this.details.get(assetId);
    return details ? Promise.resolve(details) : Promise.reject(new Error(`no AssetDetails fixture for ${assetId}`));
  }
}

/** A microtask-only flush — every fake call resolves immediately, so enough already-resolved `await`s
 * drain the facade's two-hop `fleetReadiness()` → `getAsset()`-per-row chain deterministically,
 * mirroring `rc-monitor.spec.ts#flushMicrotasks` (widened from a fixed 2 iterations now that
 * `refresh()` has a second network hop, docs/plans/active/OPERATOR-UX-7-PLAN.md P1, wave W1). */
async function flushMicrotasks(): Promise<void> {
  for (let i = 0; i < 10; i++) {
    await Promise.resolve();
  }
}

function row(partial: Partial<ReadinessRow> = {}): ReadinessRow {
  return { assetId: 'a-1', displayName: 'Drone 1', verdict: 'GO', features: {}, ...partial };
}

/** A row shaped exactly like `DefaultReadinessService#evaluateFeature`'s "no `VehicleProfile` at
 * all"/"incomplete profile" output — verdict `UNKNOWN`, every evaluated feature `UNKNOWN` — the wire
 * signature `preflight-logic.ts#isNeverProbedRow` reads as "never probed" (§2 P1). */
function neverProbedRow(partial: Partial<ReadinessRow> = {}): ReadinessRow {
  return row({ verdict: 'UNKNOWN', features: { 'map-position': 'UNKNOWN', battery: 'UNKNOWN' }, ...partial });
}

async function render(rows: readonly ReadinessRow[], configureApi?: (api: FakeVisionApi) => void) {
  TestBed.resetTestingModule();
  const api = new FakeVisionApi();
  api.setRows(rows);
  configureApi?.(api);
  TestBed.configureTestingModule({
    providers: [provideRouter([]), { provide: VisionApi, useValue: api as unknown as VisionApi }],
  });
  const fixture = TestBed.createComponent(PreflightPage);
  fixture.detectChanges();
  await flushMicrotasks();
  fixture.detectChanges();
  return fixture;
}

const ROWS: readonly ReadinessRow[] = [
  row({ assetId: 'go-1', displayName: 'Alpha', verdict: 'GO' }),
  row({
    assetId: 'nogo-1',
    displayName: 'Bravo',
    verdict: 'NO_GO',
    features: { 'map-position': 'MISSING', 'preflight-checks': 'MISSING', battery: 'DEGRADED' },
  }),
  row({ assetId: 'unknown-1', displayName: 'Charlie', verdict: 'UNKNOWN', features: { battery: 'UNKNOWN' } }),
];

describe('PreflightPage', () => {
  it('sorts rows worst-first: NO_GO, then UNKNOWN, then GO', async () => {
    const fixture = await render(ROWS);

    const names = Array.from(fixture.nativeElement.querySelectorAll('.name-cell')).map((el) => (el as HTMLElement).textContent?.trim());
    expect(names).toEqual(['Bravo', 'Charlie', 'Alpha']);
  });

  it('renders the first blocking check plus a +N chip, never a comma-joined sentence', async () => {
    const fixture = await render(ROWS);

    const attentionCells = fixture.nativeElement.querySelectorAll('.attention-cell');
    // Bravo (NO_GO, worst-first) is the only row with a real blocker — Charlie's own single
    // `battery: 'UNKNOWN'` feature reads as never-probed (OPERATOR-UX-7 P1) and renders "—" instead.
    const bravoRow = attentionCells[0] as HTMLElement;
    expect(bravoRow.querySelector('.blocker-name')?.textContent?.trim()).toBe('Map position');
    expect(bravoRow.querySelector('.chip')?.textContent?.trim()).toBe('+2');
  });

  it('renders a plain dash, no chip, when a row has no blocking check', async () => {
    const fixture = await render(ROWS);

    const attentionCells = fixture.nativeElement.querySelectorAll('.attention-cell, td.faint');
    const alphaCell = Array.from(attentionCells).find((el) => (el as HTMLElement).closest('tr')?.textContent?.includes('Alpha')) as HTMLElement;
    expect(alphaCell.classList.contains('faint')).toBe(true);
    expect(alphaCell.textContent?.trim()).toBe('—');
  });

  it('clicking a verdict card filters the table to just that verdict', async () => {
    const fixture = await render(ROWS);

    const buttons = fixture.nativeElement.querySelectorAll('.stat-filter') as NodeListOf<HTMLButtonElement>;
    const noGoButton = buttons[1]; // Go, No-go, Unknown, Not probed
    noGoButton.click();
    fixture.detectChanges();

    expect(noGoButton.classList.contains('selected')).toBe(true);
    expect(noGoButton.getAttribute('aria-pressed')).toBe('true');
    const names = Array.from(fixture.nativeElement.querySelectorAll('.name-cell')).map((el) => (el as HTMLElement).textContent?.trim());
    expect(names).toEqual(['Bravo']);
  });

  it('clicking the same card again clears the filter', async () => {
    const fixture = await render(ROWS);

    const buttons = fixture.nativeElement.querySelectorAll('.stat-filter') as NodeListOf<HTMLButtonElement>;
    buttons[1].click();
    fixture.detectChanges();
    buttons[1].click();
    fixture.detectChanges();

    expect(buttons[1].classList.contains('selected')).toBe(false);
    const names = Array.from(fixture.nativeElement.querySelectorAll('.name-cell')).map((el) => (el as HTMLElement).textContent?.trim());
    expect(names).toEqual(['Bravo', 'Charlie', 'Alpha']);
  });

  it('shows the filtered-empty state, not the table, when a filter matches nothing', async () => {
    const fixture = await render([row({ assetId: 'go-1', displayName: 'Alpha', verdict: 'GO' })]);

    const buttons = fixture.nativeElement.querySelectorAll('.stat-filter') as NodeListOf<HTMLButtonElement>;
    buttons[1].click(); // No-go — zero rows match
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('table')).toBeNull();
    const empty = fixture.nativeElement.querySelector('.empty');
    expect(empty?.textContent).toContain('No No-go drones');
    expect(empty?.querySelector('button')?.textContent?.trim()).toBe('Clear filter');
  });

  it('"Clear filter" restores the full table', async () => {
    const fixture = await render([row({ assetId: 'go-1', displayName: 'Alpha', verdict: 'GO' })]);

    const buttons = fixture.nativeElement.querySelectorAll('.stat-filter') as NodeListOf<HTMLButtonElement>;
    buttons[1].click();
    fixture.detectChanges();

    (fixture.nativeElement.querySelector('.empty button') as HTMLButtonElement).click();
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('table')).not.toBeNull();
    expect(buttons[1].classList.contains('selected')).toBe(false);
  });
});

describe('PreflightPage — OPERATOR-UX-7 P1 (§2 P1, wave W1): triage + honest never-probed/no-telemetry copy', () => {
  it('counts the page bar in vehicles, not drones', async () => {
    const fixture = await render(ROWS);

    const pageBarText = (fixture.nativeElement.querySelector('vision-page-bar') as HTMLElement).textContent ?? '';
    expect(pageBarText).toContain('3 vehicles');
  });

  it('reads "Not probed yet" with no chip, and a dash attention cell, for a never-probed row', async () => {
    const fixture = await render([neverProbedRow({ assetId: 'never-1', displayName: 'Delta' })]);

    const deltaRow = Array.from(fixture.nativeElement.querySelectorAll('tbody tr')).find((tr) =>
      (tr as HTMLElement).textContent?.includes('Delta'),
    ) as HTMLElement;
    expect(deltaRow.querySelector('.verdict-note')?.textContent?.trim()).toBe('Not probed yet');
    expect(deltaRow.querySelector('.chip')).toBeNull();
    const attentionCell = deltaRow.querySelector('td.faint') as HTMLElement;
    expect(attentionCell.textContent?.trim()).toBe('—');
  });

  it('reads "No telemetry device" once the asset\'s own devices confirm it owns no TELEMETRY-capable one', async () => {
    const fixture = await render([neverProbedRow({ assetId: 'cam-1', displayName: 'Echo' })], (api) =>
      api.setDetails('cam-1', { category: 'drones', devices: [{ id: 'd-1', name: 'Cam', capabilities: ['VIDEO'], protocol: 'mjpeg', uri: 'http://x', options: {}, state: 'ACTIVE' }] }),
    );

    const echoRow = Array.from(fixture.nativeElement.querySelectorAll('tbody tr')).find((tr) =>
      (tr as HTMLElement).textContent?.includes('Echo'),
    ) as HTMLElement;
    expect(echoRow.querySelector('.verdict-note')?.textContent?.trim()).toBe('No telemetry device');
  });

  it('keeps "Not probed yet" (never a guessed "No telemetry device") when the asset does own a TELEMETRY device', async () => {
    const fixture = await render([neverProbedRow({ assetId: 'rover-1', displayName: 'Foxtrot' })], (api) =>
      api.setDetails('rover-1', {
        category: 'drones',
        devices: [{ id: 'd-2', name: 'Telem', capabilities: ['TELEMETRY'], protocol: 'mavlink', uri: 'udp://0.0.0.0:14550', options: {}, state: 'ACTIVE' }],
      }),
    );

    const foxtrotRow = Array.from(fixture.nativeElement.querySelectorAll('tbody tr')).find((tr) =>
      (tr as HTMLElement).textContent?.includes('Foxtrot'),
    ) as HTMLElement;
    expect(foxtrotRow.querySelector('.verdict-note')?.textContent?.trim()).toBe('Not probed yet');
  });

  it('splits Your vehicles / Simulated into their own structural-label groups', async () => {
    const fixture = await render(
      [row({ assetId: 'real-1', displayName: 'Golf', verdict: 'GO' }), row({ assetId: 'sim-1', displayName: 'Hotel', verdict: 'GO' })],
      (api) => {
        api.setDetails('real-1', { category: 'drones' });
        api.setDetails('sim-1', { category: 'simulated' });
      },
    );

    const groupLabels = Array.from(fixture.nativeElement.querySelectorAll('.group-label')).map((el) => (el as HTMLElement).textContent?.trim());
    expect(groupLabels).toEqual(['Your vehicles (1)', 'Simulated (1)']);
    const names = Array.from(fixture.nativeElement.querySelectorAll('.name-cell')).map((el) => (el as HTMLElement).textContent?.trim());
    expect(names).toEqual(['Golf', 'Hotel']);
  });

  it('"Hide simulated" collapses only the Simulated group\'s rows, keeping its header count', async () => {
    const fixture = await render(
      [row({ assetId: 'real-1', displayName: 'Golf', verdict: 'GO' }), row({ assetId: 'sim-1', displayName: 'Hotel', verdict: 'GO' })],
      (api) => {
        api.setDetails('real-1', { category: 'drones' });
        api.setDetails('sim-1', { category: 'simulated' });
      },
    );

    const checkbox = fixture.nativeElement.querySelector('input[name="hideSimulated"]') as HTMLInputElement;
    checkbox.checked = true;
    checkbox.dispatchEvent(new Event('change'));
    fixture.detectChanges();

    const groupLabels = Array.from(fixture.nativeElement.querySelectorAll('.group-label')).map((el) => (el as HTMLElement).textContent?.trim());
    expect(groupLabels).toEqual(['Your vehicles (1)', 'Simulated (1)']);
    const names = Array.from(fixture.nativeElement.querySelectorAll('.name-cell')).map((el) => (el as HTMLElement).textContent?.trim());
    expect(names).toEqual(['Golf']);
  });

  it('the NOT PROBED stat tile counts and filters to never-probed rows only', async () => {
    const fixture = await render([
      row({ assetId: 'go-1', displayName: 'Alpha', verdict: 'GO' }),
      row({ assetId: 'nogo-1', displayName: 'Bravo', verdict: 'NO_GO', features: { battery: 'MISSING' } }),
      neverProbedRow({ assetId: 'never-1', displayName: 'Delta' }),
    ]);

    const buttons = fixture.nativeElement.querySelectorAll('.stat-filter') as NodeListOf<HTMLButtonElement>;
    const notProbedButton = buttons[3]; // Go, No-go, Unknown, Not probed
    expect(notProbedButton.textContent).toContain('Not probed');
    expect(notProbedButton.textContent).toContain('1');

    notProbedButton.click();
    fixture.detectChanges();

    const names = Array.from(fixture.nativeElement.querySelectorAll('.name-cell')).map((el) => (el as HTMLElement).textContent?.trim());
    expect(names).toEqual(['Delta']);
  });
});
