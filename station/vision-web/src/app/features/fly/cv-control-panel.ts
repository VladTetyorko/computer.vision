import { ChangeDetectionStrategy, Component, DestroyRef, computed, effect, inject, input, output, signal } from '@angular/core';
import { FleetStore } from '../../core/fleet/fleet-store';
import { PollScheduler } from '../../core/poll-scheduler';
import { SettingsStore, type PipelineSettings } from '../../core/settings/settings-store';
import { ToastService } from '../../core/toast.service';
import { UiStore } from '../../core/ui/ui-store';
import { readPersistedFlag, writePersistedFlag } from '../../core/panel-state';
import { SidePanel } from '../../shared/ui/side-panel';
import type { DetectionResult, StreamTracksResponse, TrackingMode, UpdateStreamConfigRequest } from '../../core/api/models';
import type { BoxesMode } from '../../shared/player/player';
import { resolveBurnedIn } from '../../shared/player/detection-overlay-logic';
import {
  CAPABILITY_LEVEL_OPTIONS,
  DEFAULT_FOLLOW_FPS,
  DEFAULT_VERIFY_EVERY_MILLIS,
  DETECTION_LAG_BUDGET_MILLIS,
  FIRST_HIDE_HINT,
  HIDDEN_CLASS_TRUTH,
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
  toggleLabelChip,
} from './cv-control-panel-logic';

/** How long a hot-knob edit (confidence/fps/labelFilter/detectionEnabled) waits for further edits
 * before actually sending the PATCH — coalesces a fast slider drag or a burst of chip clicks into
 * one request instead of one per input event. */
const HOT_KNOB_DEBOUNCE_MS = 400;

/**
 * How often the Tracking section polls `GET /api/streams/{id}/tracks` while the drawer is open and
 * a stream is running (docs/plans/done/TRACKING-PLAN.md §4.E) — feeds the flow strip and is the **only** source
 * the "Following #N" chip is allowed to confirm from (docs/extracts/TRACKING-ORCHESTRATION.md §3.3's honesty
 * rule). Mirrors `DetectionsStore`'s own poll cadence (`POLL_INTERVAL_MS`) — fast enough that the
 * chip/flow-strip feel live, slow enough to be a background read, never a user-facing spinner.
 *
 * **Deliberately left ungated on `LiveStore` (docs/plans/active/SCALE-100-PLAN.md §5 S6, item 2, the
 * plan's own recommendation).** There is no `tracks:<streamId>`/matching topic on `GET /api/live` to
 * project instead — `LiveEnvelope`'s union (`core/api/models.ts`) carries `detections`, not track
 * book/lock/duty-cycle stats, so gating this against `isLiveAvailable()` would just mean "poll
 * nothing and show nothing" rather than "poll nothing and stay fresh via live data" the way
 * `cockpit-facade.ts`/`drone-picker-facade.ts`/`core/geofence/geofence-store.ts` do. The actual
 * request-rate cost is already bounded without gating: {@link pollTracks} itself no-ops (no HTTP
 * call at all) unless the drawer is open **and** a stream is running — an idle cockpit tab with this
 * drawer closed issues zero requests from this poller regardless of `LiveStore`'s own state, so
 * there is nothing here for an idle-tab budget to spend. Folding tracks into the `detections:<assetId>`
 * payload instead was considered and rejected: it's a different domain (track book/lock/duty-cycle,
 * not detection boxes) and a wire-contract change is out of this wave's frontend-only scope.
 */
const TRACKS_POLL_INTERVAL_MS = 2_000;

