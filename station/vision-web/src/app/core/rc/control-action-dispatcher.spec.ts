import { EnvironmentInjector, createEnvironmentInjector, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ControlActionDispatcher } from './control-action-dispatcher';
import type { ControlActionRules } from './control-action-logic';
import { RcInputService } from './rc-input.service';
import { RcSource } from './rc-source.service';
import { VirtualRcInputService } from './virtual-rc-input.service';
import { VisionApi } from '../api/vision-api';
import { ToastService } from '../toast.service';
import type { ActionBinding, ControlProfile } from '../api/models';

/** The gamepad seam, driven frame by frame — the dispatcher only ever sees `RcSource`'s signals. */
class FakeRcInputService {
  private readonly _axes = signal<readonly number[]>([0, 0, 0, 0]);
  private readonly _buttons = signal<readonly number[]>([0, 0]);
  readonly connected = signal(true).asReadonly();
  readonly axes = this._axes.asReadonly();
  readonly buttons = this._buttons.asReadonly();

  frame(axes: readonly number[], buttons: readonly number[] = [0, 0]): void {
    this._axes.set([...axes]);
    this._buttons.set([...buttons]);
    TestBed.tick();
  }
}

/** Every command endpoint a bound control can reach, recorded rather than sent. */
class FakeVisionApi {
  readonly calls: string[] = [];
  result: 'ACCEPTED' | 'NO_ACK' = 'ACCEPTED';
  fail = false;
  auxRequests: { function: number; level: number }[] = [];

  arm = (assetId: string) => this.record(`arm:${assetId}`);
  disarm = (assetId: string) => this.record(`disarm:${assetId}`);
  emergencyStop = (assetId: string) => this.record(`emergency-stop:${assetId}`);
  returnHome = (assetId: string) => this.record(`return-home:${assetId}`);
  setMode = (assetId: string, mode: string) => this.record(`mode:${mode}`);
  auxFunction = (assetId: string, request: { function: number; level: number }) => {
    this.auxRequests.push(request);
    return this.record(`aux:${request.function}@${request.level}`);
  };

  private record(call: string): Promise<{ result: 'ACCEPTED' | 'NO_ACK' }> {
    this.calls.push(call);
    return this.fail ? Promise.reject(new Error('nope')) : Promise.resolve({ result: this.result });
  }
}

class FakeToastService {
  readonly shown: string[] = [];
  ok = (text: string) => this.shown.push(`ok:${text}`);
  warn = (text: string) => this.shown.push(`warn:${text}`);
  error = (text: string) => this.shown.push(`error:${text}`);
}

/** The catalogue's own answers, exactly as the host resolves them from `GET .../catalog`. */
const RULES: ControlActionRules = {
  dangerous: new Set(['ARM', 'TOGGLE_ARM', 'EMERGENCY_STOP'] as const),
  levels: new Map([
    ['LOW', 0],
    ['MIDDLE', 1],
    ['HIGH', 2],
  ] as const),
};

function profileWith(actionMap: readonly ActionBinding[]): ControlProfile {
  return {
    id: 'profile-1',
    source: 'SAVED',
    kind: 'ROVER',
    code: 'CUSTOM',
    name: 'Bench rover',
    active: true,
    updatedAt: undefined,
    channelMap: [],
    actionMap: [...actionMap],
  };
}

const RTL_ON_SW1: ActionBinding = {
  source: 'BUTTON',
  kind: 'BUTTON',
  sourceIndex: 0,
  positions: [{ position: 'HIGH', action: 'RETURN_TO_HOME', parameter: null }],
};

const ARM_ON_SW2: ActionBinding = {
  source: 'BUTTON',
  kind: 'BUTTON',
  sourceIndex: 1,
  positions: [{ position: 'HIGH', action: 'ARM', parameter: null }],
};

/** A 3-position switch on axis 2 — the shape that exercises every level the catalogue defines. */
const AUX_ON_AXIS2: ActionBinding = {
  source: 'AXIS',
  kind: 'SWITCH_3',
  sourceIndex: 2,
  positions: [
    { position: 'LOW', action: 'AUX_FUNCTION', parameter: '19' },
    { position: 'MIDDLE', action: 'AUX_FUNCTION', parameter: '19' },
    { position: 'HIGH', action: 'AUX_FUNCTION', parameter: '19' },
  ],
};

