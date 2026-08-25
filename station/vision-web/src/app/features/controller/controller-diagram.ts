import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import {
  DEFAULT_STICK_MODE,
  axisToOffsetPercent,
  diagramLayout,
  verticalOffsetPercent,
  type PadAxis,
  type StickMode,
} from '../../core/rc/controller-diagram-logic';
import { axisToPercent, defaultAxisLabel, defaultButtonLabel, isButtonOn } from '../../core/rc/rc-input-logic';
import { controlKey } from '../../core/rc/control-action-logic';
import type { ControlFunction, ControlSource } from '../../core/api/models';

/** A physical control the operator pointed at on the diagram. */
export interface DiagramPick {
  readonly source: ControlSource;
  readonly sourceIndex: number;
}

/** One control as the diagram needs it: where it is, what it does, and what to call it. */
export interface DiagramControl {
  readonly source: ControlSource;
  readonly sourceIndex: number;
  /** What it does today, in the operator's words — see `controller-setup-logic.ts#bindingSummary`. */
  readonly caption: string;
  /** The function it drives, when it drives a channel — what places it on a stick pad. */
  readonly function?: ControlFunction;
}

/**
 * `vision-controller-diagram` — the transmitter, drawn, with every reading live.
 *
 * <h2>Why a picture and not the list of chips it replaces</h2>
 * The question this page exists to answer is "which one is Sw 5", and a wrapped row of identical
 * chips answers it only by elimination. Sticks drawn as pads and switches drawn as tracks answer it
 * by resemblance: a control moves on screen the way it moves in the operator's hand, so the one they
 * just flicked is the one that lit up, with no counting.
 *
 * <h2>Two things it is told rather than guesses</h2>
 * **Which pad a function is held on** comes from {@link stickMode} — axis numbers do not say
 * (`controller-diagram-logic.ts#diagramLayout` has the EdgeTX channel-order detail). **Which end of
 * a vertical axis is up** comes from {@link positiveIsUp}, because a gamepad and a transmitter
 * disagree about that and the browser reports only a number. Both are display preferences: they move
 * the picture and nothing else.
 *
 * Presentational: it emits {@link picked} and owns no state. Nothing here commands anything.
 */
@Component({
  selector: 'vision-controller-diagram',
  templateUrl: './controller-diagram.html',
  styleUrl: './controller-diagram.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ControllerDiagram {
  readonly axes = input.required<readonly number[]>();
  readonly buttons = input.required<readonly number[]>();

  /** Every mapped control; anything not in here is drawn as unmapped. */
  readonly controls = input<readonly DiagramControl[]>([]);

  /** Which stick holds which function — the operator's transmitter mode. */
  readonly stickMode = input<StickMode>(DEFAULT_STICK_MODE);

  /** Whether a vertical axis reads `+1` at the top (a transmitter) or at the bottom (a gamepad). */
  readonly positiveIsUp = input(true);

  /** Whether pointing at a control does anything — false for a built-in layout nobody may edit. */
  readonly interactive = input(false);

  /** The control to call out, e.g. the one Autodetect just claimed. */
  readonly highlight = input<string | undefined>(undefined);

  readonly picked = output<DiagramPick>();

  protected readonly axisLabel = defaultAxisLabel;
  protected readonly buttonLabel = defaultButtonLabel;
  protected readonly isOn = isButtonOn;
  protected readonly percent = axisToPercent;

  /** Axis index → the function it drives, the only thing the pad layout needs from the profile. */
  private readonly functions = computed(() => {
    const bound = new Map<number, ControlFunction>();
    for (const control of this.controls()) {
      if (control.source === 'AXIS' && control.function) {
        bound.set(control.sourceIndex, control.function);
      }
    }
    return bound;
  });

  private readonly captions = computed(() => {
    const captions = new Map<string, string>();
    for (const control of this.controls()) {
      captions.set(controlKey(control.source, control.sourceIndex), control.caption);
    }
    return captions;
  });

  protected readonly layout = computed(() =>
    diagramLayout(this.axes().length, this.functions(), this.stickMode()),
  );

  protected axisValue(index: number | undefined): number {
    return index === undefined ? 0 : (this.axes()[index] ?? 0);
  }

  /** Left-to-right position of a pad's dot; centred when that direction is unmapped. */
  protected padLeft(slot: PadAxis | undefined): number {
    return slot ? axisToOffsetPercent(this.axisValue(slot.axis)) : 50;
  }

  /** Left-to-right position of a bar's marker. */
  protected barOffset(axis: number): number {
    return axisToOffsetPercent(this.axisValue(axis));
  }

  /** Top-down position of a pad's dot; centred when that direction is unmapped. */
  protected padTop(slot: PadAxis | undefined): number {
    return slot ? verticalOffsetPercent(this.axisValue(slot.axis), this.positiveIsUp()) : 50;
  }

  protected caption(source: ControlSource, index: number): string | undefined {
    return this.captions().get(controlKey(source, index));
  }

  protected isHighlighted(source: ControlSource, index: number): boolean {
    return this.highlight() === controlKey(source, index);
  }

  protected pick(source: ControlSource, index: number): void {
    if (this.interactive()) {
      this.picked.emit({ source, sourceIndex: index });
    }
  }
}
