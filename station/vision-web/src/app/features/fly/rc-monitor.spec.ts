import { computed, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { describe, expect, it, vi } from 'vitest';
import { RcMonitor } from './rc-monitor';
import { RcInputService } from '../../core/rc/rc-input.service';
import { VirtualRcInputService } from '../../core/rc/virtual-rc-input.service';
import { KeyboardRcInputService } from '../../core/rc/keyboard-rc-input.service';
import { RcSource } from '../../core/rc/rc-source.service';
import { ManualControlClient, type ManualControlEngageState } from '../../core/rc/manual-control-client';
import { ControlActionDispatcher } from '../../core/rc/control-action-dispatcher';
import { ControlProfileStore } from '../../core/rc/control-profile-store';
import { rulesFrom } from '../../core/rc/control-action-logic';
import { VisionApi } from '../../core/api/vision-api';
import type {
  ControlBinding,
  ControlCatalog,
  ControlProfile,
  FlightCapability,
  ManualControlChannelBinding,
  ReadinessReport,
  VehicleKind,
} from '../../core/api/models';

/** A duck-typed stand-in for `RcInputService` — writable signals a test can drive directly, since
 * the real service only mutates its own signals via `gamepadconnected`/`disconnected` browser
 * events this environment can't easily dispatch. */
class FakeRcInputService {
  private readonly _supported = signal(true);
  private readonly _connected = signal(false);
  readonly connected = this._connected.asReadonly();
  readonly device = signal<{ id: string } | null>(null);
  readonly axes = signal<readonly number[]>([]);
  readonly buttons = signal<readonly number[]>([]);
  readonly updateRateHz = signal(0);
  readonly deviceLabel = computed(() => this.device()?.id ?? '');

  supported(): boolean {
    return this._supported();
  }

  setSupported(value: boolean): void {
    this._supported.set(value);
  }

  setConnected(value: boolean): void {
    this._connected.set(value);
  }

  start(): void {}
  stop(): void {}
}

/** The one `VisionApi` call this drawer makes on its own account (`assetReadiness` — wave R) —
 * resolves to `undefined` by default (the same "still loading/failed" shape a real rejected fetch
 * degrades to), overridable per test via {@link setReadiness}. */
class FakeVisionApi {
  private report: ReadinessReport | undefined;
  private rejects = false;

  setReadiness(report: ReadinessReport | undefined): void {
    this.report = report;
    this.rejects = false;
  }

  setRejects(): void {
    this.rejects = true;
  }

  assetReadiness(_assetId: string): Promise<ReadinessReport> {
    return this.rejects
      ? Promise.reject(new Error('network error'))
      : Promise.resolve(this.report as ReadinessReport);
  }
}

class FakeManualControlClient {
  readonly state = signal<ManualControlEngageState>('idle');
  readonly deniedReason = signal<string | undefined>(undefined);
  readonly latencyMs = signal<number | undefined>(undefined);
  readonly channelMap = signal<readonly ManualControlChannelBinding[] | undefined>(undefined);
  readonly rateHz = signal<number | undefined>(undefined);
  readonly vehicleKind = signal<VehicleKind | undefined>(undefined);
  readonly profileCode = signal<string | undefined>(undefined);
  readonly profileName = signal<string | undefined>(undefined);
  readonly watchdogTripped = signal(false);

  readonly engage = vi.fn();
  readonly release = vi.fn();
}

/** The layouts store, stubbed — the drawer only ever reads `profiles`/`rules`/`catalog` and calls
 * `load`. */
class FakeControlProfileStore {
  readonly profilesSignal = signal<readonly ControlProfile[]>([]);
  readonly catalogSignal = signal<ControlCatalog | undefined>(undefined);
  readonly profiles = this.profilesSignal.asReadonly();
  readonly catalog = this.catalogSignal.asReadonly();
  readonly loading = signal(false).asReadonly();
  readonly loaded = signal(true).asReadonly();
  readonly rules = computed(() => rulesFrom(this.catalogSignal()));
  readonly load = vi.fn().mockResolvedValue(undefined);
}

/** The dispatcher, stubbed — what it does with a frame is `control-action-dispatcher.spec.ts`'s
 * job; what this drawer owes it is one `bind` call with the profile it resolved. */
class FakeControlActionDispatcher {
  readonly lastFired = signal<string | undefined>(undefined);
  readonly holding = signal<string | undefined>(undefined);
  readonly bind = vi.fn();
  readonly setArmed = vi.fn();
}

const ROVER_CAPABILITY: FlightCapability = {
  commandable: true,
  armSupported: true,
  modeSelectSupported: true,
  selectableModes: ['MANUAL', 'HOLD'],
  vehicleKind: 'ROVER',
};

/** One saved rover layout with a single arm-bound switch — the shape the drawer's transmitter view
 * draws a switch-gauge row for. */
const ROVER_PROFILE: ControlProfile = {
  id: 'profile-1',
  source: 'SAVED',
  kind: 'ROVER',
  code: 'CUSTOM',
  name: 'Bench rover',
  active: true,
  channelMap: [],
  actionMap: [
    {
      source: 'BUTTON',
      kind: 'BUTTON',
      sourceIndex: 1,
      positions: [{ position: 'HIGH', action: 'ARM', parameter: null }],
    },
  ],
};

const centered = (
  fn: string,
  sourceIndex: number,
  rcChannel: number,
  label: string,
): ManualControlChannelBinding => ({
  source: 'AXIS',
  kind: 'AXIS',
  function: fn as ManualControlChannelBinding['function'],
  travel: 'CENTERED',
  sourceIndex,
  rcChannel,
  minMicros: 1000,
  centerMicros: 1500,
  maxMicros: 2000,
  label,
});

/** The rover map the backend sends for a `ROVER` heartbeat: steering on CH1, throttle on CH3, both
 * centred (50 = stop), and nothing else bound. */
const ROVER_MAP: readonly ManualControlChannelBinding[] = [
  centered('STEERING', 0, 1, 'Steering'),
  centered('THROTTLE', 2, 3, 'Throttle'),
];

/** The same rover layout, as a saved profile's own `ControlBinding[]` — the shape
 * `transmitter-view-logic.ts#normalizeChannelMap` adapts before engage (decision U1). */
const ROVER_CHANNEL_MAP: readonly ControlBinding[] = ROVER_MAP.map((binding) => ({
  ...binding,
  deadband: 0,
  reversed: false,
}));

/** `providers` is replaced wholesale by `overrideComponent`, so the real `RcSource`/
 * `VirtualRcInputService` are re-listed here: only the two browser-touching collaborators are
 * faked, and the source-selection logic under test stays the real one. */
function render(
  fakeRc: FakeRcInputService,
  fakeClient: FakeManualControlClient,
  extras: {
    store?: FakeControlProfileStore;
    dispatcher?: FakeControlActionDispatcher;
    api?: FakeVisionApi;
  } = {},
) {
  // `vision-transmitter-view` renders a `routerLink` "Set up ›" link whenever a control is
  // unmapped (transmitter-view.html), and the readiness rows' own "Open preflight ›" link
  // (wave R) needs one too — same `provideRouter([])` precedent as transmitter-view.spec.ts
  // itself; RcMonitor doesn't route anywhere on its own, it just hosts these links.
  TestBed.configureTestingModule({ providers: [provideRouter([])] });
  const store = extras.store ?? new FakeControlProfileStore();
  const dispatcher = extras.dispatcher ?? new FakeControlActionDispatcher();
  const api = extras.api ?? new FakeVisionApi();
  TestBed.overrideComponent(RcMonitor, {
    set: {
      providers: [
        { provide: RcInputService, useValue: fakeRc },
        VirtualRcInputService,
        KeyboardRcInputService,
        RcSource,
        { provide: ManualControlClient, useValue: fakeClient },
        { provide: ControlProfileStore, useValue: store },
        { provide: ControlActionDispatcher, useValue: dispatcher },
        // The merged flight section (C10) injects this too; nothing in these tests clicks a
        // command, and wave R's own `assetReadiness` read resolves to `undefined` by default.
        { provide: VisionApi, useValue: api },
      ],
    },
  });
  const fixture = TestBed.createComponent(RcMonitor);
  fixture.componentRef.setInput('assetId', 'asset-1');
  fixture.componentRef.setInput('assetDisplayName', 'Falcon');
  fixture.componentRef.setInput('canCommand', true);
  fixture.detectChanges();
  return fixture;
}

const engageButton = (fixture: { nativeElement: HTMLElement }) =>
  fixture.nativeElement.querySelector('.rc-footer button.big') as HTMLButtonElement;

describe('RcMonitor — Take control (sticky footer, decision U6)', () => {
  it('keeps the engage section when the Gamepad API is unsupported — control no longer needs one', () => {
    const fakeRc = new FakeRcInputService();
    fakeRc.setSupported(false);
    const fixture = render(fakeRc, new FakeManualControlClient());

    expect(fixture.nativeElement.textContent).toContain("doesn't expose gamepad input");
    expect(fixture.nativeElement.querySelector('.rc-footer')).not.toBeNull();
    expect(engageButton(fixture).disabled).toBe(false);
  });

  it('a disabled Take-control button shows the poka-yoke reason when the drone is not commandable', () => {
    const fixture = render(new FakeRcInputService(), new FakeManualControlClient());
    fixture.componentRef.setInput('canCommand', false);
    fixture.detectChanges();

    const button = engageButton(fixture);
    expect(button.textContent?.trim()).toBe('Take control');
    expect(button.disabled).toBe(true);
    expect(fixture.nativeElement.querySelector('.disabled-reason')?.textContent).toBe(
      "This drone isn't commandable right now.",
    );
  });

  it('defaults to the on-screen source with no transmitter, so Take control is enabled', () => {
    const fakeClient = new FakeManualControlClient();
    const fixture = render(new FakeRcInputService(), fakeClient);

    expect(engageButton(fixture).disabled).toBe(false);
    engageButton(fixture).click();
    expect(fakeClient.engage).toHaveBeenCalledWith('asset-1');
  });

  it('an enabled Take-control button calls client.engage(assetId) on click with a transmitter too', () => {
    const fakeRc = new FakeRcInputService();
    fakeRc.setConnected(true);
    const fakeClient = new FakeManualControlClient();
    const fixture = render(fakeRc, fakeClient);

    const button = engageButton(fixture);
    expect(button.disabled).toBe(false);
    button.click();

    expect(fakeClient.engage).toHaveBeenCalledWith('asset-1');
  });

  it('a connected transmitter is selected automatically, and its unplugging blocks with a reason that names the way out', () => {
    const fakeRc = new FakeRcInputService();
    fakeRc.setConnected(true);
    const fixture = render(fakeRc, new FakeManualControlClient());
    const transmitter = fixture.nativeElement.querySelectorAll('.rc-source button')[1] as HTMLButtonElement;
    expect(transmitter.classList.contains('active')).toBe(true);

    // Unplugging never demotes the source — it blocks, so a yanked cable is a failsafe, not a
    // silent handover to an on-screen stick sitting at idle.
    fakeRc.setConnected(false);
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.disabled-reason')?.textContent).toBe(
      'Plug your transmitter in, or switch to the on-screen controls.',
    );
  });

  it('the engaged state shows a big, text-labeled, danger-styled RELEASE control and the link chips', () => {
    const fakeRc = new FakeRcInputService();
    fakeRc.setConnected(true);
    const fakeClient = new FakeManualControlClient();
    fakeClient.state.set('engaged');
    fakeClient.rateHz.set(33);
    fakeClient.latencyMs.set(41.6);
    fakeClient.vehicleKind.set('ROVER');
    fakeClient.profileName.set('Ground vehicle');
    fakeClient.channelMap.set(ROVER_MAP);
    const fixture = render(fakeRc, fakeClient);

    const footerText = fixture.nativeElement.querySelector('.rc-footer')?.textContent ?? '';
    expect(footerText).toContain('33 Hz');
    expect(footerText).toContain('42 ms RTT');
    expect(footerText).toContain('Ground vehicle');

    const releaseButton = fixture.nativeElement.querySelector('.rc-footer button.btn.danger') as HTMLButtonElement;
    expect(releaseButton.textContent?.trim()).toBe('RELEASE');
    expect(releaseButton.classList.contains('big')).toBe(true);

    releaseButton.click();
    expect(fakeClient.release).toHaveBeenCalledTimes(1);
  });

  it('freezes the input choice while a session is live', () => {
    const fakeClient = new FakeManualControlClient();
    fakeClient.state.set('engaged');
    fakeClient.channelMap.set(ROVER_MAP);
    fakeClient.vehicleKind.set('ROVER');
    fakeClient.profileName.set('Ground vehicle');
    const fixture = render(new FakeRcInputService(), fakeClient);

    const buttons = fixture.nativeElement.querySelectorAll('.rc-source button') as NodeListOf<HTMLButtonElement>;
    expect(buttons[0].disabled).toBe(true);
    expect(buttons[1].disabled).toBe(true);
    expect(buttons[2].disabled).toBe(true);
  });

  it('the denied state shows the server-provided human reason', () => {
    const fakeClient = new FakeManualControlClient();
    fakeClient.state.set('denied');
    fakeClient.deniedReason.set('No live source address.');
    const fixture = render(new FakeRcInputService(), fakeClient);

    expect(fixture.nativeElement.textContent).toContain('Control denied — No live source address.');
  });

  it('the released state distinguishes an explicit release from a watchdog trip', () => {
    const fakeClient = new FakeManualControlClient();
    fakeClient.state.set('released');
    const fixture = render(new FakeRcInputService(), fakeClient);
    expect(fixture.nativeElement.textContent).toContain('Control released.');
    expect(fixture.nativeElement.textContent).not.toContain('input stalled');

    fakeClient.watchdogTripped.set(true);
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('input stalled');
  });
});

