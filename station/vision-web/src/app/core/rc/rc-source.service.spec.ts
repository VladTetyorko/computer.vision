import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { RcSource } from './rc-source.service';
import { RcInputService } from './rc-input.service';
import { VirtualRcInputService } from './virtual-rc-input.service';
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

function create(connected = false): { source: RcSource; gamepad: FakeRcInputService; virtual: VirtualRcInputService } {
  const gamepad = new FakeRcInputService(connected);
  TestBed.configureTestingModule({
    providers: [RcSource, VirtualRcInputService, { provide: RcInputService, useValue: gamepad }],
  });
  return { source: TestBed.inject(RcSource), gamepad, virtual: TestBed.inject(VirtualRcInputService) };
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
});
