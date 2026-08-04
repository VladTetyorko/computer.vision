import { ChangeDetectionStrategy, Component, DestroyRef, computed, inject, input, output, signal } from '@angular/core';
import { FleetStore } from '../../core/fleet/fleet-store';
import { SettingsStore, type PipelineSettings } from '../../core/settings/settings-store';
import { ToastService } from '../../core/toast.service';
import { SidePanel } from '../../shared/ui/side-panel';
import type { DetectionResult } from '../../core/api/models';
import type { BoxesMode } from '../../shared/player/player';
import {
  addLabel,
  applyPreset,
  buildHotKnobPatch,
  buildModelChangePatch,
  chipCandidates,
  debounce,
  filterLabelsByQuery,
  findModel,
  hasExactLabelMatch,
  isLabelChecked,
  observedLabels,
  perfHint,
  reArmHint,
  seedLabelFilterForModel,
  sortSelectedFirst,
  toggleLabelChip,
} from './cv-control-panel-logic';

/** How long a hot-knob edit (confidence/fps/labelFilter/detectionEnabled) waits for further edits
 * before actually sending the PATCH — coalesces a fast slider drag or a burst of chip clicks into
 * one request instead of one per input event. */
const HOT_KNOB_DEBOUNCE_MS = 400;

/**
 * The Fly cockpit's live CV control panel (docs/CV-CONTROL-PLAN.md Wave E) — model picker,
 * confidence/inference-rate sliders, a class-filter chip checklist, a detection on/off toggle, and
 * (per direct user request) the detection-boxes rendering-mode control formerly owned by its own
 * standalone `layers` drawer — see {@link boxesMode}/{@link boxesModeChange} and this component's own
 * "Boxes rendering" section (`cv-control-panel.html`); `fly-logic.ts`'s `ToolRailPanelId` no longer
 * carries `layers` at all.
 * Migrated into the shared `vision-side-panel` drawer shell (docs/UI-REDESIGN-PLAN.md Wave 2, D-E):
 * this component used to own its own toggle button + hand-rolled `.cv-toggle`/`.cv-drawer`/
 * `.cv-drawer-head` chrome and a self-persisted `cvPanelOpen` flag; both are gone now — the tool-rail
 * button and the drawer's open/closed state both live on `FlyPage`'s own `PanelState` (`panels`,
 * `fly.ts`), passed in here as the plain `open` input below. This component keeps only the body.
 *
 * **Live vs. draft, one rule**: every edit always updates `SettingsStore`'s draft first (the exact
 * same "adjust() layers an edit over the active profile" mechanism `features/live/live.ts`/
 * `features/settings/settings.ts` already use) — that draft is what `fly.ts#start()` already posts
 * via `fleet.start(device.id, this.settings.effective())` for a fresh stream. **When {@link streamId}
 * is set** (a stream is actually running), the same edit *additionally* PATCHes the live stream:
 * hot knobs (confidence/fps/labelFilter/detectionEnabled) debounced via `FleetStore.patchStreamConfig`,
 * a model change immediately, both via the frozen `PATCH /api/streams/{id}/config` contract
 * (docs/CV-CONTROL-PLAN.md §3). No optimistic lies about the model change specifically: the
 * "re-arming detection" toast only ever fires off the server's own `modelReArmed` field, never
 * assumed client-side (`cv-control-panel-logic.ts#reArmHint`).
 *
 * **Class-filter chips are a checklist built from real data, not a hardcoded class list**
 * (docs/CV-CONTROL-PLAN.md Wave E, coordinator amendment after cv-service Wave A's real-vocabulary
 * measurement: prompt-free YOLOE's true vocabulary is ~4585 classes with many synonym/scene labels
 * for one real-world thing) — the candidate set is the union of the current filter and labels
 * actually observed in {@link detectionResults} (`chip-candidates`, `cv-control-panel-logic.ts`),
 * so the operator prunes from what the model is really emitting. `seedLabelFilterForModel` also
 * means switching to the open-vocab model always starts unfiltered ("show everything"), never a
 * silently-narrowing preset; `applyPreset`'s "People + vehicles + buildings" chip-fill is an
 * explicit, opt-in convenience button, not an enforced default.
 */
