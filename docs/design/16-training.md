# 16 — CV training (datasets, labeling, models, jobs)

**Files:** `features/labeling/**`, `features/models/**`, `features/training-jobs/**`
**Wave:** 2

## Current state

`/manage/training` renders a feature-gated empty state:

> ⚠ **Training tools aren't enabled here**
> This deployment hasn't turned on the CV training feature yet. Ask an administrator to enable
> `vision.training.enabled` if you need to build a dataset.

with `Models` and `Back to Manage` buttons top-right, and a `MANAGE` eyebrow above the title. The full
flow (capture → correct → export → train → promote) exists behind the flag and could not be exercised
in this walkthrough.

## Problems

- **The empty state is well-written but mis-placed.** The icon sits far left at x≈230 while the text
  is centered at x≈756 — they are not visually connected, so the page reads as two broken fragments
  rather than one message.
- **`Back to Manage`** points at a hub page that this redesign deletes (see `19-hubs.md`). It is also
  the wrong affordance — the sidebar is the way back.
- **The `MANAGE` eyebrow** duplicates what the sidebar's active group already shows.
- **`Models` is offered while training is disabled** — if the feature is off, its sub-page should not
  be a primary action on the disabled state.
- A disabled feature still occupies a full primary nav entry with no visual indication that it is
  unavailable until you land on it (F9's variant: not "soon" but "off here").
- Three sibling route trees (`labeling`, `models`, `training-jobs`) under one nav entry with no shared
  navigation between them — once inside, moving from a dataset to its job to the resulting model is
  unclear.

## Suggested design

### The disabled state

```
┌────┬──────────────────────────────────────────────────────────────┐
│ ▎🎯│ 🎯 CV training                                               │
│    ├──────────────────────────────────────────────────────────────┤
│    │                                                              │
│    │                      ⚠                                       │
│    │            Training tools aren't enabled                     │
│    │      This deployment hasn't turned on CV training.           │
│    │      An administrator can enable it with                     │
│    │      vision.training.enabled                                 │
│    │                                                              │
└────┴──────────────────────────────────────────────────────────────┘
```

- Icon, heading, body and any action in **one centered stack** — use `shared/ui/empty-state.ts`, which
  already does exactly this and is not being used here.
- **No `Models` / `Back to Manage` buttons** on the disabled state.
- **The sidebar marks the entry disabled** (dimmed + `off` chip) when the flag is false, so the state
  is knowable before clicking. Requires exposing the flag to the shell — a small config read the app
  already performs for other gates.

### The enabled state

Give the three route trees one spine, since they are one workflow:

```
│ 🎯 CV training    Datasets │ Jobs │ Models                        │
```

- A tab strip across `/manage/training` (datasets), `/manage/training/jobs`, `/manage/training/models`.
- A dataset's detail links its export → its job → the promoted model, so the loop is traversable in
  the direction the work actually flows.
- Breadcrumbs on the leaf pages (`‹ Datasets / Frames / Sample 12`).

## Refactor list

- **Replace** the hand-rolled disabled state with `vision-empty-state`.
- **Delete** `Back to Manage` everywhere in these three features (the hub is gone).
- **Delete** the `MANAGE` eyebrow.
- **Add** the tab strip shared by the three route trees.
- **Expose** `vision.training.enabled` to the sidebar for the disabled affordance.
- **Verify with the flag on** — this walkthrough could not reach the real pages; the enabled flow needs
  its own pass before any deeper redesign is committed to. Treat everything above the tab strip as
  confirmed and everything below it as provisional.

## Acceptance

- The disabled state is one centered stack with no dead buttons.
- The sidebar shows CV training as unavailable when the flag is off.
- With the flag on, a dataset → job → model round trip needs no URL editing.
