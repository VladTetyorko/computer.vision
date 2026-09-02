import { TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';
import { ArmConfirmDialog } from './arm-confirm-dialog';

/**
 * Covers the one behaviour added by docs/plans/active/FLY-CONTROL-UX-PLAN.md §2 —
 * "additionally shows the live neutral state and refuses to reach its final step while the gate
 * holds" — since it's new interactive gating a pure spec can't reach (the copy itself is
 * `flight-command-panel-logic.spec.ts#armWarningMessage`/`armFinalConfirmLabel`, unit-tested there).
 * No spec previously existed for this component; this file does not attempt full coverage of the
 * pre-existing two-stage mechanics, only what §2 changed.
 */

function render() {
  TestBed.configureTestingModule({});
  const fixture = TestBed.createComponent(ArmConfirmDialog);
  fixture.componentRef.setInput('assetDisplayName', 'Falcon 1');
  fixture.detectChanges();
  return fixture;
}

describe('ArmConfirmDialog — sticks not neutral (docs/plans/active/FLY-CONTROL-UX-PLAN.md §2)', () => {
  it('shows the plain hint and an enabled Continue when sticks are neutral (no reason)', async () => {
    const fixture = render();
    await fixture.whenStable();

    expect(fixture.nativeElement.querySelector('.arm-hint')?.textContent).toContain('Stand clear');
    expect(fixture.nativeElement.querySelector('.arm-blocked')).toBeNull();
    const continueBtn: HTMLButtonElement = fixture.nativeElement.querySelector('.dialog-actions .btn.secondary');
    expect(continueBtn.disabled).toBe(false);
  });

  it('shows the live reason in place of the hint and disables Continue while sticks are not neutral', async () => {
    const fixture = render();
    fixture.componentRef.setInput('sticksNotNeutralReason', 'Throttle 62% — center sticks to arm');
    fixture.detectChanges();
    await fixture.whenStable();

    expect(fixture.nativeElement.querySelector('.arm-hint')).toBeNull();
    expect(fixture.nativeElement.querySelector('.arm-blocked')?.textContent).toBe(
      'Throttle 62% — center sticks to arm',
    );
    const continueBtn: HTMLButtonElement = fixture.nativeElement.querySelector('.dialog-actions .btn.secondary');
    expect(continueBtn.disabled).toBe(true);
  });

  it('continueToFinalStage() is a no-op while the reason holds, even if the disabled button is somehow reached', async () => {
    const fixture = render();
    fixture.componentRef.setInput('sticksNotNeutralReason', 'Throttle 62% — center sticks to arm');
    fixture.detectChanges();
    await fixture.whenStable();

    (fixture.componentInstance as unknown as { continueToFinalStage(): void }).continueToFinalStage();
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.btn.arm-final')).toBeNull();
  });

  it('reaches the final stage and shows a live, enabled Arm button once sticks are neutral', async () => {
    const fixture = render();
    await fixture.whenStable();

    (fixture.componentInstance as unknown as { continueToFinalStage(): void }).continueToFinalStage();
    fixture.detectChanges();

    const armBtn: HTMLButtonElement = fixture.nativeElement.querySelector('.btn.arm-final');
    expect(armBtn).not.toBeNull();
    expect(armBtn.disabled).toBe(false);
  });

  it('re-blocks the final stage live if a stick drifts off-neutral after Continue was pressed', async () => {
    const fixture = render();
    await fixture.whenStable();
    (fixture.componentInstance as unknown as { continueToFinalStage(): void }).continueToFinalStage();
    fixture.detectChanges();

    fixture.componentRef.setInput('sticksNotNeutralReason', 'Throttle 40% — center sticks to arm');
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.arm-blocked')?.textContent).toBe(
      'Throttle 40% — center sticks to arm',
    );
    const armBtn: HTMLButtonElement = fixture.nativeElement.querySelector('.btn.arm-final');
    expect(armBtn.disabled).toBe(true);
  });

  it('emits cancelled from either stage regardless of the gate — Cancel is never blocked', async () => {
    const fixture = render();
    fixture.componentRef.setInput('sticksNotNeutralReason', 'Throttle 62% — center sticks to arm');
    fixture.detectChanges();
    await fixture.whenStable();
    const handler = vi.fn();
    fixture.componentInstance.cancelled.subscribe(handler);

    const cancelBtn: HTMLButtonElement = fixture.nativeElement.querySelectorAll('.dialog-actions .btn.secondary')[1];
    cancelBtn.click();

    expect(handler).toHaveBeenCalledTimes(1);
  });
});
