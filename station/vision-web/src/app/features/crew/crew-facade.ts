import { Injectable, computed, effect, inject, signal } from '@angular/core';
import { VisionApi } from '../../core/api/vision-api';
import { FleetStore } from '../../core/fleet/fleet-store';
import { SettingsFacade } from '../../core/settings/settings-facade';
import { TelemetryFacade } from '../../core/telemetry/telemetry-facade';
import { DetectionsFacade } from '../../core/detections/detections-facade';
import { SeatFacade } from '../../core/seat/seat-facade';
import { AuthFacade } from '../../core/auth/auth-facade';
import { canManageOrg } from '../../core/org/org-logic';
import { videoDevices } from '../../core/fleet/device-logic';
import { telemetryDevices, trackingIdChanged } from '../../core/telemetry/telemetry-logic';
import { resolveDetectionEnabled, videoNotice } from '../fly/stream-state-logic';
import {
  buildFollowLockPatch,
  buildHotKnobPatch,
  buildReleaseLockPatch,
  resolveCvConfig,
  type ResolvedCvConfig,
} from '../fly/cv-control-panel-logic';
import { cameraHeldByOther, cameraSeatChipLabel, crewDock, crewStage, type CrewDock, type CrewStage } from './crew-logic';
import type {
  AssetDetails,
  BoundingBox,
  EffectiveCvProfile,
  FollowStatus,
  GeoPosition,
  StreamConfigResponse,
} from '../../core/api/models';

/**
 * Console prefix for this page's diagnostic logging — mirrors `cockpit-facade.ts`'s identical
 * `[cockpit]` convention (this codebase has no logging service, grep-verified).
 */
const LOG_PREFIX = '[crew]';

/**
 * `CrewSeatPage`'s facade (docs/plans/active/CREW-CONTROL-PLAN.md §3.4, wave W3) — `/crew/:assetId`, the
 * crew seat: a second, camera-only station on the same asset a pilot is flying (or not) from
 * `/fly/:assetId`. Deliberately **structurally `LiveFacade`/`CockpitFacade` plus the CV body plus a
 * seat chip** — see this class's own field-by-field comments for exactly what carried over, what
 * was dropped, and what is new.
 *
 * **What is dropped, on purpose, and why**: every flight-command read/write (`capabilities`,
 * `preflightItems`, `canShowCommands`, `canBringHome`, `engageSession`/`endSession`,
 * `groundedReason`/`failsafeBanner`) — this page issues zero flight verbs, ever (§3.4: "no
 * `<vision-fly-hud>`, no flight verb in the template"). There is also no Stop-video action anywhere
 * in this facade — §3.4's dock table gives this page exactly one clickable affordance, C0's Start
 * video; stopping a stream is the pilot's own call, made from `/fly`.
 *
 * **What is new**: {@link seats} ({@link SeatFacade}, page-provided like every other store here) and
 * the {@link stage}/{@link dock} pair built from it via `crew-logic.ts`'s pure functions — the one
 * thing this page adds to the shape `LiveFacade`/`CockpitFacade` already established.
 *
 * **CV writes and the camera seat (§3.3).** This facade never constructs a seat-guarded write
 * itself beyond {@link start}/{@link setDetection}/{@link followTrack}/{@link releaseFollow} — every
 * other CV write (model/confidence/fps/classes, the label deny-list) is made by
 * `<vision-cv-control-panel>`/`<vision-detections-strip>` themselves (both inject `FleetStore`
 * directly, the same non-routed-child carve-out `architecture.spec.ts` already grants them) and
 * merely tell this facade to re-read afterwards via `(configChanged)`. {@link refreshStreamConfig}
 * is therefore the **one choke point** every CV write path funnels through, success or failure alike
 * — it re-reads the stream config *and* nudges {@link SeatFacade.refreshNow}, so a write that 409'd
 * because the camera seat was preempted between two polls flips this page to `C2` on the very next
 * tick rather than waiting out the ordinary 3s seat-poll cadence. The 409's own message is already
 * surfaced by `FleetStore.patchStreamConfig`'s own `run()` wrapper (one toast, `describeHttpError`)
 * — this facade's only added job is making the *posture* (stage) catch up just as fast.
 */