@Component({
  selector: 'vision-cv-control-panel',
  imports: [SidePanel],
  templateUrl: './cv-control-panel.html',
  styleUrl: './cv-control-panel.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CvControlPanel {
  /** The primary device's currently-running stream id, or `undefined` before the first Start —
   * gates whether an edit also PATCHes live (see class doc). */
  readonly streamId = input<string | undefined>(undefined);

  /** Recent detection results for the running stream (`FlyPage`'s own `detections.results()`, the
   * same signal the player's overlay already reads) — feeds the class-filter chip checklist's
   * observed-label half; `[]` before a stream has produced any detections yet. */
  readonly detectionResults = input<readonly DetectionResult[]>([]);

  /** The detection-boxes rendering mode (`FlyPage`'s own `facade.boxesMode`, formerly the standalone
   * `layers` drawer's only control) — a client-side rendering preference, not part of
   * `PipelineSettings`/the wire contract, so it round-trips via a plain input/output pair rather than
   * `SettingsStore`. */
  readonly boxesMode = input<BoxesMode>('overlay');
  /** Emitted when the operator picks a different boxes rendering mode — the host (`fly.ts`/`fly.html`)
   * owns the actual signal and writes it back via `facade.boxesMode.set($event)`. */
  readonly boxesModeChange = output<BoxesMode>();

  /** Whether the drawer is open — driven by the host's `PanelState` (`fly.ts`'s `panels`), not this
   * component's own state (docs/UI-REDESIGN-PLAN.md D-E). */
  readonly open = input<boolean>(false);
  /** Emitted when the drawer's own close control (`<vision-side-panel>`'s head button, or Esc) fires
   * — the host is the one that actually closes it (`panels.close()`). */
  readonly close = output<void>();

  protected readonly fleet = inject(FleetStore);
  protected readonly settings = inject(SettingsStore);
  private readonly toasts = inject(ToastService);

  /** Text in the Classes search box — filters {@link chips} live and, once nothing in that filtered
   * checklist matches, doubles as the "add a class not seen yet" free-text input. */
  protected readonly classQuery = signal('');
  protected readonly modelBusy = signal(false);

  protected readonly models = computed(() => this.fleet.models());
  protected readonly selectedModel = computed(() => findModel(this.models(), this.settings.effective().model));
  protected readonly isOpenVocab = computed(() => this.selectedModel()?.openVocab ?? false);
  protected readonly perfHintText = computed(() => perfHint(this.isOpenVocab()));

  protected readonly chips = computed(() =>
    chipCandidates(this.settings.effective().labelFilter, observedLabels(this.detectionResults())),
  );
  /** {@link chips}, narrowed by {@link classQuery} and selected-first sorted — what the checklist
   * actually renders (see `sortSelectedFirst`'s own doc comment for why selected-first). */
  protected readonly filteredChips = computed(() =>
    sortSelectedFirst(filterLabelsByQuery(this.chips(), this.classQuery()), this.settings.effective().labelFilter),
  );
  /** Whether the search box should offer "Add <query>" — only once a non-blank query matches nothing
   * already in {@link chips}, so typing an existing class's name filters to it instead of offering a
   * redundant duplicate add. */
  protected readonly showAddClass = computed(
    () => this.classQuery().trim().length > 0 && !hasExactLabelMatch(this.chips(), this.classQuery()),
  );

  private readonly hotKnobPatch = debounce((patch: ReturnType<typeof buildHotKnobPatch>) => {
    const streamId = this.streamId();
    if (streamId) {
      void this.fleet.patchStreamConfig(streamId, patch);
    }
  }, HOT_KNOB_DEBOUNCE_MS);

  constructor() {
    inject(DestroyRef).onDestroy(() => this.hotKnobPatch.cancel());
  }

  protected isChecked(label: string): boolean {
    return isLabelChecked(this.settings.effective().labelFilter, label);
  }

  // --- Hot knobs (live-patched, debounced, while a stream is running) --------------------------

  protected onConfidence(value: string): void {
    this.applyHotKnob({ confidenceThreshold: Number(value) });
  }

  protected onFps(value: string): void {
    this.applyHotKnob({ inferenceFps: Number(value) });
  }

  protected onDetectionEnabledToggle(checked: boolean): void {
    this.applyHotKnob({ detectionEnabled: checked });
  }

  /** Toggles one chip's checked state — bound to both the chip body (click anywhere to flip it) and
   * a checked chip's own "×" remove button, which is just this same action under a more explicit
   * affordance (`removeLabel`'s naive "filter it out" would silently no-op while `labelFilter` is
   * still `[]`/"all" — `toggleLabelChip` is the one function that handles that edge case correctly,
   * see its own doc comment). */
  protected toggleChip(label: string): void {
    const current = this.settings.effective().labelFilter;
    this.applyHotKnob({ labelFilter: toggleLabelChip(current, label, this.chips()) });
  }

  /** Adds {@link classQuery}'s text as a new class — only ever called once {@link showAddClass} is
   * true (i.e. the query matched nothing already in the checklist to toggle instead). */
  protected addClass(): void {
    const label = this.classQuery();
    const current = this.settings.effective().labelFilter;
    const next = addLabel(current, label);
    this.classQuery.set('');
    if (next !== current) {
      this.applyHotKnob({ labelFilter: next });
    }
  }

  /** Enter in the search box only acts when it would add a brand-new class (see {@link addClass}'s
   * own doc comment) — filtering down to an existing match is a click on that chip, not Enter. */
  protected onClassQueryEnter(): void {
    if (this.showAddClass()) {
      this.addClass();
    }
  }

  protected clearLabelFilter(): void {
    this.applyHotKnob({ labelFilter: [] });
  }

  protected fillPreset(): void {
    this.applyHotKnob({ labelFilter: applyPreset(this.settings.effective().labelFilter) });
  }

  private applyHotKnob(patch: Partial<PipelineSettings>): void {
    this.settings.adjust(patch);
    this.hotKnobPatch.run(buildHotKnobPatch(this.settings.effective()));
  }

  // --- Model change (live-patched immediately, never debounced/coalesced with hot knobs) --------

  protected async onModelChange(modelId: string): Promise<void> {
    if (modelId === this.settings.effective().model) {
      return;
    }
    this.hotKnobPatch.cancel(); // a deliberate model swap must never be coalesced with a pending hot-knob patch
    const seeded = seedLabelFilterForModel(findModel(this.models(), modelId));
    this.settings.adjust({ model: modelId, labelFilter: seeded });

    const streamId = this.streamId();
    if (!streamId) {
      return;
    }
    this.modelBusy.set(true);
    try {
      const response = await this.fleet.patchStreamConfig(streamId, buildModelChangePatch(modelId));
      const hint = response ? reArmHint(response) : null;
      if (hint) {
        this.toasts.info(hint);
      }
    } finally {
      this.modelBusy.set(false);
    }
  }
}
