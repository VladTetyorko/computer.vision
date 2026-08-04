import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { ReturnHomeButton } from './return-home-button';
import { VisionApi } from '../../core/api/vision-api';
import { ToastService } from '../../core/toast.service';
import type { ReturnHomeResponse } from '../../core/api/models';

/**
 * `ReturnHomeButton`'s confirm (docs/UI-STATE-PLAN.md §2.4) — `VisionApi`/`ToastService` are both
 * faked (no HTTP), mirroring `shared/ui/notification-bell.spec.ts`'s own "fake every transitive
 * dependency purely so the tree can mount" approach. `returnHomeToastFor`'s own outcome-mapping is
 * already covered by `return-home-button-logic.spec.ts`; this file only exercises the confirm's own
 * open/close lifecycle — specifically the new Escape/outside-click behavior — not the HTTP outcome.
 * `returnHome` deliberately never resolves — every test here either cancels before it would be called,
 * or (the "busy" case) only needs the request to be genuinely *in flight*, never settled.
 */
function render() {
  // Reset first: every case below creates its own fixture — `configureTestingModule` throws once a
  // fixture already exists in the current module (mirrors `page-bar.spec.ts`'s own `render` helper).
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({
    providers: [
      { provide: VisionApi, useValue: { returnHome: () => new Promise<ReturnHomeResponse>(() => {}) } },
      { provide: ToastService, useValue: { ok: () => {}, warn: () => {}, error: () => {} } },
    ],
  });
  const fixture = TestBed.createComponent(ReturnHomeButton);
  fixture.componentRef.setInput('assetId', 'a-1');
  fixture.componentRef.setInput('assetDisplayName', 'Drone 1');
  fixture.componentRef.setInput('canCommand', true);
  fixture.detectChanges();
  return fixture;
}

function triggerBtn(fixture: { nativeElement: HTMLElement }): HTMLButtonElement {
  return fixture.nativeElement.querySelector('.return-home-btn') as HTMLButtonElement;
}

function dialogCard(fixture: { nativeElement: HTMLElement }): HTMLElement | null {
  return fixture.nativeElement.querySelector('.dialog');
}

describe('ReturnHomeButton — confirm lifecycle (docs/UI-STATE-PLAN.md §2.4)', () => {
  it('opens the confirm on click, closes on Escape without sending the command', () => {
    const fixture = render();
    triggerBtn(fixture).click();
    fixture.detectChanges();
    expect(dialogCard(fixture)).not.toBeNull();

    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
    fixture.detectChanges();

    expect(dialogCard(fixture)).toBeNull();
  });

  it('Escape returns focus to the trigger button', () => {
    const fixture = render();
    triggerBtn(fixture).click();
    fixture.detectChanges();

    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
    fixture.detectChanges();

    expect(document.activeElement).toBe(triggerBtn(fixture));
  });

  it('a click on the backdrop (outside the .dialog card) cancels the confirm', () => {
    const fixture = render();
    triggerBtn(fixture).click();
    fixture.detectChanges();

    fixture.nativeElement.querySelector('.backdrop')!.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    fixture.detectChanges();

    expect(dialogCard(fixture)).toBeNull();
  });

  it('a click inside the .dialog card (e.g. its own message text) never cancels the confirm', () => {
    const fixture = render();
    triggerBtn(fixture).click();
    fixture.detectChanges();

    dialogCard(fixture)!.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    fixture.detectChanges();

    expect(dialogCard(fixture)).not.toBeNull();
  });

  it('neither Escape nor an outside click closes the confirm while the request is in flight (busy)', () => {
    const fixture = render();
    triggerBtn(fixture).click();
    fixture.detectChanges();

    const confirmBtn = fixture.nativeElement.querySelector('.dialog .btn.danger') as HTMLButtonElement;
    confirmBtn.click(); // Confirm — leaves it "busy"
    fixture.detectChanges();
    expect(confirmBtn.disabled).toBe(true);

    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
    fixture.detectChanges();
    expect(dialogCard(fixture), 'Escape must not interrupt an in-flight request').not.toBeNull();

    fixture.nativeElement.querySelector('.backdrop')!.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    fixture.detectChanges();
    expect(dialogCard(fixture), 'an outside click must not interrupt an in-flight request either').not.toBeNull();
  });
});
