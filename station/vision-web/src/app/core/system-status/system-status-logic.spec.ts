import { describe, expect, it } from 'vitest';
import {
  connectionSeverity,
  healthLabel,
  healthSeverity,
  reachableSeverity,
  shellStatusLabel,
  shellStatusSeverity,
  verdictFor,
  worstSeverity,
  type SystemTransport,
} from './system-status-logic';
import type { SubsystemHealth, SubsystemStatus } from '../api/models';

function subsystem(partial: Partial<SubsystemStatus> = {}): SubsystemStatus {
  return { id: 'cv-service', label: 'CV inference', health: 'OK', detail: 'cv-service is READY', ...partial };
}

function transport(partial: Partial<SystemTransport> = {}): SystemTransport {
  return { backendReachable: true, liveConnection: 'open', ...partial };
}

describe('healthSeverity', () => {
  it('OK is ok, DEGRADED is warn, DOWN is danger', () => {
    expect(healthSeverity('OK')).toBe('ok');
    expect(healthSeverity('DEGRADED')).toBe('warn');
    expect(healthSeverity('DOWN')).toBe('danger');
  });

  it('DISABLED and UNKNOWN both read as neutral, never a fault', () => {
    expect(healthSeverity('DISABLED')).toBe('neutral');
    expect(healthSeverity('UNKNOWN')).toBe('neutral');
  });
});

describe('healthLabel', () => {
  it('every SubsystemHealth value has a sentence-case label', () => {
    const values: readonly SubsystemHealth[] = ['OK', 'DEGRADED', 'DOWN', 'DISABLED', 'UNKNOWN'];
    expect(values.map(healthLabel)).toEqual(['OK', 'Degraded', 'Down', 'Disabled', 'Unknown']);
  });
});

describe('connectionSeverity', () => {
  it('open is ok, closed is warn, connecting is neutral (not ok)', () => {
    expect(connectionSeverity('open')).toBe('ok');
    expect(connectionSeverity('closed')).toBe('warn');
    expect(connectionSeverity('connecting')).toBe('neutral');
  });
});

describe('reachableSeverity', () => {
  it('true is ok, false is danger, null (not yet known) is neutral', () => {
    expect(reachableSeverity(true)).toBe('ok');
    expect(reachableSeverity(false)).toBe('danger');
    expect(reachableSeverity(null)).toBe('neutral');
  });
});

describe('worstSeverity', () => {
  it('danger beats warn beats neutral beats ok', () => {
    expect(worstSeverity(['ok', 'danger', 'warn'])).toBe('danger');
    expect(worstSeverity(['ok', 'warn', 'neutral'])).toBe('warn');
    expect(worstSeverity(['ok', 'neutral'])).toBe('neutral');
    expect(worstSeverity(['ok', 'ok'])).toBe('ok');
  });

  it('defaults to ok for an empty list', () => {
    expect(worstSeverity([])).toBe('ok');
  });
});

describe('shellStatusSeverity', () => {
  it('all-clear across every axis is ok', () => {
    expect(shellStatusSeverity(true, 'open', 'OK')).toBe('ok');
  });

  it('an unreachable backend wins over an otherwise-fine overall/connection', () => {
    expect(shellStatusSeverity(false, 'open', 'OK')).toBe('danger');
  });

  it('a closed live transport degrades even a reachable, OK backend to warn', () => {
    expect(shellStatusSeverity(true, 'closed', 'OK')).toBe('warn');
  });

  it('a DOWN overall wins even when the backend itself is reachable and live is open', () => {
    expect(shellStatusSeverity(true, 'open', 'DOWN')).toBe('danger');
  });

  it('overall undefined (status not loaded yet) never claims ok on its own', () => {
    expect(shellStatusSeverity(null, 'connecting', undefined)).toBe('neutral');
  });

  it('a still-loading overall does not mask a confirmed backend outage', () => {
    expect(shellStatusSeverity(false, 'connecting', undefined)).toBe('danger');
  });
});