@Injectable()
export class CrewFacade {
  private readonly api = inject(VisionApi);
  private readonly auth = inject(AuthFacade);

  readonly fleet = inject(FleetStore);
  readonly settings = inject(SettingsFacade);
  readonly telemetry = inject(TelemetryFacade);
  readonly detections = inject(DetectionsFacade);
  readonly seats = inject(SeatFacade);

  readonly activeAssetId = signal<string | undefined>(undefined);
  readonly asset = signal<AssetDetails | undefined>(undefined);
  /** The honest empty-state signal a bad/since-deleted `:assetId` degrades to — mirrors
   * `CockpitFacade.loadError`'s identical doc comment and behavior. */
  readonly loadError = signal(false);

  private lastTelemetryDeviceId: string | undefined = undefined;
  private lastDetectionsStreamId: string | undefined = undefined;
  private lastEffectiveProfileAssetId: string | undefined = undefined;
  private lastStreamConfigStreamId: string | undefined = undefined;

  readonly busy = signal(false);

  // --- Video device / stream (single primary device — no secondary-tile switching: unlike the
  // cockpit, this page's composition table names exactly one video surface) ----------------------
  readonly videoDevicesList = computed(() => videoDevices(this.asset()?.devices ?? []));
  readonly primaryDevice = computed(() => this.videoDevicesList()[0]);

  readonly stream = computed(() => {
    const device = this.primaryDevice();
    return device ? this.fleet.streamFor(device.id) : undefined;
  });
  readonly live = computed(() => this.stream() !== undefined);
  readonly streamState = computed(() => this.stream()?.state);
  readonly videoNotice = computed(() => videoNotice(this.live(), this.streamState()));

  /** Latches once `live()` is ever observed true for this session — see `stopped`'s own doc
   * comment on `LiveFacade`/`CockpitFacade`, the identical precedent this mirrors. There is no
   * `explicitlyStopped` half here (unlike those two): this page has no Stop action, so the only way
   * a previously-live stream stops being live is someone else (the pilot) stopping it. */
  private readonly hasBeenLive = signal(false);
  readonly stopped = computed(() => this.hasBeenLive() && !this.live());

  // --- CV profile hierarchy / live config read-back (docs/plans/active/CV-SETTINGS-PLAN.md §3) —
  // identical merge to `CockpitFacade`'s own pair; see that class's doc comment for the full
  // rationale. ---------------------------------------------------------------------------------
  readonly effectiveProfile = signal<EffectiveCvProfile | undefined>(undefined);
  readonly streamConfig = signal<StreamConfigResponse | undefined>(undefined);
  readonly resolvedCvConfig = computed<ResolvedCvConfig | undefined>(() =>
    resolveCvConfig(this.streamConfig(), this.effectiveProfile()),
  );

  /** Gates the CV setup modal's "Save to this asset's profile" action — same predicate `/vision/
   * profiles` and `CockpitFacade.canManage` gate on. Dev parity: `vision.auth.enabled=false`
   * resolves the dev principal to `ADMIN`/unbounded, so this is always `true` in dev. */
  readonly canManage = computed(() => canManageOrg(this.auth.capabilities()));

  readonly detectionOn = computed(() =>
    resolveDetectionEnabled(this.stream()?.detectionEnabled, this.resolvedCvConfig()?.detectionEnabled ?? false),
  );
  readonly detectionPending = signal(false);

  /** The shared, persisted declutter level — same instance `CockpitFacade`/`LiveFacade` read/write
   * (`SettingsFacade.declutterLevel`), so all three surfaces stay in visual agreement. */
  readonly boxesMode = this.settings.declutterLevel;

  /** Fed to `<vision-player>`'s `[hoveredClass]`, written by `<vision-detections-strip>`'s
   * `(hoveredClassChange)` — mirrors `CockpitFacade.hoveredDetectionClass` exactly. */
  readonly hoveredDetectionClass = signal<string | null>(null);