interface Harness {
  readonly dispatcher: ControlActionDispatcher;
  readonly rc: FakeRcInputService;
  readonly api: FakeVisionApi;
  readonly toasts: FakeToastService;
  readonly injector: EnvironmentInjector;
}

function create(): Harness {
  TestBed.configureTestingModule({});
  const rc = new FakeRcInputService();
  const api = new FakeVisionApi();
  const toasts = new FakeToastService();
  const injector = createEnvironmentInjector(
    [
      ControlActionDispatcher,
      RcSource,
      VirtualRcInputService,
      { provide: RcInputService, useValue: rc },
      { provide: VisionApi, useValue: api },
      { provide: ToastService, useValue: toasts },
    ],
    TestBed.inject(EnvironmentInjector),
  );
  return { dispatcher: injector.get(ControlActionDispatcher), rc, api, toasts, injector };
}

/** Settles the "first frame is already-settled" rule, so a later frame reads as a real edge. */
function settle(h: Harness): void {
  h.rc.frame([0, 0, 0, 0], [0, 0]);
}

beforeEach(() => {
  vi.useFakeTimers();
});

afterEach(() => {
  vi.useRealTimers();
});

describe('ControlActionDispatcher', () => {
  it('sends nothing until a bound control actually moves', async () => {
    const h = create();
    h.dispatcher.bind('asset-1', profileWith([RTL_ON_SW1]), RULES, true);

    // A switch already sitting in its firing position when the drawer opens must not fire.
    h.rc.frame([0, 0, 0, 0], [1, 0]);
    h.rc.frame([0, 0, 0, 0], [1, 0]);
    await vi.runAllTimersAsync();

    expect(h.api.calls).toEqual([]);
  });

  it('fires one command on the edge into a bound position, and does not repeat while held', async () => {
    const h = create();
    h.dispatcher.bind('asset-1', profileWith([RTL_ON_SW1]), RULES, true);
    settle(h);

    h.rc.frame([0, 0, 0, 0], [1, 0]);
    await vi.runAllTimersAsync();
    expect(h.api.calls).toEqual(['return-home:asset-1']);

    h.rc.frame([0, 0, 0, 0], [1, 0]);
    h.rc.frame([0, 0, 0, 0], [1, 0]);
    await vi.runAllTimersAsync();
    expect(h.api.calls).toEqual(['return-home:asset-1']);
  });

  it('names the control that fired, so the toast points at a row the operator can see', async () => {
    const h = create();
    h.dispatcher.bind('asset-1', profileWith([RTL_ON_SW1]), RULES, true);
    settle(h);

    h.rc.frame([0, 0, 0, 0], [1, 0]);
    await vi.runAllTimersAsync();

    expect(h.toasts.shown).toEqual(['ok:Return to home — Sw 1']);
    expect(h.dispatcher.lastFired()).toBe('Sw 1 · Return to home');
  });

  it('reports NO_ACK as its own outcome rather than as success', async () => {
    const h = create();
    h.api.result = 'NO_ACK';
    h.dispatcher.bind('asset-1', profileWith([RTL_ON_SW1]), RULES, true);
    settle(h);

    h.rc.frame([0, 0, 0, 0], [1, 0]);
    await vi.runAllTimersAsync();

    expect(h.toasts.shown[0]).toContain('warn:');
    expect(h.toasts.shown[0]).toContain('no acknowledgement');
  });

  it('surfaces a refused command instead of swallowing it', async () => {
    const h = create();
    h.api.fail = true;
    h.dispatcher.bind('asset-1', profileWith([RTL_ON_SW1]), RULES, true);
    settle(h);

    h.rc.frame([0, 0, 0, 0], [1, 0]);
    await vi.runAllTimersAsync();

    expect(h.toasts.shown[0]).toContain('error:');
    expect(h.dispatcher.lastFired()).toContain('failed');
  });

  it('holds a dangerous action for the full hold before it sends anything', async () => {
    const h = create();
    h.dispatcher.bind('asset-1', profileWith([ARM_ON_SW2]), RULES, true);
    settle(h);

    h.rc.frame([0, 0, 0, 0], [0, 1]);
    await vi.runAllTimersAsync();
    expect(h.api.calls).toEqual([]);
    expect(h.dispatcher.holding()).toBe('Hold Sw 2 to arm');

    // Still held, and now past the hold window: the next frame is what actually sends it.
    vi.advanceTimersByTime(700);
    h.rc.frame([0, 0, 0, 0], [0, 1]);
    await vi.runAllTimersAsync();

    expect(h.api.calls).toEqual(['arm:asset-1']);
    expect(h.dispatcher.holding()).toBeUndefined();
  });

  it('cancels a dangerous hold the moment the switch leaves the position', async () => {
    const h = create();
    h.dispatcher.bind('asset-1', profileWith([ARM_ON_SW2]), RULES, true);
    settle(h);

    h.rc.frame([0, 0, 0, 0], [0, 1]);
    h.rc.frame([0, 0, 0, 0], [0, 0]);
    vi.advanceTimersByTime(700);
    h.rc.frame([0, 0, 0, 0], [0, 0]);
    await vi.runAllTimersAsync();

    expect(h.api.calls).toEqual([]);
    expect(h.dispatcher.holding()).toBeUndefined();
  });

  it('sends the level of the position that actually fired, not a fixed one', async () => {
    const h = create();
    h.dispatcher.bind('asset-1', profileWith([AUX_ON_AXIS2]), RULES, true);
    settle(h);

    h.rc.frame([0, 0, 1, 0]);
    await vi.runAllTimersAsync();
    h.rc.frame([0, 0, -1, 0]);
    await vi.runAllTimersAsync();

    expect(h.api.auxRequests).toEqual([
      { function: 19, level: 2 },
      { function: 19, level: 0 },
    ]);
  });

  it('resolves TOGGLE_ARM against the latest telemetry, not a remembered state', async () => {
    const h = create();
    const toggle: ActionBinding = {
      source: 'BUTTON',
      kind: 'BUTTON',
      sourceIndex: 0,
      positions: [{ position: 'HIGH', action: 'TOGGLE_ARM', parameter: null }],
    };
    h.dispatcher.bind('asset-1', profileWith([toggle]), { ...RULES, dangerous: new Set() }, true);
    h.dispatcher.setArmed(true);
    settle(h);

    h.rc.frame([0, 0, 0, 0], [1, 0]);
    await vi.runAllTimersAsync();

    expect(h.api.calls).toEqual(['disarm:asset-1']);
  });

  it('does nothing at all for an operator who may not command this asset', async () => {
    const h = create();
    h.dispatcher.bind('asset-1', profileWith([RTL_ON_SW1]), RULES, false);
    settle(h);

    h.rc.frame([0, 0, 0, 0], [1, 0]);
    await vi.runAllTimersAsync();

    expect(h.api.calls).toEqual([]);
  });

  it('re-arms the settled rule when the layout changes, rather than firing against a stale map', async () => {
    const h = create();
    h.dispatcher.bind('asset-1', profileWith([RTL_ON_SW1]), RULES, true);
    settle(h);
    h.rc.frame([0, 0, 0, 0], [1, 0]);
    await vi.runAllTimersAsync();
    expect(h.api.calls).toEqual(['return-home:asset-1']);

    const other = { ...profileWith([RTL_ON_SW1]), id: 'profile-2' };
    h.dispatcher.bind('asset-1', other, RULES, true);
    h.rc.frame([0, 0, 0, 0], [0, 0]);
    h.rc.frame([0, 0, 0, 0], [1, 0]);
    await vi.runAllTimersAsync();

    // One further command, from the new layout's own edge -- never a replay of the old map.
    expect(h.api.calls).toEqual(['return-home:asset-1', 'return-home:asset-1']);
  });

  it('sends one command, not three, when a switch is flicked while a request is still open', async () => {
    const h = create();
    let release: (() => void) | undefined;
    h.api.arm = ((assetId: string) => {
      h.api.calls.push(`arm:${assetId}`);
      return new Promise((resolve) => {
        release = () => resolve({ result: 'ACCEPTED' });
      });
    }) as FakeVisionApi['arm'];
    h.dispatcher.bind('asset-1', profileWith([ARM_ON_SW2]), { ...RULES, dangerous: new Set() }, true);
    settle(h);

    h.rc.frame([0, 0, 0, 0], [0, 1]);
    h.rc.frame([0, 0, 0, 0], [0, 0]);
    h.rc.frame([0, 0, 0, 0], [0, 1]);
    await Promise.resolve();

    expect(h.api.calls).toEqual(['arm:asset-1']);
    release?.();
  });
});
