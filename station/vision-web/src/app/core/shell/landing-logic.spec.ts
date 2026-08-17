import { describe, expect, it } from 'vitest';
import { landingRouteFor } from './landing-logic';
import type { Role } from '../api/models';

describe('landingRouteFor', () => {
  describe('auth enabled — the role decides', () => {
    it.each<[Role | null | undefined, string]>([
      ['ADMIN', '/command'],
      ['MANAGER', '/command'],
      ['PILOT', '/fly'],
      [undefined, '/fly'],
      [null, '/fly'],
    ])('%s → %s', (role, expected) => {
      expect(landingRouteFor(role, true)).toBe(expected);
    });
  });

  // The dev principal reports topRole ADMIN with auth off (`MeResponse#devAdmin`); landing on
  // /command there would move every unsecured install off the cockpit MVP3 §C-b chose for it.
  describe('auth disabled — always the cockpit, whatever the reported role', () => {
    it.each<[Role | null | undefined]>([['ADMIN'], ['MANAGER'], ['PILOT'], [undefined], [null]])(
      '%s → /fly',
      (role) => {
        expect(landingRouteFor(role, false)).toBe('/fly');
      },
    );
  });
});
