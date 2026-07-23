import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { VisionApi } from '../../core/api/vision-api';
import { FleetStore } from '../../core/fleet-store';
import { SettingsStore } from '../../core/settings-store';
import { ToastService } from '../../core/toast.service';
import { describeHttpError } from '../../core/api-error';
import { findVideoDevice } from '../../core/device-logic';
import { type Device, type DiscoveredDevice, type ScanResult } from '../../core/api/models';
import {
  buildSimulationRequest,
  buildSyntheticRegisterRequest,
  isSimulatedAsset,
  mapSimulatedDevices,
  type SimulateMode,
  type SimulatedDeviceInfo,
} from './simulate-logic';

interface OptionRow {
  key: string;
  value: string;
}

/** Scan durations worth offering: long enough for mDNS, short enough to stay interactive. */
const SCAN_TIMEOUTS = [2_000, 4_000, 8_000] as const;

/** Shown under the mode selector — one sentence per mode, docs/CYCLES-PLAN.md §4's own wording. */
const SIMULATE_MODE_HINTS: Record<SimulateMode, string> = {
  direct: 'Plays the file straight through the pipeline — the simplest way to see it work.',
  rtsp: 'Rehearse the real protocol path: the platform transmits your file over RTSP and ingests it back like real hardware.',
  synthetic: 'No file needed — registers a classic sim-protocol source instantly, the same one-click demo source as below.',
};

