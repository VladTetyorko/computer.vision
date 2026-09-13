import { ChangeDetectionStrategy, Component, computed, inject, input, output } from '@angular/core';
import { FleetStore } from '../../core/fleet/fleet-store';
import { DetectionsStore } from '../../core/detections/detections-store';
import type { EffectiveCvProfile, UpdateStreamConfigRequest } from '../../core/api/models';
import type { BoxesMode } from '../../shared/player/player';
import { DECLUTTER_LEVELS, DEFAULT_DECLUTTER_LEVEL, declutterLevelLabel } from '../../shared/player/detection-overlay-logic';
import { TargetList } from './target-list';
import {
  DETECTION_LAG_BUDGET_MILLIS,
  buildFollowLockPatch,
  buildReleaseLockPatch,
  classesOnScreenCount,
  detectionStatus,
  effectiveProfileLine,
  findModel,
  isCapabilityDowngraded,
  isDetectionLagOverBudget,
  latestFrameTracking,
  modelCostWord,
  type ResolvedCvConfig,
} from './cv-control-panel-logic';

/**
 * The Fly cockpit's **fly-time Vision surface** (docs/plans/done/CV-PANEL-SPLIT-PLAN.md P1, §1
 * "Surface 1 — flying: seconds matter, one glance") — the seven controls an operator needs while
 * actually flying, top to bottom: the Detect hero switch with an honest status line, a "Looking
 * for" summary row (name + cost word, with a "Change…" door into the setup modal), the Boxes
 * declutter control, the "Following #N — release" lock chip, the two conditional honesty notices
 * (capability downgrade / lag-over-budget — surfaced only while they actually fire), and a
 * "Tuning…" door at the bottom (renamed from "Detection setup" in docs/plans/active/
 * CV-ORCHESTRATION-PLAN.md §4.7, wave W3.4). Every *decision* control — the model intent cards,
 * confidence, the class checklist, and the collapsed Expert tier — moved to
 * {@link CvSetupModal}, opened by {@link setupRequested} (`cockpit.ts` wires this to
 * `dialog.open('cv-setup')`, its own transient dialog `UiStore` group).
 *
 * **Split history**: this component used to be the single 474-line, 12-section CV control panel
 * (docs/plans/done/CV-CONTROL-PLAN.md Wave E through docs/plans/done/CV-CLEAN-FEED-PLAN.md wave
 * W5's merge into the one "Vision" drawer). docs/plans/done/CV-PANEL-SPLIT-PLAN.md P1 split it
 * into this fly-time panel and the calm-hands `CvSetupModal` — see that plan's §1 for the full
 * "what lives where" rationale (CV-UX-RESEARCH.md §2's task ranking is the underlying *why*). This
 * component keeps the shared pure logic in `cv-control-panel-logic.ts` alongside the modal (both
 * import from it) rather than forking a second copy.
 *
 * **Body-only, no drawer shell of its own** (wave W5, docs/plans/done/CV-CLEAN-FEED-PLAN.md D-3,
 * unchanged by the P1 split): `cockpit.html` owns the one `<vision-side-panel>` shell for the
 * merged Vision drawer, mounting this component and `<vision-detections-strip>` as siblings inside
 * it; mounting this component *is* opening it.
 *
 * **No longer owns the tracks poll** (docs/plans/active/TRACK-FOLLOW-PLAN.md §3.5, wave W4 —
 * superseding wave W5's "still owned here"): this panel used to start/stop `DetectionsStore#
 * trackTracks`/`untrackTracks` itself from its own mounted lifetime, which is exactly the D1 defect
 * the wave is named for — closing the drawer (unmounting this component) silently stopped the poll
 * that was the *only* thing keeping a `LOST` lock's recovery window observable. `CockpitFacade` now
 * drives the poll from a `wantsTracksPoll`-gated effect that outlives this panel's own mount/unmount;
 * this component only *reads* {@link DetectionsStore.tracks} for its own chip, never starts/stops it.
 *
 * **`detectionEnabled` is the one knob with a live wire readback** (docs/plans/active/STREAM-STATE-
 * PLAN.md §3.1) — see this class's own {@link onDetectionEnabledToggle} for why the hero switch
 * renders server truth, never optimistic local intent.
 *
 * **The "Following #N — release" chip is wire-confirmed only** — never a click's own optimistic
 * guess (docs/extracts/TRACKING-ORCHESTRATION.md §3.3): {@link lockedTrackId} only ever reads
 * `DetectionsStore#tracks()`'s own echoed `lockedTrackId`, unchanged by the split. This is
 * deliberately **not** the same value as `CockpitFacade#lockedTrackId` (wave W4 fixed the facade's
 * copy to read the per-frame feed instead, so the HUD/overlay survive a closed drawer) — this
 * panel's own chip is fine reading the slower tracks-poll copy since it only renders while the panel
 * itself (and therefore the poll driven for it) is mounted anyway.
 */
