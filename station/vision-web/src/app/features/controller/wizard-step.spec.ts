import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { WizardStep } from './wizard-step';
import type { ProfileDraft } from '../../core/rc/controller-setup-logic';
import type { KnownModeNames, WizardStep as WizardStepModel } from '../../core/rc/controller-wizard-logic';
import type { ControlCatalog } from '../../core/api/models';

const THROTTLE_STEP: WizardStepModel = {
  id: 'channel-throttle',
  kind: 'CHANNEL',
  title: 'Throttle',
  instruction: 'Move the throttle all the way up, then all the way down.',
  function: 'THROTTLE',
  defaultChannel: 3,
  defaultTravel: 'UNIDIRECTIONAL',
};

const ARM_STEP: WizardStepModel = {
  id: 'arm',
  kind: 'ARM',
  title: 'Arm',
  instruction: 'Flip the switch you want to arm with.',
  caveat: 'Arm from a switch needs a hold — a stray flick will not arm the vehicle.',
};

function draft(): ProfileDraft {
  return { id: 'p1', kind: 'ROVER', name: 'Bench layout', controls: [] };
}

function render() {
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({});
  const fixture = TestBed.createComponent(WizardStep);
  fixture.componentRef.setInput('step', THROTTLE_STEP);
  fixture.componentRef.setInput('stepNumber', 1);
  fixture.componentRef.setInput('stepCount', 6);
  fixture.componentRef.setInput('draft', draft());
  return fixture;
}

function selectedTileText(fixture: ReturnType<typeof render>): string[] {
  return Array.from(fixture.nativeElement.querySelectorAll('.tile.selected')).map(
    (el) => (el as HTMLElement).textContent?.trim() ?? '',
  );
}

describe('WizardStep', () => {
  it('preselects the built-in rest and flips direction to match what the operator actually moved', () => {
    const fixture = render();
    fixture.componentRef.setInput('connected', true);
    fixture.componentRef.setInput('axes', [0, 0, 0]);
    fixture.componentRef.setInput('buttons', []);
    fixture.detectChanges();

    // Before anything moves: direction defaults to "not reversed" (nothing detected yet), and the
    // built-in's own rest (UNIDIRECTIONAL — "idle is 0%") is already selected either way.
    expect(selectedTileText(fixture)).toEqual(['Up is more', 'At the bottom — idle is 0 %']);

    // The operator pushes the throttle axis (index 2) down first — a negative first excursion.
    fixture.componentRef.setInput('axes', [0, 0, -1]);
    fixture.detectChanges();
    fixture.componentRef.setInput('axes', [0, 0, 1]);
    fixture.detectChanges();

    // Travelled -1 -> 1 clears the detect threshold, and the negative first move reads as "reversed".
    expect(fixture.nativeElement.querySelector('.gauge-vertical')).toBeTruthy();
    expect(selectedTileText(fixture)).toEqual(
      expect.arrayContaining(['Up is less', 'At the bottom — idle is 0 %']),
    );
  });

  it('Next writes the detected control into a new draft and always advances', () => {
    const fixture = render();
    fixture.componentRef.setInput('connected', true);
    fixture.componentRef.setInput('editable', true);
    fixture.componentRef.setInput('axes', [0, 0, 0]);
    fixture.detectChanges();
    fixture.componentRef.setInput('axes', [0, 0, -1]);
    fixture.detectChanges();
    fixture.componentRef.setInput('axes', [0, 0, 1]);
    fixture.detectChanges();

    let applied: ProfileDraft | undefined;
    let advanced = false;
    fixture.componentInstance.applyDraft.subscribe((d) => (applied = d));
    fixture.componentInstance.advance.subscribe(() => (advanced = true));

    const next = Array.from(fixture.nativeElement.querySelectorAll('button')).find(
      (b) => (b as HTMLElement).textContent?.trim() === 'Next →',
    ) as HTMLButtonElement;
    next.click();

    expect(advanced).toBe(true);
    expect(applied).toBeDefined();
    expect(applied!.controls).toHaveLength(1);
    expect(applied!.controls[0]).toMatchObject({
      source: 'AXIS',
      sourceIndex: 2,
      role: 'CHANNEL',
      function: 'THROTTLE',
      reversed: true,
    });
  });

  it('lets an operator with no gamepad plugged in name the control manually', () => {
    const fixture = render();
    fixture.componentRef.setInput('connected', false);
    fixture.componentRef.setInput('editable', true);
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.manual-pick')).toBeTruthy();

    // Pick axis index 2 by hand instead of moving anything.
    (fixture.componentInstance as unknown as { manualIndex: { set(v: number): void } }).manualIndex.set(2);
    const useThis = Array.from(fixture.nativeElement.querySelectorAll('button')).find(
      (b) => (b as HTMLElement).textContent?.trim() === 'Use this',
    ) as HTMLButtonElement;
    useThis.click();
    fixture.detectChanges();

    let applied: ProfileDraft | undefined;
    fixture.componentInstance.applyDraft.subscribe((d) => (applied = d));
    const next = Array.from(fixture.nativeElement.querySelectorAll('button')).find(
      (b) => (b as HTMLElement).textContent?.trim() === 'Next →',
    ) as HTMLButtonElement;
    next.click();

    expect(applied?.controls[0]).toMatchObject({ source: 'AXIS', sourceIndex: 2, function: 'THROTTLE' });
  });

  it('a built-in layout is read-only — one "Make a copy to edit" action, Next never writes to the draft', () => {
    const fixture = render();
    fixture.componentRef.setInput('step', ARM_STEP);
    fixture.componentRef.setInput('editable', false);
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.tile-group')).toBeFalsy();
    expect(fixture.nativeElement.querySelector('.position-list')).toBeFalsy();

    let copyRequested = false;
    let applied: ProfileDraft | undefined;
    let advanced = false;
    fixture.componentInstance.copyRequested.subscribe(() => (copyRequested = true));
    fixture.componentInstance.applyDraft.subscribe((d) => (applied = d));
    fixture.componentInstance.advance.subscribe(() => (advanced = true));

    const copyButton = Array.from(fixture.nativeElement.querySelectorAll('button')).find(
      (b) => (b as HTMLElement).textContent?.trim() === 'Make a copy to edit',
    ) as HTMLButtonElement;
    copyButton.click();
    expect(copyRequested).toBe(true);

    const next = Array.from(fixture.nativeElement.querySelectorAll('button')).find(
      (b) => (b as HTMLElement).textContent?.trim() === 'Next →',
    ) as HTMLButtonElement;
    next.click();

    expect(advanced).toBe(true);
    expect(applied).toBeUndefined();
  });
});

