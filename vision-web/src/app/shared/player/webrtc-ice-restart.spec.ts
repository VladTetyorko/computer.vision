import { describe, expect, it } from 'vitest';
import {
  applyIceRestartAnswer,
  buildIceRestartFragment,
  candidatesFromFragment,
  isPatchFallbackStatus,
  parseSdp,
} from './webrtc-ice-restart';

/** A single-media (recvonly video) offer/answer shape, matching what this player actually negotiates. */
function offerSdp(opts: {
  ufrag: string;
  pwd: string;
  mid?: string;
  candidates?: readonly string[];
}): string {
  const mid = opts.mid ?? '0';
  const candidateLines = (opts.candidates ?? []).map((c) => `a=candidate:${c}`).join('\r\n');
  return (
    'v=0\r\n' +
    'o=- 123 2 IN IP4 127.0.0.1\r\n' +
    's=-\r\n' +
    't=0 0\r\n' +
    'a=group:BUNDLE 0\r\n' +
    `m=video 9 UDP/TLS/RTP/SAVPF 96\r\n` +
    'c=IN IP4 0.0.0.0\r\n' +
    `a=ice-ufrag:${opts.ufrag}\r\n` +
    `a=ice-pwd:${opts.pwd}\r\n` +
    'a=fingerprint:sha-256 AA:BB\r\n' +
    'a=setup:actpass\r\n' +
    `a=mid:${mid}\r\n` +
    'a=recvonly\r\n' +
    'a=rtcp-mux\r\n' +
    'a=rtpmap:96 H264/90000\r\n' +
    (candidateLines ? candidateLines + '\r\n' : '')
  );
}

describe('parseSdp', () => {
  it('extracts the ice-ufrag/ice-pwd and the media section with its mid and candidates', () => {
    const sdp = offerSdp({
      ufrag: 'abcd',
      pwd: 'xyz123',
      mid: '0',
      candidates: ['1 1 UDP 100 10.0.0.1 5000 typ host'],
    });
    const parsed = parseSdp(sdp);
    expect(parsed.iceUfrag).toBe('abcd');
    expect(parsed.icePwd).toBe('xyz123');
    expect(parsed.medias).toHaveLength(1);
    expect(parsed.medias[0].mid).toBe('0');
    expect(parsed.medias[0].mLine).toBe('video 9 UDP/TLS/RTP/SAVPF 96');
    expect(parsed.medias[0].candidateLines).toEqual(['1 1 UDP 100 10.0.0.1 5000 typ host']);
  });

  it('takes the first ice-ufrag/ice-pwd found, ignoring any repeats', () => {
    const sdp = 'a=ice-ufrag:first\r\na=ice-ufrag:second\r\na=ice-pwd:pwd1\r\n';
    const parsed = parseSdp(sdp);
    expect(parsed.iceUfrag).toBe('first');
    expect(parsed.icePwd).toBe('pwd1');
  });

  it('handles bare `\\n`-terminated SDP text, not just `\\r\\n`', () => {
    const sdp =
      'a=ice-ufrag:u1\na=ice-pwd:p1\nm=video 9 UDP 96\na=mid:0\na=candidate:1 1 UDP 1 1.1.1.1 1 typ host\n';
    const parsed = parseSdp(sdp);
    expect(parsed.iceUfrag).toBe('u1');
    expect(parsed.icePwd).toBe('p1');
    expect(parsed.medias[0].candidateLines).toEqual(['1 1 UDP 1 1.1.1.1 1 typ host']);
  });

  it('supports multiple media sections, each with its own mid and candidates', () => {
    const sdp =
      'a=ice-ufrag:u\r\na=ice-pwd:p\r\n' +
      'm=video 9 UDP 96\r\na=mid:0\r\na=candidate:1 1 UDP 1 1.1.1.1 1 typ host\r\n' +
      'm=audio 9 UDP 97\r\na=mid:1\r\na=candidate:2 1 UDP 1 2.2.2.2 2 typ host\r\n';
    const parsed = parseSdp(sdp);
    expect(parsed.medias).toHaveLength(2);
    expect(parsed.medias[0]).toMatchObject({
      mid: '0',
      candidateLines: ['1 1 UDP 1 1.1.1.1 1 typ host'],
    });
    expect(parsed.medias[1]).toMatchObject({
      mid: '1',
      candidateLines: ['2 1 UDP 1 2.2.2.2 2 typ host'],
    });
  });

  it('returns empty credentials and no medias for text with none of the above', () => {
    expect(parseSdp('v=0\r\ns=-\r\n')).toEqual({ iceUfrag: '', icePwd: '', medias: [] });
  });
});

describe('buildIceRestartFragment', () => {
  it('builds a session-level ice-ufrag/pwd header plus one media block per section with candidates', () => {
    const localSdp = offerSdp({
      ufrag: 'newufrag',
      pwd: 'newpwd',
      mid: '0',
      candidates: ['1 1 UDP 100 10.0.0.1 5000 typ host', '2 1 UDP 90 10.0.0.1 5001 typ srflx'],
    });
    const fragment = buildIceRestartFragment(localSdp);
    expect(fragment).toBe(
      'a=ice-ufrag:newufrag\r\n' +
        'a=ice-pwd:newpwd\r\n' +
        'm=video 9 UDP/TLS/RTP/SAVPF 96\r\n' +
        'a=mid:0\r\n' +
        'a=candidate:1 1 UDP 100 10.0.0.1 5000 typ host\r\n' +
        'a=candidate:2 1 UDP 90 10.0.0.1 5001 typ srflx\r\n',
    );
  });

  it('omits a media section with zero gathered candidates entirely', () => {
    const localSdp = offerSdp({ ufrag: 'u', pwd: 'p', candidates: [] });
    const fragment = buildIceRestartFragment(localSdp);
    expect(fragment).toBe('a=ice-ufrag:u\r\na=ice-pwd:p\r\n');
    expect(fragment).not.toContain('m=');
  });

  it('falls back to a positional mid when a media section carries none (defensive, should not happen in practice)', () => {
    const sdp =
      'a=ice-ufrag:u\r\na=ice-pwd:p\r\nm=video 9 UDP 96\r\na=candidate:1 1 UDP 1 1.1.1.1 1 typ host\r\n';
    const fragment = buildIceRestartFragment(sdp);
    expect(fragment).toContain('a=mid:0\r\n');
  });
});

