import { Injectable, computed, effect, signal } from '@angular/core';

/**
 * A named set of pipeline values — Tier 1 of the disclosure model in docs/main/UX-DESIGN.md §4.
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
  readonly model: string;
  /** See `PipelineSettings#labelFilter`'s own doc comment — identical semantics, just persisted per profile. */
  readonly labelFilter: readonly string[];
  /** See `PipelineSettings#labelDenyFilter`'s own doc comment — identical semantics, just persisted per profile. */
  readonly labelDenyFilter: readonly string[];
  /** See `PipelineSettings#detectionEnabled`'s own doc comment. */
  readonly detectionEnabled: boolean;
}

/**
 * `model` used to be a closed TS union (`DetectionModelId`) over a hardcoded three-entry array
 * (`DETECTION_MODEL_OPTIONS`) — docs/plans/done/CV-CONTROL-PLAN.md Wave E drops both: the roster now comes
 * from `GET /api/cv/models` (`core/api/models.ts#CvModel`/`CvModelsResponse`, cached by
 * `core/fleet/fleet-store.ts#FleetStore.models`), which can grow/change at deploy time without a
 * frontend release. `model` is therefore a plain, unvalidated `string` here — exactly the
 * checkpoint filename cv-service's registry routes on, forwarded verbatim to
 * `core/api/models.ts#StartStreamRequest.model`/`UpdateStreamConfigRequest.model` — this store no
 * longer has (or needs) a closed set to check it against; `features/fly/cv-control-panel.ts` (and
 * `features/live/live.ts`/`features/settings/settings.ts`, which also render the picker) resolve a
 * display name by looking the id up in `FleetStore.models()`, degrading to the bare id string when
 * the roster hasn't loaded (or no longer lists it) rather than rejecting anything.
 *
 * A comma-composite id (`"yolo11n.pt,orion12l.pt"`) is still a legal value the registry parses —
 * unaffected by this change, still never split/rejoined on this side.
 */
export const DEFAULT_DETECTION_MODEL: string = 'yolo26n.pt';

/** `balanced` mirrors `PipelineConfig.defaults()` exactly, so "no override" and it agree —
 * including the fast NMS-free `yolo26n.pt` default (docs/plans/done/CV-CONTROL-PLAN.md §1: the domain's own
 * `defaults()` bug-fix, replacing the dead `"yolo"` id these profiles used to carry). `labelFilter:
 * []` ("all classes") on every built-in mirrors `PipelineConfig`'s own defaults too — none of the
 * three built-ins target the open-vocabulary model, so none of them narrow the class set.
 *
 * **`detectionEnabled: false`** (docs/plans/done/CV-DEMAND-PLAN.md wave D3, flipped from `true`) — mirrors
 * `PipelineConfig.DEFAULT_DETECTION_ENABLED`'s own flip (wave D1): detection is opt-in per stream
 * now, both server-side and here. Sending `detectionEnabled: true` explicitly on every stream start
 * (the old default) would silently override the new server default right back to "always on" for
 * every SPA-driven start, which defeats the point of the flip entirely — see
 * `StartStreamRequest.detectionEnabled`'s own doc comment. A custom profile a user already saved, or
 * a draft already persisted, is untouched by this change (only the three *built-ins* move); see
 * `withValidPipelineFields`'s own doc comment below for the parallel, but distinct, decision about a
 * *missing* persisted field. */
export const BUILT_IN_PROFILES: readonly PipelineProfile[] = [
  {
    id: 'balanced',
    name: 'Balanced',
    description: 'Backend defaults. A sensible starting point for a fixed camera.',
    builtIn: true,
    confidenceThreshold: 0.4,
    inferenceFps: 5,
    model: DEFAULT_DETECTION_MODEL,
    labelFilter: [],
    labelDenyFilter: [],
    detectionEnabled: false,
  },
  {
    id: 'low-latency',
    name: 'Low latency',
    description: 'Fewer inferences per second, so frames spend less time waiting. For piloting.',
    builtIn: true,
    confidenceThreshold: 0.5,
    inferenceFps: 3,
    model: DEFAULT_DETECTION_MODEL,
    labelFilter: [],
    labelDenyFilter: [],
    detectionEnabled: false,
  },
  {
    id: 'high-quality',
    name: 'High quality',
    description: 'Detects more, including marginal objects. Costs the most CPU/GPU.',
    builtIn: true,
    confidenceThreshold: 0.3,
    inferenceFps: 10,
    model: DEFAULT_DETECTION_MODEL,
    labelFilter: [],
    labelDenyFilter: [],
    detectionEnabled: false,
  },
];

