import { ChangeDetectionStrategy, Component, computed, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ScrollingModule } from '@angular/cdk/scrolling';
import { Router } from '@angular/router';
import { VisionApi } from '../../core/api/vision-api';
import { FleetStore } from '../../core/fleet/fleet-store';
import { SettingsStore } from '../../core/settings/settings-store';
import { ToastService } from '../../core/toast.service';
import { describeHttpError } from '../../core/api-error';
import { findVideoDevice } from '../../core/fleet/device-logic';
import { FlightPlanDialog } from '../../shared/map/flight-plan-dialog';
import { buildTelemetryRequest, type FlightPlanForm } from '../../shared/map/flight-plan-logic';
import {
  type AssetDetails,
  type Device,
  type DiscoveredDevice,
  type ScanResult,
  type SettableLifecycleState,
  type TelemetryPlanRequest,
} from '../../core/api/models';
import {
  buildSimulationRequest,
  buildSyntheticRegisterRequest,
  isSimulatedAsset,
  mapSimulatedDevices,
  type SimulateMode,
  type SimulatedDeviceInfo,
} from './simulate-logic';
import { buildTestDroneRequest } from '../../core/fleet/simulation-logic';
import {
  RESTORE_TARGET_STATE,
  availableDeviceActions,
  buildAssetListRows,
  buildDeviceRenameEdit,
  buildWarehouseRows,
  filterAssetListRowsByArchived,
  filterRowsByArchived,
  mapDeviceOwners,
  type AssetListRow,
  type DeviceLifecycleAction,
  type DeviceOwner,
  type WarehouseRow,
} from './devices-page-logic';

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
  synthetic: 'No file needed — registers a still, pattern-only test source with no telemetry.',
  testDrone: 'No file needed — places a moving drone on a circular flight path around a home point, watchable immediately.',
};

/** The Add-source flow's three top-level entry points (docs/CYCLES-PLAN.md §9, CU-b item 1). */
type AddSourceMethod = 'register' | 'discover' | 'simulate';

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

