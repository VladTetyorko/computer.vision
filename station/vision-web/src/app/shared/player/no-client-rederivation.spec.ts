import { readFileSync, readdirSync } from 'fs';
import { dirname, join } from 'path';
import { fileURLToPath } from 'url';
import { describe, expect, it } from 'vitest';

// The `fs`/`path`/`url` types above come from this directory's own `node-builtins.d.ts` — this
// project has no `@types/node` (not a direct dependency, and CLAUDE.md's build instructions say not
// to add/upgrade dependencies for this wave). See that file's doc comment for why.

/**
 * Acceptance check for docs/plans/active/CV-ORCHESTRATION-PLAN.md §6's W3 row ("no client
 * re-derivation of velocity or label for tracked objects (grep test)") — a plain grep, not a
 * behavioral test, because the thing being guarded against is a *name reappearing*, not a runtime
 * value. Wave W3.2 deleted the client-side label-election stopgap and its own tuning constants
 * (`docs/plans/active/CV-ORCHESTRATION-PLAN.md §4.6) and narrowed the forward-projection machinery's
 * name/scope to the *unmatched* subset only (`detection-overlay-logic.ts#resolveDisplayDetections`).
 * If any of the four retired identifiers below reappears anywhere under `shared/player/`, someone has
 * either reverted the deletion or copy-pasted the old shape back in — either way, this should fail
 * loudly rather than silently reintroducing a second, independent re-implementation of a server fact.
 *
 * Scans every `.ts` file under this directory (recursively — `follow-hud/` included) **except**
 * `.spec.ts` files: the four names below are deliberately still spoken *about* in prose inside this
 * repo's spec history (e.g. this file's own doc comment, one line up) and in commit messages, and
 * excluding specs is simpler and just as safe as trying to word every mention around the literal
 * strings — the actual guarantee this test exists to make ("the production code doesn't re-derive
 * these") only needs non-spec files scanned. This file is itself a `.spec.ts` and is therefore
 * automatically excluded — see the note above about not matching its own literal strings.
 */

const FORBIDDEN_SUBSTRINGS = ['extrapolateOne', 'electStickyLabels', 'STICKY_LABEL_', 'EXTRAPOLATION_MATCH_GATE'] as const;

const PLAYER_DIR = dirname(fileURLToPath(import.meta.url));

function collectTsFiles(dir: string): readonly string[] {
  const files: string[] = [];
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const fullPath = join(dir, entry.name);
    if (entry.isDirectory()) {
      files.push(...collectTsFiles(fullPath));
      continue;
    }
    if (entry.isFile() && entry.name.endsWith('.ts') && !entry.name.endsWith('.spec.ts')) {
      files.push(fullPath);
    }
  }
  return files;
}

describe('no-client-rederivation (shared/player/**)', () => {
  it('none of the retired extrapolation/sticky-label identifiers reappear in any non-spec .ts file', () => {
    const files = collectTsFiles(PLAYER_DIR);
    expect(files.length).toBeGreaterThan(0); // sanity: the scan actually found files

    const offenders: string[] = [];
    for (const file of files) {
      const content = readFileSync(file, 'utf-8');
      for (const forbidden of FORBIDDEN_SUBSTRINGS) {
        if (content.includes(forbidden)) {
          offenders.push(`${file}: contains "${forbidden}"`);
        }
      }
    }
    expect(offenders).toEqual([]);
  });
});
