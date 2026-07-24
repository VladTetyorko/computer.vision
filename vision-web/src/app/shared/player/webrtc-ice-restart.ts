/**
 * Pure SDP-fragment logic behind WHEP's PATCH-based ICE restart (docs/REALTIME-PLAN.md Phase R-b
 * item 1) — split out from `player-recovery.ts` the same way `live-edge-logic.ts` was: a sibling
 * module, aware of the state machine that drives it without growing inside it (see that file's own
 * doc comment for the precedent this follows). No `RTCPeerConnection`/browser API is touched here
 * at all — every function is a plain string transform over SDP text, so this is fully unit-tested
 * without jsdom/karma needing WebRTC support at all (see `shared/player/player.ts`'s own note on keeping
 * browser APIs behind thin seams).
 *
 * **Verified byte-for-byte against mediamtx's own source** (`internal/servers/webrtc/session.go`
 * and `http_server.go`, tag `v1.19.2` — the exact version this repo pins), not guessed at from the
 * WHIP/WHEP drafts alone:
 *  - `onWHIPPatch` (`http_server.go`) requires `Content-Type: application/trickle-ice-sdpfrag`,
 *    parses the body as a `whip.SDPFragment`, and returns **200** with a fresh
 *    `application/trickle-ice-sdpfrag` body (mediamtx's own new answer-side ICE credentials) when
 *    the incoming fragment's `ice-ufrag` differs from the session's original one — mediamtx's own
 *    signal for "the client just restarted ICE" — or **204 No Content** otherwise (a plain trickled
 *    candidate, not a restart). A malformed/missing-secret/gone session answers 400/404.
 *  - `session.go#readRemoteCandidates` treats `ufrag != "" && ufrag != remoteUfrag` as the ICE-
 *    restart signal server-side, mirrored here by always sending a genuinely new `ice-ufrag`/
 *    `ice-pwd` pair (from a fresh `restartIce()`-triggered offer) — this client-side module has no
 *    reason to ever send a *plain* trickle-only PATCH (this app's WHEP offer is always gathered
 *    non-trickle first, see `shared/player/player.ts#waitForIceGatheringComplete`), so every PATCH this module
 *    builds is, by construction, an ICE-restart request.
 *  - `session.go#replaceICECredentials` (server) and `fullAnswerToSDPFragment` (server, builds the
 *    200 response body) are mirrored here by `applyIceRestartAnswer`/`parseSdp` respectively — same
 *    string-splice idiom, client side.
 *  - mediamtx's own bundled reference client (`reader.js`) does **not** exercise this path at all —
 *    it only ever trickles candidates under the *original* ufrag and falls back to a full
 *    teardown+re-POST on any `connectionState` failure (`RETRY_PAUSE`=2s, no grace period). This
 *    player is deliberately more capable than mediamtx's own demo client, which is why there is no
 *    reference JS to copy this exact flow from — it had to be derived from the server's own
 *    request-handling logic instead.
 */

/** One parsed SDP media (`m=`) section — only the fields this module's restart flow needs. */
export interface ParsedSdpMedia {
  /** Everything after `m=` on that line, verbatim (e.g. `video 9 UDP/TLS/RTP/SAVPF 96 97`). */
  readonly mLine: string;
  /** This section's own `a=mid:` value — read directly, never assumed from array position (a
   * transceiver's mid is fixed at first negotiation and stays put across every later renegotiation,
   * including an ICE restart on the same `RTCPeerConnection`, so this is always safe to trust). */
  readonly mid: string | null;
  /** Every `a=candidate:...` line's value in this section, in order, without the `candidate:` prefix. */
  readonly candidateLines: readonly string[];
}

export interface ParsedSdp {
  /** The first `ice-ufrag` found anywhere (session- or media-level) — empty string if none. */
  readonly iceUfrag: string;
  /** The first `ice-pwd` found anywhere — empty string if none. */
  readonly icePwd: string;
  readonly medias: readonly ParsedSdpMedia[];
}

