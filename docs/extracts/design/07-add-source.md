# 07 — Add a source (onboarding wizard)

**Files:** `features/onboarding/**`
**Wave:** 2

## Current state

`page-head` ("Add a source" + a 3-line description) with `Cancel`. A 4-step progress bar
(`1 Profile` · `2 Connect` · `3 Test` · `4 Create`) spanning the full 1400px, then the step body —
step 1 is `DISPLAY NAME`, `REGISTRATION / TAIL NUMBER (OPTIONAL)`, `CATEGORY` select, `PHOTO
(OPTIONAL)` with a `Choose photo` button, a validation hint, and `Next`.

## Problems

- **Every input is 1075px wide.** A display name is ~10 characters and a tail number ~6; both get a
  field wide enough for a paragraph. This is the clearest single instance of F4.
- The form is left-aligned in a 1400px column on an 1854px screen, so it reads as neither centered
  nor full-width — it looks unfinished.
- The `PHOTO` thumbnail placeholder is a 78×78 empty box with no icon or affordance; it reads as a
  broken image, not a dropzone.
- `Next` is disabled with the reason ("Enter a display name and choose a category.") rendered as small
  grey text *above* it rather than attached to it.
- The step bar's 4 segments are equal-width buttons that look clickable but are not (steps 2–4 are
  inert until reached).

## Suggested design

A wizard is one of the few places a **narrow centered column** is correct.

```
┌────┬──────────────────────────────────────────────────────────────┐
│ ▎+ │ + Add a source                                     [Cancel]  │
│    ├──────────────────────────────────────────────────────────────┤
│    │            ●───────○───────○───────○                         │
│    │          Profile Connect  Test   Create                      │
│    │        ┌────────────────────────────────────┐                │
│    │        │ Profile                            │                │
│    │        │ What is it, and what does it       │                │
│    │        │ look like?                         │                │
│    │        │                                    │                │
│    │        │ DISPLAY NAME                       │                │
│    │        │ [ Falcon-2              ]          │                │
│    │        │ REGISTRATION (OPTIONAL)            │                │
│    │        │ [ N12345    ]                      │                │
│    │        │ CATEGORY                           │                │
│    │        │ [ Choose a category…  ⌄ ]          │                │
│    │        │ PHOTO (OPTIONAL)                   │                │
│    │        │ ┌────┐  Drop an image or           │                │
│    │        │ │ 📷 │  [Choose photo]             │                │
│    │        │ └────┘  Downscaled under 2MB       │                │
│    │        │                        [Next →]    │                │
│    │        │  Enter a name and pick a category  │                │
│    │        └────────────────────────────────────┘                │
└────┴──────────────────────────────────────────────────────────────┘
```

- **`.page--form { max-width: 880px; margin-inline: auto }`** — the wizard centers.
- **Fields size to their content**: `--field-sm: 20ch` (tail number), `--field-md: 40ch` (name),
  `--field-lg: 100%` (URI). No text input is wider than its plausible value.
- **Step bar becomes a dot rail**, not four equal buttons — completed steps are clickable and marked,
  future ones are plainly inert.
- **Photo becomes a real dropzone**: dashed border, camera icon, drag-and-drop, paste support.
- **The disabled reason attaches to `Next`** as a tooltip/`aria-describedby`, and the specific missing
  fields get inline errors on blur instead of one summary sentence.
- The 3-line page description moves into the step body as the step's own subtitle (it already has one)
  and is deleted from the header.

## Refactor list

- **Add** `.page--form` to `styles.css`; apply here and on `/settings`.
- **Add** field-width tokens; apply per input.
- **Rewrite** the step indicator as a dot rail with proper `aria-current` / `disabled`.
- **Rewrite** the photo control as a dropzone (`shared/ui/` candidate if `/org` needs one too).
- **Move** validation from a summary line to per-field + a `Next` tooltip.
- **Replace** `page-head` with `vision-page-bar`.

## Acceptance

- No text input exceeds 40ch unless it holds a URI or path.
- The wizard is horizontally centered at 1854px and at 1280px.
- Drag-and-drop onto the photo box works.
