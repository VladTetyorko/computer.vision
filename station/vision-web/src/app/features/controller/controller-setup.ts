import { ChangeDetectionStrategy, Component, OnInit, computed, effect, inject, signal, untracked } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { SectionHeader } from '../../shared/ui/section-header';
import { EmptyState } from '../../shared/ui/empty-state';
import { IconButton } from '../../shared/ui/icon-button';
import { Icon } from '../../shared/ui/icon';
import { ConfirmDialog } from '../../shared/ui/confirm-dialog';
import { UiStore } from '../../core/ui/ui-store';
import { RcInputService } from '../../core/rc/rc-input.service';
import { stepStatus, wizardSteps, type WizardStep as WizardStepModel } from '../../core/rc/controller-wizard-logic';
import { channelOutputs, type ChannelOutput } from '../../core/rc/channel-output-logic';
import { asStickMode, channelOptions } from '../../core/rc/controller-setup-logic';
import { DEFAULT_STICK_MODE, STICK_MODES, type StickMode } from '../../core/rc/controller-diagram-logic';
import { ControllerSetupFacade } from './controller-setup-facade';
import { StepRail, type StepRailItem } from '../../shared/ui/step-rail';
import { WizardStep as WizardStepComponent } from './wizard-step';
import { AllControls } from './all-controls';
import type { ControlProfile, VehicleKind } from '../../core/api/models';

/**
 * `/manage/controller` (docs/plans/active/CONTROLLER-SETUP-CONTEXT.md C11,
 * docs/plans/active/CONTROLLER-UX-PLAN.md §2.3 wave X4) — where an operator says what each stick,
 * switch and button on their transmitter does.
 *
 * <h2>A wizard first, a flat editor underneath</h2>
 * The page is two views over the same draft: `vision-step-rail` + `vision-wizard-step` walk a fresh
 * layout through one control at a time — the on-ramp an operator who has never done this before
 * needs. `vision-all-controls`, collapsed by default behind "All controls", is the original flat
 * editor unchanged — everything at once, for an operator who already knows their transmitter or
 * needs to reach a control the fixed step list does not visit. Neither view owns the draft; both
 * read and write `ControllerSetupFacade` the same way, so switching between them mid-edit never
 * loses anything.
 *
 * <h2>Nothing here commands anything</h2>
 * Editing writes to a local draft; `PUT` happens on Save, and even then a saved layout does nothing
 * until it is activated. Every picker is filled from `GET /api/control-profiles/catalog` (decision
 * C8) — this page hardcodes no action, no function and no switch level, so it cannot offer something
 * the server would then refuse.
 */
@Component({
  selector: 'vision-controller-setup-page',
  imports: [FormsModule, SectionHeader, EmptyState, IconButton, Icon, ConfirmDialog, StepRail, WizardStepComponent, AllControls],
  templateUrl: './controller-setup.html',
  styleUrl: './controller-setup.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [ControllerSetupFacade, RcInputService],
})
export class ControllerSetupPage implements OnInit {
  protected readonly facade = inject(ControllerSetupFacade);
  protected readonly rc = inject(RcInputService);

  /** The channels worth offering — served, not assumed; see `controller-setup-logic.ts#relayedChannels`. */
  protected readonly channels = computed(() => channelOptions(this.facade.catalog()));

  /**
   * What the station would put on the wire right now, per channel (docs/plans/active/CONTROLLER-SETUP-CONTEXT.md
   * wave C15) — the only place on this page that shows the *output*, which is what makes `reversed` and
   * `travel` checkable on the bench rather than on the first flight. Live regardless of which view
   * (wizard or "All controls") is open, since both write the same draft.
   */
  protected readonly outputs = computed<readonly ChannelOutput[]>(() =>
    channelOutputs(this.facade.draft(), this.rc.axes(), this.rc.buttons(), this.facade.catalog()),
  );

  // --- How the transmitter is drawn --------------------------------------------------------------

  protected readonly stickModes = STICK_MODES;

  /**
   * Which stick holds which function, and which end of a vertical axis is up (wave C15).
   *
   * Neither changes a microsecond on the wire — what the vehicle does is decided entirely by axis →
   * function → channel. Both are nonetheless **saved with the layout**, not kept in this browser:
   * they describe the radio in the operator's hands, and a browser-local answer meant setting a
   * layout up on a laptop and flying it from the ground-station box asked the same question twice.
   *
   * A built-in reports the platform default and cannot be edited; the way to change its drawing is
   * the same as the way to change anything else about it — make a copy.
   */
  protected readonly stickMode = computed<StickMode>(() => this.facade.draft()?.stickMode ?? DEFAULT_STICK_MODE);
  protected readonly positiveIsUp = computed(() => this.facade.draft()?.forwardIsUp ?? true);

