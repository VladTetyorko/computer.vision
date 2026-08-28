import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';
import { RcSource } from './rc-source.service';
import { RcInputService } from './rc-input.service';
import { VirtualRcInputService } from './virtual-rc-input.service';
import { KeyboardRcInputService } from './keyboard-rc-input.service';
import type { ManualControlChannelBinding } from '../api/models';

class FakeRcInputService {
  private readonly _connected: ReturnType<typeof signal<boolean>>;

  constructor(connected = false) {
    this._connected = signal(connected);
  }

  readonly connected = () => this._connected();
  readonly axes = signal<readonly number[]>([0.5, 0.5]);
  readonly buttons = signal<readonly number[]>([1]);

  setConnected(value: boolean): void {
    this._connected.set(value);
  }
}

/** A duck-typed stand-in for `KeyboardRcInputService` — a writable pair of signals plus a spy on
 * `setEnabled`, since the real service's window listeners have nothing to test at this level (that
 * is `keyboard-rc-input.service.spec.ts`'s own job); this spec only cares that `RcSource` routes to
 * it and drives its enabled state correctly. */
class FakeKeyboardRcInputService {
  readonly axes = signal<readonly number[]>([0.3, -0.2]);
  readonly buttons = signal<readonly number[]>([]);
  readonly setEnabled = vi.fn();
}

const STEERING: ManualControlChannelBinding = {
  source: 'AXIS',
  kind: 'AXIS',
  function: 'STEERING',
  travel: 'CENTERED',
  sourceIndex: 0,
  rcChannel: 1,
  minMicros: 1000,
  centerMicros: 1500,
  maxMicros: 2000,
  label: 'Steering',
};

function create(
  connected = false,
): {
  source: RcSource;
  gamepad: FakeRcInputService;
  virtual: VirtualRcInputService;
  keyboard: FakeKeyboardRcInputService;
} {
  const gamepad = new FakeRcInputService(connected);
  const keyboard = new FakeKeyboardRcInputService();
  TestBed.configureTestingModule({
    providers: [
      RcSource,
      VirtualRcInputService,
      { provide: RcInputService, useValue: gamepad },
      { provide: KeyboardRcInputService, useValue: keyboard },
    ],
  });
  return { source: TestBed.inject(RcSource), gamepad, virtual: TestBed.inject(VirtualRcInputService), keyboard };
}

describe('RcSource', () => {
  it('falls back to the on-screen surface when there is no transmitter, and is live regardless', () => {
    const { source } = create();
    TestBed.tick();

    expect(source.kind()).toBe('virtual');
    expect(source.live()).toBe(true);
  });

  it('reads a transmitter that was already plugged in at construction, without waiting for a flush', () => {
    const { source } = create(true);

    expect(source.kind()).toBe('gamepad');
    expect(source.axes()).toEqual([0.5, 0.5]);
  });

  it('promotes a transmitter plugged in later, and reads its axes', () => {
    const { source, gamepad } = create();
    gamepad.setConnected(true);
    TestBed.tick();

    expect(source.kind()).toBe('gamepad');
    expect(source.axes()).toEqual([0.5, 0.5]);
    expect(source.buttons()).toEqual([1]);
  });

  it('never demotes: an unplugged transmitter goes not-live, so the client releases rather than silently handing over', () => {
    const { source, gamepad } = create();
    gamepad.setConnected(true);
    TestBed.tick();
    gamepad.setConnected(false);
    TestBed.tick();

    expect(source.kind()).toBe('gamepad');
    expect(source.live()).toBe(false);
  });

  it('never promotes over a live on-screen session — a mid-session plug-in cannot take over', () => {
    const { source, gamepad, virtual } = create();
    TestBed.tick();
    virtual.bindTo([STEERING]);
    gamepad.setConnected(true);
    TestBed.tick();

    expect(source.kind()).toBe('virtual');
  });

  it('an explicit choice pins against the automatic promotion', () => {
    const { source, gamepad } = create();
    source.use('virtual');
    gamepad.setConnected(true);
    TestBed.tick();

    expect(source.kind()).toBe('virtual');
  });

  describe('the keyboard source', () => {
    it('is only reached explicitly — never the initial or auto-promoted kind', () => {
      const { source } = create(false);
      TestBed.tick();
      expect(source.kind()).not.toBe('keyboard');
    });

    it('routes axes/buttons to the keyboard service once selected, and reads live regardless of any gamepad', () => {
      const { source, keyboard } = create();
      source.use('keyboard');
      TestBed.tick();

      expect(source.kind()).toBe('keyboard');
      expect(source.axes()).toEqual([0.3, -0.2]);
      expect(source.buttons()).toEqual([]);
      expect(source.live()).toBe(true);
    });

    it('a connected transmitter never steals an explicitly chosen keyboard source', () => {
      const { source, gamepad } = create();
      source.use('keyboard');
      gamepad.setConnected(true);
      TestBed.tick();

      expect(source.kind()).toBe('keyboard');
    });

    it('enables the keyboard service only while it is the selected source, and disables it the moment another source is chosen', () => {
      const { source, keyboard } = create();
      TestBed.tick();
      expect(keyboard.setEnabled).toHaveBeenLastCalledWith(false);

      source.use('keyboard');
      TestBed.tick();
      expect(keyboard.setEnabled).toHaveBeenLastCalledWith(true);

      source.use('virtual');
      TestBed.tick();
      expect(keyboard.setEnabled).toHaveBeenLastCalledWith(false);
    });
  });
});
