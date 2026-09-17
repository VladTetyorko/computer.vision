---
name: module-docs
description: Create or refresh MODULE.md context files for the vision project's Maven modules. Use when module docs are missing, stale after a refactor, or when asked to document a module.
---

# Module context docs

Every Maven module (and `cv/cv-service/`) carries **two** docs at its root. Only the first is ever
loaded eagerly.

| File | Holds | Read when |
|---|---|---|
| `MODULE.md` | purpose · deps · build/test · API surface · conventions · gotchas · current status | **always**, before touching the module |
| `MODULE-HISTORY.md` | *"wave X done"*, dated entries, build logs, test counts, notes an agent wrote to itself | **only** when you need to know *why* something is the way it is |

## The boundary

> Keep a sentence in `MODULE.md` if it says **what is true now**.
> Move it to `MODULE-HISTORY.md` if it says **what a wave did** — when, on what branch, with what
> test counts, or what the agent learned on the way.

Current state: *"`TargetLockRequest.pointX/pointY` exist on the DTO; nothing populates them."*
History: *"wave W5 added `<vision-target-list>`; 3042 tests green; bundle +0 kB."*

## Rules

1. **Read before touching** — `MODULE.md`, plus the `MODULE.md` of modules whose ports/models you
   actually use. Read the *sections you need*, not the whole file, once a doc is long enough to have
   a section index. Only open sources when the doc lacks detail you need — and when it does, that is
   a doc bug: fix the doc in the same task.
2. **Update in place** — a task that changes the public surface, behavior, conventions or gotchas
   MUST edit the affected lines of `MODULE.md`. **Never append a wave/status section to it.** If the
   wave is worth narrating, one entry at the top of `MODULE-HISTORY.md`; otherwise git and the plan
   doc in `docs/plans/` already hold it. Stale docs are worse than none — but a doc that grows by one
   changelog entry per wave is worse than stale, because every future agent pays to read it.
3. **Compact and factual** — `MODULE.md` targets **≤200 lines**. Signatures over prose. No marketing,
   no history, no build transcripts. Link, don't duplicate: rationale lives in `ARCHITECTURE.md`,
   specs in `docs/plans/`.
4. **Past 200 lines, add a section index** — a table at the top mapping topic → heading, so a reader
   can jump. Past ~400, the module doc wants sharding by responsibility into `docs/` beside it, with
   `MODULE.md` keeping the contract and routing to the rest.

## Required structure

```markdown
# <module-name>

<one-sentence purpose>

**Depends on:** <internal modules + notable external libs> · **Used by:** <internal modules>
**Build/test:** `./mvnw -B -pl <path> test`

## API surface
<per package: each public type on one line — kind, name, signature/fields, one-clause note.
 For ports: full method signatures. For records: full component list. Mark nested types.>

## Conventions
<module-specific idioms an implementer must follow (validation style, threading, DI style...)>

## Gotchas
<hard-won facts: quirks of libs, timing constraints, things that look wrong but are right>

## Status
<what is real vs placeholder vs flagged-off, right now. Current state only — no dated entries.>

Wave-by-wave history: [`MODULE-HISTORY.md`](MODULE-HISTORY.md).
```

That last pointer is **required**, not decorative: ~146 source files cite these docs by path, and some
cite *"see MODULE.md's Status entry for why"*. Once the narrative moves, the pointer is what keeps
that citation resolvable in one hop.

`MODULE-HISTORY.md` is free-form, newest first, one `## <date> — <plan doc> wave <X>` per entry.

## Index

The root `CLAUDE.md` holds the module index table. When adding a module, add its row there.

## When a doc is worth splitting

Size is the wrong proxy. Measure **narrative share** — the fraction of the file sitting in
`## Status`-family sections plus dated lines — and split only above roughly **20%**:

