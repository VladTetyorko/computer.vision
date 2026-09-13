import { ChangeDetectionStrategy, Component, DestroyRef, computed, effect, inject, input, output, signal } from '@angular/core';
import { FleetStore } from '../../core/fleet/fleet-store';
import { VisionApi } from '../../core/api/vision-api';
import { ToastService } from '../../core/toast.service';
import { describeHttpError } from '../../core/api-error';
import { DetectionsStore } from '../../core/detections/detections-store';
import { HIDDEN_CLASS_TRUTH, isLabelDenied, toggleLabelDeny } from '../../core/detections/detections-logic';
import type { CvProfileSources, EffectiveCvProfile, TrackingMode, UpdateStreamConfigRequest } from '../../core/api/models';
import { resolvedSourceLine } from './cv-setup-modal-logic';
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
  buildProfileRequestFromConfig,
  buildTrackingEnginePatch,
  buildVerifyCadencePatch,
  capabilityLevelHint,
  capabilityLevelLabel,
  chipCandidates,
  debounce,
  defaultAssetProfileDescription,
  defaultAssetProfileName,
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
  type ResolvedCvConfig,
} from './cv-control-panel-logic';

/**
 * The Fly cockpit's **Tuning modal** (renamed from "Detection setup" in docs/plans/active/
 * CV-ORCHESTRATION-PLAN.md §4.7, wave W3.4 — same component, same selector/class name, copy only)
 * (docs/plans/done/CV-PANEL-SPLIT-PLAN.md P1, §1
 * "Surface 2 — calm hands: decisions") — a centered dialog over the cockpit holding every
 * set-once/expert CV knob that used to live inside `cv-control-panel.ts`'s own "Tune"/"Expert"
 * disclosures: the "Looking for" model intent cards (each with a one-sentence "what it finds" plus
 * the honest cost word, P2 §1 item 2), one merged Classes section (search/toggle/add/clear over the
 * full roster, observed-recently labels sorted first, hidden/deny-listed labels marked and
 * un-hideable in place — P2 §5; **not** a separate "Seen now" list any more, see that section's own
 * doc comment below for why), confidence (reworded as a symptom axis, P2 §1 item 3), and the
 * collapsed Expert tier (fps floor — relabeled "Detector floor", P2 §1 item 4 — capability
 * ceiling, engine, cadences, flow strip, Serving/lag readouts). The Off/Associate/Follow tracking-
 * mode picker that used to live here is **removed** (E19, docs/plans/active/
 * CV-ORCHESTRATION-PLAN.md §4.7/§9, wave W3.4) — ASSOCIATE is always the running mode while
 * detection is on, FOLLOW is entered only by tapping a box/point, OFF only through a bound profile;
 * see {@link trackingMode}'s own doc comment. Opened by the panel's own
 * "Change…"/"Tuning…" buttons ({@link CvControlPanel#setupRequested}); the panel keeps the
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
 * **No local settings draft any more** (docs/plans/active/CV-SETTINGS-PLAN.md wave W7, H2) — every
 * control here reads {@link config} (`CockpitFacade#resolvedCvConfig`, an input like `streamId`),
 * merged with this component's own {@link pendingEdits} overlay for edits not yet echoed back by
 * the wire (see that field's own doc comment). The model roster and observed/tracked detections
 * still come from directly-injected app-wide services, the same shape `cv-control-panel.ts` already
 * uses (root-provided `FleetStore`/`VisionApi`/`ToastService`, and the cockpit-route-provided
 * `DetectionsStore` — see `cockpit.ts`'s own `providers` array; this component resolves the
 * identical instance the panel and the detections strip already share, since it is mounted inside
 * the same route's component tree). **Deliberately does not call `DetectionsStore#trackTracks`/`untrackTracks` itself** —
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
 * **Every tracking field now has a real readback on reopen** (wave W7, H6 —
 * docs/plans/active/CV-SETTINGS-PLAN.md §3.5 rule 3): `trackingEngineId` re-syncs from the tracks
 * poll's own `stats` the instant this component mounts, unchanged from before the split;
 * `trackingMode` (wave W3.4) reads that same `stats` directly as a `computed`, with nothing left to
 * "sync" since nothing local ever writes it any more. `capabilityLevel`/`verifyEveryMillis`/
 * `followFps` **used to have no readback at all** —
 * they reset to a hardcoded default on every reopen and otherwise just echoed whatever this browser
 * last clicked. They now seed from {@link config}'s own `tracking` object (`GET
 * /api/streams/{id}/config`, the exact same H6 fix `cv-control-panel.ts` never needed for this
 * family) via a second constructor `effect`, so a reopen shows the stream's actual state, not a
 * reset default.
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

  /** The one value every control in this modal renders from and builds its next PATCH from
   *  (`CockpitFacade#resolvedCvConfig`, wave W7) — see class doc's "No local settings draft" note. */
  readonly config = input<ResolvedCvConfig | undefined>(undefined);

  /** The asset's own effective CV profile — feeds "Save to this asset's profile"'s own create-vs-
   *  update decision below (see {@link saveToAssetProfile}), and, as of wave W7, its own `sources`/
   *  `intent` feed the resolved-source lines below with genuine per-knob provenance on every read
   *  (see {@link confidenceSourceLine}/{@link classesSourceLine} and {@link resolvedSourceLine}'s own
   *  doc comment) — no longer only a coarse whole-profile tier fallback. */
  readonly effectiveProfile = input<EffectiveCvProfile | undefined>(undefined);

  /**
   * This session's own most recent hot-knob PATCH's per-knob intent-resolution provenance
   * (`PatchStreamConfigResponse#sources`, wave W3.0/W3.3, bound from `CockpitFacade#lastConfigSources`
   * — wired into `<vision-cv-setup-modal>` in `cockpit.html` as of wave W7.5, closing the "always
   * `undefined`" gap this input carried since wave W3.4) — feeds {@link resolvedSourceLine}'s own
   * first-priority check, ahead of {@link effectiveProfile}'s own `sources` (wave W7): a same-session
   * live PATCH is a more immediate fact about the *running stream* than a profile-tier read can be.
   * `undefined` before any hot-knob PATCH has been sent this session, or after an asset switch resets
   * `CockpitFacade#lastConfigSources` — every resolved-source line below degrades honestly with no
   * value at all, falling through to {@link effectiveProfile}'s own per-knob fact or rendering
   * nothing, never a blocked page or a fabricated "Resolved from…" line.
   */
  readonly lastConfigSources = input<CvProfileSources | undefined>(undefined);

  /** The primary device's asset id — {@link saveToAssetProfile}'s write target; the action is a
   *  no-op with nothing to save into while this is `undefined`. */
  readonly assetId = input<string | undefined>(undefined);

  /** The asset's own display name — seeds a new profile's name/description (never used once the
   *  asset already owns one; see {@link saveToAssetProfile}). */
  readonly assetDisplayName = input<string | undefined>(undefined);

  /** `CockpitFacade#canManage` (`canManageOrg`) — gates the "Save to this asset's profile" footer
   *  button, mirroring `/vision/profiles`' own gate (`VisionProfilesFacade.canManage`). Passed as an
   *  input rather than re-derived here so the two surfaces can never disagree about who can write. */
  readonly canManage = input<boolean>(false);

  /** Emitted after any PATCH this modal sends succeeds — the host (`CockpitFacade#refreshStreamConfig`)
   *  re-reads `GET .../config` so {@link config} always renders the wire, never an assumption (H6). */
  readonly configChanged = output<void>();

  /** Emitted after a successful "Save to this asset's profile" — the host
   *  (`CockpitFacade#refreshEffectiveProfile`) re-reads the asset's own effective profile. */
  readonly profileSaved = output<void>();

  private readonly fleet = inject(FleetStore);
  private readonly api = inject(VisionApi);
  private readonly toasts = inject(ToastService);
  /** Read-only here — see this class's own doc comment for why `trackTracks`/`untrackTracks` are
   *  never called from this component. */
  protected readonly detections = inject(DetectionsStore);

  protected readonly hasStream = computed(() => !!this.streamId());

  /**
   * Local overlay of not-yet-confirmed hot-knob/model edits, merged on top of {@link config} before
   * both rendering and building the next PATCH (`applyHotKnob`/`onModelChange`) — without this,
   * editing two different fields inside the same {@link HOT_KNOB_DEBOUNCE_MS} window would silently
   * drop the earlier one: `debounce()` only ever fires its *last* call, and that call would
   * otherwise rebuild its patch from the still-stale {@link config} (nothing this component itself
   * sent has echoed back onto the wire yet). Cleared whenever {@link config} itself changes — a
   * fresh readback, either from this component's own {@link configChanged} round-trip or from a
   * stream/asset switch — so it can never drift stale once the wire actually agrees.
   */
  private readonly pendingEdits = signal<Partial<ResolvedCvConfig>>({});

  /** {@link config} merged with {@link pendingEdits} — every control below reads this, never
   *  {@link config} directly. */
  protected readonly liveConfig = computed<ResolvedCvConfig | undefined>(() => {
    const base = this.config();
    return base ? { ...base, ...this.pendingEdits() } : undefined;
  });

  protected readonly savingProfile = signal(false);

  /** The honest "hidden classes drop everywhere" sentence — shown verbatim once, at the foot of
   *  the merged Classes section (docs/plans/done/CV-PANEL-SPLIT-PLAN.md P2 §5 — the pre-P2 split
   *  across "Seen now" and "All classes" rendered this twice; the merge is also what fixed that). */
  protected readonly hiddenClassTruth = HIDDEN_CLASS_TRUTH;

  /** Confidence's own resolved-source line (wave W3.4, {@link resolvedSourceLine}'s precedence) —
   *  as of wave W7, `EffectiveCvProfile#sources.confidenceThreshold` is a real per-knob fact (the
   *  fold can now seed confidence from an intent too), so this can report `'INTENT'` in practice,
   *  unlike the pre-W7 "profile-tier fact or nothing" behavior this comment used to describe. */
  protected readonly confidenceSourceLine = computed(() =>
    resolvedSourceLine(this.lastConfigSources(), this.effectiveProfile(), 'confidenceThreshold'),
  );

  /** Classes' own resolved-source line (wave W3.4) — shown alongside the existing "Applied: N of
   *  the classes…" line, never in place of it. */
  protected readonly classesSourceLine = computed(() =>
    resolvedSourceLine(this.lastConfigSources(), this.effectiveProfile(), 'labelFilter'),
  );

  protected readonly classQuery = signal('');
  protected readonly modelBusy = signal(false);

  /** The staged **allowlist** edit — see `cv-control-panel.ts`'s pre-split doc comment for the
   *  full staging model (unchanged by this wave, only relocated). */
  protected readonly pendingLabels = signal<readonly string[] | null>(null);
  protected readonly labelFilterSeed = computed(() =>
    stagedLabelSeed(this.pendingLabels(), this.liveConfig()?.labelFilter ?? []),
  );

  protected readonly models = computed(() => this.fleet.models());
  protected readonly selectedModel = computed(() => findModel(this.models(), this.liveConfig()?.model ?? ''));
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
    chipCandidates(this.labelFilterSeed(), this.liveConfig()?.labelDenyFilter ?? [], observedLabels(this.detections.results())),
  );
  /** The most-recently-observed labels, in recency order — {@link filteredChips}' own sort
   *  priority (docs/plans/done/CV-PANEL-SPLIT-PLAN.md P2 §5). This is all that survives of the
   *  pre-P2 "Seen now" mini-checklist once it merged into the one full checklist below: recency was
   *  its real value, kept here as a sort key instead of a second rendered list. */
  protected readonly recentLabels = computed(() => recentObservedLabels(this.detections.results()));
  protected readonly filteredChips = computed(() =>
    sortRecentFirst(filterLabelsByQuery(this.chips(), this.classQuery()), this.recentLabels()),
  );
  protected readonly showAddClass = computed(
    () => this.classQuery().trim().length > 0 && !hasExactLabelMatch(this.chips(), this.classQuery()),
  );

  /** Emits {@link configChanged} on a successful PATCH only — a failed edit left the stream exactly
   *  where it was, so {@link pendingEdits} keeps standing in for it rather than being dropped. */
  private readonly hotKnobPatch = debounce((patch: ReturnType<typeof buildHotKnobPatch>) => {
    const streamId = this.streamId();
    if (!streamId) {
      return;
    }
    void this.fleet.patchStreamConfig(streamId, patch).then((result) => {
      if (result) {
        this.configChanged.emit();
      }
    });
  }, HOT_KNOB_DEBOUNCE_MS);

  // --- Tracking (docs/plans/done/TRACKING-PLAN.md §4's frozen wire contract, wave T7) — unchanged
  // from the pre-split panel beyond relocation; see this class's own doc comment for the "re-syncs
  // from stats vs. from `config`'s own readback" distinction between these fields.

  /**
   * The actual **running** tracking mode, read from the tracks poll's own `stats.mode` — never a
   * client-side choice any more (E19, docs/plans/active/CV-ORCHESTRATION-PLAN.md §4.7/§9 decision
   * #4): the Off/Associate/Follow picker that used to write this signal is removed from the
   * operator surface entirely. ASSOCIATE is the server's own default whenever detection is on
   * (`PipelineConfig#defaults()`, confirmed by reading the Java); FOLLOW is entered exclusively by
   * tapping a box/point (wave W3.5, not this component); OFF is reachable only through a bound
   * profile (`/vision/profiles`, wave W3.6). This is now purely a *read* of that outcome — the
   * Expert disclosure below still gates its capability-ceiling/engine/follow-cadence controls on
   * it, so it has to keep reflecting the real running mode even with no picker writing to it.
   * `'OFF'` before the first poll ever reports `stats` is the honest default: nothing is known to
   * be running yet.
   */
  protected readonly trackingMode = computed<TrackingMode>(() => this.detections.tracks()?.stats?.mode ?? 'OFF');
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

    // Re-syncs the engine choice from the wire's own ground truth the instant this component
    // mounts (and on every poll after) — an engine click always fires its own fresh PATCH, the
    // next poll simply confirms — or corrects — it. `trackingMode` itself needs no such sync any
    // more (E19): it is a `computed` reading the identical `stats` directly, never a signal a
    // picker used to write.
    effect(() => {
      const stats = this.detections.tracks()?.stats;
      if (stats) {
        this.trackingEngineId.set(stats.engineId);
      }
    });

    // H6 (docs/plans/active/CV-SETTINGS-PLAN.md §3.5 rule 3) — capabilityLevel/verifyEveryMillis/
    // followFps used to have **no readback at all**, resetting to a hardcoded default on every
    // reopen; see class doc's own "Every tracking field now has a real readback" note. Seeded from
    // `config()`, not `liveConfig()` — an in-flight, not-yet-confirmed tracking PATCH is always sent
    // immediately (never debounced/staged in `pendingEdits`, which exists only for the hot-knob
    // family), so there is nothing local to layer on top of the wire value here.
    effect(() => {
      const tracking = this.config()?.tracking;
      if (!tracking) {
        return;
      }
      this.capabilityLevel.set(tracking.capabilityLevel);
      this.verifyEveryMillis.set(tracking.verifyEveryMillis);
      this.followFps.set(tracking.followFps);
    });

    // Drops any not-yet-confirmed hot-knob overlay the instant a fresh readback arrives — see
    // `pendingEdits`' own doc comment for why this is the right moment (never sooner: a debounced
    // edit needs the overlay's *own* value to survive until its PATCH actually lands).
    effect(() => {
      this.config();
      this.pendingEdits.set({});
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
   *  `cv-control-panel.ts`'s identical, pre-split private method's own doc comment. Emits {@link
   *  configChanged} on success, same as `hotKnobPatch` — this is what feeds the H6 readback effect
   *  above (`config()?.tracking`) on the very next tick. */
  private patchTracking(patch: UpdateStreamConfigRequest): void {
    const streamId = this.streamId();
    if (!streamId) {
      return;
    }
    void this.fleet.patchStreamConfig(streamId, patch).then((result) => {
      if (result) {
        this.configChanged.emit();
      }
    });
  }

  protected isChecked(label: string): boolean {
    return isLabelChecked(this.labelFilterSeed(), label) && !isLabelDenied(this.liveConfig()?.labelDenyFilter ?? [], label);
  }

  /** Whether `label` is on the operator's own deny-list — the merged Classes checklist's explicit
   *  "hidden, click to un-hide" indicator (docs/plans/done/CV-PANEL-SPLIT-PLAN.md P2 §5's "hidden
   *  classes (deny-list) management … with un-hide"), mirroring `detections-strip.html`'s identical
   *  `chip.hidden` treatment so an operator sees the same "— hidden" wording in both places. The
   *  same {@link toggleChip} click both hides and un-hides — this only changes what the chip *says*. */
  protected isHidden(label: string): boolean {
    return isLabelDenied(this.liveConfig()?.labelDenyFilter ?? [], label);
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
    this.applyHotKnob({ labelDenyFilter: toggleLabelDeny(this.liveConfig()?.labelDenyFilter ?? [], label) });
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

  /** Layers `patch` onto {@link pendingEdits} (so it survives until its own PATCH lands — see that
   *  field's own doc comment) and sends the debounced hot-knob PATCH built from {@link liveConfig}
   *  merged with this one edit. A no-op before {@link config} has resolved at all — there is nothing
   *  honest to merge onto yet. */
  private applyHotKnob(patch: Partial<ResolvedCvConfig>): void {
    const current = this.liveConfig();
    if (!current) {
      return;
    }
    this.pendingEdits.update((pending) => ({ ...pending, ...patch }));
    this.hotKnobPatch.run(buildHotKnobPatch({ ...current, ...patch }));
  }

  /**
   * A model change is always its own separate PATCH (`buildModelChangePatch`, `model` alone — see
   * `core/api/models.ts#UpdateStreamConfigRequest`'s own frozen "two families of change never mix
   * in one call" rule). The seeded label filter this used to only reach the wire lazily, via
   * whatever *unrelated* hot-knob edit happened to come next (`SettingsStore#adjust` mutating a
   * local draft the old `buildHotKnobPatch` read on its own later schedule) — wave W7 sends it
   * promptly instead, as its own **second**, still-separate hot-knob PATCH right after the model
   * PATCH succeeds, rather than folding it into the model PATCH itself (which would blur the exact
   * boundary that rule exists to keep: a slider drag must never accidentally trigger a re-arm, and
   * the model PATCH must never accidentally carry an unrelated field either).
   */
  protected async onModelChange(modelId: string): Promise<void> {
    const current = this.liveConfig();
    if (!current || modelId === current.model) {
      return;
    }
    this.hotKnobPatch.cancel();
    this.pendingLabels.set(null);
    const seeded = seedLabelFilterForModel(findModel(this.models(), modelId));
    this.pendingEdits.update((pending) => ({ ...pending, model: modelId, labelFilter: seeded }));

    const streamId = this.streamId();
    if (!streamId) {
      return;
    }
    this.modelBusy.set(true);
    try {
      const response = await this.fleet.patchStreamConfig(streamId, buildModelChangePatch(modelId));
      if (response) {
        await this.fleet.patchStreamConfig(streamId, buildHotKnobPatch({ ...current, model: modelId, labelFilter: seeded }));
        this.configChanged.emit();
      }
      const hint = response ? reArmHint(response) : null;
      if (hint) {
        this.toasts.info(hint);
      }
    } finally {
      this.modelBusy.set(false);
    }
  }

  // --- "Save to this asset's profile" (docs/plans/active/CV-SETTINGS-PLAN.md §3.1 rule 2, §4) -----
  // The only write path that ever moves a live value *upward* into a profile — explicit, one click,
  // never a side effect of any of the PATCHes above. Updates the asset's own profile in place when
  // one is already bound at ASSET scope (`effectiveProfile()?.source === 'ASSET'`); otherwise creates
  // a new one and binds it, so the very next Start (and every other stream of this asset) picks up
  // what the operator is looking at right now.

  /** `true` while the asset already owns a profile of its own — {@link saveToAssetProfile} updates
   *  it in place rather than creating a second, orphaned one. */
  protected readonly assetOwnsProfile = computed(() => this.effectiveProfile()?.source === 'ASSET');

  protected async saveToAssetProfile(): Promise<void> {
    const assetId = this.assetId();
    const config = this.liveConfig();
    if (!assetId || !config || !this.canManage() || this.savingProfile()) {
      return;
    }
    this.savingProfile.set(true);
    try {
      const existing = this.effectiveProfile();
      const owned = existing?.source === 'ASSET' ? existing.profile : undefined;
      const displayName = this.assetDisplayName() ?? assetId;
      const request = buildProfileRequestFromConfig(
        config,
        owned?.name ?? defaultAssetProfileName(displayName),
        owned?.description ?? defaultAssetProfileDescription(displayName),
      );
      const profile = owned ? await this.api.updateCvProfile(owned.id, request) : await this.api.createCvProfile(request);
      await this.api.setCvProfileBinding({ scopeKind: 'ASSET', scopeId: assetId, profileId: profile.id });
      this.toasts.ok(`Saved to this asset's profile ("${profile.name}").`);
      this.profileSaved.emit();
    } catch (error) {
      this.toasts.error(describeHttpError(error));
    } finally {
      this.savingProfile.set(false);
    }
  }
}
