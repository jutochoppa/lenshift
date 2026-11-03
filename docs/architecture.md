# Lenshift architecture

Lenshift is an independent Android screen-search app: circle something on screen, ask a
model **you** configure. This document explains how it is built and why each decision was
made.

---

## 1. Module map

Single Gradle module, packages by responsibility. One module keeps the build fast and the
APK small; the package boundaries are strict enough to extract later if needed.

```
dev.lenshift.app
├── assist/          Assistant integration (VoiceInteractionService, session, handoff)
├── capture/         Capture providers and their coordination
├── selection/       Framework-free gesture geometry
├── ocr/             Offline Tesseract OCR
├── ai/              Provider contracts, capabilities, reasoning mapping, prompts
│   ├── adapters/    One class per wire protocol
│   └── transport/   HTTP, SSE/NDJSON framing, image encoding
├── conversation/    One exchange: history, streaming state, cancellation
├── data/
│   ├── settings/    Preferences + provider profiles
│   └── security/    Keystore-backed credential store
├── activation/      Assistant role wrapper, Quick Settings tile
├── ui/              Activities, Compose surfaces
└── di/              Lazy service locator
```

Dependency direction is one-way: `ui → conversation → ai → transport`,
`ui → capture → assist`, and `data` is depended on but never depends upward.

---

## 2. Activation: why the assistant role, not a floating bubble

### The decision

Lenshift's primary activation is `RoleManager.ROLE_ASSISTANT` plus a
`VoiceInteractionService`. Secondary activations are a Quick Settings tile, a launcher
shortcut, a share target, and an optional accessibility screenshot service.

### Why

The alternative — a `SYSTEM_ALERT_WINDOW` overlay button — was rejected because it
cannot capture the screen. An overlay cannot read pixels of the window beneath it, so it
would still need MediaProjection (consent dialog every session on Android 14+) or an
accessibility service (a much broader grant). The assistant path gets a real screenshot
from SystemUI with **no consent dialog and no overlay permission**, which is exactly the
Circle to Search experience.

### The flow

```
User triggers assist (gesture / keycode / OEM remap)
        │
        ▼
SystemUI  ──showSession(bundle, SHOW_WITH_ASSIST|SHOW_WITH_SCREENSHOT)
        │
        ▼
LenshiftVoiceInteractionService          (bound only while role is held)
        │  onPrepareToShowSession -> records which trigger fired
        ▼
LenshiftVoiceInteractionSessionService   onNewSession()
        │
        ▼
LenshiftSession
        ├── onShow(args, flags)          -> start a 220 ms grace timer
        ├── onHandleScreenshot(bitmap)   -> store, launch immediately
        └── onHandleAssist(AssistState)  -> extract text, launch after grace
        │
        ▼
CaptureHandoff.offer(bitmap)  +  startAssistantActivity(CaptureActivity)
        │
        ▼
CaptureActivity  (frozen screenshot + selection overlay)
```

### Two platform constraints discovered during implementation

Both are load-bearing:

1. **`recognitionService` is mandatory.** Without it the platform refuses to install the
   assistant at all. Lenshift ships an inert `LenshiftRecognitionService` that rejects
   every request and never opens the microphone. This is a truthful stub, not a pretend
   speech engine.

2. **`usesAssistScreenshots` is not a public attribute.** The app cannot declare that it
   wants a screenshot; SystemUI decides. Therefore a missing screenshot is treated as a
   normal outcome, not an error, and the fallback chain runs.

### Session reuse: the bug that mattered most

`VoiceInteractionManagerService` reuses **one** `VoiceInteractionSession` instance for
every activation. The first implementation set a `launched` guard that `onShow` never
reset, so after the first assist gesture the session would show, receive a screenshot, and
then silently do nothing — no UI, no error, no log entry. From the user's side the
assistant had simply stopped working, with nothing to go on.