describe('WizardStep — Mode step known names (CONTROLLER-UX-PLAN.md §5 wave M)', () => {
  const MODE_STEP: WizardStepModel = { id: 'mode', kind: 'MODE', title: 'Mode', instruction: 'Flip your mode switch.' };

  const CATALOG: ControlCatalog = {
    vehicleKinds: [{ name: 'ROVER', label: 'Rover' }],
    inputKinds: [{ name: 'BUTTON', label: 'Button', sources: ['BUTTON'], positions: ['LOW', 'HIGH'] }],
    positions: [
      { name: 'LOW', label: 'Low', level: 0 },
      { name: 'HIGH', label: 'High', level: 2 },
    ],
    functions: [],
    actions: [{ name: 'SET_MODE', label: 'Set mode', parameter: 'MODE_NAME', dangerous: false }],
    auxFunctions: [],
  };

  function renderModeStep() {
    const fixture = render();
    fixture.componentRef.setInput('step', MODE_STEP);
    fixture.componentRef.setInput('catalog', CATALOG);
    fixture.componentRef.setInput('connected', true);
    fixture.componentRef.setInput('editable', true);
    // A button flicked from released to pressed — enough travel for `detectedControl` to name it.
    fixture.componentRef.setInput('buttons', [0]);
    fixture.detectChanges();
    fixture.componentRef.setInput('buttons', [1]);
    fixture.detectChanges();
    return fixture;
  }

  function pickSetMode(fixture: ReturnType<typeof renderModeStep>, parameter: string): void {
    (fixture.componentInstance as unknown as { onPositionAction(p: string, v: string): void }).onPositionAction(
      'HIGH',
      'SET_MODE',
    );
    fixture.detectChanges();
    (fixture.componentInstance as unknown as { onPositionParameter(p: string, v: string): void }).onPositionParameter(
      'HIGH',
      parameter,
    );
    fixture.detectChanges();
  }

  it('populates the datalist from knownModeNames and shows how many vehicles it came from', () => {
    const fixture = renderModeStep();
    const known: KnownModeNames = { names: ['LOITER', 'HOLD'], assetCount: 2 };
    fixture.componentRef.setInput('knownModeNames', known);
    fixture.detectChanges();

    const options = Array.from(fixture.nativeElement.querySelectorAll('#wizard-mode-names option')).map(
      (o) => (o as HTMLOptionElement).value,
    );
    expect(options).toEqual(['LOITER', 'HOLD']);
    expect(fixture.nativeElement.querySelector('.mode-source-hint')?.textContent?.trim()).toBe(
      'Names from 2 online vehicles',
    );
  });

  it('falls back to an honest type-it-yourself hint when nothing is known yet', () => {
    const fixture = renderModeStep();
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelectorAll('#wizard-mode-names option').length).toBe(0);
    expect(fixture.nativeElement.querySelector('.mode-source-hint')?.textContent?.trim()).toBe(
      'No online vehicle to read mode names from — type one, e.g. LOITER',
    );
  });

  it('shows a quiet, non-blocking note when the typed mode name is not one this vehicle reported', () => {
    const fixture = renderModeStep();
    fixture.componentRef.setInput('knownModeNames', { names: ['LOITER', 'HOLD'], assetCount: 1 } satisfies KnownModeNames);
    fixture.detectChanges();

    pickSetMode(fixture, 'CRUISE');

    expect(fixture.nativeElement.querySelector('.mode-unknown-note')?.textContent?.trim()).toBe(
      'Not a mode this vehicle reported',
    );
    // Quiet, not blocking — Next is still enabled and reachable.
    const next = Array.from(fixture.nativeElement.querySelectorAll('button')).find(
      (b) => (b as HTMLElement).textContent?.trim() === 'Next →',
    ) as HTMLButtonElement;
    expect(next.disabled).toBe(false);
  });

  it('shows no note once the typed value matches a known mode name', () => {
    const fixture = renderModeStep();
    fixture.componentRef.setInput('knownModeNames', { names: ['LOITER', 'HOLD'], assetCount: 1 } satisfies KnownModeNames);
    fixture.detectChanges();

    pickSetMode(fixture, 'LOITER');

    expect(fixture.nativeElement.querySelector('.mode-unknown-note')).toBeFalsy();
  });
});
