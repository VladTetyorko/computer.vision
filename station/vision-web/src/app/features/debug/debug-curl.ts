/**
 * Builds a copy-pasteable `curl` command for a request this console sent (docs/plans/active/
 * fly-debug-redesign/PLAN.md §1.4) — R1's single highest-value cheap addition (R1-api-console-analogs.md
 * §2.5): it's how an operator hands a repro to a teammate or a bug report without hand-transcribing
 * method/path/body. One target format only, curl — see R1 §3 on why not N-language codegen.
 *
 * Pure and framework-free by design: it takes an explicit `origin` rather than reading
 * `window.location` itself, so it stays trivially spec-testable and the caller (the response pane)
 * decides what "here" means.
 */
export interface CurlSource {
  readonly method: string;
  readonly path: string;
  /** Raw textarea contents actually sent, if any — mirrors `DebugHistoryEntry.body`. */
  readonly body?: string;
}

export function buildCurl(entry: CurlSource, origin: string): string {
  const parts = [`curl -X ${entry.method} '${escapeSingleQuotes(origin + entry.path)}'`];
  if (entry.body && entry.body.trim().length > 0) {
    parts.push(`-H 'Content-Type: application/json'`);
    parts.push(`--data '${escapeSingleQuotes(entry.body)}'`);
  }
  return parts.join(' ');
}

/**
 * POSIX single-quote escaping: a single-quoted shell string can't contain a literal `'` any other
 * way, so each one becomes close-quote + escaped-quote + reopen-quote (`'\''`) — the standard idiom.
 */
function escapeSingleQuotes(value: string): string {
  return value.replace(/'/g, `'\\''`);
}
