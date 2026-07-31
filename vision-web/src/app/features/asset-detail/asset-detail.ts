import { ChangeDetectionStrategy, Component, computed, effect, inject, input, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { TelemetryStore } from '../../core/telemetry/telemetry-store';
import { UiStore } from '../../core/ui/ui-store';
import { relativeTimeLabel } from '../../core/events/events-logic';
import { ageSeconds, isStale } from '../../core/telemetry/telemetry-logic';
import {
  DEVICE_ACTION_LABELS,
  RESTORE_TARGET_STATE,
  reasonedDeviceActions,
  type ActionAvailability,
  type DeviceLifecycleAction,
} from '../../core/fleet/warehouse-logic';
import { formatDuration } from '../../core/stream-info-logic';
import { usageDurationSeconds, type FlightBar } from '../../core/fleet/asset-stats-logic';
import { LiveMap } from '../../shared/map/live-map/live-map';
import { SectionHeader } from '../../shared/ui/section-header';
import { SidePanel } from '../../shared/ui/side-panel';
import { Icon } from '../../shared/ui/icon';
import { KebabMenu } from '../../shared/ui/kebab-menu';
import { EmptyState } from '../../shared/ui/empty-state';
import { Stat } from '../../shared/ui/stat';
import { PilotsCard } from './pilots-card';
import { AssetDetailFacade } from './asset-detail-facade';
import { attributeRowsToRecord, attributesToRows, telemetryFactRows, type AttributeRow, type TelemetryFactRow } from './asset-detail-logic';
import type { AssetUsage, Device, DetectionEvent } from '../../core/api/models';

/** The page's four independent editor overlays — docs/UI-ARCHITECTURE-PLAN.md's own migration
 *  target for this page — consolidated into ONE mutually-exclusive `UiStore` group, mirroring
 *  `features/fly/flight-command-panel.ts`'s `dialog` group: opening any one implicitly closes
 *  whichever other was open, so this page can never show two editors at once. Transient (no
 *  `storageKey`) — an editor must never survive a reload. */
type AssetEditor = 'asset' | 'registration' | 'attributes' | 'assign';

/**
 * The asset **manager** page (`/assets/:id`, docs/CYCLES-PLAN.md §11, CD-b item 2 — reworked from a
 * hybrid manager/cockpit page into a pure manager view by docs/ASSET-MANAGER-PAGE-PLAN.md Wave B).
 * **No video, ever, on this page** — piloting and live video are the cockpit's job (`/fly`) and the
 * lightweight `/live/:deviceId` watch page, both one click away via the cockpit-link band.
 *
 * **Layered per docs/UI-ARCHITECTURE-PLAN.md**: every store/service injection, derived read-model,
 * and HTTP-backed command lives in {@link AssetDetailFacade} (provided below, alongside
 * `TelemetryStore` — unchanged, still one poller-set per route activation). This component is left
 * holding only:
 *   - the route-bound `assetId` input (only a component can receive one) and the single constructor
 *     `effect()` that forwards it to `facade.load()` and resets this page's own local overlay state;
 *   - the overlay/view-state a `UiStore`/`PanelState` group or a drill-in `subView` toggle is
 *     explicitly meant to be **host-owned**, per those classes' own doc comments ("a host owns one
 *     instance directly") — `editors` (the four-editor group, new this refactor), `panels` (the
 *     three/four drawers, unchanged), `subView` (the two wide-table sub-views, unchanged);
 *   - truly-ephemeral local view state no other component/route transition needs to stay consistent
 *     with: form drafts (`nameDraft`/`categoryDraft`/`registrationNumberDraft`/`renameDraft`/
 *     `assignDraft`/`attributeRows`), the "which inline row is open" pointer (`rowAction`), and the
 *     asset-photo `<img>` error flag (`imageLoadFailed`);
 *   - a handful of pure, stateless template helpers (label/format mappers, `@for`-bound per-row
 *     readers of a facade signal — the same "plain method reading a signal, called from `@for`"
 *     idiom `features/devices/devices.ts#simulatedInfo` already established).
 *
 * Every HTTP call, toast, silent-degrade path, and poll cadence is unchanged from the pre-facade
 * page — see {@link AssetDetailFacade}'s own doc comment.
 */
@Component({
  selector: 'vision-asset-detail',
  imports: [RouterLink, LiveMap, PilotsCard, SectionHeader, SidePanel, Icon, KebabMenu, EmptyState, Stat],
  templateUrl: './asset-detail.html',
  styleUrl: './asset-detail.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [AssetDetailFacade, TelemetryStore],
})
export class AssetDetailPage {
  /** Bound from the route by `withComponentInputBinding()`. */
  readonly assetId = input.required<string>();

  protected readonly facade = inject(AssetDetailFacade);

  // --- Overlay/view state — host-owned, see this class's own doc comment above ------------------

  private readonly editors = new UiStore();
  protected isEditorOpen(id: AssetEditor): boolean {
    return this.editors.isOpen(id);
  }

  protected readonly panels = new UiStore();
  protected readonly subView = signal<'overview' | 'usage' | 'hardware'>('overview');

  private readonly imageLoadFailed = signal(false);
  protected readonly assetImageSrc = computed(() => {
    const asset = this.facade.asset();
    if (!asset?.hasImage || this.imageLoadFailed()) {
      return undefined;
    }
    return this.facade.imageUrl(asset.assetId);
  });

  constructor() {
    // The only effect left on this component: forwards the route-bound `assetId` input to the
    // facade and resets this page's own local overlay/view state — a fresh navigation to a
    // *different* asset shouldn't stay parked in the previous one's editor/drill-in (Angular reuses
    // this component instance across same-route navigations rather than recreating it).
    effect(() => {
      const id = this.assetId();
      this.imageLoadFailed.set(false); // a fresh navigation deserves a fresh attempt at the photo
      this.subView.set('overview');
      this.panels.close();
      this.editors.close();
      this.facade.load(id);
    });
  }

  protected onImageError(): void {
    this.imageLoadFailed.set(true);
  }

  protected relativeTime(event: DetectionEvent): string {
    return relativeTimeLabel(event.lastSeen, this.facade.now());
  }

  // --- Drill-in navigation (docs/UI-REDESIGN-PLAN.md Wave 3) ---------------------------------

  protected openDrillIn(view: 'usage' | 'hardware'): void {
    this.subView.set(view);
  }

  protected closeDrillIn(): void {
    this.subView.set('overview');
  }

  // --- Per-device telemetry panels (called from the template) --------------------------------

  protected deviceFacts(deviceId: string): readonly TelemetryFactRow[] {
    return telemetryFactRows(this.facade.latestSampleFor(deviceId));
  }

  protected deviceSampleAgeSeconds(deviceId: string): number | undefined {
    return ageSeconds(this.facade.latestSampleFor(deviceId)?.at, this.facade.now());
  }

  protected deviceStale(deviceId: string): boolean {
    return isStale(this.deviceSampleAgeSeconds(deviceId));
  }

  protected usageDuration(usage: AssetUsage): string {
    return formatDuration(usageDurationSeconds(usage, this.facade.now()));
  }

  protected barLabel(bar: FlightBar): string {
    const when = new Date(bar.startedAt).toLocaleString();
    const duration = formatDuration(bar.durationSeconds);
    return bar.open ? `${when} · ${duration} so far · in progress` : `${when} · ${duration}`;
  }

  protected detailPairs(attributes: Record<string, string>): { key: string; value: string }[] {
    return Object.entries(attributes).map(([key, value]) => ({ key, value }));
  }

  // --- Asset header: rename/category edit + lifecycle -----------------------------------------

  protected readonly nameDraft = signal('');
  protected readonly categoryDraft = signal('');

  protected openEditAsset(): void {
    const asset = this.facade.asset();
    if (!asset) {
      return;
    }
    this.nameDraft.set(asset.displayName);
    this.categoryDraft.set(asset.category);
    this.editors.open('asset');
  }

  protected cancelEditAsset(): void {
    this.editors.close('asset');
  }

  protected async confirmEditAsset(): Promise<void> {
    const ok = await this.facade.saveAssetEdit(this.nameDraft(), this.categoryDraft());
    if (ok) {
      this.editors.close('asset');
    }
  }

  protected renameAsset(): void {
    this.openEditAsset();
  }

  protected assetActionLabel(action: 'archive' | 'restore'): string {
    return action === 'archive' ? 'Archive asset' : 'Restore asset';
  }

  protected onAssetLifecycleAction(action: 'archive' | 'restore'): void {
    if (action === 'archive') {
      void this.facade.archiveAssetNow();
    } else {
      void this.facade.restoreAssetNow();
    }
  }

  // --- Registration/tail number editor --------------------------------------------------------

  protected readonly registrationNumberDraft = signal('');

  protected openEditRegistrationNumber(): void {
    this.registrationNumberDraft.set(this.facade.registrationNumber() ?? '');
    this.editors.open('registration');
  }

  protected cancelEditRegistrationNumber(): void {
    this.editors.close('registration');
  }

  protected async confirmEditRegistrationNumber(): Promise<void> {
    const ok = await this.facade.saveRegistrationNumber(this.registrationNumberDraft());
    if (ok) {
      this.editors.close('registration');
    }
  }

  // --- Attributes editor (advanced-mode key/value rows) ----------------------------------------

  protected readonly attributeRows = signal<readonly AttributeRow[]>([]);
  protected readonly attributesSubmitting = signal(false);

  protected openAttributesEditor(): void {
    this.attributeRows.set(attributesToRows(this.facade.asset()?.attributes ?? {}));
    this.editors.open('attributes');
  }

  protected cancelAttributesEditor(): void {
    this.editors.close('attributes');
  }

  protected addAttributeRow(): void {
    this.attributeRows.update((rows) => [...rows, { key: '', value: '' }]);
  }

  protected removeAttributeRow(index: number): void {
    this.attributeRows.update((rows) => rows.filter((_, i) => i !== index));
  }

  protected updateAttributeKey(index: number, key: string): void {
    this.attributeRows.update((rows) => rows.map((row, i) => (i === index ? { ...row, key } : row)));
  }

  protected updateAttributeValue(index: number, value: string): void {
    this.attributeRows.update((rows) => rows.map((row, i) => (i === index ? { ...row, value } : row)));
  }

  protected async confirmAttributesEditor(): Promise<void> {
    this.attributesSubmitting.set(true);
    try {
      const ok = await this.facade.saveAttributes(attributeRowsToRecord(this.attributeRows()));
      if (ok) {
        this.editors.close('attributes');
      }
    } finally {
      this.attributesSubmitting.set(false);
    }
  }

  // --- Hardware section: per-device inline actions + attach-a-device ------------------------

  protected readonly rowAction = signal<{ deviceId: string; mode: 'rename' } | null>(null);
  protected readonly renameDraft = signal('');
  protected readonly assignDraft = signal('');

  protected deviceActionsFor(device: Device): readonly ActionAvailability<DeviceLifecycleAction>[] {
    const ownerDeviceCount = this.facade.asset()?.devices.length;
    return reasonedDeviceActions(device.state, true, ownerDeviceCount).filter((entry) => entry.action !== 'assign');
  }

  protected deviceActionLabel(action: DeviceLifecycleAction): string {
    return DEVICE_ACTION_LABELS[action];
  }

  protected onDeviceAction(device: Device, action: DeviceLifecycleAction): void {
    switch (action) {
      case 'rename':
        this.rowAction.set({ deviceId: device.id, mode: 'rename' });
        this.renameDraft.set(device.name);
        break;
      case 'activate':
        void this.facade.setDeviceLifecycle(device, 'ACTIVE');
        break;
      case 'deactivate':
        void this.facade.deactivateDeviceNow(device);
        break;
      case 'archive':
        void this.facade.archiveDeviceNow(device);
        break;
      case 'restore':
        void this.facade.setDeviceLifecycle(device, RESTORE_TARGET_STATE);
        break;
      case 'unassign':
        void this.facade.unassignDevice(device);
        break;
      case 'assign':
        break; // never offered — see deviceActionsFor
    }
  }

  protected rowActionMode(deviceId: string): 'rename' | null {
    const active = this.rowAction();
    return active && active.deviceId === deviceId ? active.mode : null;
  }

  protected cancelRowAction(): void {
    this.rowAction.set(null);
  }

  protected async confirmRename(device: Device): Promise<void> {
    const ok = await this.facade.renameDevice(device, this.renameDraft());
    if (ok) {
      this.rowAction.set(null);
    }
  }

  /** Attaching an existing, unowned device — resolved on demand, not kept warm on every page load. */
  protected async openAssign(): Promise<void> {
    this.editors.open('assign');
    await this.facade.loadAssignableDevices();
  }

  protected cancelAssign(): void {
    this.editors.close('assign');
    this.assignDraft.set('');
  }

  protected async confirmAssign(): Promise<void> {
    const deviceId = this.assignDraft();
    if (!deviceId) {
      return;
    }
    const ok = await this.facade.assignDevice(deviceId);
    if (ok) {
      this.cancelAssign();
    }
  }
}