  /** Transient per-session Expert-disclosure memory for the CV setup modal — mirrors
   * `CockpitFacade.cvExpertOpen`. */
  readonly cvExpertOpen = signal(false);

  // --- Follow (docs/plans/active/TRACK-FOLLOW-PLAN.md §3.1/§3.5) — reused verbatim from
  // `LiveFacade`'s identical trio; see that class's own doc comments for the full honesty
  // rationale each follows. `buildFollowLockPatch`/`buildReleaseLockPatch` are the one deliberate
  // cross-feature import (`LiveFacade`'s own precedent): duplicating them would risk this page and
  // the cockpit/`/live` disagreeing about the wire shape. -----------------------------------------
  readonly lockedTrackId = computed(() => this.detections.results()[0]?.tracking?.lockedTrackId ?? 0);
  readonly follow = computed<FollowStatus | null>(() => this.detections.tracks()?.follow ?? null);
  readonly lostBox = computed<BoundingBox | null>(() => {
    const follow = this.follow();
    return follow?.state === 'LOST' ? follow.lastBox : null;
  });
  private readonly wantsTracksPoll = computed(() => this.live());

  // --- Telemetry (read-only instruments only — `features/live/telemetry-osd.ts`, never
  // `<vision-fly-hud>`) ----------------------------------------------------------------------------
  readonly telemetryDevicesList = computed(() => telemetryDevices(this.asset()?.devices ?? []));
  readonly hasTelemetryDevice = computed(() => this.telemetryDevicesList().length > 0);

  /** "Best position we have right now" — live fix else the asset's own last-known position, mirrors
   * `CockpitFacade.dronePosition`/`weatherPosition` verbatim. Feeds `<vision-map-tools>`'s `cockpit`
   * capability (the "Mark target" bearing/distance readout) — this page has no map inset of its own
   * to feed. */
  readonly dronePosition = computed<GeoPosition | undefined>(() => {
    const latest = this.telemetry.latest();
    if (latest?.latitude !== undefined && latest.longitude !== undefined) {
      return { latitude: latest.latitude, longitude: latest.longitude };
    }
    return this.asset()?.lastKnownPosition;
  });

  // --- Seats + stage/dock (docs/plans/active/CREW-CONTROL-PLAN.md §3.4/§3.6, new here) -----------

  /** §3.2 rule 2's honest discriminator — see `crew-logic.ts#cameraHeldByOther`'s own doc comment
   * for why this is not simply `!camera.mine`. Named distinctly from the imported function (not
   * shadowed) purely for readability at call sites. */
  readonly cameraSeatHeldByOther = computed(() => cameraHeldByOther(this.seats.camera()));

  readonly stage = computed<CrewStage>(() =>
    crewStage({ live: this.live(), busy: this.busy(), cameraHeldByOther: this.cameraSeatHeldByOther() }),
  );

  readonly dock = computed<CrewDock>(() => crewDock(this.stage(), this.seats.camera()));

  /** The header's own seat chip (§3.4) — see `crew-logic.ts#cameraSeatChipLabel`'s own doc comment.
   * Named distinctly from the imported function (not shadowed) purely for readability. */
  readonly cameraChipLabel = computed(() => cameraSeatChipLabel(this.seats.camera()));

  /** `<vision-follow-hud>`'s own `[canRelease]` — unlike `LiveFacade`'s still-`true`-always residual
   * (that class's own doc comment names this exact plan as where it would tighten), this page has a
   * real observer posture now: Release is itself a CAMERA-guarded write (§3.3, the target lock), so
   * it is only genuinely actionable while this crew member holds the seat. */
  readonly canRelease = computed(() => this.stage() === 'C3');

