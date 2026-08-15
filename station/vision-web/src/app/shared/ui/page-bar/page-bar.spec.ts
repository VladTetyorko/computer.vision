import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { describe, expect, it } from 'vitest';
import { PageBar, pluralize } from './page-bar';

/**
 * Host harness — `PageBar` is a projection component, so the slots can only be exercised through a
 * real host template (the same pattern `shared/ui/kebab-menu.spec.ts` uses).
 */
@Component({
  imports: [PageBar],
  template: `
    <vision-page-bar
      [title]="title()"
      [icon]="icon()"
      [count]="count()"
      [countNoun]="countNoun()"
      [hint]="hint()"
      [crumb]="crumb()"
      [avatarSrc]="avatarSrc()"
    >
      @if (withFilters()) {
        <input pageBarFilters name="q" />
      }
      @if (withActions()) {
        <button pageBarActions type="button">Add</button>
      }
    </vision-page-bar>
  `,
})
class Host {
  // Signals, not plain fields: two cases below mutate an input *after* the first `detectChanges()`
  // to prove the component reacts to a changed input, and a plain field mutated between Angular's
  // change-detection and its dev-mode check-no-changes pass trips NG0100.
  readonly title = signal('Assets');
  readonly icon = signal<'drone' | null>(null);
  readonly count = signal<number | null>(null);
  readonly countNoun = signal('asset');
  readonly hint = signal<string | null>(null);
  readonly crumb = signal<{ label: string; to: string } | null>(null);
  readonly avatarSrc = signal<string | null>(null);
  readonly withFilters = signal(false);
  readonly withActions = signal(false);
}

function render(setup: (host: Host) => void = () => {}) {
  // Reset first: two of the cases below render twice to contrast an absent input with a present one,
  // and `configureTestingModule` throws once a fixture already exists in the current module.
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({ providers: [provideRouter([])] });
  const fixture = TestBed.createComponent(Host);
  setup(fixture.componentInstance);
  fixture.detectChanges();
  return fixture;
}

describe('pluralize', () => {
  it('keeps the singular at exactly one', () => {
    expect(pluralize(1, 'asset')).toBe('1 asset');
  });

  it('pluralises zero and many — the `asset(s)` parenthetical this replaces did neither', () => {
    expect(pluralize(0, 'asset')).toBe('0 assets');
    expect(pluralize(12, 'asset')).toBe('12 assets');
  });

  it('takes an explicit irregular plural', () => {
    expect(pluralize(2, 'entry', 'entries')).toBe('2 entries');
  });
});