describe('shellStatusLabel', () => {
  it('names each severity honestly, never a vague "connection issue"', () => {
    expect(shellStatusLabel('ok')).toBe('System status: all clear');
    expect(shellStatusLabel('warn')).toBe('System status: degraded');
    expect(shellStatusLabel('danger')).toBe('System status: down');
    expect(shellStatusLabel('neutral')).toBe('System status: checking…');
  });
});

describe('verdictFor (OPERATOR-UX-5-PLAN.md finding U3)', () => {
  it('is ok with no subsystems reporting a fault and both transports healthy', () => {
    expect(verdictFor([subsystem({ health: 'OK' })], transport())).toEqual({ severity: 'ok', message: 'System is OK.' });
  });

  it('reads "no subsystems reported" (neutral, never a guessed ok) for an empty list', () => {
    expect(verdictFor([], transport())).toEqual({ severity: 'neutral', message: 'No subsystems reported.' });
  });

  it("one optional subsystem DOWN is 'Degraded', never the whole station being down (U3's own finding)", () => {
    expect(verdictFor([subsystem({ label: 'CV inference', health: 'DOWN' })], transport())).toEqual({
      severity: 'warn',
      message: 'Degraded — CV inference down',
    });
  });

  it('a DEGRADED subsystem reads "degraded" text, still only warn tone', () => {
    expect(verdictFor([subsystem({ label: 'Live updates (SSE)', health: 'DEGRADED' })], transport())).toEqual({
      severity: 'warn',
      message: 'Degraded — Live updates (SSE) degraded',
    });
  });

  it('DOWN outranks DEGRADED when both are present, regardless of array order', () => {
    const result = verdictFor(
      [subsystem({ id: 'a', label: 'Alpha', health: 'DEGRADED' }), subsystem({ id: 'b', label: 'Bravo', health: 'DOWN' })],
      transport(),
    );
    expect(result.message).toBe('Degraded — Bravo down');
  });

  it('DISABLED and UNKNOWN never lower the verdict — excluded outright, not merely out-ranked', () => {
    const result = verdictFor(
      [subsystem({ health: 'DISABLED' }), subsystem({ id: 'mavlink-link', label: 'MAVLink', health: 'UNKNOWN' })],
      transport(),
    );
    expect(result).toEqual({ severity: 'ok', message: 'System is OK.' });
  });

  it('an unreachable backend is down, even with every subsystem OK', () => {
    const result = verdictFor([subsystem({ health: 'OK' })], transport({ backendReachable: false }));
    expect(result).toEqual({ severity: 'danger', message: 'System is down — the backend is unreachable.' });
  });

  it('a closed live transport is down, even with every subsystem OK', () => {
    const result = verdictFor([subsystem({ health: 'OK' })], transport({ liveConnection: 'closed' }));
    expect(result).toEqual({ severity: 'danger', message: 'System is down — live updates are unreachable.' });
  });

  it('an unreachable backend wins over a closed live transport (checked first, either alone is fatal)', () => {
    const result = verdictFor([], transport({ backendReachable: false, liveConnection: 'closed' }));
    expect(result.message).toBe('System is down — the backend is unreachable.');
  });

  it('a merely connecting (not closed) live transport does not force down — falls through to subsystems', () => {
    const result = verdictFor([subsystem({ health: 'OK' })], transport({ liveConnection: 'connecting' }));
    expect(result).toEqual({ severity: 'ok', message: 'System is OK.' });
  });

  it('a DOWN subsystem alongside a closed live transport still reads as the transport-down message, not degraded', () => {
    const result = verdictFor([subsystem({ health: 'DOWN' })], transport({ liveConnection: 'closed' }));
    expect(result.severity).toBe('danger');
    expect(result.message).toBe('System is down — live updates are unreachable.');
  });
});