The fix moves all per-invocation state into `SessionInvocationState`, reset by
`beginInvocation()` in `onShow`. It was extracted into its own class precisely because the
failure is silent: a silent failure needs a test, and a test needs logic that does not
require a device. Nine JVM tests now cover it, including 25 consecutive invocations.

This is the strongest argument for the "verify on a device" discipline in this project.
The bug was invisible to every unit test that existed at the time, and only appeared on
the *second* use of a feature that had already been demonstrated to work once.

### The honest limitation

On devices where Google routes the nav-handle long-press to Circle to Search, the assist
gesture does not reach Lenshift even while Lenshift holds the assistant role. The app does
not pretend otherwise: the Diagnostics screen shows which trigger actually fired, and the
Quick Settings tile is presented as the reliable path.

---

## 3. Capture: a strict priority chain

```
1. ASSIST_SCREENSHOT   bitmap already handed to us         no prompt, no permission
2. ACCESSIBILITY       takeScreenshot(), opt-in            no prompt, one-time grant
3. MEDIA_PROJECTION    system consent dialog               prompt every session (14+)
```

### Rules enforced in code

- **Capture happens only after explicit user activation.** Nothing runs on a timer, on
  boot, or on a screen-state change.
- **The bitmap is never written to disk.** `CaptureHandoff` is a single-shot in-memory
  slot, cleared on read; `CaptureActivity` nulls its reference in `onDestroy`.
- **`FLAG_SECURE` is respected, never bypassed.** `ERROR_TAKE_SCREENSHOT_SECURE_WINDOW` is
  surfaced to the user as "This screen is protected and cannot be captured".
- **MediaProjection tokens are single-use.** `MediaProjectionCapture` clears the stored
  consent before use and releases the `VirtualDisplay`, `ImageReader` and projection in a
  `finally` block.
- **The accessibility service is deliberately minimal**: `accessibilityEventTypes=""`,
  `canRetrieveWindowContent="false"`, `canTakeScreenshot="true"`. It cannot read screen
  text or observe app switches.
- **An image is only ever sent from an explicit selection.** A lasso, box, tap lens or the
  explicit *All* mode. An empty selection disables the actions; it is never silently
  widened to the whole screenshot.

### Does the user have to approve every screenshot?

Only on the last-resort path. Which one runs depends on how Lenshift was opened:

| Trigger | Mechanism | Prompt? |
|---|---|---|
| Assist gesture / assist key, Lenshift holds the assistant role | `onHandleScreenshot()` | **Never** |
| Tile or shortcut, accessibility fallback enabled once in Settings | `takeScreenshot()` | **Never** |
| Tile or shortcut, no accessibility fallback | MediaProjection | **Every time** (Android 14+ policy) |

The prompt is avoidable: enable the accessibility fallback once, or use the assist
gesture. MediaProjection exists so the app still works when neither is available.

### MediaProjection: three platform rules, all of which were broken

The "Capture screen once" path failed on every Android 14+ device until all three were
fixed, and each failure was silent:

1. **A `mediaProjection` foreground service must be running before
   `getMediaProjection()`.** `ScreenCaptureService` was declared but never started.
2. **A `MediaProjection.Callback` must be registered before `createVirtualDisplay()`.**
   Without it the call throws `IllegalStateException`, which the catch-all reported as
   "Screen capture failed".
3. **The capture must not run while Lenshift is on screen.** A virtual display mirrors
   what is visible, so capturing from the selection activity photographed Lenshift itself.

The flow is now: consent result → start the foreground service → finish the activity →
the service waits for the screen to settle, captures one frame, hands it over, and reopens
the selection surface. The virtual display is sized from `maximumWindowMetrics`, not
`displayMetrics`: inside a Service the latter excludes the system bars, which produced a
1080×2209 frame on a 1080×2400 panel and misaligned every selection. Verified on the AVD:
`captured 1080x2400`, then box-select → OCR → streamed answer.

### Why accessibility is not the default

