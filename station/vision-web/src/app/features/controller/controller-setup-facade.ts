import { Injectable, computed, inject, signal } from '@angular/core';
import { ControlProfileStore } from '../../core/rc/control-profile-store';
import { ToastService } from '../../core/toast.service';
import { describeHttpError } from '../../core/api-error';
import {
  blankControlDraft,
  draftFrom,
  draftIssues,
  draftKey,
  nextFreeChannel,
  positionsOf,
  toUpdateRequest,
  withKind,
  withPositionAction,
  type ControlDraft,
  type ControlRole,
  type ProfileDraft,
} from '../../core/rc/controller-setup-logic';
import type {
  ControlAction,
  ControlInputKind,
  ControlProfile,
  ControlSource,
  SwitchPosition,
  VehicleKind,
} from '../../core/api/models';

/**
 * `ControllerSetupPage`'s facade (docs/plans/active/CONTROLLER-SETUP-CONTEXT.md C11) — profile
 * selection, the in-progress draft, and every write.
 *
 * <h2>The draft is local until Save</h2>
 * Editing a control changes nothing on the vehicle and nothing on the server; `PUT` happens once,
 * when the operator says so. That is not a UI convenience — a layout is half-finished for most of
 * the time it is being edited, and a live-saving editor would mean a session engaging mid-edit
 * picks up a control the operator has not finished describing.
 *
 * Selecting a different profile with unsaved changes is refused rather than silently discarded
 * ({@link dirty}) — the one place this page can lose work.
 */
@Injectable()
export class ControllerSetupFacade {
  private readonly store = inject(ControlProfileStore);
  private readonly toasts = inject(ToastService);

  private readonly selectedIdSignal = signal<string | undefined>(undefined);
  private readonly draftSignal = signal<ProfileDraft | undefined>(undefined);
  private readonly savingSignal = signal(false);
  private readonly errorSignal = signal<string | undefined>(undefined);

  readonly profiles = this.store.profiles;
  readonly catalog = this.store.catalog;
  readonly loading = this.store.loading;
  readonly loaded = this.store.loaded;
  readonly draft = this.draftSignal.asReadonly();
  readonly saving = this.savingSignal.asReadonly();
  /** The one failure this page reports in place of content — a load that never resolved. */
  readonly errorMessage = this.errorSignal.asReadonly();

  /** The profile being edited, straight from the store, so an activate/delete elsewhere is reflected. */
  readonly selected = computed<ControlProfile | undefined>(() =>
    this.profiles().find((p) => p.id === this.selectedIdSignal()),
  );

  /** Built-ins are read-only (decision C7) — the page offers "copy" instead of an editor. */
  readonly editable = computed(() => this.selected()?.source === 'SAVED');

  readonly issues = computed(() => {
    const draft = this.draftSignal();
    return draft ? draftIssues(draft, this.catalog()) : [];
  });

  /** Whether the draft differs from the profile it was opened from. */
  readonly dirty = computed(() => {
    const draft = this.draftSignal();
    const profile = this.selected();
    if (!draft || !profile) {
      return false;
    }
    return JSON.stringify(toUpdateRequest(draft)) !== JSON.stringify(toUpdateRequest(draftFrom(profile)));
  });

  readonly canSave = computed(() => this.editable() && this.dirty() && this.issues().length === 0);

  async load(): Promise<void> {
    try {
      await this.store.load();
      this.errorSignal.set(undefined);
      if (this.selectedIdSignal() === undefined) {
        this.select(this.profiles().find((p) => p.source === 'SAVED')?.id ?? this.profiles()[0]?.id);
      }
    } catch (error) {
      this.errorSignal.set(describeHttpError(error));
    }
  }

  /** Opens a profile for editing. A no-op while the current draft has unsaved changes. */
  select(id: string | undefined): void {
    if (this.dirty() && id !== this.selectedIdSignal()) {
      this.toasts.warn('Save or discard your changes first.');
      return;
    }
    this.selectedIdSignal.set(id);
    const profile = this.profiles().find((p) => p.id === id);
    this.draftSignal.set(profile ? draftFrom(profile) : undefined);
  }

  /** Throws away the draft and reopens the stored profile. */
  discard(): void {
    const profile = this.selected();
    this.draftSignal.set(profile ? draftFrom(profile) : undefined);
  }

  rename(name: string): void {
    this.patch((draft) => ({ ...draft, name }));
  }

  /** Adds a row for one physical control, or selects the existing row if it already has one. */
  addControl(source: ControlSource, sourceIndex: number): void {
    this.patch((draft) => {
      const control = blankControlDraft(source, sourceIndex, draft);
      const key = draftKey(control);
      if (draft.controls.some((c) => draftKey(c) === key)) {
        return draft;
      }
      return { ...draft, controls: [...draft.controls, control] };
    });
  }

  removeControl(key: string): void {
    this.patch((draft) => ({ ...draft, controls: draft.controls.filter((c) => draftKey(c) !== key) }));
  }

