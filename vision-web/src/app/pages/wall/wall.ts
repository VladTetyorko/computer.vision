import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { FleetStore } from '../../core/fleet-store';
import { SettingsStore } from '../../core/settings-store';
import { WallTile } from './wall-tile';

@Component({
  selector: 'vision-wall',
  imports: [WallTile, RouterLink],
  templateUrl: './wall.html',
  styleUrl: './wall.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class WallPage {
  protected readonly fleet = inject(FleetStore);
  protected readonly settings = inject(SettingsStore);

  protected readonly densities = [2, 3, 4, 5, 6] as const;

  protected readonly tiles = computed(() =>
    this.fleet.streams().map((stream) => ({
      stream,
      device: this.fleet.device(stream.deviceId),
    })),
  );

  /** Streams with no publisher URL cannot be watched; say so instead of showing black boxes. */
  protected readonly unwatchable = computed(
    () => this.fleet.streams().filter((stream) => !stream.viewUrl).length,
  );

  protected setDensity(value: string): void {
    this.settings.wallDensity.set(Number(value));
  }
}
