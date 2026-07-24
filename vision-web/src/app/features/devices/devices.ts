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
  DEVICE_ACTION_LABELS,
  RESTORE_TARGET_STATE,
  buildAssetListRows,
  buildCreateAssetRequestForDevice,
  buildDeviceRenameEdit,
  buildWarehouseRows,
  deriveCategoryOptions,
  filterAssetListRowsByArchived,
  filterAssetListRowsByCategory,
  filterRowsByArchived,
  mapDeviceOwners,
  operatorAssetActions,
  reasonedDeviceActions,
  type ActionAvailability,
  type AssetListRow,
  type CategoryOption,
  type DeviceLifecycleAction,
  type DeviceOwner,
  type WarehouseRow,
} from './devices-page-logic';
import {
  CUSTOM_PROTOCOL_OPTION,
  REGISTERABLE_PROTOCOLS,
  placeholderForProtocol,
  protocolSelectionFor,
} from './protocols';

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

/** "Create asset from this device" renamed to its outcome (docs/UX-REWORK-PLAN.md §U-a2 §3). */
const PROMOTE_TO_ASSET_LABEL = 'Promote to asset…';

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

  /**
   * `?category=<slug>` — pre-filters the asset-first list to one category (docs/UX-QUICKWINS-PLAN.md
   * QF-2/QF-3): the drill-down target for the Command dashboard's readiness tiles. Binds by name,
   * same query-param-to-input mechanism as `addSource` above — no route change needed.
   */
  readonly category = input<string | undefined>(undefined);

  private readonly api = inject(VisionApi);
  private readonly toasts = inject(ToastService);
  private readonly router = inject(Router);

  protected readonly fleet = inject(FleetStore);
  protected readonly settings = inject(SettingsStore);

  protected readonly scanTimeouts = SCAN_TIMEOUTS;

  // --- Register form -------------------------------------------------------
  // The protocol field is a `<select>` of exactly what this build can consume
  // (docs/UX-QUICKWINS-PLAN.md QF-2 — `./protocols.ts`, verified against each adapter's own
  // `supports()`), plus a `Custom…` escape hatch that reveals the old free-text input for a future
  // adapter not in that list yet.

  protected readonly name = signal('');
  protected readonly protocolSelect = signal('');
  protected readonly customProtocol = signal('');
  protected readonly uri = signal('');
  protected readonly options = signal<readonly OptionRow[]>([]);
  protected readonly submitting = signal(false);
  /** Briefly outlines the form after a discovery candidate fills it in. */
  protected readonly highlighted = signal(false);

  protected readonly registerableProtocols = REGISTERABLE_PROTOCOLS;
  protected readonly customProtocolOption = CUSTOM_PROTOCOL_OPTION;
  protected readonly promoteToAssetLabel = PROMOTE_TO_ASSET_LABEL;

  protected readonly isCustomProtocol = computed(() => this.protocolSelect() === CUSTOM_PROTOCOL_OPTION);

  /** The protocol string actually sent — the select's value, or the free-text field under `Custom…`. */
  protected readonly protocol = computed(() =>
    this.isCustomProtocol() ? this.customProtocol() : this.protocolSelect(),
  );

  /** The URI field's placeholder, updated per selected protocol (docs/UX-QUICKWINS-PLAN.md QF-2). */
  protected readonly uriPlaceholder = computed(() => placeholderForProtocol(this.protocol()));

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

  // --- Warehouse (docs/CYCLES-PLAN.md §8; kebab menus + poka-yoke docs/UX-REWORK-PLAN.md §U-a item 7, §U-a2) -
  // Devices/assets lifecycle: rename, activate/deactivate, archive (soft delete)/restore, and
  // device↔asset assignment. Every mutation goes through `FleetStore`'s `run()`-wrapped thin
  // wrappers (mirroring how C4 added `simulate()`) so a 404 — CW-a, the backend half, is not
  // live while this lands — degrades to exactly one toast, never a broken page. Archive is the one
  // exception (see `archiveDeviceNow`/`archiveAssetNow` below): it bypasses `FleetStore` on purpose
  // so this page can attach an Undo action to its own toast instead of `FleetStore`'s plain one.

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

  /**
   * One inline row open at a time per device — a rename form or an assign picker; both need the
   * user to actually type/pick something, unlike Archive (docs/UX-REWORK-PLAN.md §U-a2 item 3b —
   * "Undo over confirm"), which now fires immediately from the kebab menu with no inline step at
   * all — see `archiveDeviceNow` below.
   */
  protected readonly rowAction = signal<{ deviceId: string; mode: 'rename' | 'assign' } | null>(
    null,
  );
  protected readonly renameDraft = signal('');
  protected readonly assignDraft = signal('');

  /** Advanced/raw-devices table — collapsed by default (docs/CYCLES-PLAN.md §11 item 1). */
  protected readonly advancedDevicesOpen = signal(false);

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
   *
   * `?category=` (docs/UX-QUICKWINS-PLAN.md QF-2/QF-3 — the Command dashboard's readiness-tile
   * drill-down) narrows this further, after the archived filter.
   */
  protected readonly assetListRows = computed<readonly AssetListRow[]>(() =>
    filterAssetListRowsByCategory(
      filterAssetListRowsByArchived(buildAssetListRows(this.assets(), this.fleet.liveDeviceIds()), this.showArchived()),
      this.category(),
    ),
  );

  /**
   * The active `?category=` filter's human-readable name, for the "Filtered by …" banner —
   * resolved from whichever loaded asset actually carries this slug (falls back to the bare slug
   * itself so a category with zero current assets still names what was asked for, rather than
   * showing nothing).
   */
  protected readonly categoryFilterName = computed(() => {
    const slug = this.category()?.trim();
    if (!slug) {
      return undefined;
    }
    return this.assets().find((asset) => asset.category === slug)?.categoryName ?? slug;
  });

  protected clearCategoryFilter(): Promise<boolean> {
    return this.router.navigate(['/devices']);
  }

  /** Non-archived assets are always valid assign targets — a device's ownership is the only rule. */
  protected readonly assignableAssets = computed(() =>
    this.assets().filter((asset) => (asset.lifecycle ?? 'ACTIVE') !== 'DELETED'),
  );

  // --- Create asset from a device (docs/UX-QUICKWINS-PLAN.md QF-2's orphaned-device quick fix) --
  // Reached from a successful register's toast action and from any Advanced-table row whose
  // `owner` is unassigned — one inline panel, one code path, regardless of entry point.

  protected readonly createAssetFor = signal<Device | null>(null);
  protected readonly createAssetName = signal('');
  protected readonly createAssetCategory = signal('');
  protected readonly createAssetSubmitting = signal(false);

  /** The category picker's options — real, in-use categories when any asset has been loaded, else a small default set. */
  protected readonly categoryOptions = computed<readonly CategoryOption[]>(() => deriveCategoryOptions(this.assets()));

  protected readonly canSubmitCreateAsset = computed(
    () => !this.createAssetSubmitting() && this.createAssetCategory().trim().length > 0,
  );

  protected openCreateAssetFor(device: Device): void {
    this.createAssetFor.set(device);
    this.createAssetName.set(device.name);
    this.createAssetCategory.set(this.categoryOptions()[0]?.slug ?? '');
  }

  protected cancelCreateAsset(): void {
    this.createAssetFor.set(null);
  }

  /**
   * Registers a new device wrapping `device`'s own connection details under a brand-new asset,
   * then archives `device` itself — `POST /api/assets` cannot reference an existing device by id
   * (see `buildCreateAssetRequestForDevice`'s own doc comment), so this is the quick fix's honest
   * resolution: no duplicate left behind in the Advanced table, and the new asset owns a device
   * with identical settings. Navigates to the new asset's detail page on success.
   */
  protected async confirmCreateAsset(): Promise<void> {
    const device = this.createAssetFor();
    if (!device || !this.canSubmitCreateAsset()) {
      return;
    }
    this.createAssetSubmitting.set(true);
    try {
      const request = buildCreateAssetRequestForDevice(
        device,
        this.createAssetName(),
        this.createAssetCategory().trim(),
      );
      const created = await this.api.createAsset(request);
      await this.fleet.deleteDevice(device.id); // archive the now-superseded standalone entry
      this.createAssetFor.set(null);
      this.toasts.ok(`Promoted to asset "${created.displayName}".`);
      await this.router.navigate(['/assets', created.assetId]);
    } catch (error) {
      this.toasts.error(describeHttpError(error));
    } finally {
      this.createAssetSubmitting.set(false);
    }
  }

  /**
   * The Advanced table's per-row kebab menu (docs/UX-REWORK-PLAN.md §U-a item 7): every device
   * lifecycle action, reasoned (item 3a) — `row.owner?.deviceCount` is what lets Unassign disable
   * itself *before* the click when this device is its asset's only one, instead of only after the
   * backend's own 409 (see `reasonedDeviceActions`'s own doc comment for the verified backend rule).
   */
  protected deviceActionsFor(row: WarehouseRow): readonly ActionAvailability<DeviceLifecycleAction>[] {
    return reasonedDeviceActions(row.lifecycle, !!row.owner, row.owner?.deviceCount);
  }

  /** The asset list's per-row kebab menu — just Archive/Restore, reasoned (item 2's simplification). */
  protected assetActionsFor(
    row: AssetListRow,
  ): readonly ActionAvailability<'archive' | 'restore'>[] {
    return operatorAssetActions(row.lifecycle);
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

  protected assetActionLabel(action: 'archive' | 'restore'): string {
    return action === 'archive' ? 'Archive asset' : 'Restore asset';
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

  /**
   * Archive executes immediately, no confirm dialog (docs/UX-REWORK-PLAN.md §U-a2 item 3b —
   * "Undo over confirm"): the previous inline confirm panel (`archiveConfirmAssetId`) is gone.
   * Calls `VisionApi.deleteAsset` directly rather than `FleetStore.deleteAsset` — that method's own
   * `run()`-wrapped success toast (still exactly what devices archived/usages retained/streams
   * stopped) has no Undo action and can't gain one without touching `core/fleet/fleet-store.ts`
   * (out of this task's scope this batch), so this page fires its own toast instead, mirroring
   * `FleetStore#assignDevice`/`#unassignDevice`'s own precedent of a bespoke try/catch when the
   * generic wrapper's toast isn't the one a caller needs.
   */
  protected async archiveAssetNow(row: AssetListRow): Promise<void> {
    const assetId = row.asset.assetId;
    this.busyAssetId.set(assetId);
    try {
      const result = await this.api.deleteAsset(assetId);
      this.toasts.ok(
        `Archived "${result.displayName}" — ${result.devicesDeleted} device(s) archived, ` +
          `${result.usagesRetained} usage(s) retained, ${result.streamsStopped} stream(s) stopped.`,
        { label: 'Undo', onClick: () => void this.restoreAssetNow(assetId, result.displayName) },
      );
      await Promise.all([this.fleet.refresh({ quiet: true }), this.refreshWarehouse()]);
    } catch (error) {
      this.toasts.error(describeHttpError(error));
    } finally {
      this.busyAssetId.set(null);
    }
  }

  /**
   * Both the Undo toast action above and the explicit "Restore asset" kebab entry (for an asset
   * currently shown via "Show archived") call this same method — restoring is restoring either way.
   * Only the asset's own lifecycle is reversed here, not the individual devices Archive cascaded
   * onto (`AssetDeletionResponse#devicesDeleted`) — those stay archived until independently
   * restored from the Advanced table; a documented, deliberate scope boundary (Undo's own primary
   * use case is "wrong row, seconds ago", not reconstructing a fully-diverged device set).
   */
  protected async restoreAssetNow(assetId: string, displayName: string): Promise<void> {
    this.busyAssetId.set(assetId);
    try {
      await this.api.setAssetState(assetId, RESTORE_TARGET_STATE);
      this.toasts.ok(`Restored "${displayName}".`);
      await Promise.all([this.fleet.refresh({ quiet: true }), this.refreshWarehouse()]);
    } catch (error) {
      this.toasts.error(describeHttpError(error));
    } finally {
      this.busyAssetId.set(null);
    }
  }

  protected onAssetAction(row: AssetListRow, action: 'archive' | 'restore'): void {
    if (action === 'archive') {
      void this.archiveAssetNow(row);
    } else {
      void this.restoreAssetNow(row.asset.assetId, row.asset.displayName);
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
        void this.archiveDeviceNow(row.device);
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

  /**
   * Archive executes immediately, no confirm dialog (docs/UX-REWORK-PLAN.md §U-a2 item 3b) — see
   * `archiveAssetNow`'s own doc comment for why this bypasses `FleetStore.deleteDevice` too. Undo
   * reuses the existing explicit-Restore path (`setDeviceLifecycle`/`fleet.setDeviceState`) rather
   * than a bespoke restore method — restoring is restoring, whichever button asked for it.
   */
  protected async archiveDeviceNow(device: Device): Promise<void> {
    this.busyDeviceId.set(device.id);
    try {
      await this.api.deleteDevice(device.id);
      this.toasts.ok(`Archived "${device.name}".`, {
        label: 'Undo',
        onClick: () => void this.setDeviceLifecycle(device, RESTORE_TARGET_STATE),
      });
      await Promise.all([this.fleet.refresh({ quiet: true }), this.refreshWarehouse()]);
    } catch (error) {
      this.toasts.error(describeHttpError(error));
    } finally {
      this.busyDeviceId.set(null);
    }
  }

  /** `null` when no inline row is open for this device — lets the template use one `@switch`. */
  protected rowActionMode(deviceId: string): 'rename' | 'assign' | null {
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
        await this.refreshWarehouse();
        // Orphaned-device dead end, quick version (docs/UX-QUICKWINS-PLAN.md QF-2): a freshly
        // registered device has no owning asset yet — offer the fix right away, not just from the
        // Advanced table's own "unassigned" row action below. A second, distinct toast rather than
        // extending `fleet.register()`'s own plain "Registered X." confirmation (out of this
        // cycle's scope — `FleetStore` is `core/fleet/**`, not `features/devices/**`).
        this.toasts.ok('Not yet part of any asset.', {
          label: PROMOTE_TO_ASSET_LABEL,
          onClick: () => this.openCreateAssetFor(device),
        });
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
    this.protocolSelect.set('');
    this.customProtocol.set('');
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
    const selection = protocolSelectionFor(candidate.protocol);
    this.name.set(candidate.name);
    this.protocolSelect.set(selection.select);
    this.customProtocol.set(selection.custom);
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
