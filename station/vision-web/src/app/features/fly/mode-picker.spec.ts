import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { ModePicker } from './mode-picker';
import { VisionApi } from '../../core/api/vision-api';
import { ToastService } from '../../core/toast.service';
import type { FlightCapability } from '../../core/api/models';

/**
 * `ModePicker`'s own component-level coverage — split out of `flight-command-panel.spec.ts`
 * (docs/plans/active/FLY-CONTROL-UX-PLAN.md §3, WEB1) alongside the mode row itself moving into its
 * own component. Same "component specs only for wiring a pure spec can't reach" rule
 * (`flight-command-panel-logic.spec.ts` owns `canShowCommandPanel`/`modeConfirmMessage`/
 * `commandOutcomeToast`; this file only covers this component's own template wiring).
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
  const fixture = TestBed.createComponent(ModePicker);
  fixture.componentRef.setInput('assetId', 'asset-1');
  fixture.componentRef.setInput('assetDisplayName', 'Falcon');
  fixture.componentRef.setInput('canCommand', true);
  fixture.componentRef.setInput('capabilities', ROVER_CAPABILITY);
  fixture.detectChanges();
  return fixture;
}

describe('ModePicker — visibility', () => {
  it('renders the mode row when commandable and mode-select is supported', () => {
    const fixture = render();
    expect(fixture.nativeElement.querySelector('.command-row')).not.toBeNull();
  });

  it('renders nothing while canCommand is false', () => {
    const fixture = render();
    fixture.componentRef.setInput('canCommand', false);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.command-row')).toBeNull();
  });

  it('renders nothing with no capabilities at all', () => {
    const fixture = render();
    fixture.componentRef.setInput('capabilities', undefined);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.command-row')).toBeNull();
  });

  it('renders nothing when the vehicle reports no selectable modes', () => {
    const fixture = render();
    fixture.componentRef.setInput('capabilities', { ...ROVER_CAPABILITY, selectableModes: [] });
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.command-row')).toBeNull();
  });
});

describe('ModePicker — "also on" switch hint (docs/plans/active/CONTROLLER-UX-PLAN.md decision U3)', () => {
  it('renders no hint when no switch fires SET_MODE', () => {
    const fixture = render();
    expect(fixture.nativeElement.textContent).not.toContain('also on');
  });

  it('renders the hint beside the Set button, verbatim', () => {
    const fixture = render();
    fixture.componentRef.setInput('modeAlsoOn', 'Axis 5');
    fixture.detectChanges();

    const row = fixture.nativeElement.querySelector('.command-row');
    expect(row?.textContent).toContain('also on Axis 5');
  });
});
