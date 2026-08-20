import { ChangeDetectionStrategy, Component, DestroyRef, computed, effect, inject, input, output, signal } from '@angular/core';
import { FleetStore } from '../../core/fleet/fleet-store';
import { SettingsStore, type PipelineSettings } from '../../core/settings/settings-store';
import { ToastService } from '../../core/toast.service';
import { UiStore } from '../../core/ui/ui-store';
import { DetectionsStore } from '../../core/detections/detections-store';
import { HIDDEN_CLASS_TRUTH, isLabelDenied, toggleLabelDeny } from '../../core/detections/detections-logic';
import type { TrackingMode, UpdateStreamConfigRequest } from '../../core/api/models';
import type { BoxesMode } from '../../shared/player/player';
import { DECLUTTER_LEVELS, DEFAULT_DECLUTTER_LEVEL, declutterLevelLabel } from '../../shared/player/detection-overlay-logic';
import {
  CAPABILITY_LEVEL_OPTIONS,
  DEFAULT_FOLLOW_FPS,
  DEFAULT_VERIFY_EVERY_MILLIS,
  DETECTION_LAG_BUDGET_MILLIS,
  addLabel,
  applyPreset,
  buildCapabilityLevelPatch,
  buildFollowFpsPatch,
  buildHotKnobPatch,
  buildModelChangePatch,
  buildReleaseLockPatch,
  buildTrackingEnginePatch,
  buildTrackingModePatch,
  buildVerifyCadencePatch,
  capabilityLevelHint,
  capabilityLevelLabel,
  chipCandidates,
  classesOnScreenCount,
  debounce,
  detectionStatus,
  engineOptionsForMode,
  filterLabelsByQuery,
  findModel,
  formatDetectionLag,
  formatFlowStrip,
  hasExactLabelMatch,
  isCapabilityDowngraded,
  isDetectionLagOverBudget,
  isLabelChecked,
  latestFrameTracking,
  observedLabels,
  perfHint,
  reArmHint,
  recentObservedLabels,
  seedLabelFilterForModel,
  sortSelectedFirst,
  stagedLabelSeed,
  submitLabelFilterButtonText,
} from './cv-control-panel-logic';

/** How long a hot-knob edit (confidence/fps/labelFilter/labelDenyFilter/detectionEnabled) waits for
 * further edits before actually sending the PATCH — coalesces a fast slider drag or a burst of chip
 * clicks into one request instead of one per input event. */
const HOT_KNOB_DEBOUNCE_MS = 400;