  constructor() {
    // Latches once `live()` is ever observed true — see `stopped`'s own doc comment above.
    effect(() => {
      if (this.live()) {
        this.hasBeenLive.set(true);
      }
    });

    // Any device on the asset resolves the same owning-asset/open-usage pair — mirrors
    // `CockpitFacade`'s identical effect, guarded on the derived deviceId primitive for the same
    // O(N)-re-entry reason (docs/plans/done/REALTIME-PLAN.md Phase R-a item 2).
    effect(() => {
      const devices = this.asset()?.devices ?? [];
      const deviceId = this.hasTelemetryDevice() && devices.length > 0 ? devices[0].id : undefined;
      if (!trackingIdChanged(deviceId, this.lastTelemetryDeviceId)) {
        return;
      }
      this.lastTelemetryDeviceId = deviceId;
      if (deviceId) {
        this.telemetry.track(deviceId, this.activeAssetId());
      } else {
        this.telemetry.reset();
      }
    });

    // Detections only make sense while the primary device's stream is actually running — guarded
    // on the derived streamId primitive for the identical reason as telemetry above.
    effect(() => {
      const streamId = this.stream()?.streamId;
      if (!trackingIdChanged(streamId, this.lastDetectionsStreamId)) {
        return;
      }
      this.lastDetectionsStreamId = streamId;
      this.hoveredDetectionClass.set(null);
      if (streamId) {
        this.detections.track(streamId, this.activeAssetId());
      } else {
        this.detections.reset();
      }
    });

    // Drives the `GET .../tracks` poll (docs/plans/active/TRACK-FOLLOW-PLAN.md §3.5) — mirrors
    // `LiveFacade`'s identical effect; `DetectionsStore.followTracks` already no-ops internally on
    // an unchanged `(streamId, wanted)` pair.
    effect(() => {
      const streamId = this.stream()?.streamId;
      this.detections.followTracks(streamId ?? '', streamId !== undefined && this.wantsTracksPoll());
    });

    // Seats (new here) — keyed on `activeAssetId()` alone, like `GeoStore.track()`/`GroundingStore.
    // track()` elsewhere in this app: `SeatFacade.track()` is already a no-op for an unchanged
    // assetId, so no derived-primitive guard needed.
    effect(() => {
      const assetId = this.activeAssetId();
      if (assetId) {
        this.seats.track(assetId);
      } else {
        this.seats.reset();
      }
    });

    // The asset's own effective CV profile — keyed on `activeAssetId()` alone, independent of
    // whether a stream is running (mirrors `CockpitFacade`'s identical effect).
    effect(() => {
      const assetId = this.activeAssetId();
      if (!trackingIdChanged(assetId, this.lastEffectiveProfileAssetId)) {
        return;
      }
      this.lastEffectiveProfileAssetId = assetId;
      if (assetId) {
        void this.loadEffectiveProfile(assetId);
      } else {
        this.effectiveProfile.set(undefined);
      }
    });

    // The running stream's own live config — guarded on the derived streamId primitive for the same
    // reason telemetry/detections above are.
    effect(() => {
      const streamId = this.stream()?.streamId;
      if (!trackingIdChanged(streamId, this.lastStreamConfigStreamId)) {
        return;
      }
      this.lastStreamConfigStreamId = streamId;
      if (streamId) {
        void this.loadStreamConfig(streamId);
      } else {
        this.streamConfig.set(undefined);
      }
    });
  }

  // --- Asset selection (route-driven, mirrors `CockpitFacade.selectAsset`) -----------------------

  selectAsset(assetId: string): void {
    if (assetId === this.activeAssetId()) {
      return;
    }
    console.info(`${LOG_PREFIX} selecting asset ${assetId}`);
    this.activeAssetId.set(assetId);
    this.asset.set(undefined);
    this.loadError.set(false);
    this.hasBeenLive.set(false);
    void this.loadAsset(assetId);
  }

  private async loadAsset(assetId: string): Promise<void> {
    try {
      const details = await this.api.getAsset(assetId);
      this.asset.set(details);
    } catch (error) {
      if (this.asset() === undefined) {
        console.warn(`${LOG_PREFIX} could not load asset ${assetId}`, { error });
        this.loadError.set(true);
      }
    }
  }

  // --- Start video (the one action this page ever offers — §3.4's C0 row) -----------------------