describe('applyIceRestartAnswer', () => {
  it('replaces the ice-ufrag/ice-pwd lines wherever they occur in the current remote SDP', () => {
    const currentRemoteSdp =
      'v=0\r\nm=video 9 UDP 96\r\na=ice-ufrag:oldufrag\r\na=ice-pwd:oldpwd\r\na=fingerprint:sha-256 AA\r\n';
    const answerFragment =
      'a=ice-options:trickle ice2\r\na=ice-ufrag:newufrag\r\na=ice-pwd:newpwd\r\n';
    const patched = applyIceRestartAnswer(currentRemoteSdp, answerFragment);
    expect(patched).toBe(
      'v=0\r\nm=video 9 UDP 96\r\na=ice-ufrag:newufrag\r\na=ice-pwd:newpwd\r\na=fingerprint:sha-256 AA\r\n',
    );
  });

  it('replaces every occurrence, including a per-media duplicate of the session-level pair', () => {
    const currentRemoteSdp =
      'a=ice-ufrag:old\r\na=ice-pwd:oldpwd\r\nm=video 9 UDP 96\r\na=ice-ufrag:old\r\na=ice-pwd:oldpwd\r\n';
    const patched = applyIceRestartAnswer(
      currentRemoteSdp,
      'a=ice-ufrag:new\r\na=ice-pwd:newpwd\r\n',
    );
    expect(patched.match(/a=ice-ufrag:new/g)).toHaveLength(2);
    expect(patched.match(/a=ice-pwd:newpwd/g)).toHaveLength(2);
    expect(patched).not.toContain('old');
  });

  it('throws when the answer fragment carries no usable ICE credentials — never silently no-ops', () => {
    expect(() =>
      applyIceRestartAnswer('a=ice-ufrag:old\r\na=ice-pwd:oldpwd\r\n', 'v=0\r\ns=-\r\n'),
    ).toThrow();
    expect(() =>
      applyIceRestartAnswer('a=ice-ufrag:old\r\na=ice-pwd:oldpwd\r\n', 'a=ice-ufrag:new\r\n'),
    ).toThrow();
  });
});

describe('candidatesFromFragment', () => {
  it('extracts every candidate, reconstructing the full candidate: attribute value', () => {
    const fragment =
      'a=ice-ufrag:u\r\na=ice-pwd:p\r\nm=video 9 UDP 96\r\na=mid:0\r\n' +
      'a=candidate:1 1 UDP 100 10.0.0.1 5000 typ host\r\n' +
      'a=candidate:2 1 UDP 90 10.0.0.1 5001 typ srflx\r\n';
    expect(candidatesFromFragment(fragment)).toEqual([
      { candidate: 'candidate:1 1 UDP 100 10.0.0.1 5000 typ host', sdpMid: '0', sdpMLineIndex: 0 },
      { candidate: 'candidate:2 1 UDP 90 10.0.0.1 5001 typ srflx', sdpMid: '0', sdpMLineIndex: 0 },
    ]);
  });

  it('returns an empty list for a credentials-only fragment (no candidates included)', () => {
    expect(candidatesFromFragment('a=ice-ufrag:u\r\na=ice-pwd:p\r\n')).toEqual([]);
  });

  it('indexes sdpMLineIndex positionally across multiple media sections', () => {
    const fragment =
      'm=video 9 UDP 96\r\na=mid:0\r\na=candidate:1 1 UDP 1 1.1.1.1 1 typ host\r\n' +
      'm=audio 9 UDP 97\r\na=mid:1\r\na=candidate:2 1 UDP 1 2.2.2.2 2 typ host\r\n';
    const candidates = candidatesFromFragment(fragment);
    expect(candidates[0].sdpMLineIndex).toBe(0);
    expect(candidates[1].sdpMLineIndex).toBe(1);
  });
});

describe('isPatchFallbackStatus (docs/REALTIME-PLAN.md Phase R-b item 1 — "last resort" rule)', () => {
  it('is false (success, apply the restart) for 200 — an ICE restart was applied', () => {
    expect(isPatchFallbackStatus(200)).toBe(false);
  });

  it('is false (success, nothing further to apply) for 204', () => {
    expect(isPatchFallbackStatus(204)).toBe(false);
  });

  it("is true for 404 (session gone) — the plan's own named case", () => {
    expect(isPatchFallbackStatus(404)).toBe(true);
  });

  it("is true for 412 (If-Match precondition failed) — the plan's own named case", () => {
    expect(isPatchFallbackStatus(412)).toBe(true);
  });

  it('is true for any other non-2xx status, treated uniformly', () => {
    expect(isPatchFallbackStatus(400)).toBe(true);
    expect(isPatchFallbackStatus(500)).toBe(true);
    expect(isPatchFallbackStatus(503)).toBe(true);
  });
});
