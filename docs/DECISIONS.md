# Decisions

Short records of the calls that are expensive to reverse. Each one exists
because the alternative was tried by somebody else first and cost them a
quarter.

---

## ADR-001 — The key grid is one Canvas view, not one view per key

**Status:** accepted, revisitable in M0.

`KeyGridView` draws every key in a single `onDraw` pass and hit-tests against
precomputed rectangles. Pressing a key invalidates only that key's rectangle.

The alternative — a view or composable per key — costs a measure/layout pass per
frame and N state reads per touch, and repaints the whole keyboard on every
keypress. That is the most common reason a custom keyboard feels heavy.

**Revisit if:** the M0 spike shows Compose first-composition is under 200 ms p90
on a Redmi-class device, in which case the development speed may be worth it.

---

## ADR-002 — One process for everything

The IME service, settings, and the AI sheet share a process. A separate process
for the IME would mean a fresh process start on the show path, which is the
single cost a keyboard cannot afford.

**Consequence:** the IME process graph must stay lean. No billing SDK, no Play
libraries, no network client on the `:ime` classpath except behind `:ai`.

---

## ADR-003 — AI actions run in a foreground Activity, not in the service

ML Kit's GenAI APIs over Gemini Nano only permit inference while the app is the
top foreground app; an IME window does not qualify and returns
`BACKGROUND_USE_BLOCKED`.

Every AI action therefore launches a small bottom-sheet Activity. This satisfies
the platform and gives the user somewhere to review a streamed draft before it
is committed to their text field — which is the behaviour we wanted anyway.

**Consequence:** AI actions are never one-tap-and-inserted. That is a feature.

---

## ADR-004 — Voice is tiered by capability, and tier 0 is the default

`SYSTEM` is the default everywhere. `ON_DEVICE_MODEL` is offered only when the
device passes the probe and the user opts in. `CLOUD` is opt-in and is the only
tier that sends audio off the device.

The model is never in the bundle: a 75 MB base install hurts conversion, and the
download can be offered after the keyboard has already proven itself.

**Consequence:** three code paths, one interface, and the caller never learns
which tier answered.

---

## ADR-005 — The dictionary is a trie, and the production one is memory-mapped

`TrieDictionary` is the in-memory reference implementation. The shipping
dictionary will be a precompiled binary trie, memory-mapped at startup, because
parsing a word list at runtime puts hundreds of milliseconds on the show path.

The interface stays the same, so the swap is contained.

---

## ADR-006 — Prediction is asked for after the character is committed

Every keystroke commits first and asks for suggestions second. Each lookup
cancels the previous one, and results land in a `StateFlow`, which conflates.

Nothing about prediction is allowed to sit between the touch and the character
appearing.

---

## ADR-007 — The IME owns its own lifecycle

`InputMethodService` is not a `LifecycleOwner`, so the service implements
`LifecycleOwner`, `ViewModelStoreOwner` and `SavedStateRegistryOwner` and
attaches them to the window's decor view.

Without this, any Compose content inside the IME — the clipboard panel, the
snippet manager — crashes on a missing `ViewTreeLifecycleOwner`.

---

## ADR-008 — Clipboard is read on tap only

Passive listening is unreliable across versions, and from Android 12 reading the
clipboard surfaces a system notification to the user, which reads as the
keyboard being nosy.

The clipboard key reads on demand. Sensitive items are stored flagged and never
previewed.