  protected setStickMode(mode: string): void {
    this.facade.setStickMode(asStickMode(Number(mode)));
  }

  protected setPositiveIsUp(up: boolean): void {
    this.facade.setForwardIsUp(up);
  }

  /** The delete confirm — one overlay group, per this app's own `UiStore` rule. */
  private readonly dialog = new UiStore();
  protected isConfirming(id: string): boolean {
    return this.dialog.isOpen(id);
  }

  /** The layout bar's own two disclosures ('new-layout' popover, 'all-controls' editor) — a second,
   * separate `UiStore` group from `dialog` (a delete confirm may legitimately be open at the same
   * time as one of these; `UiStore`'s own doc comment: "overlays that may legitimately overlap get
   * separate instances"). Within this group the two stay mutually exclusive — collapsed by default
   * (item 6): the wizard is the on-ramp, the flat editor is the escape hatch. */
  private readonly panels = new UiStore();
  protected isPanelOpen(id: 'new-layout' | 'all-controls'): boolean {
    return this.panels.isOpen(id);
  }
  protected togglePanel(id: 'new-layout' | 'all-controls'): void {
    this.panels.toggle(id);
  }
  protected closePanel(id: 'new-layout' | 'all-controls'): void {
    this.panels.close(id);
  }

  // --- New-layout form ---------------------------------------------------------------------------
  protected readonly newKind = signal<VehicleKind>('ROVER');
  protected readonly newName = signal('');

  /** The built-in for the selected profile's own kind — {@link wizardSteps}' totality source, so a
   * rover only ever gets rover steps and a copter only ever gets copter steps. */
  protected readonly builtInForKind = computed<ControlProfile | undefined>(() => {
    const kind = this.facade.selected()?.kind;
    return kind ? this.facade.profiles().find((p) => p.kind === kind && p.source === 'BUILT_IN') : undefined;
  });

  protected readonly steps = computed<readonly WizardStepModel[]>(() => {
    const profile = this.facade.selected();
    return profile ? wizardSteps(profile.kind, this.builtInForKind(), this.facade.catalog()) : [];
  });

  protected readonly currentStepIndex = signal(0);

  /** `shared/ui/step-rail.ts#StepRail`'s own `items` input — resolves each step's `done`-ness here
   *  (via `stepStatus`) since the shared rail no longer knows about `WizardStepModel`/`ProfileDraft`
   *  at all (see that component's own class doc for why it was generalized this way). `undefined`
   *  draft (no layout selected yet) never reaches this: the rail only renders once `facade.draft()`
   *  is truthy (`controller-setup.html`'s own `@if`), same guard as before this lift. */
  protected readonly railItems = computed<readonly StepRailItem[]>(() => {
    const draft = this.facade.draft();
    if (!draft) {
      return [];
    }
    return this.steps().map((step) => ({
      id: step.id,
      label: step.title,
      done: stepStatus(step, draft) === 'done',
    }));
  });

  constructor() {
    // A freshly opened (or newly created) layout always starts its wizard at step one.
    effect(() => {
      this.facade.selected()?.id;
      untracked(() => this.currentStepIndex.set(0));
    });
  }

  ngOnInit(): void {
    this.rc.start();
    void this.facade.load();
  }

  protected goNext(): void {
    this.currentStepIndex.update((i) => Math.min(i + 1, Math.max(this.steps().length - 1, 0)));
  }

  protected goBack(): void {
    this.currentStepIndex.update((i) => Math.max(i - 1, 0));
  }

  /** Save, then activate only once the save actually landed — a failed save already told the
   * operator why (`ControllerSetupFacade#save`'s own toast); activating a layout that is still
   * dirty would engage something that was never persisted. */
  protected async onSaveAndActivate(): Promise<void> {
    await this.facade.save();
    const profile = this.facade.selected();
    if (profile && !this.facade.dirty()) {
      await this.facade.activate(profile.id);
    }
  }

  // --- Profile actions -----------------------------------------------------------------------

  protected async create(): Promise<void> {
    const name = this.newName().trim();
    if (name.length === 0) {
      return;
    }
    await this.facade.createFrom(this.newKind(), name);
    this.newName.set('');
    this.panels.close('new-layout');
  }

  protected async copySelected(): Promise<void> {
    const profile = this.facade.selected();
    if (profile) {
      await this.facade.createFrom(profile.kind, `${profile.name} copy`);
    }
  }

  protected requestDelete(id: string): void {
    this.dialog.open(id);
  }

  protected cancelDelete(): void {
    this.dialog.close();
  }

  protected async confirmDelete(id: string): Promise<void> {
    this.dialog.close();
    await this.facade.remove(id);
  }
}