describe('RcMonitor — the transmitter view (docs/plans/active/CONTROLLER-UX-PLAN.md §2.1/§2.2)', () => {
  it('renders the transmitter view before engage, mirroring a connected gamepad live (decision U1)', () => {
    const fakeRc = new FakeRcInputService();
    fakeRc.setConnected(true);
    fakeRc.axes.set([0.5, 0, 0]);
    const store = new FakeControlProfileStore();
    store.profilesSignal.set([{ ...ROVER_PROFILE, channelMap: ROVER_CHANNEL_MAP }]);
    const fixture = render(fakeRc, new FakeManualControlClient(), { store });
    fixture.componentRef.setInput('capabilities', ROVER_CAPABILITY);
    fixture.detectChanges();

    // A rover's steer/drive pad, drawn from this operator's own saved layout — no session exists yet.
    expect(fixture.nativeElement.querySelectorAll('.tv-pad').length).toBe(1);
    const readouts = fixture.nativeElement.querySelectorAll('.tv-readout-value') as NodeListOf<HTMLElement>;
    const values = Array.from(readouts).map((el) => el.textContent?.trim());
    expect(values).toContain('75'); // steering's axes[0]=0.5, centred: round((0.5+1)*50)
  });

  it('is interactive only once engaged on the on-screen source (decision U2)', () => {
    const fakeClient = new FakeManualControlClient();
    fakeClient.state.set('engaged');
    fakeClient.vehicleKind.set('ROVER');
    fakeClient.profileName.set('Ground vehicle');
    fakeClient.channelMap.set(ROVER_MAP);
    const fixture = render(new FakeRcInputService(), fakeClient);

    // A rover binds two controls, which share one steer/drive pad.
    expect(fixture.nativeElement.querySelectorAll('.tv-pad').length).toBe(1);
    expect(fixture.nativeElement.querySelector('.tv-pad')?.classList.contains('interactive')).toBe(true);
  });

  it('stays a read-only mirror for a connected transmitter, even engaged', () => {
    const fakeRc = new FakeRcInputService();
    fakeRc.setConnected(true);
    const fakeClient = new FakeManualControlClient();
    fakeClient.state.set('engaged');
    fakeClient.vehicleKind.set('ROVER');
    fakeClient.profileName.set('Ground vehicle');
    fakeClient.channelMap.set(ROVER_MAP);
    const fixture = render(fakeRc, fakeClient);

    expect(fixture.nativeElement.querySelector('.tv-pad')?.classList.contains('interactive')).toBe(false);
  });

  it('prefers the engaged frame\'s own channel map over the browser-resolved profile once one exists', () => {
    const store = new FakeControlProfileStore();
    store.profilesSignal.set([ROVER_PROFILE]);
    const fakeClient = new FakeManualControlClient();
    const fixture = render(new FakeRcInputService(), fakeClient, { store });
    fixture.componentRef.setInput('capabilities', ROVER_CAPABILITY);
    fixture.detectChanges();
    // Before engage: the profile carries no channel map (only an arm switch) — no pads.
    expect(fixture.nativeElement.querySelectorAll('.tv-pad').length).toBe(0);

    fakeClient.state.set('engaged');
    fakeClient.channelMap.set(ROVER_MAP);
    fixture.detectChanges();
    // Once engaged, the server's own two-control rover map takes over — one steer/drive pad.
    expect(fixture.nativeElement.querySelectorAll('.tv-pad').length).toBe(1);
  });
});

