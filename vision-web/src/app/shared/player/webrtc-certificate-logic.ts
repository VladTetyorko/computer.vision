/**
 * Pure logic behind `core/webrtc-certificate.ts` (docs/plans/done/REALTIME-PLAN.md Phase R-b item 3) — split
 * out so the one genuinely decidable question ("is this certificate still safe to hand out?") is
 * unit-tested without a real `RTCCertificate`/IndexedDB, mirroring this app's own standing
 * `*-logic.ts` split (`core/telemetry/telemetry-logic.ts`, `shared/player/player-recovery.ts`, etc.).
 */

/** The one field this module actually reads off a real `RTCCertificate` — a structural type so tests can pass a plain object, no WebRTC API required. */
export interface CertificateLike {
  /** Epoch-ms expiry timestamp, mirrors `RTCCertificate.expires`. */
  readonly expires: number;
}

/** The shape of one entry from `RTCCertificate.getFingerprints()`, structurally. */
export interface FingerprintLike {
  readonly algorithm?: string;
  readonly value?: string;
}

/**
 * Safety margin subtracted from a certificate's real `expires` timestamp before treating it as
 * still usable — regenerates a little early rather than risk handing a WHEP attach a certificate
 * that expires moments into its lifetime (a fresh `RTCPeerConnection` holds its certificate for
 * that connection's whole lifetime regardless of what happens to the underlying cert object
 * afterward, but a *new* attach right at the boundary shouldn't gamble on winning that race).
 */
export const CERTIFICATE_EXPIRY_SAFETY_MARGIN_MS = 60_000;

/**
 * Whether a stored/cached certificate is still safe to hand to a new `RTCPeerConnection` —
 * `null`/`undefined` (nothing stored yet, or a corrupt read) is never usable. Safari caps a
 * generated certificate's own lifetime at roughly a week regardless of what's requested
 * (docs/plans/done/REALTIME-PLAN.md Phase R-b item 3's own caveat); this function is what makes "regenerate
 * transparently on expiry" an actual decision rather than an assumption.
 */
export function isCertificateUsable(
  cert: CertificateLike | null | undefined,
  nowMs: number,
  safetyMarginMs: number = CERTIFICATE_EXPIRY_SAFETY_MARGIN_MS,
): boolean {
  if (!cert) {
    return false;
  }
  return cert.expires - nowMs > safetyMarginMs;
}

/** A loggable one-line summary of a certificate's fingerprints, for the once-per-session correlation log (docs/plans/done/REALTIME-PLAN.md Phase R-b item 3). */
export function formatFingerprints(fingerprints: readonly FingerprintLike[]): string {
  if (fingerprints.length === 0) {
    return '(none)';
  }
  return fingerprints.map((f) => `${f.algorithm ?? '?'} ${f.value ?? '?'}`).join(', ');
}
