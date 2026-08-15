import { describe, expect, it } from 'vitest';
import { returnHomeToastFor } from './return-home-button-logic';

describe('returnHomeToastFor', () => {
  it('is a green ok toast for ACCEPTED', () => {
    expect(returnHomeToastFor('ACCEPTED')).toEqual({ kind: 'ok', text: 'Return home commanded' });
  });

  it('is an amber warning toast for NO_ACK', () => {
    expect(returnHomeToastFor('NO_ACK')).toEqual({
      kind: 'warning',
      text: 'Command sent — no acknowledgement from aircraft',
    });
  });
});