describe('RcMonitor — state strip (decision U5)', () => {
  it('shows a faint dash for an unknown armed state, and omits the mode/device chips with no signal', () => {
    const fixture = render(new FakeRcInputService(), new FakeManualControlClient());

    const strip = fixture.nativeElement.querySelector('.state-strip') as HTMLElement;
    expect(strip.textContent).toContain('—');
    expect(strip.querySelectorAll('.chip').length).toBe(1);
  });

  it('reflects armed/disarmed and the mode chip from their own inputs', () => {
    const fixture = render(new FakeRcInputService(), new FakeManualControlClient());
    fixture.componentRef.setInput('armed', true);
    fixture.componentRef.setInput('mode', 'HOLD');
    fixture.detectChanges();

    const strip = fixture.nativeElement.querySelector('.state-strip') as HTMLElement;
    expect(strip.textContent).toContain('ARMED');
    expect(strip.textContent).toContain('HOLD');
  });

  it('shows the device label and update rate only once a transmitter is connected', () => {
    const fakeRc = new FakeRcInputService();
    const fixture = render(fakeRc, new FakeManualControlClient());
    expect((fixture.nativeElement.querySelector('.state-strip') as HTMLElement).textContent).not.toContain('Hz');

    fakeRc.device.set({ id: 'RadioMaster TX16S' });
    fakeRc.setConnected(true);
    fakeRc.updateRateHz.set(62);
    fixture.detectChanges();

    const strip = (fixture.nativeElement.querySelector('.state-strip') as HTMLElement).textContent ?? '';
    expect(strip).toContain('RadioMaster TX16S');
    expect(strip).toContain('62 Hz');
  });

  it('a stale sample drops the armed chip\'s confident ARMED for a past-tense fact, and fades the mode chip (docs/plans/active/OPERATOR-UX-3-PLAN.md finding H1)', () => {
    const fixture = render(new FakeRcInputService(), new FakeManualControlClient());
    fixture.componentRef.setInput('armed', true);
    fixture.componentRef.setInput('mode', 'LOITER');
    fixture.componentRef.setInput('sampleAgeSeconds', 353099);
    fixture.detectChanges();

    const strip = fixture.nativeElement.querySelector('.state-strip') as HTMLElement;
    expect(strip.textContent).toContain('Armed 4d 2h ago');
    expect(strip.textContent).not.toContain('ARMED');
    const armedChipEl = strip.querySelector('.chip') as HTMLElement;
    expect(armedChipEl.classList.contains('ok')).toBe(false);
    const modeChipEl = Array.from(strip.querySelectorAll('.chip')).find((el) => el.textContent?.trim() === 'LOITER');
    expect(modeChipEl?.classList.contains('stale')).toBe(true);
  });

  it('stays the confident ARMED/plain mode chip while the sample is merely aging, not yet stale', () => {
    const fixture = render(new FakeRcInputService(), new FakeManualControlClient());
    fixture.componentRef.setInput('armed', true);
    fixture.componentRef.setInput('mode', 'LOITER');
    fixture.componentRef.setInput('sampleAgeSeconds', 10);
    fixture.detectChanges();

    const strip = fixture.nativeElement.querySelector('.state-strip') as HTMLElement;
    expect(strip.textContent).toContain('ARMED');
    const armedChipEl = strip.querySelector('.chip') as HTMLElement;
    expect(armedChipEl.classList.contains('ok')).toBe(true);
    const modeChipEl = Array.from(strip.querySelectorAll('.chip')).find((el) => el.textContent?.trim() === 'LOITER');
    expect(modeChipEl?.classList.contains('stale')).toBe(false);
  });
});

