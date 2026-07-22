import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { VisionApi } from '../../core/api/vision-api';
import { FleetStore } from '../../core/fleet-store';
import { SettingsStore } from '../../core/settings-store';
import { ToastService } from '../../core/toast.service';
import { describeHttpError } from '../../core/api-error';
import { type Device, type DiscoveredDevice, type ScanResult } from '../../core/api/models';

interface OptionRow {
  key: string;
  value: string;
}

/** Scan durations worth offering: long enough for mDNS, short enough to stay interactive. */
const SCAN_TIMEOUTS = [2_000, 4_000, 8_000] as const;

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
      await this.fleet.register({
        name: 'sim-demo',
        protocol: 'sim',
        uri: 'sim://demo',
      });
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
}
