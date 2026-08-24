import { computed, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';
import { RcMonitor } from './rc-monitor';
import { RcInputService } from '../../core/rc/rc-input.service';
import { VirtualRcInputService } from '../../core/rc/virtual-rc-input.service';
import { RcSource } from '../../core/rc/rc-source.service';
import { ManualControlClient, type ManualControlEngageState } from '../../core/rc/manual-control-client';
import { ControlActionDispatcher } from '../../core/rc/control-action-dispatcher';
import { ControlProfileStore } from '../../core/rc/control-profile-store';
import { rulesFrom } from '../../core/rc/control-action-logic';
import { VisionApi } from '../../core/api/vision-api';
import type {
  ControlCatalog,
  ControlProfile,
  FlightCapability,
  ManualControlChannelBinding,
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

/** The layouts store, stubbed — the drawer only ever reads `profiles`/`rules` and calls `load`. */
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

/** One saved rover layout with a single arm-bound switch — the shape the drawer lists. */
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

/** `providers` is replaced wholesale by `overrideComponent`, so the real `RcSource`/
 * `VirtualRcInputService` are re-listed here: only the two browser-touching collaborators are
 * faked, and the source-selection logic under test stays the real one. */
function render(
  fakeRc: FakeRcInputService,
  fakeClient: FakeManualControlClient,
  extras: { store?: FakeControlProfileStore; dispatcher?: FakeControlActionDispatcher } = {},
) {
  TestBed.configureTestingModule({});
  const store = extras.store ?? new FakeControlProfileStore();
  const dispatcher = extras.dispatcher ?? new FakeControlActionDispatcher();
  TestBed.overrideComponent(RcMonitor, {
    set: {
      providers: [
        { provide: RcInputService, useValue: fakeRc },
        VirtualRcInputService,
        RcSource,
        { provide: ManualControlClient, useValue: fakeClient },
        { provide: ControlProfileStore, useValue: store },
        { provide: ControlActionDispatcher, useValue: dispatcher },
        // The merged flight section (C10) injects this; nothing in these tests clicks a command.
        { provide: VisionApi, useValue: {} },
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
  fixture.nativeElement.querySelector('.rc-engage button.big') as HTMLButtonElement;

describe('RcMonitor — Take control', () => {
  it('keeps the engage section when the Gamepad API is unsupported — control no longer needs one', () => {
    const fakeRc = new FakeRcInputService();
    fakeRc.setSupported(false);
    const fixture = render(fakeRc, new FakeManualControlClient());

    expect(fixture.nativeElement.textContent).toContain("doesn't expose gamepad input");
    expect(fixture.nativeElement.querySelector('.rc-engage')).not.toBeNull();
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

  it('the engaged state shows rateHz/latency and the channel map, with a big, text-labeled, danger-styled RELEASE control', () => {
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

    expect(fixture.nativeElement.textContent).toContain('33 Hz link');
    expect(fixture.nativeElement.textContent).toContain('42 ms RTT');
    expect(fixture.nativeElement.textContent).toContain('Steering → CH1');

    const releaseButton = fixture.nativeElement.querySelector('.rc-engage button.btn.danger') as HTMLButtonElement;
    expect(releaseButton.textContent?.trim()).toBe('RELEASE');
    expect(releaseButton.classList.contains('big')).toBe(true);

    releaseButton.click();
    expect(fakeClient.release).toHaveBeenCalledTimes(1);
  });

  it('renders the on-screen surface, shaped by the engaged map, only for the virtual source', () => {
    const fakeClient = new FakeManualControlClient();
    fakeClient.state.set('engaged');
    fakeClient.vehicleKind.set('ROVER');
    fakeClient.profileName.set('Ground vehicle');
    fakeClient.channelMap.set(ROVER_MAP);
    const fixture = render(new FakeRcInputService(), fakeClient);

    // A rover binds two controls, which share one steer/drive pad.
    expect(fixture.nativeElement.querySelectorAll('.vcs-pad').length).toBe(1);
    expect(fixture.nativeElement.textContent).toContain('Ground vehicle layout');
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

  it('lists the switches the operator bound, and points the dispatcher at that same layout', () => {
    const store = new FakeControlProfileStore();
    const dispatcher = new FakeControlActionDispatcher();
    store.profilesSignal.set([ROVER_PROFILE]);
    const fixture = render(new FakeRcInputService(), new FakeManualControlClient(), { store, dispatcher });
    fixture.componentRef.setInput('capabilities', ROVER_CAPABILITY);
    fixture.detectChanges();

    const text = fixture.nativeElement.querySelector('.rc-actions')?.textContent ?? '';
    expect(text).toContain('Sw 2');
    expect(text).toContain('Arm');
    expect(dispatcher.bind).toHaveBeenCalledWith('asset-1', ROVER_PROFILE, store.rules(), true);
  });

  it('binds nothing for a vehicle kind the operator has no layout for', () => {
    const store = new FakeControlProfileStore();
    const dispatcher = new FakeControlActionDispatcher();
    store.profilesSignal.set([ROVER_PROFILE]);
    const fixture = render(new FakeRcInputService(), new FakeManualControlClient(), { store, dispatcher });
    fixture.componentRef.setInput('capabilities', { ...ROVER_CAPABILITY, vehicleKind: 'COPTER' as VehicleKind });
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.rc-actions')).toBeNull();
    expect(dispatcher.bind).toHaveBeenLastCalledWith('asset-1', undefined, store.rules(), true);
  });
});
