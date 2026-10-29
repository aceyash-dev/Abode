# Abode

An Android keyboard with AI snippets, clipboard history and tiered voice input.

**Status: scaffold.** The typing core is real — IME service, Canvas key grid,
commit path, dictionary and prediction plumbing. Clipboard, voice and AI have
their interfaces and capability logic in place and their implementations
pending. Nothing here has been compiled yet; the first CI run is the first real
build. See [Roadmap](#roadmap).

> **Icon.** Launcher assets are generated from `store-assets/icon-master.png` by
> `tools/make_icons.py`: adaptive foreground, round legacy icons, and the Android 13
> monochrome layer. The display name lives in one place,
> `app/src/main/res/values/strings.xml`.

---

## The two requirements that shape everything

**1. It has to feel like the system keyboard.** Budgets, not adjectives:

| Path | Budget (p50 / p90) |
| --- | --- |
| Field focused → keyboard visible, warm process | 60 / 100 ms |
| Field focused → keyboard visible, cold process | 120 / 250 ms |
| Key down → glyph on screen | 16 / 32 ms |
| Key down → character committed | 20 / 40 ms |
| Suggestion strip refresh | 30 / 60 ms |
| Snippet trigger → expansion | 50 / 100 ms |

The show path is logged on every invocation (`Abode` tag, "show latency"). It is
the number that decides whether this project succeeds.

**2. Voice has to work on every device.** Three tiers behind one interface,
chosen by capability, not by preference:

| Tier | Engine | Footprint |
| --- | --- | --- |
| `SYSTEM` | System `SpeechRecognizer`, on-device recogniser preferred | 0 MB |
| `ON_DEVICE_MODEL` | Local Whisper-class model, downloaded on capable devices only | 75–500 MB |
| `CLOUD` | Streaming cloud STT, for Hinglish and long dictation | 0 MB |

`CapabilityProbe` decides which tiers are *offered*: not a low-RAM device, at
least 6 GB RAM, 4 cores and 1 GB free disk. A local model is never shipped in
the bundle and is never downloaded without the user asking for it.

---

## Module structure

| Module | Contents | Depends on |
| --- | --- | --- |
| `:app` | Settings, onboarding, permission flows, billing | `:data`, `:core-ui`, `:ai` |
| `:ime` | `InputMethodService`, lifecycle owners, show and commit paths | `:ui-keyboard`, `:engine-text`, `:engine-voice`, `:data` |
| `:ui-keyboard` | Key grid (Canvas), layout engine, key model | `:core-common` |
| `:engine-text` | Trie dictionary, prediction, snippet triggers | `:core-common` |
| `:engine-voice` | Voice tiers, capability probe, model download | `:core-common` |
| `:ai` | `AiEngine` interface, on-device and cloud providers | `:core-common` |
| `:data` | Room: clipboard entries, snippets | `:core-common` |
| `:core-ui` | Theme, design system | `:core-common` |
| `:core-common` | Dispatchers, logging | — |

Three dependency rules keep the fast paths fast:

1. **`:ime` never depends on `:app`.** Settings code, billing SDKs and Play
   libraries must not load in the IME process graph.
2. **`:engine-text` has no Android UI dependency.** Dictionary work is testable
   on the JVM in milliseconds and cannot touch the main thread by accident.
3. **Network code lives only in `:ai` and `:engine-voice`,** behind interfaces.
   The keyboard core works in aeroplane mode, always.

One process, always. A second process costs a fresh process start on the show
path, which is the one cost a keyboard cannot afford.

---

## Building

Requires JDK 17 and an Android SDK with API 35.

```bash
# Android Studio: open the project root and let it generate the wrapper.
# Or, with Gradle 8.9+ on PATH:
gradle :app:assembleDebug
gradle :engine-text:testDebugUnitTest
```

The Gradle wrapper JAR is not committed yet — Android Studio generates it on
first open. CI does not need it: the workflow installs Gradle 8.9 directly.

CI runs the unit tests and assembles the debug APK on every push.

### Signing

Release builds are signed from `keystore.properties` (gitignored) or, in CI, from
environment variables of the same names. With neither present the release build
assembles unsigned, so a fresh clone still works.

```bash
cp keystore.properties.example keystore.properties   # then edit it

keytool -genkeypair -v -keystore abode-release.jks -alias abode \
  -keyalg RSA -keysize 4096 -validity 10000
```

Keep the keystore and both passwords out of the repository. Back the file up
somewhere you will still have it in five years — losing the upload key means
going through Play support to reset it, and losing an app-signing key is
terminal. Enrol in Play App Signing so Google holds the app-signing key and this
file is only the upload key.

---

## Roadmap

| Milestone | Scope | Exit criterion |
| --- | --- | --- |
| **M0** Spikes | Compose-in-IME cold start on a Redmi-class device; ML Kit foreground eligibility | Both answered with numbers |
| **M1** Typing core | Service, key grid, layout engine, commit path, dictionary, prediction, haptics | p90 show < 250 ms cold; key latency < 32 ms; daily-driverable |
| **M2** Clipboard & snippets | Room schema, clipboard panel, trigger expansion, per-app blocklist | Expansion < 100 ms; zero clipboard reads without a tap |
| **M3** Voice | Tier 0 end to end, tier 2 streaming, model download + tier 1 | Dictation into the composing region, all tiers reachable |
| **M4** AI actions | Bottom-sheet activity, five prompts, streaming, quota | Every action streams and is reviewable before insert |
| **M5** Ship | Play compliance, closed test | Submission |

Current state: M1 partially done. Missing from M1 — press-and-hold delete
repeat, shift-as-latch, symbols layout, tap-to-correct, baseline profiles.

---

## Privacy invariants

These are not aspirations; code that breaks them is a bug.

- Clipboard is read **only** on an explicit tap, never passively.
- Items flagged `ClipDescription.EXTRA_IS_SENSITIVE` are stored as sensitive,
  never previewed, never sent to a provider.
- Password and PIN fields disable clipboard, AI and cloud voice entirely.
- Audio is recorded only while the indicator is visible.
- Cloud AI keys live server-side. No provider key ships in the APK.
- `allowBackup=false`; the database and preferences are excluded from both cloud
  backup and device transfer.

---

## Licence

Not chosen yet. Until one is added, all rights are reserved.