`AccessibilityService.takeScreenshot()` would be the most convenient mechanism (no
consent dialog, works from a tile). It is offered as an **opt-in** because the
accessibility grant is far broader than a screenshot: users have been trained to distrust
apps that request it, and Google's Play policy restricts its use. Making it optional and
describing it precisely is the honest design.

---

## 4. Selection geometry

`SelectionGeometry` is pure Kotlin over `IntRect` and primitive floats — no
`android.graphics.Rect`, no `RectF`. That is not stylistic: both are stubs in local JVM
unit tests (every method returns 0), so a geometry layer built on them cannot be tested
without a device. Keeping it primitive made 12 deterministic geometry tests possible.

### Behaviour

| Mode | Gesture | Region |
|---|---|---|
| LASSO | freehand stroke | bounding box of the stroke, padded 4%, clamped |
| RECTANGLE | drag | normalised positive-area rect |
| TAP | tap | OCR word under the finger, else a lens of 12% of the short edge |
| FULL | one button | the entire screenshot |

A hand-drawn "circle" is **never** treated as a circle. People circle loosely, so the
bounding box plus padding captures the intended content more reliably than any ellipse
fit. A self-intersecting stroke is recognised as a scribble/highlight, which is why the
same bounding-box strategy is correct there too.

Degenerate input (a dot, a 2 px drag) falls back to a tap lens rather than producing an
empty crop.

### Coordinate contract

Every value crossing the UI boundary is in **source bitmap pixels**. The canvas computes a
`ContentScale.Fit` letterbox rect once per size change and converts in both directions, so
cropping, OCR boxes and the uploaded image stay aligned at any density, aspect ratio or
orientation.

---

## 5. OCR

**Tesseract4Android 4.7.0** (Apache-2.0) with `tessdata_fast/eng.traineddata` bundled in
`assets/`.

### Why not ML Kit

| | Tesseract4Android | ML Kit bundled | ML Kit unbundled |
|---|---|---|---|
| Play services | not required | not required | **required** |
| Network | never | none after install | model download |
| License | Apache-2.0 | Google proprietary | Google proprietary |
| Update path | app-controlled | opaque | opaque |

For an app whose premise is "no Google services, no telemetry", shipping Google's OCR
binary would undercut the claim even if it never phoned home. Tesseract is slower on large
images but Lenshift only OCRs a crop, which keeps latency acceptable.

### Details that matter

- Trained data is copied from assets into `filesDir/tesseract/tessdata` once, then reused.
  Nothing is written outside app-private storage.
- The engine is a single mutex-guarded instance (`TessBaseAPI` is not thread-safe) created
  lazily, so a cold assistant invocation does not pay init cost until OCR is needed.
- OCR runs on the **crop**, not the full screenshot, which is faster and avoids reading
  content the user did not select.
- Bounding boxes are shifted by the crop offset so they map back onto the full screenshot.
- Word boxes are approximated proportionally across each recognised line (Tesseract's
  line-level iterator does not expose per-word boxes). This keeps tap-to-select useful
  without a second recognition pass; it is documented as approximate.

---

## 6. AI provider layer

### The central claim

**These protocols are not interchangeable JSON.** Each has a different body shape, stream
envelope, reasoning control, and multi-turn state rule. A single "generic" client would
either lose capability or send invalid requests. So: one adapter per protocol, one shared
transport.

| Protocol | Body root | System prompt | Images | Reasoning knob | Stream |
|---|---|---|---|---|---|
| Chat Completions | `messages[]` | a `system` message | `image_url` data URL | `reasoning_effort` / `reasoning.effort` | SSE deltas |
| Responses | `input[]` + `instructions` | `instructions` | `input_image` | `reasoning.effort` | named SSE events |
| Anthropic | `messages[]` + `system` | top-level `system` | `image` block, base64 | `thinking.budget_tokens` | typed SSE events |
| Gemini | `contents[]` + `systemInstruction` | `systemInstruction` | `inline_data` | `generationConfig.thinkingConfig` | SSE (`alt=sse`) |
| Ollama | `messages[]` + `options` | a `system` message | bare base64 `images[]` | `think` | NDJSON |

