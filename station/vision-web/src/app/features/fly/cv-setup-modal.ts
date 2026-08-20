import { ChangeDetectionStrategy, Component, DestroyRef, computed, effect, inject, input, output, signal } from '@angular/core';
import { FleetStore } from '../../core/fleet/fleet-store';
import { SettingsStore, type PipelineSettings } from '../../core/settings/settings-store';
import { ToastService } from '../../core/toast.service';
import { DetectionsStore } from '../../core/detections/detections-store';
import { HIDDEN_CLASS_TRUTH, isLabelDenied, toggleLabelDeny } from '../../core/detections/detections-logic';
import type { TrackingMode, UpdateStreamConfigRequest } from '../../core/api/models';
import {
  CAPABILITY_LEVEL_OPTIONS,
  DEFAULT_FOLLOW_FPS,
  DEFAULT_VERIFY_EVERY_MILLIS,
  DETECTION_LAG_BUDGET_MILLIS,
  HOT_KNOB_DEBOUNCE_MS,
  addLabel,
  applyPreset,
  buildCapabilityLevelPatch,
  buildFollowFpsPatch,
  buildHotKnobPatch,
  buildModelChangePatch,
  buildTrackingEnginePatch,
  buildTrackingModePatch,
  buildVerifyCadencePatch,
  capabilityLevelHint,
  capabilityLevelLabel,
  chipCandidates,
  debounce,
  engineOptionsForMode,
  filterLabelsByQuery,
  findModel,
  formatDetectionLag,
  formatFlowStrip,
  hasExactLabelMatch,
  intentCardSentence,
  isCapabilityDowngraded,
  isDetectionLagOverBudget,
  isLabelChecked,
  latestFrameTracking,
  modelCostWord,
  observedLabels,
  perfHint,
  reArmHint,
  recentObservedLabels,
  seedLabelFilterForModel,
  sortRecentFirst,
  stagedLabelSeed,
  submitLabelFilterButtonText,
} from './cv-control-panel-logic';

