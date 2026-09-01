import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { FlightCommandPanel } from './flight-command-panel';
import { VisionApi } from '../../core/api/vision-api';
import { ToastService } from '../../core/toast.service';
import type { FlightCapability } from '../../core/api/models';

/**
 * Component-level coverage for the two "also on <switch>" inputs added by
 * docs/plans/active/CONTROLLER-UX-PLAN.md §2.2 decision U3 — plain template wiring
 * (`rc-monitor-logic.ts#modeAlsoOnHint`/`armAlsoOnHint` own the actual computation, and are
 * unit-tested there without Angular). Every other behaviour of this panel (visibility gating,
 * confirms, outcome toasts) is `flight-command-panel-logic.spec.ts` plus `rc-monitor.spec.ts`'s
 * "the merged Controller drawer" suite, per this codebase's own "component specs only for wiring a
 * pure spec can't reach" rule (`MODULE.md`).
 */

const ROVER_CAPABILITY: FlightCapability = {
  commandable: true,
  armSupported: true,
  modeSelectSupported: true,
  selectableModes: ['MANUAL', 'HOLD'],
  vehicleKind: 'ROVER',
};

function render() {
  TestBed.configureTestingModule({
    providers: [
      { provide: VisionApi, useValue: {} },
      { provide: ToastService, useValue: { ok: () => undefined, warn: () => undefined, error: () => undefined } },
    ],
  });
  const fixture = TestBed.createComponent(FlightCommandPanel);
  fixture.componentRef.setInput('assetId', 'asset-1');
  fixture.componentRef.setInput('assetDisplayName', 'Falcon');
  fixture.componentRef.setInput('canCommand', true);
  fixture.componentRef.setInput('capabilities', ROVER_CAPABILITY);
  fixture.detectChanges();
  return fixture;
}

describe('FlightCommandPanel — "also on" switch hints (docs/plans/active/CONTROLLER-UX-PLAN.md decision U3)', () => {
  it('renders neither hint when no switch fires the same command', () => {
    const fixture = render();

    expect(fixture.nativeElement.textContent).not.toContain('also on');
  });

  it('renders the mode row hint beside the Set button', () => {
    const fixture = render();
    fixture.componentRef.setInput('modeAlsoOn', 'Axis 5');
    fixture.detectChanges();

    const row = fixture.nativeElement.querySelector('.command-row');
    expect(row?.textContent).toContain('also on Axis 5');
    // The arm/disarm row's own hint stays independent.
    expect(fixture.nativeElement.querySelector('.command-row-danger')?.textContent ?? '').not.toContain('also on');
  });

  it('renders the arm row hint verbatim, arrow included — this component never reformats it', () => {
    const fixture = render();
    fixture.componentRef.setInput('armAlsoOn', 'Sw 2 ↑');
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.command-row-danger')?.textContent).toContain('also on Sw 2 ↑');
  });
});
