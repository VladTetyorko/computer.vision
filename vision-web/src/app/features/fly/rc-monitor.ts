import { ChangeDetectionStrategy, Component, OnInit, inject, output } from '@angular/core';
import { SidePanel } from '../../shared/ui/side-panel';
import { Notice } from '../../shared/ui/notice';
import { RcInputService } from '../../core/rc/rc-input.service';
import {
  axisToPercent,
  barLeftPercent,
  barWidthPercent,
  defaultAxisLabel,
  defaultButtonLabel,
  isButtonOn,
} from '../../core/rc/rc-input-logic';

/**
 * `vision-rc-monitor` — the cockpit's RC transmitter monitor drawer (docs/RC-CONTROL-PLAN.md Phase
 * 0). Read-only: it shows a plugged-in RadioMaster's live sticks (axes) and switches (buttons) so an
 * operator can confirm the platform sees the controller and check its update rate. **Nothing here
 * touches the drone** — relaying to the flight controller is Phase 1.
 *
 * Provides its own `RcInputService` and drives its lifecycle: reading starts when this drawer mounts
 * and stops when it closes (the cockpit mounts it via `@if (isPanelOpen('rc'))`), so the Gamepad rAF
 * loop only runs while the panel is open.
 */
@Component({
  selector: 'vision-rc-monitor',
  imports: [SidePanel, Notice],
  providers: [RcInputService],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <vision-side-panel title="Controller" icon="gamepad" subtitle="RC transmitter monitor" (close)="close.emit()">
      @if (!rc.supported()) {
        <vision-notice variant="warn">
          This browser doesn't expose gamepad input. Use Chrome or Edge to read the transmitter.
        </vision-notice>
      } @else if (!rc.connected()) {
        <p class="muted">
          Plug your RadioMaster in over USB in <strong>USB Joystick</strong> mode, then move a stick or
          flip a switch — the browser only reveals a controller after its first input.
        </p>
        <vision-notice variant="warn">
          In USB Joystick mode the radio stops transmitting over RF. Keep this a bench/SITL check —
          it does not fly anything yet.
        </vision-notice>
      } @else {
        <div class="rc-head">
          <span class="rc-device mono truncate" [title]="rc.device()?.id">{{ rc.deviceLabel() }}</span>
          <span class="chip ok"><span class="dot ok"></span>{{ rc.updateRateHz() }} Hz</span>
        </div>

        <span class="label rc-group-label">Axes ({{ rc.axes().length }})</span>
        <div class="rc-list">
          @for (v of rc.axes(); track $index) {
            <div class="rc-row">
              <span class="rc-name">{{ axisLabel($index) }}</span>
              <div class="rc-bar" role="img" [attr.aria-label]="axisLabel($index) + ': ' + axisToPercent(v) + ' percent'">
                <span class="rc-tick"></span>
                <span class="rc-fill" [style.left.%]="barLeftPercent(v)" [style.width.%]="barWidthPercent(v)"></span>
              </div>
              <span class="rc-value mono">{{ axisToPercent(v) }}</span>
            </div>
          }
        </div>

        <span class="label rc-group-label">Switches ({{ rc.buttons().length }})</span>
        <div class="rc-switches">
          @for (b of rc.buttons(); track $index) {
            <span class="rc-switch" [class.on]="isOn(b)">{{ buttonLabel($index) }}</span>
          }
        </div>
      }
    </vision-side-panel>
  `,
  styles: `
    :host {
      display: contents;
    }

    .rc-head {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: var(--space-8);
      margin-bottom: var(--space-16);
    }

    .rc-device {
      font-size: 0.85rem;
      min-width: 0;
    }

    .rc-group-label {
      margin-top: var(--space-16);
    }

    .rc-list {
      display: flex;
      flex-direction: column;
      gap: var(--space-8);
    }

    .rc-row {
      display: grid;
      grid-template-columns: 3.5rem 1fr 3rem;
      align-items: center;
      gap: var(--space-8);
    }

    .rc-name {
      font-size: 0.78rem;
      color: var(--text-muted);
    }

    /* Center-origin bar: a track with a center tick and a fill that grows from the middle. */
    .rc-bar {
      position: relative;
      height: 0.5rem;
      border-radius: var(--radius-pill);
      background: var(--panel-raised);
      border: 1px solid var(--border);
      overflow: hidden;
    }

    .rc-tick {
      position: absolute;
      top: 0;
      bottom: 0;
      left: 50%;
      width: 1px;
      background: var(--border-strong);
    }

    .rc-fill {
      position: absolute;
      top: 0;
      bottom: 0;
      background: var(--color-info);
    }

    .rc-value {
      font-size: 0.8rem;
      text-align: right;
      font-variant-numeric: tabular-nums;
    }

    .rc-switches {
      display: flex;
      flex-wrap: wrap;
      gap: var(--space-8);
    }

    .rc-switch {
      padding: var(--space-4) var(--space-8);
      border-radius: var(--radius-sm);
      background: var(--panel-raised);
      border: 1px solid var(--border);
      color: var(--text-faint);
      font-size: 0.72rem;
      font-variant-numeric: tabular-nums;
      transition: color 0.1s ease, background 0.1s ease, border-color 0.1s ease;
    }

    .rc-switch.on {
      background: var(--color-success-soft);
      border-color: var(--color-success-line);
      color: var(--color-success-text);
    }
  `,
})
export class RcMonitor implements OnInit {
  protected readonly rc = inject(RcInputService);
  readonly close = output<void>();

  protected readonly axisToPercent = axisToPercent;
  protected readonly barLeftPercent = barLeftPercent;
  protected readonly barWidthPercent = barWidthPercent;
  protected readonly axisLabel = defaultAxisLabel;
  protected readonly buttonLabel = defaultButtonLabel;
  protected readonly isOn = isButtonOn;

  ngOnInit(): void {
    this.rc.start();
  }
}