### Adapter contract

```kotlin
interface ProviderAdapter {
    val protocol: ApiProtocol
    fun endpoint(profile: ProviderProfile): String
    fun stream(request: ChatRequest): Flow<StreamEvent>
    suspend fun complete(request: ChatRequest): ChatResult
    suspend fun listModels(profile: ProviderProfile, apiKey: String?): List<String>?
    suspend fun testConnection(profile: ProviderProfile, apiKey: String?): ConnectionTest
}
```

Adapters own request construction, stream framing, response normalisation and
continuation-state extraction. They do **not** own retries, timeouts, UI or persistence —
those live in `HttpSupport` and `ConversationController`.

`StreamEvent` is the normalisation boundary: `TextDelta`, `ReasoningDelta`, `Usage`,
`Notice`, `Completed`. The UI never sees a provider-specific field name.

### Reasoning: never send what is not supported

`ReasoningMapper` is the most safety-critical file in the provider layer. A wrongly-sent
parameter turns a working request into a 400, which is worse than silently using the
provider default.

Precedence: explicit user override → documented family inference → **unknown**.

`Tri.UNKNOWN` means *do not send*. Concretely:

- `AUTO` sends nothing at all — the provider's own default.
- `OFF` is expressed by **omission** on OpenAI and Anthropic, `thinkingBudget: 0` on
  Gemini, and `think: false` on Ollama.
- `MAXIMUM` maps to `high` on effort-based APIs, because no API documents "maximum".
- Anthropic's `budget_tokens` is clamped to the documented 1024 minimum, and
  `max_tokens` is raised so `budget_tokens < max_tokens` always holds.
- Models known to reject effort parameters (`o1-preview`, `o1-mini`) get nothing.
- Gemini `MAXIMUM` uses `thinkingBudget: -1` (dynamic), which is the documented sentinel.

### Reasoning output

Reasoning text is kept in a **separate** field from the answer, shown behind a
disclosure, and never merged into the answer. If a model spends its entire output budget
on reasoning, `BaseAdapter.complete` raises `AiError.ReasoningStarved`, whose message tells
the user exactly what to change. This is tested both as a unit test and over HTTP.

### Capability inference

`CapabilityInference` matches well-known model families for vision and reasoning and
returns `Tri.UNKNOWN` otherwise. **It never produces a context-window number.** A wrong
number silently truncates requests, so the field stays null unless the user types it or a
`/models` response supplies a real value.

### Prompt-injection defence

Screen text is attacker-controlled. `PromptBuilder` enforces three rules:

1. The system message **never** contains screen-derived text.
2. Screen text is wrapped in `<screen_content>` … `</screen_content>` and labelled as data.
3. The user's own question is appended **after** the data block, so the last instruction
   the model reads is the one the human typed.

`PromptBuilderTest` includes a test that feeds a hostile `</screen_content>` escape attempt
and asserts it cannot reach the system message.

---

## 7. Security

### Credentials

`SecretStore` uses the Android Keystore directly:

- AES-256-GCM, key generated in `AndroidKeyStore`, non-exportable, hardware-backed where
  a TEE/StrongBox exists.
- A fresh 12-byte IV per secret, prepended to the ciphertext.
- Only ciphertext reaches `SharedPreferences`.
- `setUserAuthenticationRequired(false)` — deliberate: the assistant must read the key the
  instant the gesture fires, without a biometric prompt.

**Why not `EncryptedSharedPreferences`?** `androidx.security:security-crypto` is
deprecated, and the AndroidX guidance for new code is to use the Keystore directly. This
class is that, with a minimal surface area.

Backups are excluded at two levels: `android:allowBackup="false"`, and
`data_extraction_rules.xml` excludes every domain from both cloud backup and
device-to-device transfer.

### Network

`HttpSupport` is deny-by-default:

- **Redirects are not followed.** A provider could otherwise bounce a request — with its
  `Authorization` header — to a third-party host. The resulting error explains why.
