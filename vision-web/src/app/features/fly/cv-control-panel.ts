import { ChangeDetectionStrategy, Component, DestroyRef, computed, effect, inject, input, output, signal } from '@angular/core';
import { FleetStore } from '../../core/fleet/fleet-store';
import { PollScheduler } from '../../core/poll-scheduler';
import { SettingsStore, type PipelineSettings } from '../../core/settings/settings-store';
import { ToastService } from '../../core/toast.service';
import { SidePanel } from '../../shared/ui/side-panel';
import type { DetectionResult, StreamTracksResponse, TrackingMode, UpdateStreamConfigRequest } from '../../core/api/models';
import type { BoxesMode } from '../../shared/player/player';
import {
  DEFAULT_FOLLOW_FPS,
  DEFAULT_VERIFY_EVERY_MILLIS,
  addLabel,
  applyPreset,
  buildFollowFpsPatch,
  buildHotKnobPatch,
  buildModelChangePatch,
  buildReleaseLockPatch,
  buildTrackingEnginePatch,
  buildTrackingModePatch,
  buildVerifyCadencePatch,
  chipCandidates,
  debounce,
  engineOptionsForMode,
  filterLabelsByQuery,
  findModel,
  formatFlowStrip,
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
 * How often the Tracking section polls `GET /api/streams/{id}/tracks` while the drawer is open and
 * a stream is running (docs/TRACKING-PLAN.md §4.E) — feeds the flow strip and is the **only** source
 * the "Following #N" chip is allowed to confirm from (docs/TRACKING-ORCHESTRATION.md §3.3's honesty
 * rule). Mirrors `DetectionsStore`'s own poll cadence (`POLL_INTERVAL_MS`) — fast enough that the
 * chip/flow-strip feel live, slow enough to be a background read, never a user-facing spinner.
 */
const TRACKS_POLL_INTERVAL_MS = 2_000;

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
 *
 * **Tracking section** (docs/TRACKING-PLAN.md, wave T7) — mode segmented control (Off/Associate/
 * Follow), an engine picker filtered to the roster's own `modes` for whichever is selected, and (in
 * Follow only) verify-cadence/follow-fps sliders. Deliberately **not** part of the "live vs. draft"
 * rule above — there is no `StartStreamRequest.tracking` in this app's scope, so every tracking
 * control is a live-only PATCH, meaningful only once {@link streamId} is set (see this class's own
 * "Tracking" field-group doc comment for the full reasoning, including why mode/engine sync from the
 * tracks poll while the cadence sliders don't). **The "Following #N — release" chip is the one place
 * this panel is stricter than every other control here**: it never appears from a click, only once
 * `GET .../tracks` echoes the lock back — docs/TRACKING-ORCHESTRATION.md §3.3's honesty rule, the
 * same "reflect the wire, never local intent" doctrine the dashed `COASTING` box expresses at the
 * pixel level in `shared/player/player.ts`. The flow strip beside it (`stats`-fed, hidden entirely
 * when `stats` is absent — an old/absent server, or tracking never configured this session) is this
 * app's own "visible flow" surface (docs/TRACKING-ORCHESTRATION.md §7) — see `cv-control-panel-logic.ts#formatFlowStrip`.
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

  // --- Tracking (docs/TRACKING-PLAN.md §4's frozen wire contract, wave T7) ---------------------
  // Unlike the model/classes/confidence sections above, there is no `SettingsStore` draft behind
  // any of this — `StartStreamRequest` doesn't carry a `tracking` object in this app (out of this
  // wave's own scope), so every control here is a **live-only** PATCH, meaningful only once
  // {@link streamId} is set. `trackingMode`/`trackingEngineId` are re-synced from the tracks poll's
  // own `stats.mode`/`stats.engineId` whenever a poll succeeds (see the constructor `effect` below)
  // — the honest "what's actually running" figure, per docs/TRACKING-PLAN.md R11 (an engine, or even
  // a whole mode, can silently fall back if what the operator picked won't construct). The
  // verify-cadence/follow-fps sliders have **no such readback** — `TrackStats` carries no such
  // figure on the wire — so those two stay pure local draft, seeded from the server's own defaults.

  /** The operator's last-clicked/last-confirmed tracking mode — see this section's own doc comment
   * for the "synced from stats, not a draft" nuance. */
  protected readonly trackingMode = signal<TrackingMode>('OFF');
  /** The operator's last-clicked/last-confirmed engine id, `''` = "server default for the mode". */
  protected readonly trackingEngineId = signal('');
  /** `FOLLOW`-only: milliseconds between detector re-verify passes — local draft, no readback (see
   * this section's own doc comment). */
  protected readonly verifyEveryMillis = signal(DEFAULT_VERIFY_EVERY_MILLIS);
  /** `FOLLOW`-only: the Java-side sampler rate feeding the tracker — local draft, no readback. */
  protected readonly followFps = signal(DEFAULT_FOLLOW_FPS);

  /** The most recent `GET .../tracks` poll, or `null` before the first poll settles, while the
   * drawer is closed, while no stream is running, or on any transport failure (including this
   * endpoint not existing yet on an old/absent server) — every downstream signal below degrades to
   * "hidden" from this one `null`, never a fabricated value. */
  private readonly tracksResponse = signal<StreamTracksResponse | null>(null);

  /** The engine picker's own candidate list, filtered to the currently-selected mode (`[]` for `OFF`). */
  protected readonly engineOptions = computed(() => engineOptionsForMode(this.fleet.trackers(), this.trackingMode()));

  /**
   * `0` = no lock held. **This is the one and only signal the "Following #N — release" chip reads**
   * — never the trackId just clicked, never an optimistic local flag. See this class's own doc
   * comment and docs/TRACKING-ORCHESTRATION.md §3.3.
   */
  protected readonly lockedTrackId = computed(() => this.tracksResponse()?.lockedTrackId ?? 0);

  /** The flow strip's own text, or `null` to hide it entirely (`stats` absent — docs/TRACKING-PLAN.md
   * §10 touchable outcome #2). */
  protected readonly flowStripText = computed(() => {
    const stats = this.tracksResponse()?.stats;
    return stats ? formatFlowStrip(stats) : null;
  });

  constructor() {
    inject(DestroyRef).onDestroy(() => this.hotKnobPatch.cancel());

    // Re-syncs the mode/engine picker's own selected-state from the wire's own ground truth
    // whenever a poll actually carries `stats` — see this section's own doc comment for why this is
    // the honest behavior (R11), not an override fight with the operator's own last click (a click
    // always fires its own fresh PATCH; the next poll simply confirms — or corrects — it).
    effect(() => {
      const stats = this.tracksResponse()?.stats;
      if (stats) {
        this.trackingMode.set(stats.mode);
        this.trackingEngineId.set(stats.engineId);
      }
    });

    // A stream change (including "stream stopped", `undefined`) must never show a stale lock/flow
    // strip from a *previous* stream while the next poll catches up — cleared immediately, not left
    // to linger for up to `TRACKS_POLL_INTERVAL_MS`.
    effect(() => {
      this.streamId();
      this.tracksResponse.set(null);
    });

    const stopTracksPoll = inject(PollScheduler).schedule(TRACKS_POLL_INTERVAL_MS, () => this.pollTracks());
    inject(DestroyRef).onDestroy(stopTracksPoll);
  }

  private async pollTracks(): Promise<void> {
    const streamId = this.streamId();
    if (!this.open() || !streamId) {
      return; // nothing to confirm — the stream-change effect above already cleared any stale response
    }
    try {
      const response = await this.fleet.getStreamTracks(streamId);
      this.tracksResponse.set(response);
    } catch {
      this.tracksResponse.set(null); // honest degrade — flow strip + chip both hide, no toast (background poll)
    }
  }

  protected onTrackingMode(mode: TrackingMode): void {
    this.trackingMode.set(mode);
    this.patchTracking(buildTrackingModePatch(mode));
  }

  protected onTrackingEngine(engineId: string): void {
    this.trackingEngineId.set(engineId);
    this.patchTracking(buildTrackingEnginePatch(engineId));
  }

  protected onVerifyEveryMillis(value: string): void {
    const millis = Number(value);
    this.verifyEveryMillis.set(millis);
    this.patchTracking(buildVerifyCadencePatch(millis));
  }

  protected onFollowFps(value: string): void {
    const fps = Number(value);
    this.followFps.set(fps);
    this.patchTracking(buildFollowFpsPatch(fps));
  }

  /** The release chip's own action — drops the lock, falls back to the mode's own policy. */
  protected releaseLock(): void {
    this.patchTracking(buildReleaseLockPatch());
  }

  /** Every tracking PATCH is sent immediately, **never debounced/coalesced with `hotKnobPatch`** —
   * tracking is its own independent family of change (`UpdateStreamConfigRequest#tracking`'s own doc
   * comment); a mode click and a confidence drag landing in the same request would make one a
   * side-effect of the other. */
  private patchTracking(patch: UpdateStreamConfigRequest): void {
    const streamId = this.streamId();
    if (streamId) {
      void this.fleet.patchStreamConfig(streamId, patch);
    }
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