  setKind(key: string, kind: ControlInputKind): void {
    this.patchControl(key, (control) => withKind(control, kind, this.catalog()));
  }

  setRole(key: string, role: ControlRole): void {
    this.patchControl(key, (control) => ({
      ...control,
      role,
      // A row switching to a channel must land on a channel nothing else drives, or the save it is
      // heading for fails on an invariant the operator never chose to break.
      rcChannel: role === 'CHANNEL' ? this.freeChannelFor(key, control) : control.rcChannel,
    }));
  }

  setChannel(key: string, rcChannel: number): void {
    this.patchControl(key, (control) => ({ ...control, rcChannel }));
  }

  setFunction(key: string, fn: ControlDraft['function']): void {
    this.patchControl(key, (control) => ({ ...control, function: fn }));
  }

  setTravel(key: string, travel: ControlDraft['travel']): void {
    this.patchControl(key, (control) => ({ ...control, travel }));
  }

  setReversed(key: string, reversed: boolean): void {
    this.patchControl(key, (control) => ({ ...control, reversed }));
  }

  setPositionAction(key: string, position: SwitchPosition, action: ControlAction | undefined): void {
    this.patchControl(key, (control) =>
      withPositionAction(control, position, action, null, positionsOf(this.catalog(), control.kind)),
    );
  }

  /**
   * Replaces the whole draft in one shot — the setup wizard's own write path
   * (docs/plans/active/CONTROLLER-UX-PLAN.md §2.3, wave X4), which computes a new draft with
   * `controller-wizard-logic.ts`'s pure `applyChannelStep`/`applyActionStep` rather than driving the
   * per-field setters above one at a time.
   *
   * Not a workaround: those two functions keep invariants chaining the setters cannot reproduce —
   * `applyChannelStep` *drops* a different control's now-superseded binding of the same function
   * entirely (there is no "unbind this other control" setter), and both functions replace a
   * re-roled control's `positions` outright, where `setRole` leaves whatever the row's previous role
   * had there. Reaching the same end state through the setters would mean, for every wizard step,
   * first finding and removing the stale binding by hand and then re-deriving `positions: []`
   * separately — strictly more code to keep in sync with the same two pure functions, not less.
   */
  replaceDraft(draft: ProfileDraft): void {
    this.draftSignal.set(draft);
  }

  setPositionParameter(key: string, position: SwitchPosition, parameter: string): void {
    this.patchControl(key, (control) => {
      const current = control.positions.find((p) => p.position === position);
      return current === undefined
        ? control
        : withPositionAction(control, position, current.action, parameter, positionsOf(this.catalog(), control.kind));
    });
  }

  async save(): Promise<void> {
    const draft = this.draftSignal();
    if (!draft || !this.canSave()) {
      return;
    }
    this.savingSignal.set(true);
    try {
      const saved = await this.store.update(draft.id, toUpdateRequest(draft));
      this.draftSignal.set(draftFrom(saved));
      this.toasts.ok(`${saved.name} saved.`);
    } catch (error) {
      this.toasts.error(describeHttpError(error));
    } finally {
      this.savingSignal.set(false);
    }
  }

  /** Starts a copy of the built-in for a vehicle kind, and opens it. */
  async createFrom(kind: VehicleKind, name: string): Promise<void> {
    try {
      const created = await this.store.create({ kind, name });
      this.selectedIdSignal.set(created.id);
      this.draftSignal.set(draftFrom(created));
      this.toasts.ok(`${created.name} created — nothing uses it until you activate it.`);
    } catch (error) {
      this.toasts.error(describeHttpError(error));
    }
  }

  async activate(id: string): Promise<void> {
    try {
      await this.store.activate(id);
      this.toasts.ok('This layout is what a session will engage with.');
    } catch (error) {
      this.toasts.error(describeHttpError(error));
    }
  }

  async remove(id: string): Promise<void> {
    try {
      await this.store.delete(id);
      if (this.selectedIdSignal() === id) {
        this.draftSignal.set(undefined);
        this.select(this.profiles()[0]?.id);
      }
      this.toasts.ok('Layout deleted.');
    } catch (error) {
      this.toasts.error(describeHttpError(error));
    }
  }

  private freeChannelFor(key: string, control: ControlDraft): number {
    const draft = this.draftSignal();
    if (!draft) {
      return control.rcChannel;
    }
    const others = { ...draft, controls: draft.controls.filter((c) => draftKey(c) !== key) };
    return nextFreeChannel(others);
  }

  private patch(change: (draft: ProfileDraft) => ProfileDraft): void {
    const draft = this.draftSignal();
    if (draft) {
      this.draftSignal.set(change(draft));
    }
  }

  private patchControl(key: string, change: (control: ControlDraft) => ControlDraft): void {
    this.patch((draft) => ({
      ...draft,
      controls: draft.controls.map((c) => (draftKey(c) === key ? change(c) : c)),
    }));
  }
}