function splitSdpLines(sdp: string): readonly string[] {
  // Real SDP is always `\r\n`-terminated, but this mirrors mediamtx's own server-side parsing
  // (`session.go#replaceICECredentials` does the identical `\r\n`-or-`\n` sniff) defensively.
  return sdp.split(sdp.includes('\r\n') ? '\r\n' : '\n');
}

/**
 * Parses a full SDP (an offer, an answer, or mediamtx's own response fragment — all three share
 * this same line shape) into its ICE credentials and an ordered list of media sections, each with
 * its own `mid` and gathered `a=candidate:` lines. Mirrors mediamtx's own `reader.js#parseOffer`'s
 * "first ufrag/pwd found anywhere wins" rule (this player only ever negotiates one media section —
 * a single recvonly video transceiver — so session-level vs. media-level placement is never
 * actually ambiguous in practice either way).
 */
export function parseSdp(sdp: string): ParsedSdp {
  let iceUfrag = '';
  let icePwd = '';
  const medias: ParsedSdpMedia[] = [];
  let current: { mLine: string; mid: string | null; candidateLines: string[] } | null = null;

  for (const line of splitSdpLines(sdp)) {
    if (line.startsWith('m=')) {
      current = { mLine: line.slice('m='.length), mid: null, candidateLines: [] };
      medias.push(current);
      continue;
    }
    if (iceUfrag === '' && line.startsWith('a=ice-ufrag:')) {
      iceUfrag = line.slice('a=ice-ufrag:'.length);
    } else if (icePwd === '' && line.startsWith('a=ice-pwd:')) {
      icePwd = line.slice('a=ice-pwd:'.length);
    } else if (current && line.startsWith('a=mid:')) {
      current.mid = line.slice('a=mid:'.length);
    } else if (current && line.startsWith('a=candidate:')) {
      current.candidateLines.push(line.slice('a=candidate:'.length));
    }
  }

  return { iceUfrag, icePwd, medias };
}

/**
 * Builds the `application/trickle-ice-sdpfrag` PATCH body for an ICE restart from a completed
 * (non-trickle-gathered) local offer's SDP — session-wide `ice-ufrag`/`ice-pwd` lines first, then
 * one `m=`/`a=mid:`/`a=candidate:...` block per media section that actually gathered candidates.
 * Mirrors mediamtx's own `reader.js#generateSdpFragment` exactly, generalized from "one freshly-
 * trickled candidate at a time" to "every candidate already sitting in a completed local
 * description" — this player gathers ICE non-trickle before ever sending an offer (see class doc
 * of `shared/player/player.ts`), so there is never a discrete per-candidate event to trickle in the first
 * place; the whole batch goes out in one PATCH instead. A media section with zero gathered
 * candidates is omitted entirely (mirrors the reference implementation) — still a fully valid
 * restart-triggering fragment on its own, since mediamtx's own restart detection keys purely off
 * the new `ice-ufrag` differing from the original, never off candidate presence
 * (`session.go#readRemoteCandidates`).
 */
export function buildIceRestartFragment(localSdp: string): string {
  const parsed = parseSdp(localSdp);
  let fragment = `a=ice-ufrag:${parsed.iceUfrag}\r\na=ice-pwd:${parsed.icePwd}\r\n`;

  parsed.medias.forEach((media, index) => {
    if (media.candidateLines.length === 0) {
      return;
    }
    const mid = media.mid ?? String(index);
    fragment += `m=${media.mLine}\r\na=mid:${mid}\r\n`;
    for (const candidateLine of media.candidateLines) {
      fragment += `a=candidate:${candidateLine}\r\n`;
    }
  });

  return fragment;
}

