import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import { fleetCentroid } from '../../../../core/weather/weather-logic';
import type { GeoPosition } from '../../../../core/api/models';
import { SidePanel } from '../../../ui/side-panel';
import { TacticalMap } from '../../tactical-map/tactical-map';
import { LayerManager } from '../layer-manager';
import { DrawingToolbar } from '../drawing-toolbar';
import { MarksPanel, type MarksPanelCockpit } from '../marks-panel/marks-panel';
import { ZonesPanel } from '../../../../features/command/zones-panel';

/**
 * Which sections a host offers its `<vision-map-tools>` door (`docs/plans/active/COMMAND-MAP-FLOW-
 * PLAN.md` §3.2, **frozen**). `'off'`/`false` means the section does not render at all — one
 * settings record, never a growing parameter list (CLAUDE.md rule 10).
 */
export interface MapToolsCapabilities {
  readonly marks: boolean;
  /** `'view'` = "Show on map" + "Basemap" only; `'manage'` additionally renders "Manage layers". */
  readonly layers: 'off' | 'view' | 'manage';
  readonly draw: boolean;
  readonly zones: boolean;
  /** Cockpit-only: the drone whose telemetry backs "Mark target" and the bearing/distance readout. */
  readonly cockpit?: MarksPanelCockpit;
}

/**
 * `<vision-map-tools>` — the ONE cross-surface drawer (`docs/plans/active/COMMAND-MAP-FLOW-PLAN.md`
 * §3.2). A `<vision-side-panel title="Map tools" icon="layers">` wrapping four sections in one
 * **fixed order, always this order** — Marks → Layers → Draw → Zones — each rendered only when its
 * capability is on. Every host (`/command`, `/fly`'s two tool-rail doors, `/live/:deviceId`,
 * `/assets/:id`) mounts this same component with a different {@link MapToolsCapabilities}; no host
 * is merged, per §3.2.1's first refusal-honored — "the fix is in what each host shows from the
 * shared toolbox, not in merging the hosts".
 *
 * **`assetPositions`/`centerHint` for the Zones section** are derived here, once, from {@link map}
 * (the host's own `TacticalMap` viewChild) rather than threaded through `MapToolsCapabilities` —
 * every capable host already has a map instance with exactly this data (`TacticalMap.assets()`),
 * so a second, host-specific plumbing path would just be `CommandFacade.assetPositions`'s pre-
 * existing derivation (`marker.position`, `fleetCentroid`) duplicated three more times. `undefined`
 * (no map instance yet — an empty stage, or the section rendering before Leaflet has mounted)
 * degrades to `[]`/`null`, the same honest empty `<vision-zones-panel>` already handled.
 *
 * **`ZonesPanel` stays in `features/command/`** (co-located with `GeofenceZoneDialog`, which several
 * unrelated files reference by that path in doc comments only) and is imported directly here — an
 * intentional, disclosed exception to the usual features-depend-on-shared direction; see
 * `zones-panel.ts`'s own class doc for the full reasoning and `MODULE.md`'s changelog entry for this
 * wave.
 *
 * Everything else each section needs (`MarksStore`, `LayersStore`, `DrawingsStore`, `GeofenceStore`)
 * is injected by that section's own component, not here — this component holds no store references
 * of its own, only the `map`-derived Zones advisory above.
 */
@Component({
  selector: 'vision-map-tools',
  imports: [SidePanel, MarksPanel, LayerManager, DrawingToolbar, ZonesPanel],
  templateUrl: './map-tools.html',
  styleUrl: './map-tools.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class MapTools {
  readonly capabilities = input.required<MapToolsCapabilities>();
  /** The host's own `TacticalMap` instance (its `viewChild(TacticalMap)`), for the Layers section's
   * "Show on map"/"Basemap" rows and the Zones section's asset-position advisory. `undefined` when
   * the host has no map to control right now — every dependent section degrades honestly (empty
   * lists, no crash). */
  readonly map = input<TacticalMap | undefined>(undefined);
  /** The host names its own entry point; this is just what the drawer's own head says. */
  readonly title = input('Map tools');
  readonly close = output<void>();

  protected readonly assetPositions = computed<readonly GeoPosition[]>(
    () => this.map()?.assets().map((asset) => asset.position) ?? [],
  );
  protected readonly centerHint = computed<GeoPosition | null>(() => fleetCentroid(this.assetPositions()) ?? null);
}