/**
 * The Fly cockpit's live CV control panel (docs/plans/done/CV-CONTROL-PLAN.md Wave E) — model picker,
 * confidence/inference-rate sliders, a class-filter chip checklist, a detection on/off toggle, and
 * (per direct user request) the detection-boxes rendering-mode control formerly owned by its own
 * standalone `layers` drawer — see {@link boxesMode}/{@link boxesModeChange} and this component's own
 * "Boxes rendering" section (`cv-control-panel.html`); `fly-logic.ts`'s `ToolRailPanelId` no longer
 * carries `layers` at all.
 *
 * **Body-only, no drawer shell of its own** (wave W5, docs/plans/active/CV-CLEAN-FEED-PLAN.md D-3):
 * this component used to self-wrap a `<vision-side-panel>` behind its own `open`/`close` input/
 * output. The merged Vision drawer — this panel's content plus the detections strip
 * (`shared/player/detections-strip.ts`) above it — is now one `<vision-side-panel>` owned by
 * `cockpit.html` directly, with this component and the strip as siblings inside it; the strip stays
 * reachable while `!facade.watchMode()` hides this component specifically, so a watch-mode viewer
 * keeps the strip (no mutating controls) instead of losing the drawer entirely. Because
 * `cockpit.html` only mounts this component while the drawer is actually open, **mounting IS
 * opening** — the same doc-commented contract `<vision-side-panel>` itself states — so there is
 * nothing left for a local `open` input to gate.
 *
 * **Live vs. draft, one rule**: every edit always updates `SettingsStore`'s draft first (the exact
 * same "adjust() layers an edit over the active profile" mechanism `features/live/live.ts`/
 * `features/settings/settings.ts` already use) — that draft is what `fly.ts#start()` already posts
 * via `fleet.start(device.id, this.settings.effective())` for a fresh stream. **When {@link streamId}
 * is set** (a stream is actually running), the same edit *additionally* PATCHes the live stream:
 * hot knobs (confidence/fps/labelFilter/labelDenyFilter/detectionEnabled) debounced via
 * `FleetStore.patchStreamConfig`, a model change immediately, both via the frozen
 * `PATCH /api/streams/{id}/config` contract (docs/plans/done/CV-CONTROL-PLAN.md §3). No optimistic lies
 * about the model change specifically: the "re-arming detection" toast only ever fires off the
 * server's own `modelReArmed` field, never assumed client-side (`cv-control-panel-logic.ts#reArmHint`).
 *
 * **`detectionEnabled` is the one knob that left that rule** (docs/plans/active/STREAM-STATE-PLAN.md §3.1).
 * It is the only knob with a *read* surface — `GET /api/streams` now carries the running stream's own
 * value — so rendering the draft for it was a measurable lie, not merely an approximation: a second
 * browser, a reload, or switching drone could each show a switch position that was false for the
 * stream on screen. It is now an `input`/`output` pair ({@link detectionEnabled} /
 * {@link detectionEnabledChange}) whose write the host owns (`CockpitFacade#setDetection`), because
 * the rail's off-dot and the video-surface "Turn on" chip must resolve it identically. Every other
 * knob here keeps the draft-first rule above: none of them can be read back, so the draft remains
 * the only answer anyone has.
 *
 * **Class-filter chips are a checklist built from real data, not a hardcoded class list**
 * (docs/plans/done/CV-CONTROL-PLAN.md Wave E, coordinator amendment after cv-service Wave A's real-vocabulary
 * measurement: prompt-free YOLOE's true vocabulary is ~4585 classes with many synonym/scene labels
 * for one real-world thing) — the candidate set is the union of the current filter, the deny-list,
 * and labels actually observed in {@link DetectionsStore#results} (`chip-candidates`,
 * `cv-control-panel-logic.ts`), so the operator prunes from what the model is really emitting.
 * `seedLabelFilterForModel` also means switching to the open-vocab model always starts unfiltered
 * ("show everything"), never a silently-narrowing preset; `applyPreset`'s "People + vehicles +
 * buildings" chip-fill is an explicit, opt-in convenience button, not an enforced default.
 *
 * **Tracking section** (docs/plans/done/TRACKING-PLAN.md, wave T7) — mode segmented control (Off/Associate/
 * Follow), an engine picker filtered to the roster's own `modes` for whichever is selected, and (in
 * Follow only) verify-cadence/follow-fps sliders. Deliberately **not** part of the "live vs. draft"
 * rule above — there is no `StartStreamRequest.tracking` in this app's scope, so every tracking
 * control is a live-only PATCH, meaningful only once {@link streamId} is set (see this class's own
 * "Tracking" field-group doc comment for the full reasoning, including why mode/engine sync from the
 * tracks poll while the cadence sliders don't). **The "Following #N — release" chip is the one place
 * this panel is stricter than every other control here**: it never appears from a click, only once
 * `GET .../tracks` echoes the lock back — docs/extracts/TRACKING-ORCHESTRATION.md §3.3's honesty rule, the
 * same "reflect the wire, never local intent" doctrine the dashed `COASTING` box expresses at the
 * pixel level in `shared/player/player.ts`. The flow strip beside it (`stats`-fed, hidden entirely
 * when `stats` is absent — an old/absent server, or tracking never configured this session) is this
 * app's own "visible flow" surface (docs/extracts/TRACKING-ORCHESTRATION.md §7) — see `cv-control-panel-logic.ts#formatFlowStrip`.
 * **The tracks poll itself is owned by {@link DetectionsStore}** (`trackTracks`/`untrackTracks`, wave
 * W5) — this component only starts/stops that session from its own constructor/`DestroyRef`, which,
 * given the "body-only" doc paragraph above, already bounds the poll to "while the drawer is open".
 *
 * **Three-tier layout** (docs/plans/active/CV-UX-RESEARCH.md, waves U1-U5) — the panel used to be
 * one flat scroll of 15 controls with the primary act (detection on/off) last; it is now:
 * - **Tier 0 (always visible)**: the Detect hero switch with an honest status line
 *   ({@link detectionStatusText}), the "Looking for" intent cards ({@link models}, U2), the capped
 *   "Seen now" chips ({@link seenNowChips}, U4), Boxes rendering, the click-to-follow hint/lock
 *   chip, and the two *conditional* capability-downgrade/lag-over-budget notices (still surfaced
 *   here even though their quiet numeric readouts move to Expert — burying honest bad news would
 *   violate docs/main/UX-DESIGN.md §7.2).
 * - **Tier 1 "Tune"** ({@link tuneTier}, a `<details>`): confidence (reworded as a symptom axis),
 *   the full class checklist (search/add/clear — today's mechanism, unchanged), tracking mode.
 * - **Tier 2 "Expert"** ({@link expertTier}): the fps floor slider (relabeled — the adaptive rate
 *   controller only ever raises above it, never holds it, so "the rate" would be a false label),
 *   capability ceiling, engine picker, verify cadence, follow sampling, the flow strip, and the full
 *   Serving/lag readout. Nothing is deleted, only demoted — every function above still backs it.
 *
 * Each disclosure's open state is a plain `UiStore` instance owned by this component
 * (docs/plans/done/UI-ARCHITECTURE-PLAN.md's own pattern: "a host owns one as a plain field") rather
 * than a new boolean — persisted via `localStorage` so an expert who keeps Expert open doesn't lose
 * that every reload. The two disclosures are independent (separate `UiStore` instances, not one
 * shared mutually-exclusive group) — nothing about this panel requires Tune and Expert to be
 * exclusive of each other.
 *
 * **Per-chip click writes the deny-list, immediately — never staged, never the allowlist** (wave W5,
 * docs/plans/active/CV-CLEAN-FEED-PLAN.md D-3, replacing the old staged-selection-only mechanism):
 * {@link toggleChip} calls `toggleLabelDeny` (`core/detections/detections-logic.ts`) and writes
 * straight through {@link applyHotKnob}, the same debounced hot-knob path every other slider already
 * uses — there is no first-click surprise to warn about (an empty deny-list unambiguously means
 * "deny nothing", unlike `labelFilter`'s "empty means all" allowlist). {@link isChecked} reflects the
 * honest combined truth of *both* gates ({@link isLabelChecked} against the allowlist, and-ed with
 * `!isLabelDenied` against the deny-list) — so a class the (separately, rarely edited) allowlist
 * already excludes never renders as falsely visible just because it isn't individually denied.
 * `labelFilter` itself stays staged ({@link pendingLabels}, direct user request): only the preset
 * fill, free-text add, and Clear-all touch it, and only {@link submitLabelFilter} actually writes it,
 * once. See {@link pendingLabels}'s own doc comment for the full staging model, including why it
 * stays deliberately orthogonal to instance lock ({@link lockedTrackId}).
 */
