import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { Icon } from './icon';
import { ICONS, type IconName } from './icon-registry';

// The frozen F2 name set (docs/plans/done/UI-REDESIGN-PLAN.md) — every glyph any wave needs at launch. This
// list must stay a subset of `Object.keys(ICONS)` forever (additive-only); it is not required to be
// an exact match if a later wave adds a name, so the assertion below only checks "at least these".
const FROZEN_NAMES: readonly IconName[] = [
  'home', 'cockpit', 'eye', 'scan', 'layers', 'list', 'help', 'close', 'plus', 'edit', 'archive',
  'trash', 'kebab', 'chevron-left', 'chevron-right', 'chevron-down', 'chevron-up', 'grid', 'bell',
  'user', 'settings', 'gear', 'operate', 'monitor', 'manage', 'logout', 'building-org',
  'drone', 'battery', 'satellite', 'compass', 'gauge', 'signal', 'wind', 'thermometer', 'map-pin',
  'ruler', 'power',
  'alert', 'replay', 'history', 'wrench', 'chip', 'firmware', 'report', 'category', 'warehouse',
  'source', 'pilot', 'map',
  // docs/plans/active/RC-CONTROL-PLAN.md Phase 0 — the RC transmitter monitor's tool-rail glyph.
  'gamepad',
  // docs/plans/done/TACTICAL-MARKS-PLAN.md M5 — TARGET's crosshair and FRIENDLY's flag.
  'target', 'flag',
  // docs/plans/done/OPS-UX-PLAN.md §3 B1 — the "Audit trail" nav entry's glyph.
  'shield',
  // docs/plans/done/OPS-UX-PLAN.md §3 B2 — the setup checklist's own "done" tick.
  'check',
];

describe('ICONS registry', () => {
  it('defines non-empty markup for every frozen F2 IconName', () => {
    for (const name of FROZEN_NAMES) {
      expect(ICONS[name], `ICONS['${name}']`).toBeDefined();
      expect(ICONS[name].trim().length, `ICONS['${name}'] must not be empty`).toBeGreaterThan(0);
    }
  });

  it('has no extra keys beyond the frozen set (this wave adds exactly the launch set)', () => {
    expect(Object.keys(ICONS).sort()).toEqual([...FROZEN_NAMES].sort());
  });

  it('every entry is inner markup only (no <svg> wrapper — Icon supplies that)', () => {
    for (const name of Object.keys(ICONS) as IconName[]) {
      expect(ICONS[name]).not.toContain('<svg');
    }
  });
});

describe('Icon', () => {
  it('renders a 24x24-viewBox svg, 16px by default', () => {
    TestBed.configureTestingModule({});
    const fixture = TestBed.createComponent(Icon);
    fixture.componentRef.setInput('name', 'home');
    fixture.detectChanges();

    const svg = fixture.nativeElement.querySelector('svg') as SVGSVGElement;
    expect(svg.getAttribute('viewBox')).toBe('0 0 24 24');
    expect(svg.getAttribute('width')).toBe('16');
    expect(svg.getAttribute('height')).toBe('16');
    expect(svg.getAttribute('stroke')).toBe('currentColor');
    expect(svg.getAttribute('fill')).toBe('none');
  });

  it('honors an explicit size input', () => {
    TestBed.configureTestingModule({});
    const fixture = TestBed.createComponent(Icon);
    fixture.componentRef.setInput('name', 'close');
    fixture.componentRef.setInput('size', 24);
    fixture.detectChanges();

    const svg = fixture.nativeElement.querySelector('svg') as SVGSVGElement;
    expect(svg.getAttribute('width')).toBe('24');
    expect(svg.getAttribute('height')).toBe('24');
  });

  it('is aria-hidden on the host — decorative, never itself the accessible name', () => {
    TestBed.configureTestingModule({});
    const fixture = TestBed.createComponent(Icon);
    fixture.componentRef.setInput('name', 'bell');
    fixture.detectChanges();

    expect(fixture.nativeElement.getAttribute('aria-hidden')).toBe('true');
  });

  it('renders real markup for every known icon name without throwing', () => {
    TestBed.configureTestingModule({});
    for (const name of Object.keys(ICONS) as IconName[]) {
      const fixture = TestBed.createComponent(Icon);
      fixture.componentRef.setInput('name', name);
      expect(() => fixture.detectChanges()).not.toThrow();
      const svg = fixture.nativeElement.querySelector('svg') as SVGSVGElement;
      expect(svg.children.length, `${name} should render at least one shape element`).toBeGreaterThan(0);
    }
  });
});