@Component({
  selector: 'vision-cv-control-panel',
  imports: [TargetList],
  templateUrl: './cv-control-panel.html',
  styleUrl: './cv-control-panel.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CvControlPanel {
  /** The primary device's currently-running stream id, or `undefined` before the first Start —
   * gates whether the follow-lock's release action also PATCHes live. */
  readonly streamId = input<string | undefined>(undefined);

  /** The running stream's own server-side detect intent (`CockpitFacade`'s `facade.detectionOn()`,
   * docs/plans/done/STREAM-STATE-PLAN.md §3.1) — already resolved by the facade against the draft, so
   * this component renders it rather than re-deciding the rule. See {@link onDetectionEnabledToggle}
   * for why the switch never renders local intent. */
  readonly detectionEnabled = input<boolean>(false);

  /** True while the host's Detect on/off request is in flight — see {@link onDetectionEnabledToggle}. */
  readonly detectionPending = input<boolean>(false);

  /** Emitted by the Detect switch; the host (`CockpitFacade#setDetection`) owns the actual write. */
  readonly detectionEnabledChange = output<boolean>();

  /** Emitted by the "Change…" and "Tuning…" buttons alike — the host (`cockpit.ts`) owns
   *  the actual `dialog.open('cv-setup')` call, mirroring `flight-command-panel.ts`'s own
   *  "component only reports the request, host owns the `UiStore` write" shape for its dialogs. */
  readonly setupRequested = output<void>();

  /** The detection-boxes declutter level (`FlyPage`'s own `facade.boxesMode`, itself an alias of
   * `SettingsStore.declutterLevel` — wave W7, H12) — one shared, persisted **View** preference
   * (docs/plans/active/CV-SETTINGS-PLAN.md §3.5 rule 1: a control with no backend effect lives in a
   * View group and says so), never part of {@link ResolvedCvConfig}/the wire contract, so it still
   * round-trips via a plain input/output pair rather than reading `SettingsStore` directly here. */
  readonly boxesMode = input<BoxesMode>(DEFAULT_DECLUTTER_LEVEL);
  readonly boxesModeChange = output<BoxesMode>();
  /** The four declutter levels, in cycle order — the segmented control's own `@for` source. */
  protected readonly declutterLevels = DECLUTTER_LEVELS;
  protected boxesModeLabel(mode: BoxesMode): string {
    return declutterLevelLabel(mode);
  }

  /** The one value this panel renders every live knob from (`CockpitFacade#resolvedCvConfig`,
   *  wave W7) — merges the running stream's own readback with the asset's effective profile, so
   *  this panel never renders a browser-local draft. `undefined` before either half has resolved. */
  readonly config = input<ResolvedCvConfig | undefined>(undefined);

  /** The asset's own effective CV profile (`GET /api/cv/profiles/effective?assetId=`) — feeds the
   *  "From profile …" line below, alongside {@link assetId} (see {@link profileLine}). */
  readonly effectiveProfile = input<EffectiveCvProfile | undefined>(undefined);

  /** The primary device's asset id, or `undefined` while nothing is selected — see {@link profileLine}. */
  readonly assetId = input<string | undefined>(undefined);

  /** Emitted after any PATCH this panel sends succeeds — the host (`CockpitFacade#refreshStreamConfig`)
   *  re-reads `GET .../config` so {@link config} always renders the wire, never an assumption (H6). */
  readonly configChanged = output<void>();

  /** "From profile "name" (source)" / "Platform defaults" / "—" — see that function's own doc
   *  comment for the three honest outcomes (docs/plans/active/CV-SETTINGS-PLAN.md §4 mockup). */
  protected readonly profileLine = computed(() => effectiveProfileLine(this.assetId(), this.effectiveProfile()));

  private readonly fleet = inject(FleetStore);
  /** Recent detection results and the tracks poll (`GET .../tracks`) alike — host-provided
   *  (`cockpit.ts`'s own `providers`), the same instance `CvSetupModal`, the detections strip, and
   *  `CockpitFacade` all share. See class doc's own "The tracks poll is still owned here" paragraph. */
  protected readonly detections = inject(DetectionsStore);

  // --- "Looking for" summary row (docs/plans/done/CV-PANEL-SPLIT-PLAN.md P1 §1.1 item 2) -------

  protected readonly models = computed(() => this.fleet.models());
  protected readonly selectedModel = computed(() => findModel(this.models(), this.config()?.model ?? ''));
  /** The summary row's own cost word — shared with the setup modal's intent cards via
   *  `cv-control-panel-logic.ts#modelCostWord`, so the two surfaces can never disagree. */
  protected costWord(openVocab: boolean): string {
    return modelCostWord(openVocab);
  }

  // --- Capability ladder + detection lag (docs/plans/active/TRACKING-V3-BAND1-CONTEXT.md, wave J4) --
  // Duplicated (not shared via an output) from `CvSetupModal`'s identical computed pair — both read
  // the same `DetectionsStore#results()` directly, so there is nothing to keep in sync by wiring;
  // this panel needs only the two *booleans* for its conditional notices below, never the modal's
  // own quiet "Serving"/lag-value readout.

  private readonly frameTracking = computed(() => latestFrameTracking(this.detections.results()));
  /** The downgrade notice's own reason text (`servedCapability()?.reason`) — the server's own
   *  `reason` field, never a local comparison (invariant B5). */
  protected readonly servedCapability = computed(() => this.frameTracking()?.capability);
  protected readonly capabilityDowngraded = computed(() => isCapabilityDowngraded(this.servedCapability()));
  protected readonly detectionLagBudgetMillis = DETECTION_LAG_BUDGET_MILLIS;
  protected readonly detectionLagOverBudget = computed(() =>
    isDetectionLagOverBudget(this.frameTracking()?.detectionLagMillis ?? 0),
  );

  // --- Click-to-follow lock chip (docs/plans/done/TRACKING-PLAN.md, wave T7; docs/plans/active/
  // CV-FLY-INTERACTION-RESEARCH.md §3.2, wave W4) — stays in this panel per the split plan: the
  // real Follow interaction is clicking a box in the video, not a control in either surface, and
  // the chip's own honesty rule (wire-confirmed only) is unrelated to which controls live where.

  /** `0` = no lock held. **This is the one and only signal the "Following #N — release" chip reads**
   * — never the trackId just clicked, never an optimistic local flag. */
  protected readonly lockedTrackId = computed(() => this.detections.tracks()?.lockedTrackId ?? 0);

  // --- Target list (docs/plans/active/TRACK-FOLLOW-PLAN.md §3.4, wave W5) — "the second door" onto
  // Follow, mounted at the top of this panel's own template (below the sibling detections strip in
  // `cockpit.html`, above this panel's own body — §3.4's placement, achieved from inside this
  // component since `cockpit.html` itself is out of this wave's file scope). Reads the exact same
  // `DetectionsStore#tracks()` poll {@link lockedTrackId} above already reads — "it adds no
  // request" (§3.4) — never a second subscription.

  /** `<vision-target-list>`'s own `[tracks]` — the stream's live tracks, straight off the tracks
   *  poll this panel already renders a chip from. `[]` before the first read resolves or with
   *  nothing to show, which the component's own empty state renders honestly. */
  protected readonly liveTracks = computed(() => this.detections.tracks()?.tracks ?? []);

  /**
   * `<vision-target-list>`'s own `(trackSelected)` — the identical `buildFollowLockPatch(trackId)`
   * PATCH `CockpitFacade#followTrack`'s own glass-click write path already sends, mirroring how
   * {@link releaseLock} below already duplicates `CockpitFacade#releaseFollow`'s own PATCH: same
   * builder function, two independent call sites, so a list click and a box click can never produce
   * a different lock. No optimistic UI — the row's own "followed" tint waits for the next tracks-poll
   * read, same honesty rule as every other lock affordance in this drawer.
   */
  protected selectTrack(trackId: number): void {
    const streamId = this.streamId();
    if (!streamId) {
      return;
    }
    void this.fleet.patchStreamConfig(streamId, buildFollowLockPatch(trackId)).then((result) => {
      if (result) {
        this.configChanged.emit();
      }
    });
  }

  // --- Detection status line (docs/plans/done/CV-UX-RESEARCH.md §1.2/§3/§9.2, waves U3+U5) -----

  protected readonly hasStream = computed(() => !!this.streamId());
  private readonly latestResult = computed(() => this.detections.results()[0]);
  protected readonly classesOnScreen = computed(() =>
    classesOnScreenCount(this.latestResult(), this.config()?.labelFilter ?? []),
  );
  protected readonly detectionStatusInfo = computed(() =>
    detectionStatus(
      this.detectionEnabled(),
      this.hasStream(),
      this.detections.tracks()?.detectionState,
      this.detections.tracks()?.rate,
      this.classesOnScreen(),
    ),
  );
  protected readonly detectionStatusText = computed(() => this.detectionStatusInfo().text);

  /** The release chip's own action — drops the lock, falls back to the mode's own policy. Every
   *  tracking PATCH is sent immediately, never debounced. Emits {@link configChanged} on success
   *  only — a failed PATCH left the stream exactly where it was, so there is nothing new to re-read. */
  protected releaseLock(): void {
    const streamId = this.streamId();
    if (!streamId) {
      return;
    }
    void this.fleet.patchStreamConfig(streamId, buildReleaseLockPatch() as UpdateStreamConfigRequest).then((result) => {
      if (result) {
        this.configChanged.emit();
      }
    });
  }

  /**
   * **Not** a debounced edit (docs/plans/done/STREAM-STATE-PLAN.md §3.1) — a single explicit
   * click is not a gesture that might still be mid-drag, and it does not write what the operator
   * clicked into the rendered value: {@link detectionEnabled} is server truth, so the switch moves
   * once the wire says it moved. Emitting rather than self-applying is what keeps that single rule
   * in one place (`CockpitFacade`) instead of two.
   */
  protected onDetectionEnabledToggle(checked: boolean): void {
    this.detectionEnabledChange.emit(checked);
  }
}
