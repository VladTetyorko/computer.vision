import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { FlightCommandPanel } from './flight-command-panel';
import { VisionApi } from '../../core/api/vision-api';
import { ToastService } from '../../core/toast.service';
import type { FlightCapability } from '../../core/api/models';

/**
 * Component-level coverage for `FlightCommandPanel` — now Arm/Disarm only
 * (docs/plans/active/FLY-CONTROL-UX-PLAN.md §3, WEB1): the mode row and its own "also on <switch>"
 * hint moved out to `mode-picker.ts`/`mode-picker.spec.ts` when Arm/Disarm relocated onto the video
 * HUD (the "one live action", R2's industry-consensus framing in §0) and mode picking stayed in the
 * informational rail. `rc-monitor-logic.ts#armAlsoOnHint` owns the hint's actual computation and is
 * unit-tested there without Angular; `flight-command-panel-logic.spec.ts` owns confirm copy and the
 * `armDisableReason` grounding-wins composition. This file only covers this component's own template
 * wiring, per this codebase's "component specs only for wiring a pure spec can't reach" rule
 * (`MODULE.md`).
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

describe('FlightCommandPanel — "also on" switch hint (docs/plans/active/CONTROLLER-UX-PLAN.md decision U3)', () => {
  it('renders no hint when no switch fires the same command', () => {
    const fixture = render();

    expect(fixture.nativeElement.textContent).not.toContain('also on');
  });

  it('renders the arm row hint verbatim, arrow included — this component never reformats it', () => {
    const fixture = render();
    fixture.componentRef.setInput('armAlsoOn', 'Sw 2 ↑');
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.command-row-danger')?.textContent).toContain('also on Sw 2 ↑');
  });
});

describe('FlightCommandPanel — grounded (docs/plans/active/ASSET-FLOWS-PLAN.md §2 "S1 gate semantics", wave WB1)', () => {
  it('leaves Arm enabled and shows no reason when not grounded', () => {
    const fixture = render();

    const armBtn: HTMLButtonElement = fixture.nativeElement.querySelector('.arm-btn');
    expect(armBtn.disabled).toBe(false);
    expect(fixture.nativeElement.querySelector('.disabled-reason')).toBeNull();
  });

  it('disables only Arm — Disarm stays clickable — and renders the reason inline, verbatim', () => {
    const fixture = render();
    fixture.componentRef.setInput('groundedReason', 'Grounded — Prop strike on landing');
    fixture.detectChanges();

    const armBtn: HTMLButtonElement = fixture.nativeElement.querySelector('.arm-btn');
    const buttons: NodeListOf<HTMLButtonElement> = fixture.nativeElement.querySelectorAll('.command-row-danger button');
    const disarmBtn = buttons[1];
    expect(armBtn.disabled).toBe(true);
    expect(disarmBtn.disabled).toBe(false);
    expect(fixture.nativeElement.querySelector('.disabled-reason')?.textContent).toBe('Grounded — Prop strike on landing');
  });

  it('requestArm() is a no-op while grounded, even if the disabled button is somehow reached', () => {
    const fixture = render();
    fixture.componentRef.setInput('groundedReason', 'Grounded — Prop strike on landing');
    fixture.detectChanges();

    (fixture.componentInstance as unknown as { requestArm(): void }).requestArm();
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('vision-arm-confirm-dialog')).toBeNull();
  });
});

describe('FlightCommandPanel — sticks not neutral (docs/plans/active/FLY-CONTROL-UX-PLAN.md §2, wave WEB1)', () => {
  it('disables Arm and shows the sticks reason when sticks are not neutral, with no grounding', () => {
    const fixture = render();
    fixture.componentRef.setInput('sticksNotNeutralReason', 'Throttle 62% — center sticks to arm');
    fixture.detectChanges();

    const armBtn: HTMLButtonElement = fixture.nativeElement.querySelector('.arm-btn');
    expect(armBtn.disabled).toBe(true);
    expect(fixture.nativeElement.querySelector('.disabled-reason')?.textContent).toBe(
      'Throttle 62% — center sticks to arm',
    );
  });

  it('requestArm() is a no-op while sticks are not neutral, even with no grounding', () => {
    const fixture = render();
    fixture.componentRef.setInput('sticksNotNeutralReason', 'Throttle 62% — center sticks to arm');
    fixture.detectChanges();

    (fixture.componentInstance as unknown as { requestArm(): void }).requestArm();
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('vision-arm-confirm-dialog')).toBeNull();
  });

  it('shows the grounding reason, not the sticks one, when both hold at once', () => {
    const fixture = render();
    fixture.componentRef.setInput('groundedReason', 'Grounded — Prop strike on landing');
    fixture.componentRef.setInput('sticksNotNeutralReason', 'Throttle 62% — center sticks to arm');
    fixture.detectChanges();

    const armBtn: HTMLButtonElement = fixture.nativeElement.querySelector('.arm-btn');
    expect(armBtn.disabled).toBe(true);
    expect(fixture.nativeElement.querySelector('.disabled-reason')?.textContent).toBe(
      'Grounded — Prop strike on landing',
    );
  });

  it('leaves Arm enabled when sticks are neutral (reason undefined) and not grounded', () => {
    const fixture = render();
    fixture.componentRef.setInput('sticksNotNeutralReason', undefined);
    fixture.detectChanges();

    const armBtn: HTMLButtonElement = fixture.nativeElement.querySelector('.arm-btn');
    expect(armBtn.disabled).toBe(false);
    expect(fixture.nativeElement.querySelector('.disabled-reason')).toBeNull();
  });
});