  async start(): Promise<void> {
    const device = this.primaryDevice();
    if (!device) {
      return;
    }
    console.info(`${LOG_PREFIX} starting stream for device ${device.id}`);
    this.busy.set(true);
    try {
      const result = await this.fleet.start(device.id);
      if (result === null) {
        // A failed start can be a CAMERA-seat 409 (§3.3) among other causes — refresh seats so the
        // dock's reason (if that's what happened) shows up on the very next render, not after the
        // ordinary 3s seat-poll cadence.
        this.seats.refreshNow();
      }
    } finally {
      this.busy.set(false);
    }
  }

  // --- CV writes — see this class's own doc comment on the seat/409 relationship -----------------

  async setDetection(enabled: boolean): Promise<void> {
    const streamId = this.stream()?.streamId;
    const current = this.resolvedCvConfig();
    if (!streamId || !current) {
      return;
    }
    this.detectionPending.set(true);
    try {
      await this.fleet.patchStreamConfig(streamId, buildHotKnobPatch({ ...current, detectionEnabled: enabled }));
      await Promise.all([this.fleet.refresh({ quiet: true }), this.loadStreamConfig(streamId)]);
      this.seats.refreshNow();
    } finally {
      this.detectionPending.set(false);
    }
  }

  /** `<vision-player>`'s own `(trackFollowed)` — mirrors `LiveFacade.followTrack`/`CockpitFacade.
   * followTrack` exactly, same single PATCH, no optimistic UI. */
  followTrack(trackId: number): void {
    const streamId = this.stream()?.streamId;
    if (!streamId) {
      return;
    }
    void this.fleet.patchStreamConfig(streamId, buildFollowLockPatch(trackId)).then(() => this.seats.refreshNow());
  }

  /** `<vision-follow-hud>`'s own `(release)` — mirrors `LiveFacade.releaseFollow`. Reachable only
   * while {@link canRelease} is `true` (the template gates it), i.e. only at `C3`. */
  releaseFollow(): void {
    const streamId = this.stream()?.streamId;
    if (!streamId) {
      return;
    }
    void this.fleet.patchStreamConfig(streamId, buildReleaseLockPatch()).then(() => this.seats.refreshNow());
  }

  /** `<vision-follow-hud>`'s own `(reacquire)` — offered only while {@link follow} reports `LOST`
   * and `reacquirable`; re-issues the original lock for the same track id. */
  reacquireFollow(): void {
    const trackId = this.follow()?.trackId;
    if (trackId === undefined) {
      return;
    }
    this.followTrack(trackId);
  }

  private async loadEffectiveProfile(assetId: string): Promise<void> {
    try {
      const profile = await this.api.getEffectiveCvProfile(assetId);
      this.effectiveProfile.set(profile);
    } catch (error) {
      console.warn(`${LOG_PREFIX} could not load the effective CV profile for ${assetId}`, { error });
      this.effectiveProfile.set(undefined);
    }
  }

  private async loadStreamConfig(streamId: string): Promise<void> {
    try {
      const config = await this.api.getStreamConfig(streamId);
      this.streamConfig.set(config);
    } catch (error) {
      console.warn(`${LOG_PREFIX} could not read back live config for stream ${streamId}`, { error });
      this.streamConfig.set(undefined);
    }
  }

  /** `<vision-cv-setup-modal>`'s `(profileSaved)` — re-reads the asset's effective profile after an
   * explicit save. Not seat-guarded (§3.3's table has no entry for profile management) — no
   * `seats.refreshNow()` needed here, unlike {@link refreshStreamConfig} below. */
  refreshEffectiveProfile(): void {
    const assetId = this.activeAssetId();
    if (assetId) {
      void this.loadEffectiveProfile(assetId);
    }
  }

  /** The one choke point every CV write path re-reads through — see this class's own doc comment. */
  refreshStreamConfig(): void {
    const streamId = this.stream()?.streamId;
    if (streamId) {
      void this.loadStreamConfig(streamId);
    }
    this.seats.refreshNow();
  }
}
