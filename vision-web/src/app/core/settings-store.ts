import { Injectable, computed, effect, signal } from '@angular/core';

/**
 * A named set of pipeline values — Tier 1 of the disclosure model in docs/UX-DESIGN.md §4.
 *
 * A profile is not a simplified parallel system: it is a saved set of the very same fields
 * the advanced controls edit, which is what makes "preset" and "expert" two views of one
 * thing rather than two products.
 */
export interface PipelineProfile {
  readonly id: string;
  readonly name: string;
  readonly description: string;
  /** Built-ins cannot be edited or deleted, only used as the base for a custom profile. */
  readonly builtIn: boolean;
  readonly confidenceThreshold: number;
  readonly inferenceFps: number;
}

/** `balanced` mirrors `PipelineConfig.defaults()` exactly, so "no override" and it agree. */
export const BUILT_IN_PROFILES: readonly PipelineProfile[] = [
  {
    id: 'balanced',
    name: 'Balanced',
    description: 'Backend defaults. A sensible starting point for a fixed camera.',
    builtIn: true,
    confidenceThreshold: 0.4,
    inferenceFps: 5,
  },
  {
    id: 'low-latency',
    name: 'Low latency',
    description: 'Fewer inferences per second, so frames spend less time waiting. For piloting.',
    builtIn: true,
    confidenceThreshold: 0.5,
    inferenceFps: 3,
  },
  {
    id: 'high-quality',
    name: 'High quality',
    description: 'Detects more, including marginal objects. Costs the most CPU/GPU.',
    builtIn: true,
    confidenceThreshold: 0.3,
    inferenceFps: 10,
  },
];

/** The values a profile carries — Tier 2 of the disclosure model. */
export interface PipelineSettings {
  readonly confidenceThreshold: number;
  readonly inferenceFps: number;
}

/**
 * The four base map layers (docs/CYCLES-PLAN.md §9, CU-b item 6) — shared by the fleet map
 * (`ui/fleet-map.ts`) and the live cockpit's map inset (`ui/live-map.ts`) via this
 * one persisted choice, rather than each map remembering its own. Definitions (tile URL,
 * attribution, max zoom) live in `ui/leaflet-loader.ts#MAP_LAYERS`, keyed by this id — this file
 * only owns *which one is selected*, not the tile-provider details.
 */
export type MapLayerId = 'standard' | 'night' | 'relief' | 'satellite';

/**
 * `night` (CARTO Dark Matter) rather than `standard` (plain OSM) — this console is dark by
 * default (docs/UX-DESIGN.md §7.7), and `night` is the true-dark-tile replacement for what used
 * to be an always-on CSS invert filter over OSM, so it is the closer match to the app's existing
 * look out of the box.
 */
const DEFAULT_MAP_LAYER: MapLayerId = 'night';

interface PersistedSettings {
  advancedMode: boolean;
  wallDensity: number;
  activeProfileId: string;
  customProfiles: PipelineProfile[];
  draft: PipelineSettings | null;
  mapLayer: MapLayerId;
  eventNotifications: boolean;
  flyAssetId: string | null;
}

const STORAGE_KEY = 'vision.settings.v1';

@Injectable({ providedIn: 'root' })
export class SettingsStore {
  /**
   * Account-level, not per-page: an expert flips this once and stays expert
   * (docs/UX-DESIGN.md §4, rule 4).
   */
  readonly advancedMode = signal(false);

  /** Wall tiles per row. */
  readonly wallDensity = signal(3);

  /** The active base map layer — persisted, shared by the fleet map and the live map inset. */
  readonly mapLayer = signal<MapLayerId>(DEFAULT_MAP_LAYER);

  /**
   * Opt-in browser `Notification`s for newly-opened detection events (docs/MVP2-PLAN.md §E, E-b)
   * — off by default, both because it's a permission-gated browser feature a user should
   * deliberately turn on, and because `Notification.requestPermission()` must be called from a
   * direct user gesture in most browsers, which only the Settings page's toggle can provide.
   * `core/events-store.ts` still independently checks `Notification.permission === 'granted'`
   * before ever firing one — flipping this signal alone (e.g. a stale/tampered persisted value)
   * can never bypass the browser's own permission gate.
   */
  readonly eventNotifications = signal(false);

  /**
   * The operator's last-chosen drone for the Fly cockpit (docs/MVP3-PLAN.md §C-b) — `null` until a
   * first pick is made. `null` is what tells `FlyPage` to show the asset picker instead of jumping
   * straight into a cockpit; every later visit (and every use of the in-cockpit switcher, which
   * writes here too) skips the picker. Not validated against the live fleet here — an id for an
   * asset that was since archived/deleted is a normal, expected staleness this store has no way to
   * detect on its own; `FlyPage` is the layer that checks the id still resolves and falls back to
   * the picker if not (same "store persists, page validates" split `mapLayer`/`activeProfileId`
   * already follow for their own stale-value cases).
   */
  readonly flyAssetId = signal<string | null>(null);