describe('RcMonitor — the merged Controller drawer (docs/plans/active/CONTROLLER-SETUP-CONTEXT.md C10)', () => {
  it('shows mode and arm/disarm inside this one drawer, with no drawer of their own', () => {
    const fixture = render(new FakeRcInputService(), new FakeManualControlClient());
    fixture.componentRef.setInput('capabilities', ROVER_CAPABILITY);
    fixture.detectChanges();

    const panels = fixture.nativeElement.querySelectorAll('vision-side-panel');
    expect(panels.length).toBe(1);
    expect(fixture.nativeElement.querySelector('.command-cluster')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('.arm-btn')).not.toBeNull();
  });

  it('hides the flight section entirely for a vehicle whose capabilities never resolved', () => {
    const fixture = render(new FakeRcInputService(), new FakeManualControlClient());

    expect(fixture.nativeElement.querySelector('.command-cluster')).toBeNull();
  });

  it('lists the switches the operator bound as transmitter-view rows, and points the dispatcher at that same layout', () => {
    const store = new FakeControlProfileStore();
    const dispatcher = new FakeControlActionDispatcher();
    store.profilesSignal.set([ROVER_PROFILE]);
    const fixture = render(new FakeRcInputService(), new FakeManualControlClient(), { store, dispatcher });
    fixture.componentRef.setInput('capabilities', ROVER_CAPABILITY);
    fixture.detectChanges();

    const text = fixture.nativeElement.textContent ?? '';
    expect(text).toContain('Sw 2');
    expect(text).toContain('Arm');
    expect(fixture.nativeElement.querySelectorAll('vision-switch-gauge').length).toBe(1);
    expect(dispatcher.bind).toHaveBeenCalledWith('asset-1', ROVER_PROFILE, store.rules(), true);
  });

  it('binds nothing for a vehicle kind the operator has no layout for', () => {
    const store = new FakeControlProfileStore();
    const dispatcher = new FakeControlActionDispatcher();
    store.profilesSignal.set([ROVER_PROFILE]);
    const fixture = render(new FakeRcInputService(), new FakeManualControlClient(), { store, dispatcher });
    fixture.componentRef.setInput('capabilities', { ...ROVER_CAPABILITY, vehicleKind: 'COPTER' as VehicleKind });
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelectorAll('vision-switch-gauge').length).toBe(0);
    expect(dispatcher.bind).toHaveBeenLastCalledWith('asset-1', undefined, store.rules(), true);
  });

  it('computes the "also on" hints from the active profile and passes them to the flight command panel', () => {
    const store = new FakeControlProfileStore();
    store.profilesSignal.set([
      {
        ...ROVER_PROFILE,
        actionMap: [
          ...ROVER_PROFILE.actionMap,
          {
            source: 'AXIS',
            kind: 'SWITCH_2',
            sourceIndex: 4,
            positions: [{ position: 'HIGH', action: 'SET_MODE', parameter: 'HOLD' }],
          },
        ],
      },
    ]);
    const fixture = render(new FakeRcInputService(), new FakeManualControlClient(), { store });
    fixture.componentRef.setInput('capabilities', ROVER_CAPABILITY);
    fixture.detectChanges();

    const text = fixture.nativeElement.querySelector('.command-cluster')?.textContent ?? '';
    expect(text).toContain('also on Sw 2 ↑');
    expect(text).toContain('also on Axis 5');
  });
});

