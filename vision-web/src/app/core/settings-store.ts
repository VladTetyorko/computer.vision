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

interface PersistedSettings {
  advancedMode: boolean;
  wallDensity: number;
  activeProfileId: string;
  customProfiles: PipelineProfile[];
  draft: PipelineSettings | null;
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
    };
    localStorage.setItem(STORAGE_KEY, JSON.stringify(snapshot));
  }
}