  readonly activeProfileId = signal(BUILT_IN_PROFILES[0].id);
  readonly customProfiles = signal<readonly PipelineProfile[]>([]);

  readonly profiles = computed(() => [...BUILT_IN_PROFILES, ...this.customProfiles()]);

  readonly activeProfile = computed(
    () =>
      this.profiles().find((profile) => profile.id === this.activeProfileId()) ??
      BUILT_IN_PROFILES[0],
  );

  /**
   * Unsaved edits layered on top of the selected profile.
   *
   * Editing a preset must not be a trap: the draft is what actually gets applied, the UI
   * badges it as `Custom (based on …)`, and both Revert and Save-as-new stay one click away
   * (docs/UX-DESIGN.md §4, rule 3).
   */
  private readonly draftSignal = signal<PipelineSettings | null>(null);

  readonly isCustom = computed(() => this.draftSignal() !== null);

  /** What a new stream is actually started with. */
  readonly effective = computed<PipelineSettings>(
    () =>
      this.draftSignal() ?? {
        confidenceThreshold: this.activeProfile().confidenceThreshold,
        inferenceFps: this.activeProfile().inferenceFps,
      },
  );

  constructor() {
    this.restore();
    effect(() => this.persist());
  }

  /** Selecting a preset discards any draft — the preset is the whole answer. */
  selectProfile(id: string): void {
    this.activeProfileId.set(id);
    this.draftSignal.set(null);
  }

  adjust(patch: Partial<PipelineSettings>): void {
    this.draftSignal.set({ ...this.effective(), ...patch });
  }

  revertDraft(): void {
    this.draftSignal.set(null);
  }

  saveDraftAs(name: string): void {
    const trimmed = name.trim();
    if (trimmed.length === 0) {
      return;
    }
    const current = this.effective();
    const profile: PipelineProfile = {
      id: `custom-${trimmed.toLowerCase().replace(/[^a-z0-9]+/g, '-')}`,
      name: trimmed,
      description: `Based on ${this.activeProfile().name}.`,
      builtIn: false,
      confidenceThreshold: current.confidenceThreshold,
      inferenceFps: current.inferenceFps,
    };
    this.saveCustomProfile(profile);
    this.draftSignal.set(null);
  }

  saveCustomProfile(profile: PipelineProfile): void {
    this.customProfiles.update((current) => {
      const without = current.filter((candidate) => candidate.id !== profile.id);
      return [...without, profile];
    });
    this.activeProfileId.set(profile.id);
  }

  deleteCustomProfile(id: string): void {
    this.customProfiles.update((current) => current.filter((profile) => profile.id !== id));
    if (this.activeProfileId() === id) {
      this.activeProfileId.set(BUILT_IN_PROFILES[0].id);
    }
  }

  private restore(): void {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) {
      return;
    }
    try {
      const parsed = JSON.parse(raw) as Partial<PersistedSettings>;
      if (typeof parsed.advancedMode === 'boolean') {
        this.advancedMode.set(parsed.advancedMode);
      }
      if (typeof parsed.wallDensity === 'number') {
        this.wallDensity.set(parsed.wallDensity);
      }
      if (isMapLayerId(parsed.mapLayer)) {
        this.mapLayer.set(parsed.mapLayer);
      }
      if (typeof parsed.eventNotifications === 'boolean') {
        this.eventNotifications.set(parsed.eventNotifications);
      }
      if (typeof parsed.flyAssetId === 'string') {
        this.flyAssetId.set(parsed.flyAssetId);
      }
      if (Array.isArray(parsed.customProfiles)) {
        this.customProfiles.set(parsed.customProfiles);
      }
      if (typeof parsed.activeProfileId === 'string') {
        this.activeProfileId.set(parsed.activeProfileId);
      }
      if (parsed.draft) {
        this.draftSignal.set(parsed.draft);
      }
    } catch {
      // Corrupt or stale settings must never keep the app from starting.
      localStorage.removeItem(STORAGE_KEY);
    }
  }

  private persist(): void {
    const snapshot: PersistedSettings = {
      advancedMode: this.advancedMode(),
      wallDensity: this.wallDensity(),
      activeProfileId: this.activeProfileId(),
      customProfiles: [...this.customProfiles()],
      draft: this.draftSignal(),
      mapLayer: this.mapLayer(),
      eventNotifications: this.eventNotifications(),
      flyAssetId: this.flyAssetId(),
    };
    localStorage.setItem(STORAGE_KEY, JSON.stringify(snapshot));
  }
}

const MAP_LAYER_IDS: readonly MapLayerId[] = ['standard', 'night', 'relief', 'satellite'];

function isMapLayerId(value: unknown): value is MapLayerId {
  return typeof value === 'string' && (MAP_LAYER_IDS as readonly string[]).includes(value);
}