/**
 * The Fly cockpit's **Detection setup modal** (docs/plans/active/CV-PANEL-SPLIT-PLAN.md P1, §1
 * "Surface 2 — calm hands: decisions") — a centered dialog over the cockpit holding every
 * set-once/expert CV knob that used to live inside `cv-control-panel.ts`'s own "Tune"/"Expert"
 * disclosures: the "Looking for" model intent cards (each with a one-sentence "what it finds" plus
 * the honest cost word, P2 §1 item 2), one merged Classes section (search/toggle/add/clear over the
 * full roster, observed-recently labels sorted first, hidden/deny-listed labels marked and
 * un-hideable in place — P2 §5; **not** a separate "Seen now" list any more, see that section's own
 * doc comment below for why), confidence (reworded as a symptom axis, P2 §1 item 3), tracking mode,
 * and the collapsed Expert tier (fps floor — relabeled "Detector floor", P2 §1 item 4 — capability
 * ceiling, engine, cadences, flow strip, Serving/lag readouts). Opened by the panel's own
 * "Change…"/"Detection setup…" buttons ({@link CvControlPanel#setupRequested}); the panel keeps the
 * seconds-matter controls (Detect hero, a "Looking for" summary row, Boxes, the "Following #N" lock
 * chip, the two conditional honesty notices) — see that class's own doc comment for the full split.
 *
 * **Not a form.** Every control here PATCHes live through the exact same debounced hot-knob path
 * (`{@link applyHotKnob}`) or immediate PATCH the old single panel used — there is no local Save/
 * Cancel state and no `(closed)` side effect beyond dismissing the dialog. This is "the same hot
 * knobs, staged calmly" (plan §1.2), not a settings form.
 *
 * **A true `position: fixed` modal**, mirroring `shared/map/fleet-plan-dialog/flight-plan-
 * dialog.ts`/`features/command/geofence-zone-dialog.ts`'s own backdrop-click-to-dismiss
 * convention (`(click)="closed.emit()"` on `.backdrop`, `$event.stopPropagation()` on the inner
 * `.dialog` card) rather than `shared/ui/confirm-dialog.ts`/`arm-confirm-dialog.ts`'s deliberately
 * *no*-dismiss convention — this dialog holds no destructive/one-shot action, so an accidental
 * scrim click or `Esc` losing nothing is the right default (the plan's own "Esc key and scrim
 * click close it"). `Esc` itself is **not** handled here: `cockpit.ts` owns the cockpit's one
 * `document` `keydown` listener and its `collapseOverlays()` cascade (`fly-logic.ts#
 * nextCollapseAction`), extended by this wave to check `dialog.isOpen('cv-setup')` first — the
 * topmost overlay closes first, before any tool-rail drawer or the Stop-stream confirm.
 *
 * **The video stays visible, dimmed, behind this dialog** — the backdrop uses `--scrim-strong`
 * (the frontend-style skill's compositing-over-video/map token), not `--hud-bg-strong`/blur the
 * way the app's non-video-adjacent dialogs do; a wash this component's own `.backdrop` rule
 * documents.
 *
 * **`streamId` is the one input** — every other fact this dialog needs (the model roster, the
 * settings draft, observed/tracked detections) comes from directly-injected app-wide services,
 * the same shape `cv-control-panel.ts` already used before the split (root-provided `FleetStore`/
 * `SettingsStore`/`ToastService`, and the cockpit-route-provided `DetectionsStore` — see
 * `cockpit.ts`'s own `providers` array; this component resolves the identical instance the panel
 * and the detections strip already share, since it is mounted inside the same route's component
 * tree). **Deliberately does not call `DetectionsStore#trackTracks`/`untrackTracks` itself** —
 * that poll's lifecycle stays owned by `cv-control-panel.ts`, which stays mounted for the whole
 * time the Vision drawer is open (this modal's own mount/unmount is a *shorter*, independent
 * window nested inside that). If this component started/stopped the same poll on its own
 * mount/unmount, closing the modal while the drawer stayed open would kill tracking for the panel
 * still on screen — this component only ever *reads* `DetectionsStore#tracks()`/`results()`.
 *
 * **Expert disclosure state is facade-owned** (docs/plans/done/UI-ARCHITECTURE-PLAN.md — "no new
 * booleans in the component"; mirrors how wave W4 threaded `lockedTrackId` and W5 threaded
 * `hoveredDetectionClass` as plain `CockpitFacade` signals through an input/output pair rather
 * than a component-local field): {@link expertOpen}/{@link expertOpenChange} round-trip
 * `CockpitFacade#cvExpertOpen`, so an operator's "I always want Expert open" preference survives
 * this component being destroyed and recreated every time the modal itself is reopened (this
 * dialog, unlike the old always-mounted-while-the-drawer-is-open panel, is not continuously
 * mounted — a component-local field would forget the choice on every close).
 *
 * **Tracking mode/engine and the capability ceiling reset to their defaults on every reopen** —
 * unchanged from before the split: `trackingMode`/`trackingEngineId` immediately re-sync from the
 * tracks poll's own `stats` the instant this component mounts (the constructor `effect` below), so
 * that pair reads the honest wire state within one tick regardless. `capabilityLevel`/
 * `verifyEveryMillis`/`followFps` have no such readback (see each field's own doc comment) and so
 * do genuinely reset to their seed values on reopen — the exact same behavior the old panel already
 * had every time an operator closed and reopened the Vision drawer (that component was destroyed/
 * recreated too, `cockpit.html`'s `@if (isPanelOpen('cv') ...)`), not a regression this wave
 * introduces.
 */
