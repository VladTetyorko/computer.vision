import { describe, expect, it } from 'vitest';
import { landingRouteFor } from './landing-logic';
import type { AuthCapability } from '../api/models';

const MANAGE_ORG_CAPS: readonly AuthCapability[] = ['OPERATE_PAYLOAD', 'COMMAND_FLIGHT', 'MANAGE_FLEET', 'MANAGE_ORG'];
const PILOT_CAPS: readonly AuthCapability[] = ['OPERATE_PAYLOAD', 'COMMAND_FLIGHT'];

describe('landingRouteFor', () => {
  describe('auth enabled — MANAGE_ORG decides', () => {
    it.each<[readonly AuthCapability[] | null | undefined, string]>([
      [MANAGE_ORG_CAPS, '/command'], // ADMIN/MANAGER
      [PILOT_CAPS, '/fly'],
      [[], '/fly'], // VIEWER
      [undefined, '/fly'],
      [null, '/fly'],
    ])('capabilities=%s → %s', (capabilities, expected) => {
      expect(landingRouteFor(capabilities, true)).toBe(expected);
    });
  });

  // The dev principal reports the full capability set with auth off (`MeResponse#devAdmin`);
  // landing on /command there would move every unsecured install off the cockpit MVP3 §C-b chose for it.
  describe('auth disabled — always the cockpit, whatever the reported capabilities', () => {
    it.each<[readonly AuthCapability[] | null | undefined]>([[MANAGE_ORG_CAPS], [PILOT_CAPS], [[]], [undefined], [null]])(
      '%s → /fly',
      (capabilities) => {
        expect(landingRouteFor(capabilities, false)).toBe('/fly');
      },
    );
  });
});