- **Cleartext is refused** unless the profile opts in *and* the host is loopback or in a
  private range. The manifest permits cleartext so local Ollama can work at all; the
  code-level check is the real gate and is stricter than the XML could express.
- **No authorization header is ever logged.** `redactedHeaders()` exists so call sites
  cannot do it accidentally.
- One host only: the configured endpoint. There is no analytics, crash reporting, ad or
  update-check traffic.

### Exported components

| Component | Exported | Protection |
|---|---|---|
| `MainActivity` | yes | launcher only |
| `LenshiftVoiceInteractionService` | yes | `BIND_VOICE_INTERACTION` (role-granted) |
| `LenshiftRecognitionService` | yes | `BIND_RECOGNITION_SERVICE` |
| `LenshiftVoiceInteractionSessionService` | **no** | platform-only |
| `CaptureActivity` | **no** | internal |
| `AccessibilityCaptureService` | yes | `BIND_ACCESSIBILITY_SERVICE` |
| `LenshiftTileService` | yes | `BIND_QUICK_SETTINGS_TILE` |
| `ShareTargetActivity` | yes | `ACTION_SEND` only |

No component accepts a caller-supplied file path or URL. `ShareTargetActivity` reads a
`content://` URI through `ContentResolver` and decodes it to a bitmap; nothing is written.

---

## 8. Performance

Measured on the emulator, not estimated:

| Segment | Measurement | Where it comes from |
|---|---|---|
| Activation → overlay visible | ~1.4 s cold, ~0.4 s warm | logcat timestamps |
| Activation → screenshot ready | delivered in the same `showSession` call | `Lenshift/Trigger` log |
| Selection → request dispatched | OCR-dependent (crop only) | `OcrResult.durationMs` |
| Request → first token | shown live in the response sheet | `ConversationState.firstTokenMs` |
| Full response | shown live in the response sheet | `ConversationState.totalMs` |

The last three are surfaced to the user in the expanded response sheet rather than being
collected into a benchmark file — real numbers from real requests, not fabricated ones.

### Choices that keep it fast

- The bitmap is handed over out-of-band, never through an `Intent` (`TransactionTooLarge`).
- No full-screen recompression: only the crop is encoded, once.
- No upscaling — `ImageEncoder` only ever scales down.
- Compose `MarkdownText` parses blocks once per text change and re-renders only the last
  block while streaming.
- The OCR engine is created lazily and reused.
- No background service runs between invocations; the assistant session is created on
  demand and destroyed on hide.

---

## 9. Testing strategy

| Layer | Tests | What it proves |
|---|---|---|
| JVM unit | 81 | Geometry and canvas↔bitmap mapping (incl. letterboxing), tap-lens bounds, session-reuse state, endpoint/cleartext policy (incl. model discovery), per-element settings parsing, reasoning mapping, prompt-injection defence, body construction per protocol |
| Instrumented | 57 | Real HTTP against a mock provider, the capture UI (selection, no-selection privacy guard, tap lens), session lifecycle, settings Back navigation |

The instrumented tests run against `tools/mock_provider.py`, which speaks four protocols
and can be told to fail in specific ways (`auth-fail`, `not-found`, `rate-limit`,
`server-error`, `redirect`, `reasoning-only`, `slow`). It records every request so tests
assert on what actually went over the wire — including that a slashed model id is not
rewritten and that a non-vision model never receives an image part.

Tests **skip** rather than fail when the mock server is unreachable, so the suite is green
on a physical device.

---

## 10. Known limitations

Recorded honestly; these are not oversights.

1. **Nav-handle long-press is not Lenshift's on Pixel-class devices** while Circle to
   Search is enabled. SystemUI owns that gesture. The tile is the reliable trigger.
2. **`recognitionService` is required**, so an inert stub is declared. It never records
   audio, but it is visible in the manifest.
