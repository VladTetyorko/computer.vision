/**
 * Response-body formatting for the Debug tab's viewer.
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