```bash
python3 - <<'EOF'
import io, re, glob, os
for f in sorted(glob.glob('**/MODULE.md', recursive=True)):
    if '.claude/worktrees' in f or 'node_modules' in f: continue
    s = io.open(f, encoding='utf-8').read()
    parts = re.split(r'(?m)^(##+ .*)$', s)
    narr = sum(len(parts[i]) + len(parts[i+1]) for i in range(1, len(parts), 2)
               if re.search(r'status|wave|history|changelog|done\b', parts[i], re.I))
    dated = sum(len(l) for l in s.splitlines(True) if re.search(r'\b20\d\d-\d\d-\d\d\b', l))
    print(f"{100*max(narr,dated)/len(s):3.0f}%  {len(s)/1024:5.0f}K  {os.path.dirname(f)}")
EOF
```

Below ~20% a split costs an agent, a commit and a second file per module and returns a few
kilobytes, while adding real risk — every split so far has needed hand-repair for a dropped or
invented citation. A big doc with a small narrative share is **not** a candidate: its cost is API
surface, and the fix for that is sharding by responsibility, not a history file.

## Verifying a split

A set-based citation diff is not enough. It compares the *unique* paths before and after, so it
passes while an agent quietly rewrites a heading from `docs/plans/active/AUTH-ROLES-PLAN.md` to a
bare `AUTH-ROLES-PLAN.md` — the full path survives elsewhere in the body, so the set is unchanged.
Three of the first seven splits did exactly this, 23 headings between them, and one of the 23 named
a plan file that **does not exist** (`ZERO-CONFIG-ONBOARDING-PLAN.md`; the real doc is
`ZERO-CONFIG-ONBOARDING-CONTEXT.md`).

Run all four checks:

```bash
M=<module-path>
# 1. no bare-filename headings
grep -c '^## [A-Z0-9-]*-\(PLAN\|CONTEXT\)\.md' $M/MODULE-HISTORY.md   # must be 0
# 2. every path cited actually exists on disk
for f in $M/MODULE.md $M/MODULE-HISTORY.md; do
  grep -o '\bdocs/[A-Za-z0-9/._-]*\.md' $f | sort -u |
    while read -r p; do [ -f "$p" ] || echo "BROKEN $f -> $p"; done
done
# 3. no unique path lost across the pair
diff <(git show HEAD:$M/MODULE.md | grep -o '\bdocs/[A-Za-z0-9/._-]*\.md' | sort -u) \
     <(cat $M/MODULE.md $M/MODULE-HISTORY.md | grep -o '\bdocs/[A-Za-z0-9/._-]*\.md' | sort -u)
# 4. no wave residue left in the contract
grep -ci 'tests green\|Tests run:\|wave [A-Z][0-9]* done' $M/MODULE.md      # must be 0
```

Check 2 is the one that catches a fabricated citation, and it is the only check that does — but it
only sees citations written as a `docs/...` path. A **bare** filename that resolves nowhere slips
past it, which is how `PLATFORM-AUDIT-2026-08-21.md` sat in two module docs for weeks: no file has
ever had that name (the 2026-08-21 audit is six `docs/plans/active/PLATFORM-AUDIT-*.md` lane
reports). So also run check 2b, repo-wide:

```bash
python3 - <<'EOF'
import io, re, glob, os
real = {os.path.basename(f) for f in glob.glob('docs/**/*.md', recursive=True)}
real |= {'CLAUDE.md', 'ARCHITECTURE.md', 'SKILL.md', 'MODULE.md', 'MODULE-HISTORY.md'}
for f in sorted(glob.glob('**/MODULE*.md', recursive=True)):
    if '.claude/worktrees' in f: continue
    for m in re.finditer(r'(?<![/\w-])([A-Z][A-Z0-9-]{3,}\.md)', io.open(f, encoding='utf-8').read()):
        if m.group(1) not in real:
            print(f'{f}: {m.group(1)}')
EOF
```

Anything it prints is either a phantom or a doc that has been renamed or moved. Resolve it to a real
path — never delete the citation, since the fact it supports is usually still true.
