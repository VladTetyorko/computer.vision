import { computed, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';
import { RcMonitor } from './rc-monitor';
import { RcInputService } from '../../core/rc/rc-input.service';
import { ManualControlClient, type ManualControlEngageState } from '../../core/rc/manual-control-client';
import type { ManualControlChannelBinding } from '../../core/api/models';

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
  readonly watchdogTripped = signal(false);

  readonly engage = vi.fn();
  readonly release = vi.fn();
}

function render(fakeRc: FakeRcInputService, fakeClient: FakeManualControlClient) {
  TestBed.configureTestingModule({});
  TestBed.overrideComponent(RcMonitor, {
    set: {
      providers: [
        { provide: RcInputService, useValue: fakeRc },
        { provide: ManualControlClient, useValue: fakeClient },
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

describe('RcMonitor — Take control', () => {
  it('hides the monitor and the engage section entirely when the Gamepad API is unsupported', () => {
    const fakeRc = new FakeRcInputService();
    fakeRc.setSupported(false);
    const fixture = render(fakeRc, new FakeManualControlClient());

    expect(fixture.nativeElement.textContent).toContain("doesn't expose gamepad input");
    expect(fixture.nativeElement.querySelector('.rc-engage')).toBeNull();
  });

  it('a disabled Take-control button shows the poka-yoke reason when the drone is not commandable', () => {
    const fixture = render(new FakeRcInputService(), new FakeManualControlClient());
    fixture.componentRef.setInput('canCommand', false);
    fixture.detectChanges();

    const button = fixture.nativeElement.querySelector('.rc-engage button') as HTMLButtonElement;
    expect(button.textContent?.trim()).toBe('Take control');
    expect(button.disabled).toBe(true);
    expect(fixture.nativeElement.querySelector('.disabled-reason')?.textContent).toBe(
      "This drone isn't commandable right now.",
    );
  });

  it('a disabled Take-control button shows the poka-yoke reason when no transmitter is plugged in', () => {
    const fixture = render(new FakeRcInputService(), new FakeManualControlClient());

    const button = fixture.nativeElement.querySelector('.rc-engage button') as HTMLButtonElement;
    expect(button.disabled).toBe(true);
    expect(fixture.nativeElement.querySelector('.disabled-reason')?.textContent).toBe('Plug your transmitter in first.');
  });

  it('an enabled Take-control button calls client.engage(assetId) on click', () => {
    const fakeRc = new FakeRcInputService();
    fakeRc.setConnected(true);
    const fakeClient = new FakeManualControlClient();
    const fixture = render(fakeRc, fakeClient);

    const button = fixture.nativeElement.querySelector('.rc-engage button') as HTMLButtonElement;
    expect(button.disabled).toBe(false);
    button.click();

    expect(fakeClient.engage).toHaveBeenCalledWith('asset-1');
  });

  it('the engaged state shows rateHz/latency and the channel map, with a big, text-labeled, danger-styled RELEASE control', () => {
    const fakeRc = new FakeRcInputService();
    fakeRc.setConnected(true);
    const fakeClient = new FakeManualControlClient();
    fakeClient.state.set('engaged');
    fakeClient.rateHz.set(33);
    fakeClient.latencyMs.set(41.6);
    fakeClient.channelMap.set([{ source: 'AXIS', sourceIndex: 0, rcChannel: 1, label: 'Roll' }]);
    const fixture = render(fakeRc, fakeClient);

    expect(fixture.nativeElement.textContent).toContain('33 Hz link');
    expect(fixture.nativeElement.textContent).toContain('42 ms RTT');
    expect(fixture.nativeElement.textContent).toContain('Roll → CH1');

    const releaseButton = fixture.nativeElement.querySelector('.rc-engage button.btn.danger') as HTMLButtonElement;
    expect(releaseButton.textContent?.trim()).toBe('RELEASE');
    expect(releaseButton.classList.contains('big')).toBe(true);

    releaseButton.click();
    expect(fakeClient.release).toHaveBeenCalledTimes(1);
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
