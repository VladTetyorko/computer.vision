---
name: module-docs
description: Create or refresh MODULE.md context files for the vision project's Maven modules. Use when module docs are missing, stale after a refactor, or when asked to document a module.
---

# Module context docs (MODULE.md)

Every Maven module (and `cv/cv-service/`) carries a `MODULE.md` at its root: a compact, current snapshot of what the module exposes, so that agents and humans get full working context WITHOUT re-reading sources.

## Rules

1. **Read before touching.** Before modifying a module, read its `MODULE.md` (and those of modules it depends on). Only read actual sources when the doc is missing detail you need — and if it is, that's a doc bug: fix the doc too.
2. **Update after touching.** Any task that changes a module's public surface, behavior, conventions, or gotchas MUST update that module's `MODULE.md` in the same task. Stale docs are worse than none.
3. **Compact and factual.** Target ≤150 lines. Signatures over prose. No marketing, no history — current state only. Link, don't duplicate: architecture rationale lives in `ARCHITECTURE.md`, phase plans in `docs/*-PLAN.md`.

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
<what's real vs placeholder; which phase implements what's missing>
```

## Index

The root `CLAUDE.md` holds the module index table. When adding a module, add its row there.
