# 11 — Settings

**Files:** `features/settings/**`
**Wave:** 2 (page bar + form width), 4 (the split)

## Current state

One page reached under two different names (F7): **"Flight & detection settings"** from the Operate
nav, **"Account settings"** from the avatar menu. It contains, in order:

1. **Interface** — `Advanced mode` checkbox, `DEFAULT TILES PER ROW ON THE WALL` select.
2. **Notifications** — `Notify me about new detection events` checkbox with a 4-line explanation.
3. **Detection profile** — `Balanced` / `Low latency` / `High quality` preset cards, each with a
   description and a `conf / fps / model` summary; then `DETECTION MODEL` cards
   (`General`, `Military vehicles`, `Everything (incl. buildings, slower)`), and more below the fold.

## Problems

- **Two concerns in one page** (F7). Interface and Notifications are **per-account**; Detection
  profile and model are **fleet-wide operational defaults** applied to every stream anyone starts.
  A pilot changing "tiles per row" and a manager changing the detection model are different acts with
  different blast radii, presented identically.
- **Two names for one URL** is the symptom; the mixed content is the cause.
- `DEFAULT TILES PER ROW ON THE WALL` duplicates the Wall's own control (see `03-wall.md`) — two
  mechanisms for one setting.
- The page is left-aligned in a 1400px column; the Notifications explanation runs to a 600px measure
  while its checkbox sits alone on a 1000px row.
- No save affordance visible — it is unclear whether changes apply immediately (they appear to) or
  need confirming. For fleet-wide detection settings that ambiguity matters.
- Preset cards and model cards use the same visual treatment at the same level, so the hierarchy
  (profile *contains* model) is invisible.

## Suggested design

**Split the page along the ownership line.**

| Route | Nav home | Contents |
|---|---|---|
| `/settings` | Avatar menu → "Account settings" | Interface, Notifications |
| `/settings/detection` | Sidebar → Operate → "Detection defaults" | Detection profile, model, per-pipeline options |

```
┌────┬──────────────────────────────────────────────────────────────┐
│ ▎⚙ │ ⚙ Detection defaults          Applies to every new stream    │
│    ├──────────────────────────────────────────────────────────────┤
│    │        PROFILE                                               │
│    │        ┌──────────┐┌──────────┐┌──────────┐                  │
│    │        │●Balanced ││ Low lat. ││ High qual│                   │
│    │        │conf 0.4  ││conf 0.5  ││conf 0.3  │                   │
│    │        │5 fps     ││3 fps     ││10 fps    │                   │
│    │        └──────────┘└──────────┘└──────────┘                  │
│    │          └─ MODEL                                            │
│    │             ( General ) ( Military ) ( Everything )          │
│    │          └─ ADVANCED                    [⌄]                  │
│    │                                                              │
│    │        Changed from Balanced        [Revert] [Save profile]  │
└────┴──────────────────────────────────────────────────────────────┘
```

- **`.page--form`** centers both pages at 880px — settings are a reading-and-deciding task, not a
  scanning task.
- **Hierarchy made visible**: Model and Advanced are *indented under* the selected Profile, since
  changing them forks the preset into a custom profile (which the copy already says).
- **A persistent action bar** appears the moment a value differs from the preset:
  `Changed from Balanced · [Revert] [Save profile]`. Removes the save ambiguity.
- **Blast-radius line in the header** — "Applies to every new stream" on the detection page,
  "Only affects your account" on `/settings`.
- **Tiles-per-row is deleted here** and lives only on the Wall (its density control persists per user).
- Notifications' 4-line explanation collapses to one line + a `?` popover.

## Refactor list

- **Split** `settings.html`/`.ts` into `account-settings` and `detection-settings`; add
  `/settings/detection` to `SETTINGS_ROUTES`.
- **Update** `nav-entries.ts`: Operate's "Flight & detection settings" → **"Detection defaults"** →
  `/settings/detection`. The avatar menu keeps `/settings` as "Account settings". One name each.
- **Delete** the tiles-per-row control; migrate the stored value to the Wall's density preference.
- **Add** `.page--form`.
- **Add** the dirty-state action bar.
- **Nest** model/advanced under profile visually.

## Acceptance

- No page is reachable under two different names.
- Changing a detection value shows an explicit save/revert affordance.
- Wall density has exactly one control.