@Component({
  selector: 'vision-cv-setup-modal',
  imports: [],
  templateUrl: './cv-setup-modal.html',
  styleUrl: './cv-setup-modal.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CvSetupModal {
  /** The primary device's currently-running stream id, or `undefined` before the first Start —
   * gates whether an edit also PATCHes live, exactly like `cv-control-panel.ts`'s identical input. */
  readonly streamId = input<string | undefined>(undefined);

  /** `CockpitFacade#cvExpertOpen` — see this class's own doc comment for why facade-owned. */
  readonly expertOpen = input<boolean>(false);
  readonly expertOpenChange = output<boolean>();

  /** Emitted on a scrim click or the header's "×" — the host (`cockpit.ts`) owns the actual
   *  `dialog.close('cv-setup')` call, mirroring `arm-confirm-dialog.ts#cancelled`'s identical
   *  "component only reports the request, host owns the store write" shape. */
  readonly closed = output<void>();

  private readonly fleet = inject(FleetStore);
  protected readonly settings = inject(SettingsStore);
  private readonly toasts = inject(ToastService);
  /** Read-only here — see this class's own doc comment for why `trackTracks`/`untrackTracks` are
   *  never called from this component. */
  protected readonly detections = inject(DetectionsStore);

  protected readonly hasStream = computed(() => !!this.streamId());

  /** The honest "hidden classes drop everywhere" sentence — shown verbatim once, at the foot of
   *  the merged Classes section (docs/plans/active/CV-PANEL-SPLIT-PLAN.md P2 §5 — the pre-P2 split
   *  across "Seen now" and "All classes" rendered this twice; the merge is also what fixed that). */
  protected readonly hiddenClassTruth = HIDDEN_CLASS_TRUTH;

  protected readonly classQuery = signal('');
  protected readonly modelBusy = signal(false);

  /** The staged **allowlist** edit — see `cv-control-panel.ts`'s pre-split doc comment for the
   *  full staging model (unchanged by this wave, only relocated). */
  protected readonly pendingLabels = signal<readonly string[] | null>(null);
  protected readonly labelFilterSeed = computed(() =>
    stagedLabelSeed(this.pendingLabels(), this.settings.effective().labelFilter),
  );

  protected readonly models = computed(() => this.fleet.models());
  protected readonly selectedModel = computed(() => findModel(this.models(), this.settings.effective().model));
  protected readonly isOpenVocab = computed(() => this.selectedModel()?.openVocab ?? false);
  protected readonly perfHintText = computed(() => perfHint(this.isOpenVocab()));

  /** The model/cost-word pairing's own word — shared with the panel's "Looking for" summary row
   *  via `cv-control-panel-logic.ts#modelCostWord`, so the two surfaces can never disagree. */
  protected costWord(openVocab: boolean): string {
    return modelCostWord(openVocab);
  }

  /** The intent card's own "what it finds" sentence — `null` degrades to no line at all (never a
   *  fabricated description) for a `kind` this app doesn't recognize. */
  protected intentSentence(kind: string): string | null {
    return intentCardSentence(kind);
  }

  protected readonly chips = computed(() =>
    chipCandidates(this.labelFilterSeed(), this.settings.effective().labelDenyFilter, observedLabels(this.detections.results())),
  );
  /** The most-recently-observed labels, in recency order — {@link filteredChips}' own sort
   *  priority (docs/plans/active/CV-PANEL-SPLIT-PLAN.md P2 §5). This is all that survives of the
   *  pre-P2 "Seen now" mini-checklist once it merged into the one full checklist below: recency was
   *  its real value, kept here as a sort key instead of a second rendered list. */
  protected readonly recentLabels = computed(() => recentObservedLabels(this.detections.results()));
  protected readonly filteredChips = computed(() =>
    sortRecentFirst(filterLabelsByQuery(this.chips(), this.classQuery()), this.recentLabels()),
  );
  protected readonly showAddClass = computed(
    () => this.classQuery().trim().length > 0 && !hasExactLabelMatch(this.chips(), this.classQuery()),
  );

  private readonly hotKnobPatch = debounce((patch: ReturnType<typeof buildHotKnobPatch>) => {
    const streamId = this.streamId();
    if (streamId) {
      void this.fleet.patchStreamConfig(streamId, patch);
    }
  }, HOT_KNOB_DEBOUNCE_MS);

  // --- Tracking (docs/plans/done/TRACKING-PLAN.md §4's frozen wire contract, wave T7) — unchanged
  // from the pre-split panel beyond relocation; see this class's own doc comment for the "resets on
  // reopen vs. re-syncs from stats" distinction between these fields.

  protected readonly trackingMode = signal<TrackingMode>('OFF');
  protected readonly trackingEngineId = signal('');
  protected readonly verifyEveryMillis = signal(DEFAULT_VERIFY_EVERY_MILLIS);
  protected readonly followFps = signal(DEFAULT_FOLLOW_FPS);

  protected readonly capabilityLevel = signal(0);
  protected readonly capabilityLevelOptions = CAPABILITY_LEVEL_OPTIONS;
  protected readonly capabilityLevelHintText = computed(() => capabilityLevelHint(this.capabilityLevel()));

  protected readonly frameTracking = computed(() => latestFrameTracking(this.detections.results()));
  protected readonly servedCapability = computed(() => this.frameTracking()?.capability);
  protected readonly capabilityDowngraded = computed(() => isCapabilityDowngraded(this.servedCapability()));

  protected readonly detectionLagBudgetMillis = DETECTION_LAG_BUDGET_MILLIS;
  protected readonly detectionLagText = computed(() => formatDetectionLag(this.frameTracking()?.detectionLagMillis ?? 0));
  protected readonly detectionLagOverBudget = computed(() =>
    isDetectionLagOverBudget(this.frameTracking()?.detectionLagMillis ?? 0),
  );

  protected capabilityLevelName(level: number): string {
    return capabilityLevelLabel(level);
  }

  protected onCapabilityLevel(value: string): void {
    const level = Number(value);
    this.capabilityLevel.set(level);
    this.patchTracking(buildCapabilityLevelPatch(level));
  }

  protected readonly engineOptions = computed(() => engineOptionsForMode(this.fleet.trackers(), this.trackingMode()));

  protected readonly flowStripText = computed(() => {
    const stats = this.detections.tracks()?.stats;
    return stats ? formatFlowStrip(stats) : null;
  });

  constructor() {
    inject(DestroyRef).onDestroy(() => {
      this.hotKnobPatch.cancel();
    });

    // Re-syncs mode/engine from the wire's own ground truth the instant this component mounts (and
    // on every poll after) — see this class's own doc comment for why this is honest (R11) rather
    // than a lost operator choice: a click always fires its own fresh PATCH, the next poll simply
    // confirms — or corrects — it. Unchanged logic from the pre-split panel, just relocated.
    effect(() => {
      const stats = this.detections.tracks()?.stats;
      if (stats) {
        this.trackingMode.set(stats.mode);
        this.trackingEngineId.set(stats.engineId);
      }
    });

    // A stream change drops any staged-but-unsubmitted allowlist edit — built against the previous
    // stream's observed vocabulary, with no guaranteed meaning against whatever comes next.
    effect(() => {
      this.streamId();
      this.pendingLabels.set(null);
    });
  }

  protected onExpertToggle(event: Event): void {
    this.expertOpenChange.emit((event.target as HTMLDetailsElement).open);
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

  /** Every tracking PATCH is sent immediately, never debounced/coalesced with `hotKnobPatch` — see
   *  `cv-control-panel.ts`'s identical, pre-split private method's own doc comment. */
  private patchTracking(patch: UpdateStreamConfigRequest): void {
    const streamId = this.streamId();
    if (streamId) {
      void this.fleet.patchStreamConfig(streamId, patch);
    }
  }

  protected isChecked(label: string): boolean {
    return isLabelChecked(this.labelFilterSeed(), label) && !isLabelDenied(this.settings.effective().labelDenyFilter, label);
  }

  /** Whether `label` is on the operator's own deny-list — the merged Classes checklist's explicit
   *  "hidden, click to un-hide" indicator (docs/plans/active/CV-PANEL-SPLIT-PLAN.md P2 §5's "hidden
   *  classes (deny-list) management … with un-hide"), mirroring `detections-strip.html`'s identical
   *  `chip.hidden` treatment so an operator sees the same "— hidden" wording in both places. The
   *  same {@link toggleChip} click both hides and un-hides — this only changes what the chip *says*. */
  protected isHidden(label: string): boolean {
    return isLabelDenied(this.settings.effective().labelDenyFilter, label);
  }

  protected onConfidence(value: string): void {
    this.applyHotKnob({ confidenceThreshold: Number(value) });
  }

  protected onFps(value: string): void {
    this.applyHotKnob({ inferenceFps: Number(value) });
  }

  /** One chip's click/× action — immediately toggles the deny-list, never the staged allowlist. The
   *  single merged Classes checklist's only click handler as of P2 (pre-P2, the now-deleted "Seen
   *  now" mini-checklist called this same method too — one apparatus, not two, from the start). */
  protected toggleChip(label: string): void {
    this.applyHotKnob({ labelDenyFilter: toggleLabelDeny(this.settings.effective().labelDenyFilter, label) });
  }

  protected addClass(): void {
    const label = this.classQuery();
    const seed = this.labelFilterSeed();
    const next = addLabel(seed, label);
    this.classQuery.set('');
    if (next !== seed) {
      this.pendingLabels.set(next);
    }
  }

  protected onClassQueryEnter(): void {
    if (this.showAddClass()) {
      this.addClass();
    }
  }

  protected clearLabelFilter(): void {
    this.pendingLabels.set([]);
  }

  protected fillPreset(): void {
    this.pendingLabels.set(applyPreset(this.labelFilterSeed()));
  }

  protected submitButtonText(pending: readonly string[]): string {
    return submitLabelFilterButtonText(pending);
  }

  protected submitLabelFilter(): void {
    const pending = this.pendingLabels();
    if (pending === null) {
      return;
    }
    this.applyHotKnob({ labelFilter: pending });
    this.pendingLabels.set(null);
  }

  protected discardStagedLabels(): void {
    this.pendingLabels.set(null);
  }

  private applyHotKnob(patch: Partial<PipelineSettings>): void {
    this.settings.adjust(patch);
    this.hotKnobPatch.run(buildHotKnobPatch(this.settings.effective()));
  }

  protected async onModelChange(modelId: string): Promise<void> {
    if (modelId === this.settings.effective().model) {
      return;
    }
    this.hotKnobPatch.cancel();
    this.pendingLabels.set(null);
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
