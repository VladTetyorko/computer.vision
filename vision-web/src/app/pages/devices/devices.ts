import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { VisionApi } from '../../core/api/vision-api';
import { FleetStore } from '../../core/fleet-store';
import { SettingsStore } from '../../core/settings-store';
import { ToastService } from '../../core/toast.service';
import { describeHttpError } from '../../core/api-error';
import { findVideoDevice } from '../../core/device-logic';
import {
  type AssetSummary,
  type Device,
  type DiscoveredDevice,
  type ScanResult,
  type SettableLifecycleState,
} from '../../core/api/models';
import {
  buildSimulationRequest,
  buildSyntheticRegisterRequest,
  isSimulatedAsset,
  mapSimulatedDevices,
  type SimulateMode,
  type SimulatedDeviceInfo,
} from './simulate-logic';
import {
  RESTORE_TARGET_STATE,
  availableAssetActions,
  availableDeviceActions,
  buildAssetEdit,
  buildAssetRows,
  buildDeviceRenameEdit,
  buildWarehouseRows,
  filterAssetRowsByArchived,
  filterRowsByArchived,
  mapDeviceOwners,
  type AssetLifecycleAction,
  type AssetRow,
  type DeviceLifecycleAction,
  type DeviceOwner,
  type WarehouseRow,
} from './warehouse-logic';

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

/** Button labels for the warehouse action menus (docs/CYCLES-PLAN.md §8). */
const DEVICE_ACTION_LABELS: Record<DeviceLifecycleAction, string> = {
  rename: 'Rename',
  activate: 'Activate',
  deactivate: 'Deactivate',
  archive: 'Archive',
  restore: 'Restore',
  assign: 'Assign to asset…',
  unassign: 'Unassign',
};

