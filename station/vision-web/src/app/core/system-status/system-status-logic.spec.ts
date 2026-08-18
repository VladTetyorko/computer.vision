import { describe, expect, it } from 'vitest';
import {
  connectionSeverity,
  healthLabel,
  healthSeverity,
  reachableSeverity,
  shellStatusLabel,
  shellStatusSeverity,
  worstSeverity,
} from './system-status-logic';
import type { SubsystemHealth } from '../api/models';

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