@Component({
  selector: 'vision-devices',
  imports: [FormsModule],
  templateUrl: './devices.html',
  styleUrl: './devices.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class DevicesPage {
  private readonly api = inject(VisionApi);
  private readonly toasts = inject(ToastService);
  private readonly router = inject(Router);

  protected readonly fleet = inject(FleetStore);
  protected readonly settings = inject(SettingsStore);

  protected readonly scanTimeouts = SCAN_TIMEOUTS;

  // --- Register form -------------------------------------------------------

  protected readonly name = signal('');
  protected readonly protocol = signal('');
  protected readonly uri = signal('');
  protected readonly options = signal<readonly OptionRow[]>([]);
  protected readonly submitting = signal(false);
  /** Briefly outlines the form after a discovery candidate fills it in. */
  protected readonly highlighted = signal(false);

  protected readonly canSubmit = computed(
    () =>
      !this.submitting() &&
      this.name().trim().length > 0 &&
      this.protocol().trim().length > 0 &&
      this.uri().trim().length > 0,
  );

  // --- Discovery -----------------------------------------------------------

  protected readonly scanTimeout = signal<number>(4_000);
  protected readonly scanning = signal(false);
  protected readonly scanResult = signal<ScanResult | null>(null);

  protected readonly busyDeviceId = signal<string | null>(null);

  protected isLive(device: Device): boolean {
    return this.fleet.liveDeviceIds().has(device.id);
  }

  // --- Simulate wizard (docs/CYCLES-PLAN.md §4) -----------------------------

  protected readonly simulateOpen = signal(false);
  protected readonly simName = signal('');
  protected readonly simVideoPath = signal('');
  protected readonly simMode = signal<SimulateMode>('direct');
  protected readonly simLatitude = signal<number | null>(null);
  protected readonly simLongitude = signal<number | null>(null);
  protected readonly simAutoStart = signal(true);
  protected readonly simSubmitting = signal(false);

  /** deviceId → the simulated asset owning it; empty for a device that isn't simulated. */
  protected readonly simulatedDevices = signal<ReadonlyMap<string, SimulatedDeviceInfo>>(new Map());
  protected readonly busySimulatedAssetId = signal<string | null>(null);

  protected readonly simModeHint = computed(() => SIMULATE_MODE_HINTS[this.simMode()]);

  protected readonly simCanSubmit = computed(
    () =>
      !this.simSubmitting() &&
      (this.simMode() === 'synthetic' || this.simVideoPath().trim().length > 0),
  );

  protected simulatedInfo(device: Device): SimulatedDeviceInfo | undefined {
    return this.simulatedDevices().get(device.id);
  }

  constructor() {
    void this.refreshSimulatedAssets();
  }

  // --- Registration --------------------------------------------------------

  protected async submit(): Promise<void> {
    if (!this.canSubmit()) {
      return;
    }
    this.submitting.set(true);
    try {
      const device = await this.fleet.register({
        name: this.name().trim(),
        protocol: this.protocol().trim().toLowerCase(),
        uri: this.uri().trim(),
        options: this.collectOptions(),
      });
      if (device) {
        this.resetForm();
      }
    } finally {
      this.submitting.set(false);
    }
  }

  /**
   * One-click way to get something on screen with no hardware.
   *
   * The simulated source exists precisely so the product is demonstrable on an empty
   * network (docs/UX-DESIGN.md §6).
   */
  protected async registerSimulator(): Promise<void> {
    this.submitting.set(true);
    try {
      await this.fleet.register(buildSyntheticRegisterRequest(''));
    } finally {
      this.submitting.set(false);
    }
  }

  protected addOptionRow(): void {
    this.options.update((rows) => [...rows, { key: '', value: '' }]);
  }

  protected removeOptionRow(index: number): void {
    this.options.update((rows) => rows.filter((_, i) => i !== index));
  }

  protected updateOptionKey(index: number, key: string): void {
    this.options.update((rows) => rows.map((row, i) => (i === index ? { ...row, key } : row)));
  }

  protected updateOptionValue(index: number, value: string): void {
    this.options.update((rows) => rows.map((row, i) => (i === index ? { ...row, value } : row)));
  }

  private collectOptions(): Record<string, string> | undefined {
    const entries = this.options()
      .filter((row) => row.key.trim().length > 0)
      .map((row) => [row.key.trim(), row.value] as const);
    return entries.length > 0 ? Object.fromEntries(entries) : undefined;
  }

  private resetForm(): void {
    this.name.set('');
    this.protocol.set('');
    this.uri.set('');
    this.options.set([]);
  }

  // --- Discovery -----------------------------------------------------------

  protected async scan(): Promise<void> {
    this.scanning.set(true);
    try {
      const result = await this.api.scan({ timeoutMs: this.scanTimeout() });
      this.scanResult.set(result);
      if (result.failedMethods.length > 0) {
        // Surfaced, never swallowed: a scanner that failed is not the same as "nothing found".
        this.toasts.error(
          `These scanners failed and found nothing: ${result.failedMethods.join(', ')}.`,
        );
      }
      if (result.devices.length === 0 && result.failedMethods.length === 0) {
        this.toasts.info('Scan finished — nothing responded on this network.');
      }
    } catch (error) {
      this.toasts.error(describeHttpError(error));
    } finally {
      this.scanning.set(false);
    }
  }

  /** Fills the register form from a discovery candidate; the user still confirms. */
  protected useCandidate(candidate: DiscoveredDevice): void {
    this.name.set(candidate.name);
    this.protocol.set(candidate.protocol ?? '');
    this.uri.set(candidate.uri ?? candidate.address);
    this.options.set([]);

    this.highlighted.set(true);
    setTimeout(() => this.highlighted.set(false), 1_600);
    document.getElementById('register-card')?.scrollIntoView({ behavior: 'smooth', block: 'center' });

    if (!candidate.uri) {
      this.toasts.info(
        `${candidate.method} could not supply a stream URI — check the address before registering.`,
      );
    }
  }

  protected detailPairs(details: Record<string, string>): { key: string; value: string }[] {
    return Object.entries(details).map(([key, value]) => ({ key, value }));
  }

  // --- Stream actions ------------------------------------------------------

  protected async start(device: Device): Promise<void> {
    this.busyDeviceId.set(device.id);
    try {
      const result = await this.fleet.start(device.id, this.settings.effective());
      if (result) {
        await this.router.navigate(['/live', device.id]);
      }
    } finally {
      this.busyDeviceId.set(null);
    }
  }

  protected async stop(device: Device): Promise<void> {
    const stream = this.fleet.streamFor(device.id);
    if (!stream) {
      return;
    }
    this.busyDeviceId.set(device.id);
    try {
      await this.fleet.stop(stream.streamId);
    } finally {
      this.busyDeviceId.set(null);
    }
  }

  protected watch(device: Device): Promise<boolean> {
    return this.router.navigate(['/live', device.id]);
  }

  // --- Simulate wizard -------------------------------------------------------

  /** The page's "Refresh" button re-reads devices/streams *and* which of them are simulated. */
  protected async refreshAll(): Promise<void> {
    await Promise.all([this.fleet.refresh(), this.refreshSimulatedAssets()]);
  }

  protected toggleSimulate(): void {
    this.simulateOpen.update((open) => !open);
  }

  protected async submitSimulate(): Promise<void> {
    if (!this.simCanSubmit()) {
      return;
    }
    this.simSubmitting.set(true);
    try {
      const mode = this.simMode();
      if (mode === 'synthetic') {
        await this.submitSyntheticSimulation();
      } else {
        await this.submitFileSimulation(mode);
      }
    } finally {
      this.simSubmitting.set(false);
    }
  }

  /** `synthetic` is just the existing register flow, so all zero-hardware entries live in one place. */
  private async submitSyntheticSimulation(): Promise<void> {
    const device = await this.fleet.register(buildSyntheticRegisterRequest(this.simName()));
    if (!device) {
      return; // failure already toasted by FleetStore.run()
    }
    this.toasts.ok(`Registered ${device.name} as a synthetic source.`);
    this.closeSimulateForm();
  }

  private async submitFileSimulation(mode: 'direct' | 'rtsp'): Promise<void> {
    const request = buildSimulationRequest({
      name: this.simName(),
      videoPath: this.simVideoPath(),
      mode,
      latitude: this.simLatitude(),
      longitude: this.simLongitude(),
      autoStart: this.simAutoStart(),
    });
    const response = await this.fleet.simulate(request);
    if (!response) {
      return; // failure already toasted by FleetStore.run()
    }
    this.closeSimulateForm();
    await this.refreshSimulatedAssets();

    if (!response.streamId) {
      this.toasts.ok('Simulated asset created — start it from the device list when ready.');
      return;
    }

    const deviceId = await this.resolveWatchTarget(response.assetId);
    this.toasts.ok(
      'Simulation started — now streaming.',
      deviceId ? { label: 'Watch', onClick: () => void this.router.navigate(['/live', deviceId]) } : undefined,
    );
  }

  /** The started simulation's watchable device — resolved from the freshly-created asset's devices. */
  private async resolveWatchTarget(assetId: string): Promise<string | undefined> {
    try {
      const asset = await this.api.getAsset(assetId);
      return findVideoDevice(asset.devices)?.id;
    } catch {
      return undefined; // best-effort — worst case the toast has no Watch action
    }
  }

  protected async stopSimulatedAsset(info: SimulatedDeviceInfo): Promise<void> {
    this.busySimulatedAssetId.set(info.assetId);
    try {
      const stopped = await this.fleet.stopSimulation(info.assetId);
      if (stopped) {
        this.toasts.ok(`Stopped simulation "${info.displayName}".`);
        await this.refreshSimulatedAssets();
      }
    } finally {
      this.busySimulatedAssetId.set(null);
    }
  }

  /**
   * Re-derives which devices belong to a `simulated`-category asset.
   *
   * One `listAssets()` call, then `getAsset()` only for the (usually few) simulated ones —
   * cheap, and best-effort like `TelemetryStore`'s own asset lookups: the chip/stop action is
   * enrichment, not a user-initiated action, so a failure here degrades silently rather than
   * raising a toast.
   */
  private async refreshSimulatedAssets(): Promise<void> {
    try {
      const summaries = await this.api.listAssets();
      const simulated = summaries.filter(isSimulatedAsset);
      const details = await Promise.all(simulated.map((asset) => this.api.getAsset(asset.assetId)));
      this.simulatedDevices.set(mapSimulatedDevices(details));
    } catch {
      // Silent-degrade — see doc comment above.
    }
  }

  private closeSimulateForm(): void {
    this.simulateOpen.set(false);
    this.simName.set('');
    this.simVideoPath.set('');
    this.simMode.set('direct');
    this.simLatitude.set(null);
    this.simLongitude.set(null);
    this.simAutoStart.set(true);
  }
}