@Component({
  selector: 'vision-cv-control-panel',
  imports: [],
  templateUrl: './cv-control-panel.html',
  styleUrl: './cv-control-panel.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CvControlPanel {
  /** The primary device's currently-running stream id, or `undefined` before the first Start —
   * gates whether an edit also PATCHes live (see class doc). */
  readonly streamId = input<string | undefined>(undefined);

  /** The running stream's own server-side detect intent (`CockpitFacade`'s `facade.detectionOn()`,
   * docs/plans/active/STREAM-STATE-PLAN.md §3.1) — already resolved by the facade against the draft, so
   * this component renders it rather than re-deciding the rule. See {@link onDetectionEnabledToggle}
   * for why the switch never renders local intent. */
  readonly detectionEnabled = input<boolean>(false);

  /** True while the host's Detect on/off request is in flight — see {@link onDetectionEnabledToggle}. */
  readonly detectionPending = input<boolean>(false);

  /** Emitted by the Detect switch; the host (`CockpitFacade#setDetection`) owns the actual write. */
  readonly detectionEnabledChange = output<boolean>();

  /** The detection-boxes declutter level (`FlyPage`'s own `facade.boxesMode`, formerly the standalone
   * `layers` drawer's only control) — a client-side rendering preference, not part of
   * `PipelineSettings`/the wire contract, so it round-trips via a plain input/output pair rather than
   * `SettingsStore`. Defaults to {@link DEFAULT_DECLUTTER_LEVEL} ('priority') rather than the old
   * literal `'overlay'`, which is no longer a valid `BoxesMode` value as of wave W4. */
  readonly boxesMode = input<BoxesMode>(DEFAULT_DECLUTTER_LEVEL);
  /** Emitted when the operator picks a different declutter level — the host (`fly.ts`/`fly.html`)
   * owns the actual signal and writes it back via `facade.boxesMode.set($event)`. */
  readonly boxesModeChange = output<BoxesMode>();
  /** The four declutter levels, in cycle order — the segmented control's own `@for` source, so the
   *  template names each level via {@link boxesModeLabel} instead of restating literals. */
  protected readonly declutterLevels = DECLUTTER_LEVELS;
  /** The declutter level's own display name — thin wrapper so the template calls it as a method,
   *  matching this file's existing `capabilityLevelName` precedent. Named distinctly from the
   *  imported {@link declutterLevelLabel} pure function it wraps, rather than shadowing it. */
  protected boxesModeLabel(mode: BoxesMode): string {
    return declutterLevelLabel(mode);
  }

  protected readonly fleet = inject(FleetStore);
  protected readonly settings = inject(SettingsStore);
  private readonly toasts = inject(ToastService);
  /** Recent detection results and the tracks poll (`GET .../tracks`) alike — host-provided
   *  (`cockpit.ts`'s own `providers`), same instance the detections strip and `CockpitFacade` share.
   *  See class doc's own "The tracks poll itself is owned by DetectionsStore" paragraph. */
  protected readonly detections = inject(DetectionsStore);

  /** Tier 1's open/closed state — see this class's own "Three-tier layout" doc comment for why a
   *  plain `UiStore` field, not a new boolean. */
  protected readonly tuneTier = new UiStore('cv-panel-tune-open');
  /** Tier 2's open/closed state — independent of {@link tuneTier} (its own `UiStore` instance, not a
   *  shared mutually-exclusive group). */
  protected readonly expertTier = new UiStore('cv-panel-expert-open');
  private static readonly TIER_OPEN_ID = 'open';

  /** A native `<details>`'s own `toggle` event carries whether it just opened or closed — read
   *  straight off the element, mirroring `app-sidebar.ts#onAdvancedToggle`'s identical idiom. */
  protected onTierToggle(tier: UiStore, event: Event): void {
    const open = (event.target as HTMLDetailsElement).open;
    if (open) {
      tier.open(CvControlPanel.TIER_OPEN_ID);
    } else {
      tier.close(CvControlPanel.TIER_OPEN_ID);
    }
  }

  /** The honest "hidden classes drop everywhere" sentence (docs/plans/active/CV-CLEAN-FEED-PLAN.md D-3) —
   *  shown verbatim in both the tier-0 "Seen now" section and Tune's full checklist, and by the
   *  detections strip's own one-click hide, so all three surfaces never say something different. */
  protected readonly hiddenClassTruth = HIDDEN_CLASS_TRUTH;

  /** Text in the Classes search box — filters {@link chips} live and, once nothing in that filtered
   * checklist matches, doubles as the "add a class not seen yet" free-text input. */
  protected readonly classQuery = signal('');
  protected readonly modelBusy = signal(false);

  /**
   * The staged **allowlist** edit, shared by tier 0's "Seen now" chips (candidate-list-only, since
   * wave W5 — see class doc's own "Per-chip click writes the deny-list" paragraph) and Tune's full
   * checklist — `null` means "no staged edit, mirrors {@link settings}'s own applied `labelFilter`";
   * a concrete (possibly empty) array is unapplied work waiting on {@link submitLabelFilter} or
   * {@link discardStagedLabels}. Only the preset fill ({@link fillPreset}), free-text add
   * ({@link addClass}), and {@link clearLabelFilter} write this signal — a chip click no longer does.
   *
   * Reset to `null` on a {@link streamId} change (constructor `effect`, below) and on a model switch
   * ({@link onModelChange}) — a staged edit built against one stream/model's observed vocabulary has
   * no guaranteed meaning against the next one, and silently carrying it over would submit a
   * selection the operator never actually reviewed against what's now on screen.
   *
   * **Orthogonal to instance lock, deliberately** ({@link lockedTrackId}/{@link releaseLock}): this
   * signal is *which classes are kept* server-side after inference; the lock is *which single
   * tracked object* the pipeline follows. A future "track just this one" feature (once tracking is
   * reliable enough to hold a target) must stay a separate mechanism — do not fold instance identity
   * into this array or otherwise couple the two axes.
   */
  protected readonly pendingLabels = signal<readonly string[] | null>(null);

  /** The list any staged edit currently builds on — see {@link pendingLabels}'s own doc comment. */
  protected readonly labelFilterSeed = computed(() =>
    stagedLabelSeed(this.pendingLabels(), this.settings.effective().labelFilter),
  );

  protected readonly models = computed(() => this.fleet.models());
  protected readonly selectedModel = computed(() => findModel(this.models(), this.settings.effective().model));
  protected readonly isOpenVocab = computed(() => this.selectedModel()?.openVocab ?? false);
  protected readonly perfHintText = computed(() => perfHint(this.isOpenVocab()));

  /** The staged allowlist selection (or, with nothing staged, the applied filter) unioned with the
   *  deny-list and every recently-observed label — reading through {@link labelFilterSeed} rather
   *  than the applied filter directly means a class the operator just staged (but hasn't submitted
   *  yet) stays visible in the checklist instead of disappearing until Submit; unioning in the
   *  deny-list means an already-hidden class stays visible (as "hidden") instead of disappearing the
   *  moment the server stops emitting it (see `chipCandidates`'s own doc comment). */
  protected readonly chips = computed(() =>
    chipCandidates(this.labelFilterSeed(), this.settings.effective().labelDenyFilter, observedLabels(this.detections.results())),
  );
  /** Tier 0's capped "Seen now" chips (docs/plans/active/CV-UX-RESEARCH.md §4.1, wave U4) — the
   *  quick glance; the full, uncapped {@link chips}/{@link filteredChips} checklist lives in Tune. */
  protected readonly seenNowChips = computed(() => recentObservedLabels(this.detections.results()));
  /** {@link chips}, narrowed by {@link classQuery} and selected-first sorted — what the checklist
   * actually renders (see `sortSelectedFirst`'s own doc comment for why selected-first). Sorts
   * against {@link labelFilterSeed} (staged, if any), the same "what's being edited right now" read
   * {@link isChecked} uses, so a chip's checked state and its position in the list never disagree. */
  protected readonly filteredChips = computed(() =>
    sortSelectedFirst(filterLabelsByQuery(this.chips(), this.classQuery()), this.labelFilterSeed()),
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

  // --- Tracking (docs/plans/done/TRACKING-PLAN.md §4's frozen wire contract, wave T7) ---------------------
  // Unlike the model/classes/confidence sections above, there is no `SettingsStore` draft behind
  // any of this — `StartStreamRequest` doesn't carry a `tracking` object in this app (out of this
  // wave's own scope), so every control here is a **live-only** PATCH, meaningful only once
  // {@link streamId} is set. `trackingMode`/`trackingEngineId` are re-synced from the tracks poll's
  // own `stats.mode`/`stats.engineId` whenever a poll succeeds (see the constructor `effect` below)
  // — the honest "what's actually running" figure, per docs/plans/done/TRACKING-PLAN.md R11 (an engine, or even
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

  // --- Capability ladder (docs/plans/active/TRACKING-V3-BAND1-CONTEXT.md, wave J4) -----------------------
  // The ceiling the operator picks (`capabilityLevel`, below) and what the host actually *served*
  // (`servedCapability`, further down) are deliberately two different signals fed from two different
  // places — invariant B5, "never render the requested level as though it were the outcome". Like
  // `verifyEveryMillis`/`followFps` above, the ceiling has **no server readback**: the wire carries no
  // "requested level" field to sync from (only `levelServed`+`reason` — see `TrackingCapability`'s own
  // doc comment), so this stays a local draft, seeded to `0` (Auto) and never corrected from a poll.

  /** The operator's last-clicked capability ceiling — `0` = Auto (the default, and the only value
   *  that must stay the default per this wave's own brief). See this section's own doc comment for
   *  why this has no readback, unlike {@link trackingMode}/{@link trackingEngineId} above. */
  protected readonly capabilityLevel = signal(0);
  protected readonly capabilityLevelOptions = CAPABILITY_LEVEL_OPTIONS;
  protected readonly capabilityLevelHintText = computed(() => capabilityLevelHint(this.capabilityLevel()));

  /** The most recent frame carrying tracking telemetry (`FrameTracking`, the nested `"tracking"`
   *  object on a detection result) — `undefined` while tracking has produced nothing yet this
   *  session. This is where `capability`/`detectionLagMillis` actually live on the wire; **not** the
   *  `GET .../tracks` poll {@link DetectionsStore#tracks} reads (that response has no such fields). */
  protected readonly frameTracking = computed(() => latestFrameTracking(this.detections.results()));

  /** The capability facts for the most recent frame, or `undefined` — absent while `frameTracking()`
   *  itself is absent (tracking off / nothing yet), **and** absent whenever a frame exists but
   *  reports no level at all (a pre-V3 cv-service — invariant B3). `cv-control-panel.html` renders
   *  this exact distinction: no `frameTracking()` hides the whole capability readout (nothing to
   *  report yet, same posture as the flow strip); a present `frameTracking()` with no `capability`
   *  shows an explicit "no level reported" reading instead of silently hiding or, worse, echoing
   *  {@link capabilityLevel}'s own local ceiling into this slot (B5). */
  protected readonly servedCapability = computed(() => this.frameTracking()?.capability);

  /** Whether {@link servedCapability} should render as a downgrade rather than a quiet reading — see
   *  `isCapabilityDowngraded`'s own doc comment for why this reads the server's own `reason` field
   *  and never a local comparison against {@link capabilityLevel}. */
  protected readonly capabilityDowngraded = computed(() => isCapabilityDowngraded(this.servedCapability()));

  /** `docs/conclusions/CV-RATE-BUDGET.md` §1's *Hold* budget, re-exported for the template's own
   *  "< Xms" copy — kept as one constant, never restated as a literal in the template. */
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

  /** The engine picker's own candidate list, filtered to the currently-selected mode (`[]` for `OFF`). */
  protected readonly engineOptions = computed(() => engineOptionsForMode(this.fleet.trackers(), this.trackingMode()));

  /**
   * `0` = no lock held. **This is the one and only signal the "Following #N — release" chip reads**
   * — never the trackId just clicked, never an optimistic local flag. See this class's own doc
   * comment and docs/extracts/TRACKING-ORCHESTRATION.md §3.3.
   */
  protected readonly lockedTrackId = computed(() => this.detections.tracks()?.lockedTrackId ?? 0);

  /**
   * Mirrors {@link lockedTrackId} out to the host, for `shared/player/player.ts`'s `lockedTrackId`
   * input (docs/plans/active/CV-FLY-INTERACTION-RESEARCH.md §3.2, wave W4) — the overlay's T0 tier
   * needs the same honest, wire-confirmed-only lock id the "Following #N" chip already reads. An
   * `effect` rather than a template binding: this component has no direct reference to the player, only
   * `cockpit.html` does, so the value has to leave via an output for the host to re-bind onto
   * `<vision-player [lockedTrackId]>`.
   */
  readonly lockedTrackIdChange = output<number>();

  /** The flow strip's own text, or `null` to hide it entirely (`stats` absent — docs/plans/done/TRACKING-PLAN.md
   * §10 touchable outcome #2). */
  protected readonly flowStripText = computed(() => {
    const stats = this.detections.tracks()?.stats;
    return stats ? formatFlowStrip(stats) : null;
  });

  // --- Detection status line (docs/plans/active/CV-UX-RESEARCH.md §1.2/§3/§9.2, waves U3+U5) -----
  // The Detect hero's one honest sentence — see `detectionStatus`'s own doc comment
  // (`cv-control-panel-logic.ts`) for the full priority order. The tracks poll already carries
  // `rate`/`detectionState` (mirrored 1:1 in `core/api/models.ts`, wave U3) — no new poll needed.

  protected readonly hasStream = computed(() => !!this.streamId());
  /** The single most recent detection result, or `undefined` — what's actually on screen right
   *  now, distinct from {@link chips}'s running observed-label history. */
  private readonly latestResult = computed(() => this.detections.results()[0]);
  protected readonly classesOnScreen = computed(() =>
    classesOnScreenCount(this.latestResult(), this.settings.effective().labelFilter),
  );
  protected readonly detectionStatusInfo = computed(() =>
    detectionStatus(
      // Server truth while a stream runs, the draft otherwise — the same {@link detectionEnabled}
      // the switch above renders, so the switch position and this sentence can never disagree
      // (docs/plans/active/STREAM-STATE-PLAN.md §3.1). Reading the draft here was how "Off — video only"
      // could sit under a switch the backend had on.
      this.detectionEnabled(),
      this.hasStream(),
      this.detections.tracks()?.detectionState,
      this.detections.tracks()?.rate?.submittedFps,
      this.classesOnScreen(),
    ),
  );
  protected readonly detectionStatusText = computed(() => this.detectionStatusInfo().text);

  constructor() {
    inject(DestroyRef).onDestroy(() => {
      this.hotKnobPatch.cancel();
      this.detections.untrackTracks();
    });

    // Re-syncs the mode/engine picker's own selected-state from the wire's own ground truth
    // whenever a poll actually carries `stats` — see this section's own doc comment for why this is
    // the honest behavior (R11), not an override fight with the operator's own last click (a click
    // always fires its own fresh PATCH; the next poll simply confirms — or corrects — it).
    effect(() => {
      const stats = this.detections.tracks()?.stats;
      if (stats) {
        this.trackingMode.set(stats.mode);
        this.trackingEngineId.set(stats.engineId);
      }
    });

    // A stream change (including "stream stopped", `undefined`) starts/stops the tracks poll this
    // component owns the lifetime of (`DetectionsStore#trackTracks`/`untrackTracks`, wave W5) — that
    // call itself clears any stale lock/flow-strip reading immediately, so a *previous* stream's
    // response can never linger into the next. Also drops any staged-but-unsubmitted allowlist edit
    // ({@link pendingLabels}'s own doc comment) — built against the previous stream's observed
    // vocabulary, with no guaranteed meaning against whatever comes next.
    effect(() => {
      const streamId = this.streamId();
      this.pendingLabels.set(null);
      if (streamId) {
        this.detections.trackTracks(streamId);
      } else {
        this.detections.untrackTracks();
      }
    });

    // See {@link lockedTrackIdChange}'s own doc comment — re-emits on every change, including back to
    // `0` the instant a poll confirms the lock was released (never a stale "still locked" echo).
    effect(() => this.lockedTrackIdChange.emit(this.lockedTrackId()));
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

  /** Whether `label` renders as visible in the checklist — the honest combined truth of both gates:
   *  in the (staged, if any) allowlist ({@link isLabelChecked}) *and* not in the deny-list
   *  ({@link isLabelDenied}). A click ({@link toggleChip}) only ever writes the deny-list half; the
   *  allowlist half can only be narrowed via the preset/add/clear bulk-edit paths, so a class the
   *  allowlist already excludes correctly stays unchecked even though clicking it cannot restore it
   *  (see class doc's own "Per-chip click writes the deny-list" paragraph). */
  protected isChecked(label: string): boolean {
    return isLabelChecked(this.labelFilterSeed(), label) && !isLabelDenied(this.settings.effective().labelDenyFilter, label);
  }

  // --- Hot knobs (live-patched, debounced, while a stream is running) --------------------------

  protected onConfidence(value: string): void {
    this.applyHotKnob({ confidenceThreshold: Number(value) });
  }

  protected onFps(value: string): void {
    this.applyHotKnob({ inferenceFps: Number(value) });
  }

  /**
   * **Not** an `applyHotKnob` edit, unlike its slider siblings above
   * (docs/plans/active/STREAM-STATE-PLAN.md §3.1). Two differences, both deliberate:
   *
   * <ul>
   *   <li>it is not debounced — a single explicit click is not a gesture that might still be
   *       mid-drag, and the facade's own "Turn on" chip has always fired this immediately;</li>
   *   <li>it does not write what the operator clicked into the rendered value. {@link detectionEnabled}
   *       is server truth, so the switch moves once the wire says it moved. Emitting rather than
   *       self-applying is what keeps that single rule in one place (the facade) instead of two.</li>
   * </ul>
   */
  protected onDetectionEnabledToggle(checked: boolean): void {
    this.detectionEnabledChange.emit(checked);
  }

  // --- Class-filter chips: one immediate deny-list action, three staged allowlist actions --------

  /** One chip's click/× action — immediately toggles `label` in the deny-list and PATCHes through
   *  {@link applyHotKnob}, the same debounced hot-knob path every slider already uses. **Never**
   *  touches the staged allowlist ({@link pendingLabels}) — see class doc's own "Per-chip click
   *  writes the deny-list" paragraph and `toggleLabelDeny`'s own doc comment
   *  (`core/detections/detections-logic.ts`) for why this needs no staging: an empty deny-list has
   *  an unambiguous "deny nothing" meaning, so there is no "first click narrows everything else away"
   *  surprise the way there was for `labelFilter`. Shared verbatim by tier 0's "Seen now" chips and
   *  Tune's full checklist (both call this same method from their own templates). */
  protected toggleChip(label: string): void {
    this.applyHotKnob({ labelDenyFilter: toggleLabelDeny(this.settings.effective().labelDenyFilter, label) });
  }

  /** Adds {@link classQuery}'s text as a new staged **allowlist** class — only ever called once
   * {@link showAddClass} is true (i.e. the query matched nothing already in the checklist to toggle
   * instead). */
  protected addClass(): void {
    const label = this.classQuery();
    const seed = this.labelFilterSeed();
    const next = addLabel(seed, label);
    this.classQuery.set('');
    if (next !== seed) {
      this.pendingLabels.set(next);
    }
  }

  /** Enter in the search box only acts when it would add a brand-new class (see {@link addClass}'s
   * own doc comment) — filtering down to an existing match is a click on that chip, not Enter. */
  protected onClassQueryEnter(): void {
    if (this.showAddClass()) {
      this.addClass();
    }
  }

  /** Stages "show every class" for the **allowlist** — does **not** submit by itself (design
   *  decision: Clear-then-Submit is the one documented path back to "all objects", not an implicit
   *  side effect of clicking Clear alone). Never touches the deny-list. */
  protected clearLabelFilter(): void {
    this.pendingLabels.set([]);
  }

  /** Stages the convenience preset on top of {@link labelFilterSeed} — the **allowlist** only, same
   *  "seed once, build on the stage" rule every other staging handler here follows. */
  protected fillPreset(): void {
    this.pendingLabels.set(applyPreset(this.labelFilterSeed()));
  }

  /** The Submit button's own label (see `submitLabelFilterButtonText`'s own doc comment for why it
   *  states the outcome rather than reading "Submit"). A thin wrapper so the template can call it as
   *  a method, matching this file's existing `capabilityLevelName` precedent. */
  protected submitButtonText(pending: readonly string[]): string {
    return submitLabelFilterButtonText(pending);
  }

  /** Applies the staged **allowlist** edit exactly once, through the same `applyHotKnob` path every
   *  other hot knob already uses, then clears the stage. A no-op if nothing is staged (defensive —
   *  the Submit button only ever renders while {@link pendingLabels} is non-null). */
  protected submitLabelFilter(): void {
    const pending = this.pendingLabels();
    if (pending === null) {
      return;
    }
    this.applyHotKnob({ labelFilter: pending });
    this.pendingLabels.set(null);
  }

  /** Drops the staged allowlist edit outright, reverting the checklist back to mirroring whatever
   *  is actually applied — the "discard" half of "count + Submit + a way to discard". */
  protected discardStagedLabels(): void {
    this.pendingLabels.set(null);
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
    this.pendingLabels.set(null); // a staged edit was built against the old model's own observed vocabulary
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
