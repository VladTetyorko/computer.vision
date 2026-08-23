import { ChangeDetectionStrategy, Component, computed, effect, inject, input, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { HttpErrorResponse } from '@angular/common/http';
import { VisionApi } from '../../core/api/vision-api';
import { describeHttpError } from '../../core/api-error';
import { ToastService } from '../../core/toast.service';
import {
  armFrameClick,
  calibratedPoseRequest,
  calibrationResultTone,
  calibrationSummary,
  canAddCalibrationPoint,
  canRunCalibration,
  canSaveCalibration,
  isFixedCameraGeoDisabledError,
  measuredPosition,
  pairMapClick,
  removeCalibrationPoint,
  toCalibrationRequest,
  type CalibrationPointDraft,
  type MeasuredPositionDraft,
  type PendingFrameClick,
} from '../../core/camera-geo/camera-geo-logic';
import { Icon } from '../../shared/ui/icon';
import { ConfirmDialog } from '../../shared/ui/confirm-dialog';
import { TacticalMap } from '../../shared/map/tactical-map/tactical-map';
import type { ActiveStream, CalibrationResult, CameraPoseResponse, Device, GeoPosition } from '../../core/api/models';

const LOG_PREFIX = '[camera-geo]';

/**
 * The calibration wizard (`docs/plans/done/FIXED-CAMERA-GEO-PLAN.md` D5, this wave's G5 row) — click a
 * landmark in a still frame, click the same point on the map, repeat for 2–8 pairs, run the solve,
 * and either save an honest `solved:true` pose or read the refusal reason verbatim with no save
 * offered. A non-routed, modal-in-a-modal child of `camera-pose-panel.ts` — mounted only while that
 * panel's own `wizard` overlay is open, so its constructor-time reads of `assetId()`/`existingPose()`
 * see one fixed value for the whole of this component's life (it is destroyed and recreated, never
 * reused, whenever the host toggles the overlay) — still routed through `effect()` rather than a bare
 * constructor read, mirroring `camera-pose-panel.ts`/`command.ts`'s own established idiom for reading
 * a signal input at construction time rather than assuming initial-value timing.
 *
 * **The "no video, ever" tension, resolved deliberately**: `features/asset-detail/asset-detail.ts`'s
 * own doc comment bans live video from that page — piloting and live view belong to the cockpit and
 * the watch page. This wizard shows a single static JPEG (`VisionApi.snapshotUrl`, an `<img>`, not a
 * player) inside a wizard the operator explicitly opened — categorically different from ambient page
 * video, and the same judgment call `features/command/live-strip-tile.ts` made before it was deleted
 * (a snapshot image living outside the cockpit). Flagged here plainly rather than assumed silently.
 * `snapshotUrl` itself was otherwise unused since that deletion (see its own doc comment in
 * `core/api/vision-api.ts`) — this wizard is its first live consumer again.
 *
 * **The map-click half of a pair** reuses `shared/map/tactical-map/tactical-map.ts` directly
 * (`interactionMode="view"`, whose `onBackgroundClick` already just re-emits `mapClicked` for any
 * non-drawing mode — no new map capability needed). **Deliberately not** plotted: the wizard does not
 * overlay already-picked map points back onto that embedded map (`TacticalMap` has no generic
 * "arbitrary point marker" input, only typed `marks`/`assets`/`events`/`tracks`/`zones`, and inventing
 * one for this single wizard's own scratch points was judged not worth extending that shared
 * component's surface for). The paired-points list below the two panes is the wizard's one source of
 * confirmation instead — every pair's frame pixel and map coordinate, plain text, removable.
 *
 * **Image pixel space**: §5's `u`/`v` are raw frame pixels, paired with the request's own
 * `imageWidth`/`imageHeight` — there is no existing API surface that reports a stream's resolution
 * ahead of time, so this reads it from the loaded snapshot `<img>`'s own `naturalWidth`/`naturalHeight`
 * (the actual object, not a guess) and converts each click's on-screen position into that pixel space
 * via the rendered image's own bounding rect ratio — correct at any CSS display size.
 */
@Component({
  selector: 'vision-camera-calibration-wizard',
  imports: [FormsModule, Icon, ConfirmDialog, TacticalMap],
  templateUrl: './camera-calibration-wizard.html',
  styleUrl: './camera-calibration-wizard.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CameraCalibrationWizard {
  readonly assetId = input.required<string>();
  readonly existingPose = input<CameraPoseResponse | null>(null);

  readonly saved = output<CameraPoseResponse>();
  readonly closed = output<void>();

  private readonly api = inject(VisionApi);
  private readonly toasts = inject(ToastService);

  protected readonly snapshotUrl = signal<string | null>(null);
  protected readonly resolvingStream = signal(true);
  protected readonly imageSize = signal<{ readonly width: number; readonly height: number } | null>(null);

  protected readonly measured = signal<MeasuredPositionDraft>({ latitude: '', longitude: '', aglMeters: '' });
  protected readonly points = signal<readonly CalibrationPointDraft[]>([]);
  protected readonly pending = signal<PendingFrameClick>(null);

  protected readonly result = signal<CalibrationResult | null>(null);
  protected readonly solving = signal(false);
  protected readonly saving = signal(false);
  protected readonly confirmingSave = signal(false);

  protected readonly canRun = computed(() => canRunCalibration(this.points()));
  protected readonly canAddPoint = computed(() => canAddCalibrationPoint(this.points()));
  protected readonly resultTone = computed(() => {
    const result = this.result();
    return result ? calibrationResultTone(result) : null;
  });
  protected readonly resultSummary = computed(() => {
    const result = this.result();
    return result ? calibrationSummary(result) : null;
  });
  protected readonly canSave = computed(() => {
    const result = this.result();
    return result !== null && canSaveCalibration(result);
  });

  constructor() {
    effect(() => {
      const pose = this.existingPose();
      if (pose) {
        this.measured.set({
          latitude: String(pose.latitude),
          longitude: String(pose.longitude),
          aglMeters: String(pose.aglMeters),
        });
      }
    });
    effect(() => {
      const id = this.assetId();
      if (id) {
        void this.resolveSnapshot(id);
      }
    });
  }

  private async resolveSnapshot(assetId: string): Promise<void> {
    this.resolvingStream.set(true);
    try {
      const [details, streams] = await Promise.all([this.api.getAsset(assetId), this.api.listStreams()]);
      const videoDevice = details.devices.find((device: Device) => device.capabilities.includes('VIDEO'));
      const stream = videoDevice ? streams.find((activeStream: ActiveStream) => activeStream.deviceId === videoDevice.id) : undefined;
      this.snapshotUrl.set(stream ? this.api.snapshotUrl(stream.streamId) : null);
    } catch (error) {
      console.warn(`${LOG_PREFIX} failed to resolve a calibration snapshot for ${assetId}`, { error });
      this.snapshotUrl.set(null);
    } finally {
      this.resolvingStream.set(false);
    }
  }

  /** Captures the loaded snapshot's own pixel dimensions — see class doc's "image pixel space" note. */
  protected onFrameImageLoad(image: HTMLImageElement): void {
    this.imageSize.set({ width: image.naturalWidth, height: image.naturalHeight });
  }

  protected onFrameClick(event: MouseEvent, image: HTMLImageElement): void {
    const size = this.imageSize();
    if (!size || !this.canAddPoint()) {
      return;
    }
    const rect = image.getBoundingClientRect();
    const xRatio = (event.clientX - rect.left) / rect.width;
    const yRatio = (event.clientY - rect.top) / rect.height;
    const u = Math.round(xRatio * size.width);
    const v = Math.round(yRatio * size.height);
    this.pending.set(armFrameClick(u, v));
    this.result.set(null);
  }

  protected onMapClick(position: GeoPosition): void {
    const paired = pairMapClick(this.pending(), this.points(), position.latitude, position.longitude);
    this.points.set(paired.points);
    this.pending.set(paired.pending);
    this.result.set(null);
  }

  protected removePoint(index: number): void {
    this.points.update((points) => removeCalibrationPoint(points, index));
    this.result.set(null);
  }

  protected updateMeasured(field: keyof MeasuredPositionDraft, value: string): void {
    this.measured.update((draft) => ({ ...draft, [field]: value }));
  }

  protected async runCalibration(): Promise<void> {
    const position = measuredPosition(this.measured());
    const size = this.imageSize();
    if (!position) {
      this.toasts.error("Enter this camera's own measured latitude, longitude, and height AGL first.");
      return;
    }
    if (!size) {
      this.toasts.error('Waiting for the calibration snapshot to load.');
      return;
    }
    if (!this.canRun()) {
      return;
    }
    const request = toCalibrationRequest(
      position.latitude,
      position.longitude,
      position.aglMeters,
      size.width,
      size.height,
      this.points(),
    );
    this.solving.set(true);
    this.result.set(null);
    try {
      const result = await this.api.calibrateCameraPose(this.assetId(), request);
      this.result.set(result);
    } catch (error) {
      this.reportError(error);
    } finally {
      this.solving.set(false);
    }
  }

  protected requestSave(): void {
    if (this.canSave()) {
      this.confirmingSave.set(true);
    }
  }

  protected cancelSave(): void {
    this.confirmingSave.set(false);
  }

  protected async confirmSave(): Promise<void> {
    const result = this.result();
    if (!result || !canSaveCalibration(result)) {
      return;
    }
    this.saving.set(true);
    try {
      const saved = await this.api.putCameraPose(this.assetId(), calibratedPoseRequest(result));
      this.confirmingSave.set(false);
      this.toasts.ok('Saved calibrated camera pose.');
      this.saved.emit(saved);
    } catch (error) {
      this.reportError(error);
    } finally {
      this.saving.set(false);
    }
  }

  protected close(): void {
    this.closed.emit();
  }

  private reportError(error: unknown): void {
    if (isFixedCameraGeoDisabledError(error)) {
      this.toasts.error("Fixed-camera geolocation isn't enabled on this deployment.");
    } else if (error instanceof HttpErrorResponse && error.status === 403) {
      this.toasts.error('That asset is outside your scope — you can only calibrate your own assets.');
    } else {
      this.toasts.error(describeHttpError(error));
    }
  }
}