@Component({
  selector: 'vision-devices',
  imports: [FormsModule, ScrollingModule, FlightPlanDialog],
  templateUrl: './devices.html',
  styleUrl: './devices.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class DevicesPage {
  /**
   * `?addSource=` — opens the Add-source flow straight away (docs/MVP3-PLAN.md §C-b: the Fly
   * cockpit's empty-state picker links here as `/devices?addSource=1` rather than a bare
   * `/devices`, so "no drones yet" actually lands in the flow, not just the tab). Binds by name
   * like every other query-param input in this app (`FlyPage.requestedAssetId`/`watch`) — no route
   * change needed. Any non-empty value opens it; the value itself is never read.
   */
  readonly addSource = input<string | undefined>(undefined);

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

  // --- Add source (docs/CYCLES-PLAN.md §9, CU-b item 1) -----------------------
  // One progressive flow replaces the old stacked Register/Discover/Simulate cards: an entry
  // button opens a single card; `addSourceMethod` (null = the method picker) narrows it to
  // whichever path the user picked, each reusing the exact fields/logic the old standalone cards
  // had — no capability lost, everything just moved behind one door instead of three.

  protected readonly addSourceOpen = signal(false);
  protected readonly addSourceMethod = signal<AddSourceMethod | null>(null);

  protected toggleAddSource(): void {
    this.addSourceOpen.update((open) => !open);
    if (!this.addSourceOpen()) {
      this.addSourceMethod.set(null);
    }
  }

  protected chooseMethod(method: AddSourceMethod): void {
    this.addSourceMethod.set(method);
  }

  protected backToMethods(): void {
    this.addSourceMethod.set(null);
  }

  private closeAddSource(): void {
    this.addSourceOpen.set(false);
    this.addSourceMethod.set(null);
  }

  // --- Simulate wizard (docs/CYCLES-PLAN.md §4, §9) -----------------------------

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

  /** Only `direct`/`rtsp` need a server-side file path; `synthetic`/`testDrone` need nothing but a name. */
  protected readonly simNeedsVideoPath = computed(
    () => this.simMode() === 'direct' || this.simMode() === 'rtsp',
  );

  /** `testDrone` carries a home point like `direct`/`rtsp`; `synthetic` (no telemetry) does not. */
  protected readonly simNeedsHomePoint = computed(() => this.simMode() !== 'synthetic');

  protected readonly simCanSubmit = computed(
    () => !this.simSubmitting() && (!this.simNeedsVideoPath() || this.simVideoPath().trim().length > 0),
  );

  protected simulatedInfo(device: Device): SimulatedDeviceInfo | undefined {
    return this.simulatedDevices().get(device.id);
  }

  // --- Flight-plan editor (docs/CYCLES-PLAN.md §7, CT-b) --------------------------------------
  // The map-based waypoint editor replaces raw home-lat/lon fields for the Simulate step's
  // telemetry-accepting modes (direct/rtsp/testDrone); `flightPlan` holds the editor's own draft
  // shape, serialized to the wire `telemetry` field only at submit time (`currentTelemetryRequest`).

  protected readonly flightPlanDialogOpen = signal(false);
  protected readonly flightPlan = signal<FlightPlanForm | undefined>(undefined);

  protected readonly flightPlanSummary = computed(() => {
    const plan = this.flightPlan();
    if (!plan) {
      return null;
    }
    return `${plan.waypoints.length} waypoints · ${plan.routeMode}`;
  });

  protected openFlightPlanDialog(): void {
    this.flightPlanDialogOpen.set(true);
  }

  protected onFlightPlanSaved(plan: FlightPlanForm): void {
    this.flightPlan.set(plan);
    this.flightPlanDialogOpen.set(false);
  }

  protected onFlightPlanCancelled(): void {
    this.flightPlanDialogOpen.set(false);
  }

  protected clearFlightPlan(): void {
    this.flightPlan.set(undefined);
  }

  private currentTelemetryRequest(): TelemetryPlanRequest | undefined {
    const plan = this.flightPlan();
    return plan ? buildTelemetryRequest(plan) : undefined;
  }

  // --- Warehouse (docs/CYCLES-PLAN.md §8) -----------------------------------
  // Devices/assets lifecycle: rename, activate/deactivate, archive (soft delete)/restore, and
  // device↔asset assignment. Every mutation goes through `FleetStore`'s `run()`-wrapped thin
  // wrappers (mirroring how C4 added `simulate()`) so a 404 — CW-a, the backend half, is not
  // live while this lands — degrades to exactly one toast, never a broken page.

  protected readonly showArchived = signal(false);
  /** Populated only while `showArchived` is on — `includeDeleted=true` returns *every* device. */
  protected readonly allDevicesIncludingArchived = signal<readonly Device[]>([]);
  /**
   * Every asset the page has loaded, as full `AssetDetails` (not just `AssetSummary`) — the
   * asset-first primary list needs each asset's resolved device count/owner-of-video-device
   * (docs/CYCLES-PLAN.md §11, CD-b item 1), and the Advanced table already needed the same fetch
   * for its "owned by" column and the "Simulated" chip, so one shared fetch now serves all three
   * (see `refreshWarehouseAssets` below) rather than a second, asset-list-specific one.
   */
  protected readonly assets = signal<readonly AssetDetails[]>([]);
  /** deviceId → owning asset, across every asset the page has loaded (not just simulated ones). */
  protected readonly deviceOwners = signal<ReadonlyMap<string, DeviceOwner>>(new Map());
  protected readonly busyAssetId = signal<string | null>(null);

  /** One inline row/card open at a time per device — rename form, archive confirm, or assign picker. */
  protected readonly rowAction = signal<{ deviceId: string; mode: 'rename' | 'archive' | 'assign' } | null>(
    null,
  );
  protected readonly renameDraft = signal('');
  protected readonly assignDraft = signal('');

  /** Advanced/raw-devices table — collapsed by default (docs/CYCLES-PLAN.md §11 item 1). */
  protected readonly advancedDevicesOpen = signal(false);

  /** The list-level asset row's one destructive action — an inline confirm, mirroring the Advanced table's idiom. */
  protected readonly archiveConfirmAssetId = signal<string | null>(null);

  private readonly warehouseDevices = computed<readonly Device[]>(() =>
    this.showArchived() ? this.allDevicesIncludingArchived() : this.fleet.devices(),
  );

  protected readonly warehouseRows = computed<readonly WarehouseRow[]>(() =>
    filterRowsByArchived(
      buildWarehouseRows(this.warehouseDevices(), this.deviceOwners(), this.fleet.liveDeviceIds()),
      this.showArchived(),
    ),
  );

  /**
   * The page's primary surface (docs/CYCLES-PLAN.md §11 item 1): one row per asset, exactly
   * Watch · Open · Archive. CDK virtual scroll (`asset-viewport` in the template) keeps rendering
   * cost `O(visible rows)` regardless of how many assets exist (item 4) — the *fetch* behind this
   * list is not O(visible) (`refreshWarehouseAssets` below resolves every asset's devices, not
   * just the ones currently scrolled into view), a known, documented ceiling tied to the backend's
   * in-memory repositories rather than something this cycle solves (see MODULE.md Status).
   */
  protected readonly assetListRows = computed<readonly AssetListRow[]>(() =>
    filterAssetListRowsByArchived(buildAssetListRows(this.assets(), this.fleet.liveDeviceIds()), this.showArchived()),
  );

  /** Non-archived assets are always valid assign targets — a device's ownership is the only rule. */
  protected readonly assignableAssets = computed(() =>
    this.assets().filter((asset) => (asset.lifecycle ?? 'ACTIVE') !== 'DELETED'),
  );

  protected deviceActionsFor(row: WarehouseRow): readonly DeviceLifecycleAction[] {
    return availableDeviceActions(row.lifecycle, !!row.owner);
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

  protected trackAssetRow(_index: number, row: AssetListRow): string {
    return row.asset.assetId;
  }

  protected toggleAdvancedDevices(): void {
    this.advancedDevicesOpen.update((open) => !open);
  }

  // --- Asset-first list actions (docs/CYCLES-PLAN.md §11 item 1) -----------------------------

  protected watchAsset(row: AssetListRow): Promise<boolean> | undefined {
    return row.watchDeviceId ? this.router.navigate(['/live', row.watchDeviceId]) : undefined;
  }

  protected openAsset(row: AssetListRow): Promise<boolean> {
    return this.router.navigate(['/assets', row.asset.assetId]);
  }

  protected requestArchiveAsset(row: AssetListRow): void {
    this.archiveConfirmAssetId.set(row.asset.assetId);
  }

  protected cancelArchiveAsset(): void {
    this.archiveConfirmAssetId.set(null);
  }

  protected async confirmArchiveAsset(assetId: string): Promise<void> {
    this.busyAssetId.set(assetId);
    try {
      const result = await this.fleet.deleteAsset(assetId);
      if (result) {
        this.archiveConfirmAssetId.set(null);
        await this.refreshWarehouse();
      }
    } finally {
      this.busyAssetId.set(null);
    }
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
   * Loads every asset (respecting `showArchived`) plus its resolved devices, then stores the full
   * `AssetDetails` list (not just the lighter `AssetSummary`) — the primary asset list needs each
   * asset's device count/watch-target (`buildAssetListRows`), and the same fetch already served
   * the device→owner map and the `simulated`-category subset the C4 wizard's chip/stop-action
   * relies on, so one round now serves all three. Best-effort like `TelemetryStore`'s own asset
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

      const details = await Promise.all(summaries.map((asset) => this.api.getAsset(asset.assetId)));
      this.assets.set(details);
      this.deviceOwners.set(mapDeviceOwners(details));
      this.simulatedDevices.set(mapSimulatedDevices(details.filter(isSimulatedAsset)));
    } catch {
      // Silent-degrade — see doc comment above.
    }
  }

  constructor() {
    void this.refreshWarehouse();
    if (this.addSource()) {
      this.addSourceOpen.set(true);
    }
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
        this.closeAddSource();
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

  /** Fills the register form from a discovery candidate and switches to it; the user still confirms. */
  protected useCandidate(candidate: DiscoveredDevice): void {
    this.name.set(candidate.name);
    this.protocol.set(candidate.protocol ?? '');
    this.uri.set(candidate.uri ?? candidate.address);
    this.options.set([]);

    this.addSourceMethod.set('register');
    this.highlighted.set(true);
    setTimeout(() => this.highlighted.set(false), 1_600);
    document.getElementById('add-source-card')?.scrollIntoView({ behavior: 'smooth', block: 'center' });

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

  protected async submitSimulate(): Promise<void> {
    if (!this.simCanSubmit()) {
      return;
    }
    this.simSubmitting.set(true);
    try {
      switch (this.simMode()) {
        case 'synthetic':
          await this.submitSyntheticSimulation();
          break;
        case 'testDrone':
          await this.submitTestDroneSimulation();
          break;
        case 'direct':
        case 'rtsp':
          await this.submitFileSimulation(this.simMode() as 'direct' | 'rtsp');
          break;
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

  /**
   * `testDrone` (docs/CYCLES-PLAN.md §9, CU-b item 7): a fully synthetic, moving VIDEO+TELEMETRY
   * device — CU-a's no-`videoPath` simulation — reached from the same wizard as the file-backed
   * modes, sharing this method's watch-resolution/toast shape with `submitFileSimulation`.
   */
  private async submitTestDroneSimulation(): Promise<void> {
    const request = buildTestDroneRequest({
      name: this.simName(),
      latitude: this.simLatitude(),
      longitude: this.simLongitude(),
      autoStart: this.simAutoStart(),
      telemetry: this.currentTelemetryRequest(),
    });
    await this.startFileOrSyntheticSimulation(request);
  }

  private async submitFileSimulation(mode: 'direct' | 'rtsp'): Promise<void> {
    const request = buildSimulationRequest({
      name: this.simName(),
      videoPath: this.simVideoPath(),
      mode,
      latitude: this.simLatitude(),
      longitude: this.simLongitude(),
      autoStart: this.simAutoStart(),
      telemetry: this.currentTelemetryRequest(),
    });
    await this.startFileOrSyntheticSimulation(request);
  }

  /** Shared by `submitFileSimulation`/`submitTestDroneSimulation` — both post to `/api/simulations`. */
  private async startFileOrSyntheticSimulation(request: Parameters<FleetStore['simulate']>[0]): Promise<void> {
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
    this.closeAddSource();
    this.simName.set('');
    this.simVideoPath.set('');
    this.simMode.set('direct');
    this.simLatitude.set(null);
    this.simLongitude.set(null);
    this.simAutoStart.set(true);
    this.flightPlan.set(undefined);
  }
}
