import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { describe, expect, it } from 'vitest';
import { PreflightPage } from './preflight';
import { VisionApi } from '../../core/api/vision-api';
import type { FleetReadiness, ReadinessRow } from '../../core/api/models';

/** The one `VisionApi` call this page's facade makes (`fleetReadiness`) — a duck-typed stand-in,
 * same pattern `rc-monitor.spec.ts`'s own `FakeVisionApi` uses for its narrower `assetReadiness`. */
class FakeVisionApi {
  private response: FleetReadiness = { assets: [] };

  setRows(rows: readonly ReadinessRow[]): void {
    this.response = { assets: rows };
  }

  fleetReadiness(): Promise<FleetReadiness> {
    return Promise.resolve(this.response);
  }
}

/** A microtask-only flush — `fleetReadiness()` resolves immediately, so a couple of already-resolved
 * `await`s drain its `.then()` deterministically, mirroring `rc-monitor.spec.ts#flushMicrotasks`. */
async function flushMicrotasks(): Promise<void> {
  await Promise.resolve();
  await Promise.resolve();
}

function row(partial: Partial<ReadinessRow> = {}): ReadinessRow {
  return { assetId: 'a-1', displayName: 'Drone 1', verdict: 'GO', features: {}, ...partial };
}

async function render(rows: readonly ReadinessRow[]) {
  TestBed.resetTestingModule();
  const api = new FakeVisionApi();
  api.setRows(rows);
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
    // Bravo (NO_GO, worst-first) is row 0: map-position first in frozen key order, 2 more (preflight-checks, battery).
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
    const noGoButton = buttons[1]; // Go, No-go, Unknown
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
