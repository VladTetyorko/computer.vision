import { Injectable, computed, inject, signal } from '@angular/core';
import { ControlProfileFacade } from '../../core/rc/control-profile-facade';
import { ToastService } from '../../core/toast.service';
import { VisionApi } from '../../core/api/vision-api';
import { describeHttpError } from '../../core/api-error';
import {
  blankControlDraft,
  byInput,
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
import { modeNamesFor, type KnownModeNames } from '../../core/rc/controller-wizard-logic';
import { controlKey } from '../../core/rc/control-action-logic';
import type { StickMode } from '../../core/rc/controller-diagram-logic';
import type {
  ControlAction,
  ControlInputKind,
  ControlProfile,
  ControlSource,
  FlightCapability,
  SwitchPosition,
  VehicleKind,
} from '../../core/api/models';

/** Console prefix for this facade's diagnostic logging — this codebase's convention is a per-file
 * prefix rather than a logging service (`station/vision-web/MODULE.md`'s own Gotchas entry). */
const LOG_PREFIX = '[controller-setup]';

const NO_KNOWN_MODE_NAMES: KnownModeNames = { names: [], assetCount: 0 };

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
  private readonly store = inject(ControlProfileFacade);
  private readonly toasts = inject(ToastService);
  private readonly api = inject(VisionApi);

  private readonly selectedIdSignal = signal<string | undefined>(undefined);
  private readonly draftSignal = signal<ProfileDraft | undefined>(undefined);
  private readonly savingSignal = signal(false);
  private readonly errorSignal = signal<string | undefined>(undefined);
  /** Every online asset's `flightCapabilities()` this facade has managed to read since the page
   * opened — see {@link loadKnownModeCapabilities}. Never blocking, never toasted: this is
   * background enrichment for one `<datalist>`, not a user-initiated action. */
  private readonly modeCapabilitiesSignal = signal<readonly FlightCapability[]>([]);

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

  /** {@link modeNamesFor}'s pool, read for whichever layout is currently open — recomputed
   * automatically as either the selection or the background capabilities read changes; never a
   * second facade signal the two could disagree about (see {@link KnownModeNames}'s own doc
   * comment). Empty on a fresh page load (the background read hasn't resolved yet), on a read
   * failure, or when nothing online matches the layout's kind — {@link modeSourceHint} renders all
   * three the same honest way, never a fabricated name. */
  readonly knownModeNames = computed<KnownModeNames>(() => {
    const kind = this.selected()?.kind;
    if (!kind) {
      return NO_KNOWN_MODE_NAMES;
    }
    const capabilities = this.modeCapabilitiesSignal();
    return {
      names: modeNamesFor(kind, capabilities),
      assetCount: capabilities.filter((c) => c.vehicleKind === kind).length,
    };
  });

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
    // Fire-and-forget: the Mode step's known-name pool is background enrichment for one
    // `<datalist>`, never something the page's own load should wait on or fail for.
    void this.loadKnownModeCapabilities();
  }

  /**
   * One-shot lazy read behind {@link knownModeNames} (CONTROLLER-UX-PLAN.md §5 wave M) — reuses
   * `VisionApi#flightCapabilities`, the exact same per-asset call `features/fly/cockpit-facade.ts`
   * already makes for the cockpit's own command panel; this just calls it for every currently
   * *online* asset instead of the one the cockpit happens to have open. "Online" is read as
   * `AssetStatus === 'STREAMING'` — the same vocabulary `features/fly/drone-picker.ts`'s own cards
   * already use for "Streaming"/"Offline", and the closest honest proxy available on
   * `AssetSummary` for "a live heartbeat might actually answer this vehicle's capabilities request
   * right now" (no separate "vehicle connected" flag exists on the DTO).
   *
   * Never blocking and never toasted, at either the list step or a single asset's capability read
   * — a failure (a stale/torn-down stream, an asset with no MAVLink link at all) just leaves that
   * one asset's names out of the pool, same "background enrichment degrades to hidden" posture
   * `core/fleet/fleet-store.ts#loadModels` already uses for its own one-shot roster fetch.
   */
  private async loadKnownModeCapabilities(): Promise<void> {
    let online: readonly { readonly assetId: string }[];
    try {
      online = (await this.api.listAssets()).filter((asset) => asset.status === 'STREAMING');
    } catch (error) {
      console.warn(`${LOG_PREFIX} could not list assets for the Mode step's known-name read`, { error });
      return;
    }
    const settled = await Promise.allSettled(online.map((asset) => this.api.flightCapabilities(asset.assetId)));
    const capabilities: FlightCapability[] = [];
    settled.forEach((result, i) => {
      if (result.status === 'fulfilled') {
        capabilities.push(result.value);
      } else {
        console.warn(
          `${LOG_PREFIX} could not read flight capabilities for ${online[i].assetId} — left out of the Mode step's name pool`,
          { error: result.reason },
        );
      }
    });
    this.modeCapabilitiesSignal.set(capabilities);
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

  /**
   * Adds a row for one physical control, optionally already declared as what Autodetect saw it do.
   *
   * A control that already has a row is left exactly as it is, kind included: re-running Autodetect
   * over a layout the operator has been tuning must not quietly overwrite a choice they made by
   * hand, and a sampling window is not better evidence than someone saying what their own switch is.
   *
   * @return whether a row was actually added — Autodetect counts rows, not flicks, so that its
   *         running total cannot claim seven when the operator can see three.
   */
  addControl(source: ControlSource, sourceIndex: number, kind?: ControlInputKind): boolean {
    let added = false;
    this.patch((draft) => {
      const blank = blankControlDraft(source, sourceIndex, draft);
      const control = kind ? withKind(blank, kind, this.catalog()) : blank;
      const key = draftKey(control);
      if (draft.controls.some((c) => draftKey(c) === key)) {
        return draft;
      }
      added = true;
      return { ...draft, controls: [...draft.controls, control] };
    });
    return added;
  }

  removeControl(key: string): void {
    this.patch((draft) => ({ ...draft, controls: draft.controls.filter((c) => draftKey(c) !== key) }));
  }

  /**
   * Re-declaring a row as a continuous axis also moves it onto a channel: AXIS + ACTIONS is the one
   * pairing with nothing to configure, so leaving the role behind would strand the row.
   */
  setKind(key: string, kind: ControlInputKind): void {
    this.patchControl(key, (control) => {
      const redeclared = withKind(control, kind, this.catalog());
      return kind === 'AXIS' && redeclared.role === 'ACTIONS'
        ? { ...redeclared, role: 'CHANNEL', rcChannel: this.freeChannelFor(key, redeclared) }
        : redeclared;
    });
  }

  /**
   * How the operator's transmitter is arranged. Part of the draft, and so part of the save: it
   * describes their radio, which follows them between machines in a way browser storage does not.
   */
  setStickMode(stickMode: StickMode): void {
    this.patch((draft) => ({ ...draft, stickMode }));
  }

  setForwardIsUp(forwardIsUp: boolean): void {
    this.patch((draft) => ({ ...draft, forwardIsUp }));
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

  /**
   * Binds one physical control to one vehicle function — what the guided run writes when the
   * operator answers a step.
   *
   * <h2>It takes over, deliberately</h2>
   * Any other row holding that function or that channel is dropped first. The operator has just
   * said "*this* is my throttle"; leaving the previous guess in place would either duplicate the
   * channel (which `draftIssues` then refuses to save) or leave two rows claiming one function,
   * and neither is what they asked for. On a fresh copy of a built-in the row being dropped is the
   * platform's own guess, which is exactly the thing being corrected.
   *
   * An existing row's `kind` and `reversed` survive: the operator declaring their switch a
   * `SWITCH_3`, or reversing an axis, is knowledge this run does not have and must not overwrite.
   */
  assignFunction(
    source: ControlSource,
    sourceIndex: number,
    fn: ControlDraft['function'],
    rcChannel: number,
    travel: ControlDraft['travel'],
  ): void {
    const key = controlKey(source, sourceIndex);
    this.patch((draft) => {
      const existing = draft.controls.find((c) => draftKey(c) === key);
      const bound: ControlDraft = {
        ...(existing ?? blankControlDraft(source, sourceIndex, draft)),
        role: 'CHANNEL',
        function: fn,
        rcChannel,
        travel,
      };
      const kept = draft.controls.filter(
        (c) => draftKey(c) !== key && !(c.role === 'CHANNEL' && (c.rcChannel === rcChannel || c.function === fn)),
      );
      return { ...draft, controls: [...kept, bound].sort(byInput) };
    });
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
