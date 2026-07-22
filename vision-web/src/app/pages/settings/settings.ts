import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { BUILT_IN_PROFILES, SettingsStore } from '../../core/settings-store';
import { FleetStore } from '../../core/fleet-store';

@Component({
  selector: 'vision-settings',
  templateUrl: './settings.html',
  styleUrl: './settings.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SettingsPage {
  protected readonly settings = inject(SettingsStore);
  protected readonly fleet = inject(FleetStore);

  protected readonly newProfileName = signal('');

  /** Backend defaults, for the "what does this preset change" comparison. */
  protected readonly defaults = BUILT_IN_PROFILES[0];

  protected readonly deltaFromDefaults = computed(() => {
    const current = this.settings.effective();
    const changes: string[] = [];
    if (current.confidenceThreshold !== this.defaults.confidenceThreshold) {
      changes.push(
        `confidence ${this.defaults.confidenceThreshold} → ${current.confidenceThreshold}`,
      );
    }
    if (current.inferenceFps !== this.defaults.inferenceFps) {
      changes.push(`inference ${this.defaults.inferenceFps} → ${current.inferenceFps} fps`);
    }
    return changes;
  });

  protected readonly customProfiles = computed(() => this.settings.customProfiles());

  protected onProfileChange(id: string): void {
    this.settings.selectProfile(id);
  }

  protected onConfidence(value: string): void {
    this.settings.adjust({ confidenceThreshold: Number(value) });
  }

  protected onFps(value: string): void {
    this.settings.adjust({ inferenceFps: Number(value) });
  }

  protected saveAs(): void {
    this.settings.saveDraftAs(this.newProfileName());
    this.newProfileName.set('');
  }

  protected toggleAdvanced(checked: boolean): void {
    this.settings.advancedMode.set(checked);
  }

  protected setDensity(value: string): void {
    this.settings.wallDensity.set(Number(value));
  }
}