describe('PageBar', () => {
  it('renders the title', () => {
    const el = render().nativeElement as HTMLElement;
    expect(el.querySelector('.page-bar-name')?.textContent?.trim()).toBe('Assets');
  });

  it('renders no count chip when count is null, but does render one at zero', () => {
    expect(render().nativeElement.querySelector('.page-bar-count')).toBeNull();

    const withZero = render((host) => host.count.set(0));
    expect(withZero.nativeElement.querySelector('.page-bar-count').textContent.trim()).toBe('0 assets');
  });

  it('hides the hint body until the trigger is clicked', () => {
    const fixture = render((host) => host.hint.set('Only affects your account.'));
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('.page-bar-hint-body')).toBeNull();

    el.querySelector<HTMLButtonElement>('.page-bar-hint-trigger')!.click();
    fixture.detectChanges();
    expect(el.querySelector('.page-bar-hint-body')?.textContent).toContain('Only affects your account.');
  });

  it('renders no hint affordance at all when no hint is supplied', () => {
    expect(render().nativeElement.querySelector('.page-bar-hint-trigger')).toBeNull();
  });

  // docs/plans/done/UI-STATE-PLAN.md §2.4 — before this, the hint popover only closed by clicking its own
  // trigger a second time; an operator hitting Escape (or clicking anywhere else) expects it gone.
  it('closes the hint on Escape, from anywhere in the document', () => {
    const fixture = render((host) => host.hint.set('Only affects your account.'));
    const el = fixture.nativeElement as HTMLElement;
    el.querySelector<HTMLButtonElement>('.page-bar-hint-trigger')!.click();
    fixture.detectChanges();
    expect(el.querySelector('.page-bar-hint-body')).not.toBeNull();

    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    fixture.detectChanges();
    expect(el.querySelector('.page-bar-hint-body')).toBeNull();
  });

  it('closes the hint on a click outside it, but a click inside the popover leaves it open', () => {
    const fixture = render((host) => host.hint.set('Only affects your account.'));
    const el = fixture.nativeElement as HTMLElement;
    el.querySelector<HTMLButtonElement>('.page-bar-hint-trigger')!.click();
    fixture.detectChanges();

    el.querySelector<HTMLElement>('.page-bar-hint-body')!.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    fixture.detectChanges();
    expect(el.querySelector('.page-bar-hint-body'), 'a click inside the popover must not close it').not.toBeNull();

    document.body.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    fixture.detectChanges();
    expect(el.querySelector('.page-bar-hint-body')).toBeNull();
  });

  it('does not reopen the hint from its own trigger click bubbling to the document listener', () => {
    const fixture = render((host) => host.hint.set('Only affects your account.'));
    const el = fixture.nativeElement as HTMLElement;
    const trigger = el.querySelector<HTMLButtonElement>('.page-bar-hint-trigger')!;
    trigger.click();
    fixture.detectChanges();
    expect(el.querySelector('.page-bar-hint-body')).not.toBeNull();

    // The trigger's own (click) toggles it closed first (target-phase, runs before the document
    // listener sees the same bubbling event) — the document handler must read that as "already
    // closed" and leave it closed, never re-toggle it back open.
    trigger.click();
    fixture.detectChanges();
    expect(el.querySelector('.page-bar-hint-body')).toBeNull();
  });

  it('projects filters and actions into their own slots', () => {
    const el = render((host) => {
      host.withFilters.set(true);
      host.withActions.set(true);
    }).nativeElement as HTMLElement;

    expect(el.querySelector('.page-bar-filters input')).not.toBeNull();
    expect(el.querySelector('.page-bar-actions button')).not.toBeNull();
  });

  // The asset photo (docs/plans/done/UX-REWORK-PLAN.md §U-d item 3) lives here; the endpoint 404s for an asset
  // with no uploaded photo, so a failed load must hide the element rather than leave a torn icon.
  it('renders the avatar when given a src, and hides it once that src fails to load', () => {
    const fixture = render((host) => host.avatarSrc.set('/api/assets/abc/image'));
    const el = fixture.nativeElement as HTMLElement;
    const img = el.querySelector<HTMLImageElement>('.page-bar-avatar');
    expect(img).not.toBeNull();

    img!.dispatchEvent(new Event('error'));
    fixture.detectChanges();
    expect(el.querySelector('.page-bar-avatar')).toBeNull();
  });

  it('retries on a different asset — the failure is keyed on the src, not a sticky boolean', () => {
    const fixture = render((host) => host.avatarSrc.set('/api/assets/abc/image'));
    const el = fixture.nativeElement as HTMLElement;
    el.querySelector<HTMLImageElement>('.page-bar-avatar')!.dispatchEvent(new Event('error'));
    fixture.detectChanges();
    expect(el.querySelector('.page-bar-avatar')).toBeNull();

    fixture.componentInstance.avatarSrc.set('/api/assets/xyz/image');
    fixture.detectChanges();
    expect(el.querySelector('.page-bar-avatar')).not.toBeNull();
  });

  it('renders no avatar element when no src is given', () => {
    expect(render().nativeElement.querySelector('.page-bar-avatar')).toBeNull();
  });

  it('renders a breadcrumb link only when one is given', () => {
    expect(render().nativeElement.querySelector('.page-bar-crumb')).toBeNull();

    const el = render((host) => host.crumb.set({ label: 'Assets', to: '/assets' })).nativeElement as HTMLElement;
    const crumb = el.querySelector<HTMLAnchorElement>('.page-bar-crumb');
    expect(crumb?.getAttribute('href')).toBe('/assets');
  });
});
