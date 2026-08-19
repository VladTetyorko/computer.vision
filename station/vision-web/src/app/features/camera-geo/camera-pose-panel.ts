import { ChangeDetectionStrategy, Component, computed, effect, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { HttpErrorResponse } from '@angular/common/http';
import { VisionApi } from '../../core/api/vision-api';
import { describeHttpError } from '../../core/api-error';
import { ToastService } from '../../core/toast.service';
import { UiStore } from '../../core/ui/ui-store';
import {
  detectionStateExplanation,
  detectionStateLabel,
  detectionStateTone,
  draftFromPose,
  isFixedCameraGeoDisabledError,
  manualPoseRequest,
  poseFactRows,
  type ManualPoseDraft,
} from '../../core/camera-geo/camera-geo-logic';
import { SectionHeader } from '../../shared/ui/section-header';
import { EmptyState } from '../../shared/ui/empty-state';
import { ConfirmDialog } from '../../shared/ui/confirm-dialog';
import { CameraCalibrationWizard } from './camera-calibration-wizard';
import type { ActiveStream, CameraPoseResponse, DetectionState, Device } from '../../core/api/models';

/** Stable per-file console tag, mirroring `[pilots]`/`[fleet]`/`[org]`. */
const LOG_PREFIX = '[camera-geo]';

/**
 * The camera-pose card on the asset manager page (`docs/plans/active/FIXED-CAMERA-GEO-PLAN.md` wave G5,
 * §8's own G5 row) — a stationary camera's measured/solved aim (lat/lon/height-AGL/yaw/pitch/hFOV),
 * plus the owning stream's `DetectionState` so "calibrated but detection off" reads as an explained
 * state, not a silent dead map (D9). Mounted the same way `pilots-card.ts` is: a self-contained,
 * non-routed `[assetId]`-input child with no dedicated facade, per that class's own doc comment on
 * the codebase's carve-out for non-routed children (`architecture.spec.ts`'s own exemption list).
 *
 * **Backend does not exist yet** (this wave, G5, only needs §5's frozen contract — G4 is blocked on
 * G2+G3). Every read/write here goes through the five `core/api/vision-api.ts` methods this wave
 * added (`getCameraPose`/`putCameraPose`/`deleteCameraPose`/`calibrateCameraPose`, plus
 * `listMapTracks` for the map layer elsewhere) — nothing is mocked *in* this component; it degrades
 * exactly as it will once G4 ships, via the same generic HTTP-error handling every other feature here
 * uses.
 *
 * **Honesty by construction (this wave's own brief):**
 * - **No pose stored** (a `404`, or the flag off — `isFixedCameraGeoDisabledError`, D8) renders
 *   `<vision-empty>` with exactly two calls to action (Calibrate / Enter manually) and **nothing
 *   else** — no fact grid, no detection-state chip, no stream lookup even attempted. That is the
 *   whole of "no geo UI beyond an invitation to calibrate" (this wave's own exit criterion).
 * - **A pose exists**: the fact grid (`poseFactRows`) plus the owning stream's `DetectionState`
 *   chip/explanation, resolved only now (not for the uncalibrated case above) — an asset's video
 *   device is found via `getAsset`, its active stream via `listStreams`, its detection state
 *   via the existing `getStreamTracks` (`StreamTracksResponse#detectionState`, already shipped by
 *   CV-DEMAND). No video device or no active stream degrades to "Detection unknown" — the honest
 *   `undefined` case `detectionStateLabel`/`-Tone`/`-Explanation` already handle, never a guess.
 *
 * **Role-gating**: none, deliberately — grepping this page's own rename/archive actions found no
 * client-side "can I manage this asset" predictor anywhere in this codebase (asset mutation buttons
 * are shown to any viewer of a visible asset; a `403` is caught and toasted). Camera-pose writes
 * follow the identical, already-established pattern (D10: visible-but-unmanageable is a `403`, not a
 * hidden button) rather than inventing new client-side authority prediction this app has never had.
 * In dev-parity mode (`vision.auth.enabled=false`) the dev admin is ADMIN/unbounded, so every action
 * here behaves exactly as it does today — nothing here reads `topRole` at all.
 */
@Component({
  selector: 'vision-camera-pose-panel',
  imports: [FormsModule, SectionHeader, EmptyState, ConfirmDialog, CameraCalibrationWizard],
  templateUrl: './camera-pose-panel.html',
  styleUrl: './camera-pose-panel.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CameraPosePanel {
  readonly assetId = input.required<string>();

  private readonly api = inject(VisionApi);
  private readonly toasts = inject(ToastService);

  protected readonly pose = signal<CameraPoseResponse | null>(null);
  protected readonly loading = signal(false);
  protected readonly busy = signal(false);

  /** Resolved only once a pose exists — see this class's own doc comment on why the uncalibrated case skips this entirely. */
  protected readonly detectionState = signal<DetectionState | undefined>(undefined);
  protected readonly streamId = signal<string | null>(null);

  /** The panel's own mutually-exclusive overlays — wizard / manual-edit form / remove confirm. */
  protected readonly overlays = new UiStore();
  protected isOpen(id: 'wizard' | 'manual' | 'remove'): boolean {
    return this.overlays.isOpen(id);
  }

  protected readonly factRows = computed(() => {
    const pose = this.pose();
    return pose ? poseFactRows(pose) : [];
  });

  protected readonly detectionLabel = computed(() => detectionStateLabel(this.detectionState()));
  protected readonly detectionTone = computed(() => detectionStateTone(this.detectionState()));
  protected readonly detectionExplanation = computed(() => detectionStateExplanation(this.detectionState()));

  protected readonly manualDraft = signal<ManualPoseDraft>(draftFromPose(null));

  constructor() {
    effect(() => {
      const id = this.assetId();
      if (id) {
        void this.load(id);
      }
    });
  }

  private async load(assetId: string): Promise<void> {
    this.loading.set(true);
    this.detectionState.set(undefined);
    this.streamId.set(null);
    try {
      const pose = await this.api.getCameraPose(assetId);
      this.pose.set(pose);
      await this.loadDetectionState(assetId);
    } catch (error) {
      // A 404 = no pose stored yet; the flag being off (D8) is likewise "nothing to show" — both are
      // this panel's own honest empty state, not an error. Anything else: one quiet log, same empty
      // state (`describeHttpError`'s toast is for a caller-initiated action, not a background load).
      if (!(error instanceof HttpErrorResponse && error.status === 404) && !isFixedCameraGeoDisabledError(error)) {
        console.warn(`${LOG_PREFIX} failed to load camera pose for ${assetId}`, { error });
      }
      this.pose.set(null);
    } finally {
      this.loading.set(false);
    }
  }

  /** Resolves the asset's video device → its active stream → that stream's `DetectionState` (D9). Any missing link degrades to "unknown", never a guess. */
  private async loadDetectionState(assetId: string): Promise<void> {
    try {
      const [details, streams] = await Promise.all([this.api.getAsset(assetId), this.api.listStreams()]);
      const videoDevice = details.devices.find((device: Device) => device.capabilities.includes('VIDEO'));
      const stream = videoDevice ? streams.find((activeStream: ActiveStream) => activeStream.deviceId === videoDevice.id) : undefined;
      if (!stream) {
        return;
      }
      this.streamId.set(stream.streamId);
      const tracks = await this.api.getStreamTracks(stream.streamId);
      this.detectionState.set(tracks.detectionState);
    } catch (error) {
      console.warn(`${LOG_PREFIX} failed to resolve detection state for ${assetId}`, { error });
    }
  }

  // --- Calibration wizard ----------------------------------------------------------------------

  protected openWizard(): void {
    this.overlays.open('wizard');
  }

  protected closeWizard(): void {
    this.overlays.close('wizard');
  }

  protected onPoseSaved(pose: CameraPoseResponse): void {
    this.pose.set(pose);
    this.overlays.close('wizard');
    this.overlays.close('manual');
    void this.loadDetectionState(this.assetId());
  }

  // --- Manual entry ------------------------------------------------------------------------------

  protected openManualForm(): void {
    this.manualDraft.set(draftFromPose(this.pose()));
    this.overlays.open('manual');
  }

  protected cancelManualForm(): void {
    this.overlays.close('manual');
  }

  protected updateManualField(field: keyof ManualPoseDraft, value: string): void {
    this.manualDraft.update((draft) => ({ ...draft, [field]: value }));
  }

  protected async saveManualForm(): Promise<void> {
    const request = manualPoseRequest(this.manualDraft(), this.pose()?.targetLayerId ?? null);
    if (!request) {
      this.toasts.error('Enter a valid number for every field.');
      return;
    }
    this.busy.set(true);
    try {
      const saved = await this.api.putCameraPose(this.assetId(), request);
      this.onPoseSaved(saved);
      this.toasts.ok('Saved camera pose.');
    } catch (error) {
      this.reportMutationError(error);
    } finally {
      this.busy.set(false);
    }
  }

  // --- Remove --------------------------------------------------------------------------------------

  protected requestRemove(): void {
    this.overlays.open('remove');
  }

  protected cancelRemove(): void {
    this.overlays.close('remove');
  }

  protected async confirmRemove(): Promise<void> {
    this.busy.set(true);
    try {
      await this.api.deleteCameraPose(this.assetId());
      this.pose.set(null);
      this.detectionState.set(undefined);
      this.overlays.close('remove');
      this.toasts.ok('Removed camera pose — this asset no longer projects tracks onto the map.');
    } catch (error) {
      this.reportMutationError(error);
    } finally {
      this.busy.set(false);
    }
  }

  private reportMutationError(error: unknown): void {
    if (isFixedCameraGeoDisabledError(error)) {
      this.toasts.error("Fixed-camera geolocation isn't enabled on this deployment.");
    } else if (error instanceof HttpErrorResponse && error.status === 403) {
      this.toasts.error('That asset is outside your scope — you can only edit the pose of your own assets.');
    } else if (error instanceof HttpErrorResponse && error.status === 404) {
      this.toasts.error('That asset no longer exists — it may have been removed.');
    } else {
      this.toasts.error(describeHttpError(error));
    }
  }
}