/**
 * The Fly cockpit's live CV control panel (docs/plans/done/CV-CONTROL-PLAN.md Wave E) — model picker,
 * confidence/inference-rate sliders, a class-filter chip checklist, a detection on/off toggle, and
 * (per direct user request) the detection-boxes rendering-mode control formerly owned by its own
 * standalone `layers` drawer — see {@link boxesMode}/{@link boxesModeChange} and this component's own
 * "Boxes rendering" section (`cv-control-panel.html`); `fly-logic.ts`'s `ToolRailPanelId` no longer
 * carries `layers` at all.
 * Migrated into the shared `vision-side-panel` drawer shell (docs/plans/done/UI-REDESIGN-PLAN.md Wave 2, D-E):
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
 * (docs/plans/done/CV-CONTROL-PLAN.md §3). No optimistic lies about the model change specifically: the
 * "re-arming detection" toast only ever fires off the server's own `modelReArmed` field, never
 * assumed client-side (`cv-control-panel-logic.ts#reArmHint`).
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
 * for one real-world thing) — the candidate set is the union of the current filter and labels
 * actually observed in {@link detectionResults} (`chip-candidates`, `cv-control-panel-logic.ts`),
 * so the operator prunes from what the model is really emitting. `seedLabelFilterForModel` also
 * means switching to the open-vocab model always starts unfiltered ("show everything"), never a
 * silently-narrowing preset; `applyPreset`'s "People + vehicles + buildings" chip-fill is an
 * explicit, opt-in convenience button, not an enforced default.
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
 * **The class filter is staged, not immediate** ({@link pendingLabels}, direct user request): a
 * chip click no longer PATCHes on every click — it edits a local staged selection that both class-
 * filter surfaces (tier 0's "Seen now" chips, Tune's full checklist) share, and only
 * {@link submitLabelFilter} actually writes the wire, once. This is a UI-only change: `labelFilter`
 * itself is still the exact "empty means all" whitelist `PipelineConfig`'s own javadoc always
 * described (see {@link HIDDEN_CLASS_TRUTH} for what a non-empty selection actually does — and does
 * not do — once submitted). See {@link pendingLabels}'s own doc comment for the full staging model,
 * including why it stays deliberately orthogonal to instance lock ({@link lockedTrackId}).
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

  /** The running stream's own server-side detect intent (`CockpitFacade`'s `facade.detectionOn()`,
   * docs/plans/active/STREAM-STATE-PLAN.md §3.1) — already resolved by the facade against the draft, so
   * this component renders it rather than re-deciding the rule. See {@link onDetectionEnabledToggle}
   * for why the switch never renders local intent. */
  readonly detectionEnabled = input<boolean>(false);

  /** True while the host's Detect on/off request is in flight — see {@link onDetectionEnabledToggle}. */
  readonly detectionPending = input<boolean>(false);

  /** Emitted by the Detect switch; the host (`CockpitFacade#setDetection`) owns the actual write. */
  readonly detectionEnabledChange = output<boolean>();

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

  /** Whether this stream's video actually carries burned-in boxes (`CockpitFacade`'s own
   * `facade.stream()?.burnedIn`, docs/plans/active/MEDIA-SOT-PLAN.md §8 wave M8) — `undefined` (no
   * stream yet, or a pre-M5 backend that never sends the field) keeps today's three-way toggle. Gates
   * {@link showBurnedInOption}: once a stream is confirmed burn-in-free, offering "Burned in" would
   * be a control that visibly does nothing when clicked. */
  readonly burnedIn = input<boolean | undefined>(undefined);
  /** `cv-control-panel.html`'s own "Boxes rendering" segmented control — see {@link burnedIn}'s doc
   * comment. */
  protected readonly showBurnedInOption = computed(() => resolveBurnedIn(this.burnedIn()));

  /** Whether the drawer is open — driven by the host's `PanelState` (`fly.ts`'s `panels`), not this
   * component's own state (docs/plans/done/UI-REDESIGN-PLAN.md D-E). */
  readonly open = input<boolean>(false);
  /** Emitted when the drawer's own close control (`<vision-side-panel>`'s head button, or Esc) fires
   * — the host is the one that actually closes it (`panels.close()`). */
  readonly close = output<void>();

  protected readonly fleet = inject(FleetStore);
  protected readonly settings = inject(SettingsStore);
  private readonly toasts = inject(ToastService);

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

  /** The honest "hidden classes drop everywhere" sentence (docs/plans/active/CV-UX-RESEARCH.md
   *  §4.3) — shown verbatim in both the tier-0 "Seen now" section and Tune's full checklist, so the
   *  two surfaces never say something different. */
  protected readonly hiddenClassTruth = HIDDEN_CLASS_TRUTH;

  private static readonly FIRST_HIDE_HINT_KEY = 'cv-panel-first-hide-hint-dismissed';
  /** Whether the tier-0 "Seen now" first-hide hint has already been dismissed once — persisted, so
   *  it never nags an operator who has already seen it (docs/plans/active/CV-UX-RESEARCH.md §4.4). */
  private readonly firstHideHintDismissed = signal(readPersistedFlag(CvControlPanel.FIRST_HIDE_HINT_KEY, false));
  /** Shown once, as soon as the operator has staged (not necessarily submitted yet) their first
   *  narrowing edit — a filter still at `[]` ("all") has nothing to warn about yet. Reads
   *  {@link pendingLabels} directly rather than {@link labelFilterSeed} so the warning appears while
   *  they're still deciding, before Submit, not only after it's already applied. */
  protected readonly showFirstHideHint = computed(() => {
    if (this.firstHideHintDismissed()) {
      return false;
    }
    const pending = this.pendingLabels();
    return pending !== null ? pending.length > 0 : this.settings.effective().labelFilter.length > 0;
  });
  protected readonly firstHideHintText = FIRST_HIDE_HINT;
  protected dismissFirstHideHint(): void {
    this.firstHideHintDismissed.set(true);
    writePersistedFlag(CvControlPanel.FIRST_HIDE_HINT_KEY, true);
  }

  /** Text in the Classes search box — filters {@link chips} live and, once nothing in that filtered
   * checklist matches, doubles as the "add a class not seen yet" free-text input. */
  protected readonly classQuery = signal('');
  protected readonly modelBusy = signal(false);

  /**
   * The staged class-filter edit, shared by tier 0's "Seen now" chips and Tune's full checklist —
   * `null` means "no staged edit, mirrors {@link settings}'s own applied `labelFilter`"; a concrete
   * (possibly empty) array is unapplied work waiting on {@link submitLabelFilter} or
   * {@link discardStagedLabels}. Every chip/preset/add/clear handler in this component writes only
   * this signal, never `settings.adjust` directly — see {@link labelFilterSeed} for the one read
   * every one of them starts from, so the two surfaces can never disagree about what's staged.
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

  /** The staged selection (or, with nothing staged, the applied filter) unioned with every
   *  recently-observed label — reading through {@link labelFilterSeed} rather than the applied
   *  filter directly means a class the operator just staged (but hasn't submitted yet) stays visible
   *  in the checklist instead of disappearing until Submit. */
  protected readonly chips = computed(() =>
    chipCandidates(this.labelFilterSeed(), observedLabels(this.detectionResults())),
  );
  /** Tier 0's capped "Seen now" chips (docs/plans/active/CV-UX-RESEARCH.md §4.1, wave U4) — the
   *  quick glance; the full, uncapped {@link chips}/{@link filteredChips} checklist lives in Tune. */
  protected readonly seenNowChips = computed(() => recentObservedLabels(this.detectionResults()));
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
   *  `GET .../tracks` poll {@link tracksResponse} reads (that response has no such fields). */
  protected readonly frameTracking = computed(() => latestFrameTracking(this.detectionResults()));

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
   * comment and docs/extracts/TRACKING-ORCHESTRATION.md §3.3.
   */
  protected readonly lockedTrackId = computed(() => this.tracksResponse()?.lockedTrackId ?? 0);

  /** The flow strip's own text, or `null` to hide it entirely (`stats` absent — docs/plans/done/TRACKING-PLAN.md
   * §10 touchable outcome #2). */
  protected readonly flowStripText = computed(() => {
    const stats = this.tracksResponse()?.stats;
    return stats ? formatFlowStrip(stats) : null;
  });

  // --- Detection status line (docs/plans/active/CV-UX-RESEARCH.md §1.2/§3/§9.2, waves U3+U5) -----
  // The Detect hero's one honest sentence — see `detectionStatus`'s own doc comment
  // (`cv-control-panel-logic.ts`) for the full priority order. `tracksResponse` already carries
  // `rate`/`detectionState` (mirrored 1:1 in `core/api/models.ts`, wave U3) — no new poll needed.

  protected readonly hasStream = computed(() => !!this.streamId());
  /** The single most recent detection result, or `undefined` — what's actually on screen right
   *  now, distinct from {@link chips}'s running observed-label history. */
  private readonly latestResult = computed(() => this.detectionResults()[0]);
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
      this.tracksResponse()?.detectionState,
      this.tracksResponse()?.rate?.submittedFps,
      this.classesOnScreen(),
    ),
  );
  protected readonly detectionStatusText = computed(() => this.detectionStatusInfo().text);

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
    // to linger for up to `TRACKS_POLL_INTERVAL_MS`. Also drops any staged-but-unsubmitted class
    // filter edit ({@link pendingLabels}'s own doc comment) — built against the previous stream's
    // observed vocabulary, with no guaranteed meaning against whatever comes next.
    effect(() => {
      this.streamId();
      this.tracksResponse.set(null);
      this.pendingLabels.set(null);
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

  /** Reads {@link labelFilterSeed} — the staged selection if one is in progress, else the applied
   *  filter — so a chip's checked state always matches what a click on it is about to do next. */
  protected isChecked(label: string): boolean {
    return isLabelChecked(this.labelFilterSeed(), label);
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

  // --- Staged class-filter edits (see {@link pendingLabels}'s own doc comment) — every handler
  // below writes only `pendingLabels`, never `settings.adjust`/`applyHotKnob` directly; only
  // {@link submitLabelFilter} does that, once, on an explicit Submit click. Shared verbatim by tier
  // 0's "Seen now" chips and Tune's full checklist (both call these same methods from their own
  // templates), so the two surfaces can never commit `labelFilter` under two different rules.

  /** Toggles one chip's staged checked state — bound to both the chip body (click anywhere to flip
   * it) and a checked chip's own "×" remove button, which is just this same action under a more
   * explicit affordance. Seeds the stage from what's actually applied on the first edit
   * ({@link labelFilterSeed}), then reuses `toggleLabelChip`'s own array math unchanged — see that
   * function's own doc comment for why staging didn't need a new toggle algorithm, only a new place
   * to put the result. */
  protected toggleChip(label: string): void {
    this.pendingLabels.set(toggleLabelChip(this.labelFilterSeed(), label, this.chips()));
  }

  /** Adds {@link classQuery}'s text as a new staged class — only ever called once {@link showAddClass}
   * is true (i.e. the query matched nothing already in the checklist to toggle instead). */
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

  /** Stages "show every class" — does **not** submit by itself (design decision: Clear-then-Submit
   *  is the one documented path back to "all objects", not an implicit side effect of clicking
   *  Clear alone). */
  protected clearLabelFilter(): void {
    this.pendingLabels.set([]);
  }

  /** Stages the convenience preset on top of {@link labelFilterSeed}, same "seed once, build on the
   *  stage" rule every other staging handler here follows. */
  protected fillPreset(): void {
    this.pendingLabels.set(applyPreset(this.labelFilterSeed()));
  }

  /** The Submit button's own label (see `submitLabelFilterButtonText`'s own doc comment for why it
   *  states the outcome rather than reading "Submit"). A thin wrapper so the template can call it as
   *  a method, matching this file's existing `capabilityLevelName` precedent. */
  protected submitButtonText(pending: readonly string[]): string {
    return submitLabelFilterButtonText(pending);
  }

  /** Applies the staged class-filter edit exactly once, through the same `applyHotKnob` path every
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

  /** Drops the staged edit outright, reverting both class-filter surfaces back to mirroring whatever
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
