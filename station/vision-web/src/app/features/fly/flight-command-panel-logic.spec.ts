import { describe, expect, it } from 'vitest';
import type { FlightCapability } from '../../core/api/models';
import {
  armFinalConfirmLabel,
  armWarningMessage,
  canShowCommandPanel,
  commandOutcomeToast,
  disarmConfirmMessage,
  modeConfirmMessage,
} from './flight-command-panel-logic';

const COMMANDABLE: FlightCapability = {
  commandable: true,
  armSupported: true,
  modeSelectSupported: true,
  selectableModes: ['Stabilize', 'Loiter', 'RTL'],
  vehicleKind: 'COPTER',
};

describe('canShowCommandPanel', () => {
  it('shows for a commandable vehicle with fresh telemetry', () => {
    expect(canShowCommandPanel(COMMANDABLE, 'ardupilot', 1)).toBe(true);
  });

  it('hides with no capabilities at all (not yet loaded, or the fetch failed)', () => {
    expect(canShowCommandPanel(undefined, 'ardupilot', 1)).toBe(false);
  });

  it('hides when capabilities report not-commandable (Betaflight/never-heard)', () => {
    const notCommandable: FlightCapability = { ...COMMANDABLE, commandable: false };
    expect(canShowCommandPanel(notCommandable, 'ardupilot', 1)).toBe(false);
  });

  it('hides for the wrong firmware even if somehow marked commandable', () => {
    expect(canShowCommandPanel(COMMANDABLE, 'betaflight', 1)).toBe(false);
  });

  it('hides with no firmware known yet', () => {
    expect(canShowCommandPanel(COMMANDABLE, undefined, 1)).toBe(false);
  });

  it('hides when telemetry is stale', () => {
    expect(canShowCommandPanel(COMMANDABLE, 'ardupilot', 999)).toBe(false);
  });

  it('hides with no telemetry sample at all (ageSeconds undefined)', () => {
    expect(canShowCommandPanel(COMMANDABLE, 'ardupilot', undefined)).toBe(false);
  });
});

describe('confirm copy', () => {
  it('builds the mode picker\'s standard confirm', () => {
    expect(modeConfirmMessage('Falcon 1', 'Loiter')).toBe('Set Falcon 1 to Loiter?');
  });

  it('builds the arm warning — states the propeller consequence, never an instruction', () => {
    expect(armWarningMessage('Falcon 1')).toBe('This will ARM Falcon 1 — the propellers will spin.');
  });

  it('builds the arm final-stage confirm label, distinct from the warning text', () => {
    const label = armFinalConfirmLabel('Falcon 1');
    expect(label).toBe('Yes, arm Falcon 1');
    expect(label).not.toBe(armWarningMessage('Falcon 1'));
  });

  it('builds a plain disarm confirm when not known to be armed', () => {
    expect(disarmConfirmMessage('Falcon 1', false)).toBe('Disarm Falcon 1?');
    expect(disarmConfirmMessage('Falcon 1', undefined)).toBe('Disarm Falcon 1?');
  });

  it('builds a crash-warning disarm confirm when armed', () => {
    expect(disarmConfirmMessage('Falcon 1', true)).toBe(
      "Falcon 1 is armed — if it's currently flying, disarming will make it fall.",
    );
  });
});

describe('commandOutcomeToast', () => {
  it('is a green ok toast for ACCEPTED, worded per action', () => {
    expect(commandOutcomeToast('ACCEPTED', 'mode')).toEqual({ kind: 'ok', text: 'Mode set' });
    expect(commandOutcomeToast('ACCEPTED', 'arm')).toEqual({ kind: 'ok', text: 'Armed' });
    expect(commandOutcomeToast('ACCEPTED', 'disarm')).toEqual({ kind: 'ok', text: 'Disarmed' });
  });

  it('is an amber warning toast for NO_ACK, worded per action', () => {
    expect(commandOutcomeToast('NO_ACK', 'mode')).toEqual({
      kind: 'warning',
      text: 'Mode command sent — no acknowledgement from aircraft',
    });
    expect(commandOutcomeToast('NO_ACK', 'arm')).toEqual({
      kind: 'warning',
      text: 'Arm command sent — no acknowledgement from aircraft',
    });
    expect(commandOutcomeToast('NO_ACK', 'disarm')).toEqual({
      kind: 'warning',
      text: 'Disarm command sent — no acknowledgement from aircraft',
    });
  });
});
