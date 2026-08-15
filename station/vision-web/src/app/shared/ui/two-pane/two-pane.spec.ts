import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { TwoPane } from './two-pane';

/** Host harness — `TwoPane` is a projection component, so its slots need a real host template. */
@Component({
  imports: [TwoPane],
  template: `
    <vision-two-pane [detailOpen]="open()" [detailLabel]="label()" (detailClose)="closes.set(closes() + 1)">
      <table twoPaneList>
        <tbody>
          <tr id="row"></tr>
        </tbody>
      </table>
      @if (open()) {
        <div twoPaneDetail id="detail">Battery 87%</div>
      }
    </vision-two-pane>
  `,
})
class Host {
  // Signals, not plain fields: several cases mutate an input after the first `detectChanges()`, and
  // a plain field mutated between change detection and its dev-mode check-no-changes pass trips NG0100.
  readonly open = signal(false);
  readonly label = signal('Details');
  readonly closes = signal(0);
}

function render(setup: (host: Host) => void = () => {}) {
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({});
  const fixture = TestBed.createComponent(Host);
  setup(fixture.componentInstance);
  fixture.detectChanges();
  return fixture;
}

describe('TwoPane', () => {
  it('renders the list slot always, and the detail pane only when open', () => {
    const closed = render().nativeElement as HTMLElement;
    expect(closed.querySelector('#row')).not.toBeNull();
    expect(closed.querySelector('.two-pane-detail')).toBeNull();
    expect(closed.querySelector('#detail')).toBeNull();

    const open = render((host) => host.open.set(true)).nativeElement as HTMLElement;
    expect(open.querySelector('.two-pane-detail')).not.toBeNull();
    expect(open.querySelector('#detail')?.textContent).toContain('Battery 87%');
  });

  it('labels the detail region for assistive tech', () => {
    const el = render((host) => {
      host.open.set(true);
      host.label.set('Falcon-2');
    }).nativeElement as HTMLElement;

    const aside = el.querySelector('.two-pane-detail')!;
    expect(aside.getAttribute('role')).toBe('complementary');
    expect(aside.getAttribute('aria-label')).toBe('Falcon-2');
    expect(el.querySelector('.two-pane-detail-title')?.textContent?.trim()).toBe('Falcon-2');
  });

  // The host owns selection (a `?sel=` query param) — this component only reports the intent to
  // dismiss, so every affordance below must reach the same output rather than closing itself.
  it('emits detailClose from the close button', () => {
    const fixture = render((host) => host.open.set(true));
    (fixture.nativeElement as HTMLElement).querySelector<HTMLButtonElement>('.two-pane-detail-head button')!.click();
    fixture.detectChanges();
    expect(fixture.componentInstance.closes()).toBe(1);
  });

  it('emits detailClose from the scrim', () => {
    const fixture = render((host) => host.open.set(true));
    (fixture.nativeElement as HTMLElement).querySelector<HTMLElement>('.two-pane-scrim')!.click();
    fixture.detectChanges();
    expect(fixture.componentInstance.closes()).toBe(1);
  });

  it('emits detailClose on Escape from inside the pane', () => {
    const fixture = render((host) => host.open.set(true));
    const aside = (fixture.nativeElement as HTMLElement).querySelector('.two-pane-detail')!;
    aside.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    fixture.detectChanges();
    expect(fixture.componentInstance.closes()).toBe(1);
  });

  it('does not close itself — the host stays in control of the pane', () => {
    const fixture = render((host) => host.open.set(true));
    (fixture.nativeElement as HTMLElement).querySelector<HTMLButtonElement>('.two-pane-detail-head button')!.click();
    fixture.detectChanges();
    // `detailOpen` is still true because the host never flipped it, so the pane is still mounted.
    expect((fixture.nativeElement as HTMLElement).querySelector('.two-pane-detail')).not.toBeNull();
  });
});