3. **Assistant screenshots are not guaranteed.** Only one AVD was tested. A null bitmap
   falls through to accessibility, then MediaProjection.
4. **MediaProjection still needs per-session consent on Android 14+.** This is platform
   policy and is not worked around.
5. **OCR is English-only** in this build. Adding a language means adding a
   `.traineddata` file and a picker entry.
6. **Word-level OCR boxes are approximate** (proportional across the recognised line).
7. **OEM behaviour is untested.** Samsung S Pen Air actions, OnePlus, Xiaomi and
   three-button navigation were not available on the test device.
8. **No conversation persistence across invocations.** Each selection is a fresh
   exchange; history is opt-in and stores only the question and an answer preview.
9. **Single-module build.** Fine at this size; would be split if the app grew.
10. **Not behaviourally equivalent to Circle to Search.** Lenshift reaches the same kind of
    entry point through public APIs. Google's selection rules and AI pipeline were not
    reverse engineered and are not imitated.

---

## 10a. Stabilization audit (October 2026)

A full audit found defects that the earlier test suite did not catch, because it exercised
components in isolation and on a 1:1 bitmap. Every fix below has a regression test or a
recorded device verification.

| Area | Defect | Effect |
|---|---|---|
| Selection | Tap compared canvas coordinates against bitmap-space OCR boxes | Wrong word selected on any scaled screenshot |
| Selection | Box outline drawn in bitmap space on the canvas | Visible box did not match the crop |
| Privacy | Empty selection fell back to the full screenshot | A stray tap uploaded the whole screen |
| State | `singleTask` re-entry kept the previous screenshot and answer | Old screen could be submitted with a new request |
| Navigation | No back stack; system Back exited settings | Back from any sub-screen left the app |
| Layout | No system-bar insets on settings | Header under the status bar |
| Settings | Share row only wrote an onboarding flag | Looked like an action, did nothing |
| Settings | Provider editor state not keyed to the profile | Could carry one provider's fields into another |
| Capture | MediaProjection missing FGS, callback and correct size | "Capture screen once" never worked on Android 14+ |
| Security | Model discovery skipped the endpoint policy | API key sent in cleartext to a public host |
| Network | Stop did not cancel the HTTP call | Provider kept generating and billing |
| State | Late events and a superseded `finally` overwrote state | Stop could stop working for the rest of the session |
| Persistence | One malformed provider entry reset all settings | Every saved provider silently lost |

### Is the architecture appropriate?

Yes, with the trade-offs stated in §2 and §10. Re-examined against the requirement — a
privacy-respecting, user-controlled replacement for screen search — the alternatives are
worse:

- **A floating bubble / overlay service** needs `SYSTEM_ALERT_WINDOW` and still cannot read
  pixels without MediaProjection or accessibility. More permissions, no capability gained.
- **Accessibility as the primary mechanism** removes all prompts but is a broad grant that
  Play policy restricts. It stays opt-in.
- **The contextual-search role** that Circle to Search uses is privileged and cannot be
  held by a third-party app; the role dump on a test device confirms it.

The assistant role is therefore the only public, prompt-free, per-activation path to a
real screenshot. The chain of fallbacks exists because that role does not own the
nav-handle gesture on Pixel-class devices.

---

## 11. Build and verify

```bash
# Unit tests (81)
./gradlew :app:testDebugUnitTest

# Instrumented tests (57) — start the mock provider first
python tools/mock_provider.py 8787 &
adb reverse tcp:8787 tcp:8787
./gradlew :app:connectedDebugAndroidTest

# Install and make Lenshift the assistant. Uninstall first: `adb install -r` was observed
# leaving a stale base.apk in place, so verify with sha256sum after installing.
adb uninstall dev.lenshift.app.debug
adb install -g app/build/outputs/apk/debug/app-debug.apk
adb shell cmd role add-role-holder android.app.role.ASSISTANT dev.lenshift.app.debug
adb shell input keyevent KEYCODE_ASSIST

# Release
./gradlew :app:assembleRelease
```