/** A microtask-only flush — the mocked `assetReadiness()` promise resolves/rejects immediately, so
 * a couple of already-resolved `await`s drain its `.then()`/`.catch()` deterministically without
 * depending on `fixture.whenStable()`'s own task-tracking (which nothing here registers this
 * hand-written promise into). */
async function flushMicrotasks(): Promise<void> {
  await Promise.resolve();
  await Promise.resolve();
}

const NOT_READY_REPORT: ReadinessReport = {
  assetId: 'asset-1',
  verdict: 'NO_GO',
  evaluatedAt: '2026-08-28T00:00:00Z',
  profileObservedAt: '2026-08-28T00:00:00Z',
  features: [
    { feature: 'rc-relay', label: 'RC relay', status: 'MISSING', detail: 'GCS sysid not set.', remedy: 'PARAM_WRITE' },
  ],
  blockers: ['rc-relay'],
};

describe('RcMonitor — keyboard source (docs/plans/active/CONTROLLER-UX-PLAN.md §5 wave K)', () => {
  it('adds a Keyboard button after Transmitter, selectable with no gamepad plugged in', () => {
    const fixture = render(new FakeRcInputService(), new FakeManualControlClient());

    const buttons = fixture.nativeElement.querySelectorAll('.rc-source button') as NodeListOf<HTMLButtonElement>;
    expect(buttons.length).toBe(3);
    expect(buttons[2].textContent?.trim()).toBe('Keyboard');
    expect(buttons[2].disabled).toBe(false);

    buttons[2].click();
    fixture.detectChanges();

    expect(buttons[2].classList.contains('active')).toBe(true);
    expect(engageButton(fixture).disabled).toBe(false);
  });

  it('shows a quiet key-legend line once the keyboard source is selected and the layout binds axes', () => {
    const store = new FakeControlProfileStore();
    store.profilesSignal.set([{ ...ROVER_PROFILE, channelMap: ROVER_CHANNEL_MAP }]);
    const fixture = render(new FakeRcInputService(), new FakeManualControlClient(), { store });
    fixture.componentRef.setInput('capabilities', ROVER_CAPABILITY);
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.key-legend')).toBeNull();

    const buttons = fixture.nativeElement.querySelectorAll('.rc-source button') as NodeListOf<HTMLButtonElement>;
    buttons[2].click();
    fixture.detectChanges();

    const legend = fixture.nativeElement.querySelector('.key-legend');
    expect(legend?.textContent?.trim().toLowerCase()).toBe('w/s throttle · a/d steering');
  });
});

