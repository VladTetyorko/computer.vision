import { Injectable } from '@angular/core';
import { getStoredCertificate, putStoredCertificate } from './webrtc-certificate-db';
import { formatFingerprints, isCertificateUsable } from './webrtc-certificate-logic';

const LOG_PREFIX = '[webrtc-cert]';

/**
 * ECDSA P-256 — a small, fast, universally-supported `RTCCertificate` key type. Typed as
 * `EcKeyGenParams` (not the narrower `AlgorithmIdentifier` `generateCertificate` itself declares)
 * so `namedCurve` type-checks — a real, spec'd `RTCPeerConnection.generateCertificate` accepts this
 * shape (verified against MDN/the WebRTC spec), but `lib.dom.d.ts`'s own parameter type hasn't
 * caught up to it; assigning through this typed constant, rather than an inline object literal at
 * the call site, sidesteps TypeScript's excess-property check without a cast.
 */
const CERTIFICATE_ALGORITHM: EcKeyGenParams = { name: 'ECDSA', namedCurve: 'P-256' };

/**
 * One stable ECDSA DTLS certificate per browser, shared by every `<vision-player>` WHEP attach on
 * this page (docs/plans/done/REALTIME-PLAN.md Phase R-b item 3) — `providedIn: 'root'`, the same convention
 * `FleetStore`/`PollScheduler` already use for a single app-wide instance.
 *
 * **Why this matters**: without it, every fresh `RTCPeerConnection` generates its own ephemeral
 * certificate, so every WHEP session — even from the same browser, even for the same asset a
 * moment later — presents a *different* DTLS fingerprint. Persisting one certificate in IndexedDB
 * gives the server (or an operator watching mediamtx's own session list) a stable identity to
 * correlate "this is the same viewer reconnecting", across every tile and every reconnect, without
 * needing any application-level session/cookie of its own. It also skips ECDSA keygen latency on
 * every attach after the first.
 *
 * **Lazy, memoized, and re-validated on every call** — `certificates()` only ever does real work
 * (an IndexedDB read, or a `generateCertificate()` call) the first time, or again once the
 * previously-resolved certificate has actually expired (Safari caps a certificate's own lifetime at
 * roughly a week regardless of what's requested — docs/plans/done/REALTIME-PLAN.md Phase R-b item 3's own
 * caveat) — `isCertificateUsable` (`core/webrtc-certificate-logic.ts`) is the one place that
 * decision is made, unit-tested independent of any real certificate/IndexedDB.
 *
 * **Private-mode / IndexedDB-unavailable fallback**: `webrtc-certificate-db.ts`'s own
 * `getStoredCertificate`/`putStoredCertificate` both degrade to `undefined`/a no-op rather than
 * throwing when IndexedDB is blocked or unavailable — this service still generates and hands out a
 * perfectly good certificate in that case, it just can't persist it, so a fresh one is generated
 * again next page load ("per-tab" for the session it's actually used in, exactly the documented
 * fallback).
 *
 * **Never actually empty**: if `RTCPeerConnection.generateCertificate` itself is unsupported (very
 * old browsers), `certificates()` resolves to an empty array rather than rejecting — the caller
 * (`shared/player/player.ts`) then constructs its `RTCPeerConnection` with no explicit `certificates` option at
 * all, which is exactly what every WHEP attach did before this cycle (the browser generates its own
 * ephemeral certificate) — a graceful feature-degrade, never a hard WHEP failure just because
 * cert-pinning isn't available in this particular browser.
 */
@Injectable({ providedIn: 'root' })
export class WebrtcCertificateService {
  private cached: { promise: Promise<readonly RTCCertificate[]>; expires: number | null } | null =
    null;

  /** Resolves to a 0-or-1-element array, ready for `new RTCPeerConnection({certificates})`. */
  certificates(): Promise<readonly RTCCertificate[]> {
    const cached = this.cached;
    const nowMs = Date.now();
    if (
      cached &&
      (cached.expires === null || isCertificateUsable({ expires: cached.expires }, nowMs))
    ) {
      return cached.promise;
    }
    const promise = this.loadOrCreate();
    this.cached = { promise, expires: null };
    void promise.then((certs) => {
      if (this.cached?.promise === promise) {
        this.cached = { promise, expires: certs[0]?.expires ?? null };
      }
    });
    return promise;
  }

  private async loadOrCreate(): Promise<readonly RTCCertificate[]> {
    if (
      typeof RTCPeerConnection === 'undefined' ||
      typeof RTCPeerConnection.generateCertificate !== 'function'
    ) {
      console.warn(
        `${LOG_PREFIX} RTCPeerConnection.generateCertificate unsupported — WHEP attaches will use an ephemeral per-connection certificate instead`,
      );
      return [];
    }

    const nowMs = Date.now();
    const stored = await getStoredCertificate().catch(() => undefined);
    if (isCertificateUsable(stored, nowMs)) {
      console.info(
        `${LOG_PREFIX} reusing the persisted WHEP certificate — fingerprint ${formatFingerprints(stored!.getFingerprints())}`,
      );
      return [stored!];
    }

    const fresh = await RTCPeerConnection.generateCertificate(CERTIFICATE_ALGORITHM);
    console.info(
      `${LOG_PREFIX} generated a fresh WHEP certificate${stored ? ' (previous one expired)' : ''} — fingerprint ${formatFingerprints(fresh.getFingerprints())}`,
    );
    await putStoredCertificate(fresh).catch(() => undefined);
    return [fresh];
  }
}