/** The values a profile carries — Tier 2 of the disclosure model. */
export interface PipelineSettings {
  readonly confidenceThreshold: number;
  readonly inferenceFps: number;
  readonly model: string;
  /**
   * Which detection labels to keep, applied Java-side (docs/plans/done/CV-CONTROL-PLAN.md §A) —
   * **empty means "keep every label"**, the existing/unchanged semantics `StartStreamRequest`'s own
   * doc comment already states. Never validated against the roster here — an entry that doesn't
   * (yet) match anything the current model emits is simply never drawn, not rejected; see
   * `features/fly/cv-control-panel-logic.ts` for how the panel seeds/edits this on a model switch.
   */
  readonly labelFilter: readonly string[];
  /**
   * Which detection labels to drop, applied Java-side alongside {@link labelFilter} in the same
   * pre-fan-out drop site (docs/plans/done/CV-CLEAN-FEED-PLAN.md D-2) — **empty means "deny
   * nothing"**. The everyday one-click "hide this class" act (the Vision drawer's detections strip,
   * the class-chip checklist's own toggle) writes here, immediately, never to {@link labelFilter} —
   * that field stays the rarer model-intent allowlist (preset fill, seeding on a model switch). A
   * class in both lists is dropped regardless (deny always wins); this app's own UI never puts one
   * there deliberately, but the wire contract makes no such promise either way.
   */
  readonly labelDenyFilter: readonly string[];
  /** Server-side detection on/off (docs/plans/done/CV-CONTROL-PLAN.md §1) — `false` means zero inference CPU
   * spent on this stream; video keeps flowing regardless either way. **Defaults `false`**
   * (docs/plans/done/CV-DEMAND-PLAN.md wave D3, flipped from `true`), mirroring
   * `PipelineConfig.DEFAULT_DETECTION_ENABLED`'s own flip — detection is opt-in per stream now, not
   * on-by-default. Distinct from `FlyPage`'s own `boxesMode` (a purely client-side render toggle for
   * detections already computed) — see `cv-control-panel.html`'s own copy for the exact wording that
   * keeps the two from being confused in the UI. */
  readonly detectionEnabled: boolean;
}

/**
 * The four base map layers (docs/main/CYCLES-PLAN.md §9, CU-b item 6) — shared by the fleet map
 * (`shared/map/fleet-map.ts`) and the live cockpit's map inset (`shared/map/live-map.ts`) via this
 * one persisted choice, rather than each map remembering its own. Definitions (tile URL,
 * attribution, max zoom) live in `shared/map/leaflet-loader.ts#MAP_LAYERS`, keyed by this id — this file
 * only owns *which one is selected*, not the tile-provider details.
 */
export type MapLayerId = 'standard' | 'night' | 'relief' | 'satellite';