describe('RcMonitor — keyboard action-key rows (docs/plans/active/MAVLINK-COMMANDS-PLAN.md D3, wave W2)', () => {
  const selectKeyboard = (fixture: { nativeElement: HTMLElement; detectChanges: () => void }) => {
    const buttons = fixture.nativeElement.querySelectorAll('.rc-source button') as NodeListOf<HTMLButtonElement>;
    buttons[2].click();
    fixture.detectChanges();
  };

  it('shows no key rows before the keyboard source is selected', () => {
    const fixture = render(new FakeRcInputService(), new FakeManualControlClient());
    fixture.componentRef.setInput('capabilities', ROVER_CAPABILITY);
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.tv-keys')).toBeNull();
  });

  it("lists Space/Shift+Enter always, the vehicle's own selectableModes for the digits, and the live-armed verb", () => {
    const fixture = render(new FakeRcInputService(), new FakeManualControlClient());
    fixture.componentRef.setInput('capabilities', ROVER_CAPABILITY); // selectableModes: ['MANUAL', 'HOLD']
    fixture.componentRef.setInput('armed', true);
    fixture.detectChanges();
    selectKeyboard(fixture);

    const rows = fixture.nativeElement.querySelectorAll('.tv-keys .tv-row');
    const view = Array.from(rows).map((row) => ({
      id: (row as HTMLElement).querySelector('.tv-row-id')?.textContent?.trim(),
      text: (row as HTMLElement).querySelector('.tv-cell-text')?.textContent?.trim(),
    }));
    expect(view).toEqual([
      { id: 'Space', text: 'Emergency stop' },
      { id: 'Shift+Enter', text: 'Disarm' }, // armed
      { id: '1', text: 'MANUAL' },
      { id: '2', text: 'HOLD' },
    ]);
  });

  it('shows nothing for digits past the reported mode list — a two-mode rover gets no row 3 or 4', () => {
    const fixture = render(new FakeRcInputService(), new FakeManualControlClient());
    fixture.componentRef.setInput('capabilities', ROVER_CAPABILITY);
    fixture.detectChanges();
    selectKeyboard(fixture);

    const ids = Array.from(fixture.nativeElement.querySelectorAll('.tv-keys .tv-row-id')).map((el) =>
      (el as HTMLElement).textContent?.trim(),
    );
    expect(ids).toEqual(['Space', 'Shift+Enter', '1', '2']);
  });

  it('lights the cell text while the operator physically holds the chord', () => {
    const fixture = render(new FakeRcInputService(), new FakeManualControlClient());
    fixture.componentRef.setInput('capabilities', ROVER_CAPABILITY);
    fixture.detectChanges();
    selectKeyboard(fixture);

    window.dispatchEvent(new KeyboardEvent('keydown', { code: 'Digit1' }));
    fixture.detectChanges();

    const modeRow = Array.from(fixture.nativeElement.querySelectorAll('.tv-keys .tv-row')).find(
      (row) => (row as HTMLElement).querySelector('.tv-row-id')?.textContent?.trim() === '1',
    ) as HTMLElement;
    expect(modeRow.querySelector('.tv-cell-text')?.classList.contains('lit')).toBe(true);

    window.dispatchEvent(new KeyboardEvent('keyup', { code: 'Digit1' }));
  });
});

