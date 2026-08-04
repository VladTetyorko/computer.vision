/**
 * Response-body formatting for the Debug tab's viewer, plus (below) the Health panel's own
 * probe-vs-verdict semantics — both pure, both framework-free, both about turning a raw HTTP response
 * into something honest to render.
 *
 * A raw console's whole point is showing exactly what came back — a non-JSON or malformed
 * body must render as-is, never as a swallowed error, so pretty-printing always has a
 * plain-text fallback rather than throwing.
 */
export interface FormattedBody {
  readonly text: string;
  readonly isJson: boolean;
}

export function formatResponseBody(bodyText: string | null): FormattedBody {
  if (bodyText === null || bodyText.length === 0) {
    return { text: '(empty body)', isJson: false };
  }
  try {
    return { text: JSON.stringify(JSON.parse(bodyText), null, 2), isJson: true };
  } catch {
    return { text: bodyText, isJson: false };
  }
}

/** True for the 2xx family the response viewer badges as success. */
export function isSuccessStatus(status: number): boolean {
  return status >= 200 && status < 300;
}

/**
 * The Health panel's chip semantics (docs/NAV-IA-REDESIGN-PLAN.md §2.2's health-probe correctness
 * fix, docs/design/18-debug.md) — a *probe that isn't there* and a *system that is unhealthy* used to
 * render identically, a red chip reading the raw HTTP status. Verified independently: this deployment
 * has no `spring-boot-starter-actuator` on the classpath at all (`vision-app/pom.xml`), so
 * `GET /actuator/health` 404s the same way any unmapped path would — the request never even reaches a
 * health check, there is nothing here to be unhealthy. The old code parsed the 404 error body's own
 * `{"status":404,...}` field as if it were `HealthComponent#status` ("UP"/"DOWN"), so the *HTTP status
 * code* rendered as a red "Overall: 404" chip — a coincidence of both bodies using a field named
 * `status`, not a real health verdict.
 *
 * `'not-exposed'` covers every case that isn't a genuine, parseable health document from a 2xx
 * response — a 404, any other non-2xx, an unreachable backend, or a 2xx body that doesn't parse as
 * `{status: string}` (a future actuator with a differently-shaped body degrades honestly here too,
 * rather than crashing). Only a real `{"status": "..."}` on a successful response ever earns a colored
 * verdict — `'healthy'` (green) for `"UP"`, `'unhealthy'` (red) for anything else Actuator can report
 * (`"DOWN"`/`"OUT_OF_SERVICE"`/`"UNKNOWN"`).
 */
export type HealthProbeKind = 'not-exposed' | 'healthy' | 'unhealthy';

export interface HealthProbeStatus {
  readonly kind: HealthProbeKind;
  /** Ready-to-render chip text — "UP", the raw Actuator status word, or the neutral explanation. */
  readonly label: string;
  readonly tone: 'ok' | 'danger' | 'neutral';
}

/**
 * The narrow slice of `DebugApiService`'s `RawResponse` this function actually needs — a purpose-built
 * shape rather than an import of that interface, so this module stays what its own doc comment above
 * claims: no dependency on anything outside itself. `RawResponse` already satisfies this structurally,
 * so `DebugPage` passes one straight through with no adapting.
 */
export interface HealthProbeResponse {
  readonly status: number;
  readonly bodyText: string | null;
  readonly reached: boolean;
}

/** `null` only when no health check has ever been attempted this session (`response` itself `null`) — see `DebugPage`'s own "No health data yet" fallback for that case. */
export function describeHealthProbe(response: HealthProbeResponse | null): HealthProbeStatus | null {
  if (!response) {
    return null;
  }
  if (!response.reached || !isSuccessStatus(response.status)) {
    return { kind: 'not-exposed', label: 'health probe not exposed', tone: 'neutral' };
  }
  const status = parseHealthStatus(response.bodyText);
  if (!status) {
    return { kind: 'not-exposed', label: 'health probe not exposed', tone: 'neutral' };
  }
  return status === 'UP'
    ? { kind: 'healthy', label: 'UP', tone: 'ok' }
    : { kind: 'unhealthy', label: status, tone: 'danger' };
}

/** Extracts Actuator's own `HealthComponent#status` field, tolerating anything that isn't exactly that shape. */
function parseHealthStatus(bodyText: string | null): string | null {
  if (!bodyText) {
    return null;
  }
  try {
    const parsed = JSON.parse(bodyText) as { readonly status?: unknown };
    return typeof parsed.status === 'string' && parsed.status.length > 0 ? parsed.status : null;
  } catch {
    return null;
  }
}