/**
 * `night` (CARTO Dark Matter) rather than `standard` (plain OSM) — this console is dark by
 * default (docs/main/UX-DESIGN.md §7.7), and `night` is the true-dark-tile replacement for what used
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
   * (docs/main/UX-DESIGN.md §4, rule 4).
   */
  readonly advancedMode = signal(false);

  /** Wall tiles per row. */
  readonly wallDensity = signal(3);

  /** The active base map layer — persisted, shared by the fleet map and the live map inset. */
  readonly mapLayer = signal<MapLayerId>(DEFAULT_MAP_LAYER);

  /**
   * Opt-in browser `Notification`s for newly-opened detection events (docs/plans/done/MVP2-PLAN.md §E, E-b)
   * — off by default, both because it's a permission-gated browser feature a user should
   * deliberately turn on, and because `Notification.requestPermission()` must be called from a
   * direct user gesture in most browsers, which only the Settings page's toggle can provide.
   * `core/events/events-store.ts` still independently checks `Notification.permission === 'granted'`
   * before ever firing one — flipping this signal alone (e.g. a stale/tampered persisted value)
   * can never bypass the browser's own permission gate.
   */
  readonly eventNotifications = signal(false);

  /**
   * The operator's last-chosen drone for the Fly cockpit (docs/plans/done/MVP3-PLAN.md §C-b) — `null` until a
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
   * (docs/main/UX-DESIGN.md §4, rule 3).
   */
  private readonly draftSignal = signal<PipelineSettings | null>(null);

  readonly isCustom = computed(() => this.draftSignal() !== null);

  /** What a new stream is actually started with. */
  readonly effective = computed<PipelineSettings>(
    () =>
      this.draftSignal() ?? {
        confidenceThreshold: this.activeProfile().confidenceThreshold,
        inferenceFps: this.activeProfile().inferenceFps,
        model: this.activeProfile().model,
        labelFilter: this.activeProfile().labelFilter,
        labelDenyFilter: this.activeProfile().labelDenyFilter,
        detectionEnabled: this.activeProfile().detectionEnabled,
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
      model: current.model,
      labelFilter: current.labelFilter,
      labelDenyFilter: current.labelDenyFilter,
      detectionEnabled: current.detectionEnabled,
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
        // Migration-safe: a profile saved before the model picker (or, this cycle, before
        // labelFilter/detectionEnabled) existed has no such field at all (not just an invalid one)
        // — every one backfills to its own honest default the same way a corrupt value on any
        // other field here does, rather than rejecting the whole profile.
        this.customProfiles.set(parsed.customProfiles.map(withValidPipelineFields));
      }
      if (typeof parsed.activeProfileId === 'string') {
        this.activeProfileId.set(parsed.activeProfileId);
      }
      if (parsed.draft) {
        this.draftSignal.set(withValidPipelineFields(parsed.draft));
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

function isNonEmptyString(value: unknown): value is string {
  return typeof value === 'string' && value.length > 0;
}

function isStringArray(value: unknown): value is readonly string[] {
  return Array.isArray(value) && value.every((entry) => typeof entry === 'string');
}

/**
 * Backfills a missing/corrupt `model`/`labelFilter`/`detectionEnabled` field — the one place both
 * `customProfiles` and `draft` go through on restore, so a profile saved before any of these fields
 * existed (or a tampered/stale value) reads exactly as if it had always carried the honest default,
 * never a reason to drop the rest of the profile.
 *
 * **`model` is no longer checked against a closed set** (docs/plans/done/CV-CONTROL-PLAN.md Wave E — see
 * `DEFAULT_DETECTION_MODEL`'s own doc comment for why): any non-empty string passes through
 * unchanged, including an id the current roster no longer lists — the picker degrades that to a
 * bare id display, it is never treated as corrupt data. Only a missing/non-string value falls back
 * to the default.
 *
 * **`detectionEnabled`'s own fallback flipped from `true` to `false`** (docs/plans/done/CV-DEMAND-PLAN.md
 * wave D3) — but only for a value that is genuinely *missing or corrupt*, never one that is present.
 * An explicit `true` or `false` already sitting in storage is a choice a person made (most profiles
 * a real user has saved since docs/plans/done/CV-CONTROL-PLAN.md Wave E carry the field explicitly, defaulted
 * `true` at the time) — silently flipping that to `false` on this wave's redeploy would be exactly
 * the kind of dishonest surprise this app avoids everywhere else (an operator who deliberately turned
 * detection on finds it off next session, with no toast, no explanation). Only the *absence* of the
 * field (a profile saved before it existed at all, or a value that isn't literally `true`/`false`)
 * counts as "no decision was ever made" — and an undecided value should read as the app's current
 * honest default, which is now off, not the stale `true` this function used to backfill to. The
 * `typeof value.detectionEnabled === 'boolean'` check below is what draws that line: it already
 * passes an explicit `false` (or `true`) through untouched, so this change is exactly and only the
 * one literal below.
 */
function withValidPipelineFields<
  T extends { model?: unknown; labelFilter?: unknown; labelDenyFilter?: unknown; detectionEnabled?: unknown },
>(
  value: T,
): T & { model: string; labelFilter: readonly string[]; labelDenyFilter: readonly string[]; detectionEnabled: boolean } {
  return {
    ...value,
    model: isNonEmptyString(value.model) ? value.model : DEFAULT_DETECTION_MODEL,
    labelFilter: isStringArray(value.labelFilter) ? value.labelFilter : [],
    // A profile/draft saved before wave W5 (docs/plans/done/CV-CLEAN-FEED-PLAN.md D-2) has no such
    // field at all — backfills to "deny nothing", the same honest default a fresh built-in carries,
    // not a reason to drop the rest of the profile (mirrors `labelFilter`'s own migration-safe rule
    // above).
    labelDenyFilter: isStringArray(value.labelDenyFilter) ? value.labelDenyFilter : [],
    detectionEnabled: typeof value.detectionEnabled === 'boolean' ? value.detectionEnabled : false,
  };
}