describe('RcMonitor — readiness rows under Take control (docs/plans/active/CONTROLLER-UX-PLAN.md §5 wave R)', () => {
  it('renders nothing while the readiness read is still in flight, and the button stays enabled', async () => {
    const fixture = render(new FakeRcInputService(), new FakeManualControlClient());
    await flushMicrotasks();
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.readiness-rows')).toBeNull();
    expect(fixture.nativeElement.querySelector('.disabled-reason')).toBeNull();
    expect(engageButton(fixture).disabled).toBe(false);
  });

  it('surfaces the rc-relay row once the read resolves, advisory only — Take control stays enabled', async () => {
    const api = new FakeVisionApi();
    api.setReadiness(NOT_READY_REPORT);
    const fixture = render(new FakeRcInputService(), new FakeManualControlClient(), { api });
    await flushMicrotasks();
    fixture.detectChanges();

    const rows = fixture.nativeElement.querySelector('.readiness-rows');
    expect(rows?.textContent).toContain('RC relay');
    expect(rows?.textContent).toContain('GCS sysid not set.');
    expect(rows?.querySelector('.dot.danger')).not.toBeNull();
    expect(rows?.querySelector('a[href="/operate/preflight"]')?.textContent).toContain('Open preflight');
    expect(engageButton(fixture).disabled).toBe(false);
  });

  it('a more fundamental disabled reason takes priority over the readiness rows, and the rows are omitted', async () => {
    const api = new FakeVisionApi();
    api.setReadiness(NOT_READY_REPORT);
    const fixture = render(new FakeRcInputService(), new FakeManualControlClient(), { api });
    fixture.componentRef.setInput('canCommand', false);
    await flushMicrotasks();
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.disabled-reason')?.textContent).toBe(
      "This drone isn't commandable right now.",
    );
    expect(fixture.nativeElement.querySelector('.readiness-rows')).toBeNull();
  });

  it('degrades to nothing, never a fabricated warning, when the readiness read fails', async () => {
    const api = new FakeVisionApi();
    api.setRejects();
    const fixture = render(new FakeRcInputService(), new FakeManualControlClient(), { api });
    await flushMicrotasks();
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.readiness-rows')).toBeNull();
    expect(fixture.nativeElement.querySelector('.disabled-reason')).toBeNull();
  });
});