/**
 * Splices a PATCH response's new answer-side ICE credentials into a full copy of the *current*
 * remote description SDP, producing the SDP to hand to `setRemoteDescription` — the browser API has
 * no partial/fragment form of `setRemoteDescription`, so the only way to actually apply a restart's
 * new remote ufrag/pwd is to patch them into the full SDP already in place. Mirrors mediamtx's own
 * server-side `session.go#replaceICECredentials` exactly (same replace-every-occurrence idiom),
 * just run against the *client's* remote description instead of the server's.
 *
 * Throws if `answerFragment` carries no usable `ice-ufrag`/`ice-pwd` at all — a malformed or empty
 * 200 response body is a genuine, unexpected failure, not a silent no-op (the caller's own restart
 * attempt should be treated as failed, falling back to full teardown, same as any other rejected
 * PATCH — see `isPatchFallbackStatus`).
 */
export function applyIceRestartAnswer(currentRemoteSdp: string, answerFragment: string): string {
  const parsed = parseSdp(answerFragment);
  if (!parsed.iceUfrag || !parsed.icePwd) {
    throw new Error('WHEP ICE restart answer carried no ice-ufrag/ice-pwd');
  }
  return replaceIceCredentials(currentRemoteSdp, parsed.iceUfrag, parsed.icePwd);
}

function replaceIceCredentials(sdp: string, ufrag: string, pwd: string): string {
  const sep = sdp.includes('\r\n') ? '\r\n' : '\n';
  return sdp
    .split(sep)
    .map((line) => {
      if (line.startsWith('a=ice-ufrag:')) {
        return `a=ice-ufrag:${ufrag}`;
      }
      if (line.startsWith('a=ice-pwd:')) {
        return `a=ice-pwd:${pwd}`;
      }
      return line;
    })
    .join(sep);
}

/** One candidate from a PATCH response fragment, shaped for `RTCPeerConnection.addIceCandidate`. */
export interface FragmentCandidate {
  readonly candidate: string;
  readonly sdpMid: string;
  readonly sdpMLineIndex: number;
}

/**
 * Every `a=candidate:...` line carried in a PATCH response fragment (mediamtx's own answer may
 * include its own local candidates alongside the new credentials — `session.go#fullAnswerToSDPFragment`
 * includes them when present) as `RTCIceCandidateInit`-shaped objects ready for `addIceCandidate`.
 * Mirrors mediamtx's own server-side `sdpFragmentToCandidates`. `candidate` carries the full
 * `candidate:...` attribute value (the WebRTC spec's own `RTCIceCandidateInit.candidate` shape
 * includes that literal prefix, unlike the bare value this parser strips during `parseSdp`).
 */
export function candidatesFromFragment(fragment: string): readonly FragmentCandidate[] {
  const parsed = parseSdp(fragment);
  const result: FragmentCandidate[] = [];
  parsed.medias.forEach((media, index) => {
    const mid = media.mid ?? String(index);
    for (const candidateLine of media.candidateLines) {
      result.push({ candidate: `candidate:${candidateLine}`, sdpMid: mid, sdpMLineIndex: index });
    }
  });
  return result;
}

/**
 * Whether a WHEP PATCH response status means "give up on the ICE restart, fall back to full
 * teardown+re-POST" (docs/REALTIME-PLAN.md Phase R-b item 1's own "last resort" rule) — **200**
 * (restart applied, mediamtx's own answer fragment follows) and **204** (acknowledged, no restart
 * — defensive; this module never actually sends a non-restart PATCH, see the module doc comment,
 * but a `204` is still a success shape, not a fallback trigger, per `http_server.go#onWHIPPatch`) are
 * the only two success codes; everything else — **404** (session gone), **412** (`If-Match`
 * precondition failed), any other 4xx/5xx — falls back, treated uniformly and conservatively rather
 * than special-cased per status, since "the PATCH didn't work" is the only fact that actually
 * matters to the caller.
 */
export function isPatchFallbackStatus(status: number): boolean {
  return status !== 200 && status !== 204;
}