const ASSET_ACTION_LABELS: Record<AssetLifecycleAction, string> = {
  rename: 'Rename',
  activate: 'Activate',
  deactivate: 'Deactivate',
  archive: 'Archive',
  restore: 'Restore',
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

  // --- Warehouse (docs/CYCLES-PLAN.md §8) -----------------------------------
  // Devices/assets lifecycle: rename, activate/deactivate, archive (soft delete)/restore, and
  // device↔asset assignment. Every mutation goes through `FleetStore`'s `run()`-wrapped thin
  // wrappers (mirroring how C4 added `simulate()`) so a 404 — CW-a, the backend half, is not
  // live while this lands — degrades to exactly one toast, never a broken page.

  protected readonly showArchived = signal(false);
  /** Populated only while `showArchived` is on — `includeDeleted=true` returns *every* device. */
  protected readonly allDevicesIncludingArchived = signal<readonly Device[]>([]);
  protected readonly assets = signal<readonly AssetSummary[]>([]);
  /** deviceId → owning asset, across every asset the page has loaded (not just simulated ones). */
  protected readonly deviceOwners = signal<ReadonlyMap<string, DeviceOwner>>(new Map());
  protected readonly busyAssetId = signal<string | null>(null);

  /** One inline row/card open at a time per device — rename form, archive confirm, or assign picker. */
  protected readonly rowAction = signal<{ deviceId: string; mode: 'rename' | 'archive' | 'assign' } | null>(
    null,
  );
  protected readonly renameDraft = signal('');
  protected readonly assignDraft = signal('');

  protected readonly assetAction = signal<{ assetId: string; mode: 'rename' | 'archive' } | null>(null);
  protected readonly assetNameDraft = signal('');
  protected readonly assetCategoryDraft = signal('');

  private readonly warehouseDevices = computed<readonly Device[]>(() =>
    this.showArchived() ? this.allDevicesIncludingArchived() : this.fleet.devices(),
  );

  protected readonly warehouseRows = computed<readonly WarehouseRow[]>(() =>
    filterRowsByArchived(
      buildWarehouseRows(this.warehouseDevices(), this.deviceOwners(), this.fleet.liveDeviceIds()),
      this.showArchived(),
    ),
  );

  protected readonly assetRows = computed<readonly AssetRow[]>(() =>
    filterAssetRowsByArchived(buildAssetRows(this.assets()), this.showArchived()),
  );

  /** Non-archived assets are always valid assign targets — a device's ownership is the only rule. */
  protected readonly assignableAssets = computed(() =>
    this.assetRows()
      .filter((row) => !row.archived)
      .map((row) => row.asset),
  );

  protected deviceActionsFor(row: WarehouseRow): readonly DeviceLifecycleAction[] {
    return availableDeviceActions(row.lifecycle, !!row.owner);
  }

  protected assetActionsFor(row: AssetRow): readonly AssetLifecycleAction[] {
    return availableAssetActions(row.lifecycle);
  }

  protected lifecycleLabel(state: WarehouseRow['lifecycle']): string {
    switch (state) {
      case 'ACTIVE':
        return 'Active';
      case 'DEACTIVATED':
        return 'Deactivated';
      case 'DELETED':
        return 'Archived';
    }
  }

  protected deviceActionLabel(action: DeviceLifecycleAction): string {
    return DEVICE_ACTION_LABELS[action];
  }

  protected assetActionLabel(action: AssetLifecycleAction): string {
    return ASSET_ACTION_LABELS[action];
  }

  protected onDeviceAction(row: WarehouseRow, action: DeviceLifecycleAction): void {
    switch (action) {
      case 'rename':
        this.rowAction.set({ deviceId: row.device.id, mode: 'rename' });
        this.renameDraft.set(row.device.name);
        break;
      case 'activate':
        void this.setDeviceLifecycle(row.device, 'ACTIVE');
        break;
      case 'deactivate':
        void this.setDeviceLifecycle(row.device, 'DEACTIVATED');
        break;
      case 'archive':
        this.rowAction.set({ deviceId: row.device.id, mode: 'archive' });
        break;
      case 'restore':
        void this.setDeviceLifecycle(row.device, RESTORE_TARGET_STATE);
        break;
      case 'assign':
        this.rowAction.set({ deviceId: row.device.id, mode: 'assign' });
        this.assignDraft.set('');
        break;
      case 'unassign':
        if (row.owner) {
          void this.unassignDevice(row.device, row.owner);
        }
        break;
    }
  }

  /** `null` when no inline row is open for this device — lets the template use one `@switch`. */
  protected rowActionMode(deviceId: string): 'rename' | 'archive' | 'assign' | null {
    const active = this.rowAction();
    return active && active.deviceId === deviceId ? active.mode : null;
  }

  protected cancelRowAction(): void {
    this.rowAction.set(null);
  }

  protected async confirmRename(device: Device): Promise<void> {
    const edit = buildDeviceRenameEdit(this.renameDraft(), device);
    if (Object.keys(edit).length === 0) {
      this.rowAction.set(null);
      return;
    }
    await this.runDeviceAction(device.id, async () => {
      const updated = await this.fleet.updateDevice(device.id, edit);
      if (updated) {
        this.rowAction.set(null);
      }
    });
  }

  protected async confirmArchiveDevice(device: Device): Promise<void> {
    await this.runDeviceAction(device.id, async () => {
      const archived = await this.fleet.deleteDevice(device.id);
      if (archived) {
        this.rowAction.set(null);
      }
    });
  }

  protected async confirmAssign(device: Device): Promise<void> {
    const assetId = this.assignDraft();
    if (!assetId) {
      return;
    }
    await this.runDeviceAction(device.id, async () => {
      const updated = await this.fleet.assignDevice(assetId, device.id);
      if (updated) {
        this.rowAction.set(null);
      }
    });
  }

  protected async unassignDevice(device: Device, owner: DeviceOwner): Promise<void> {
    await this.runDeviceAction(device.id, () => this.fleet.unassignDevice(owner.assetId, device.id));
  }

  private async setDeviceLifecycle(device: Device, state: SettableLifecycleState): Promise<void> {
    await this.runDeviceAction(device.id, () => this.fleet.setDeviceState(device.id, state));
  }

  /** Runs a device mutation with the row's busy indicator, then re-derives the warehouse view. */
  private async runDeviceAction(deviceId: string, action: () => Promise<unknown>): Promise<void> {
    this.busyDeviceId.set(deviceId);
    try {
      await action();
      await this.refreshWarehouse();
    } finally {
      this.busyDeviceId.set(null);
    }
  }

  protected onAssetAction(row: AssetRow, action: AssetLifecycleAction): void {
    switch (action) {
      case 'rename':
        this.assetAction.set({ assetId: row.asset.assetId, mode: 'rename' });
        this.assetNameDraft.set(row.asset.displayName);
        this.assetCategoryDraft.set(row.asset.category);
        break;
      case 'activate':
        void this.setAssetLifecycle(row.asset, 'ACTIVE');
        break;
      case 'deactivate':
        void this.setAssetLifecycle(row.asset, 'DEACTIVATED');
        break;
      case 'archive':
        this.assetAction.set({ assetId: row.asset.assetId, mode: 'archive' });
        break;
      case 'restore':
        void this.setAssetLifecycle(row.asset, RESTORE_TARGET_STATE);
        break;
    }
  }

  /** `null` when no inline card is open for this asset — lets the template use one `@switch`. */
  protected assetActionMode(assetId: string): 'rename' | 'archive' | null {
    const active = this.assetAction();
    return active && active.assetId === assetId ? active.mode : null;
  }

  protected cancelAssetAction(): void {
    this.assetAction.set(null);
  }

  protected async confirmAssetEdit(asset: AssetSummary): Promise<void> {
    const edit = buildAssetEdit({ displayName: this.assetNameDraft(), category: this.assetCategoryDraft() }, asset);
    if (Object.keys(edit).length === 0) {
      this.assetAction.set(null);
      return;
    }
    await this.runAssetAction(asset.assetId, async () => {
      const updated = await this.fleet.updateAsset(asset.assetId, edit);
      if (updated) {
        this.assetAction.set(null);
      }
    });
  }

  protected async confirmArchiveAsset(asset: AssetSummary): Promise<void> {
    await this.runAssetAction(asset.assetId, async () => {
      const result = await this.fleet.deleteAsset(asset.assetId);
      if (result) {
        this.assetAction.set(null);
      }
    });
  }

  private async setAssetLifecycle(asset: AssetSummary, state: SettableLifecycleState): Promise<void> {
    await this.runAssetAction(asset.assetId, () => this.fleet.setAssetState(asset.assetId, state));
  }

  /** Runs an asset mutation with the card's busy indicator, then re-derives the warehouse view. */
  private async runAssetAction(assetId: string, action: () => Promise<unknown>): Promise<void> {
    this.busyAssetId.set(assetId);
    try {
      await action();
      await this.refreshWarehouse();
    } finally {
      this.busyAssetId.set(null);
    }
  }

  protected async toggleShowArchived(): Promise<void> {
    this.showArchived.update((value) => !value);
    await this.refreshWarehouse();
  }

  /**
   * Re-reads whatever the warehouse view currently needs: the archived-inclusive device list
   * (only while `showArchived` is on — it's a second, page-local fetch that deliberately never
   * touches `FleetStore`'s own `devicesSignal`, so Wall/Live keep seeing only non-archived
   * devices regardless of what this page's toggle is set to) and every asset plus its resolved
   * devices (for the "owned by" column and the "Simulated" chip alike — one shared fetch now
   * serves both, where `refreshSimulatedAssets` used to fetch details only for simulated assets).
   */
  private async refreshWarehouse(): Promise<void> {
    await Promise.all([
      this.showArchived() ? this.refreshArchivedDevices() : Promise.resolve(),
      this.refreshWarehouseAssets(),
    ]);
  }

  private async refreshArchivedDevices(): Promise<void> {
    const devices = await this.fleet.listDevicesIncludingArchived();
    if (devices) {
      this.allDevicesIncludingArchived.set(devices);
    }
  }

  /**
   * Loads every asset (respecting `showArchived`) plus its resolved devices, deriving both the
   * device→owner map (the warehouse table's "owned by" column and unassign action) and the
   * `simulated`-category subset the C4 wizard's chip/stop-action already relies on — one fetch
   * now serves both instead of two separate ones. Best-effort like `TelemetryStore`'s own asset
   * lookups: this is enrichment for already-visible rows, not a user-initiated action, so a
   * failure degrades silently rather than raising a toast.
   */
  private async refreshWarehouseAssets(): Promise<void> {
    try {
      const summaries = this.showArchived()
        ? await this.fleet.listAssetsIncludingArchived()
        : await this.api.listAssets();
      if (!summaries) {
        return; // failure already toasted by FleetStore.run() (only reachable when showArchived)
      }
      this.assets.set(summaries);

      const details = await Promise.all(summaries.map((asset) => this.api.getAsset(asset.assetId)));
      this.deviceOwners.set(mapDeviceOwners(details));
      this.simulatedDevices.set(mapSimulatedDevices(details.filter(isSimulatedAsset)));
    } catch {
      // Silent-degrade — see doc comment above.
    }
  }

  constructor() {
    void this.refreshWarehouse();
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

  /** The page's "Refresh" button re-reads devices/streams *and* the whole warehouse view. */
  protected async refreshAll(): Promise<void> {
    await Promise.all([this.fleet.refresh(), this.refreshWarehouse()]);
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
    await this.refreshWarehouse();

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
        await this.refreshWarehouse();
      }
    } finally {
      this.busySimulatedAssetId.set(null);
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
