# Fr3kChatBubble — Full Technical Review

**File:** `app/src/main/java/com/mcpintelligence/fr3k/hud/overlays/Fr3kChatBubble.kt`
**Nodes:** 660 lines, 1 class + 1 private helper (`Space`)
**Changes applied:** 2026-09-06 — heading stripped to `"CHAT"`, M/TTS moved to toolbar row below transcript

---

## Table of Contents

1. Architecture & Responsibility
2. WindowManager Integration (OverlayParams)
3. Layout Hierarchy
4. Touch Handling (Drag + Resize + Pinch)
5. Input Handling
6. Lifecycle & State Management
7. Coroutines & Threading
8. Memory & Leak Analysis
9. Accessibility
10. Regression Checklist After This Change Set

---

## 1. Architecture & Responsibility

**What it does:** One `TYPE_APPLICATION_OVERLAY` window for Hermes/OpenCode chat. Has a transcript, input field, send button, model switcher, TTS toggle, drag handle, resize grip, and a particle-link anchor for the orb.

**Contract:** Implements `Fr3kOverlay` interface (show/hide/drag callbacks). Owned by `OverlayManager` via lazy init. Shared `OverlayHost` gives it a `WindowManager` reference.

**Key dependencies:**
- `Fr3kApplication.get()` (static — global singleton pattern)
- `HermesAskCommand` / `AskOpenCodeCommand` (command pattern)
- `TtsPreference` (singleton object + SharedPreferences)
- `OverlayHost` (window manager wrapper + pre-measure logic)

**Review questions:**
- [ ] `Fr3kApplication.get()` — is this a safe static? Is there a path where the Application is detached or destroyed while the overlay is up? (The overlay is held by a foreground service, so this should be fine during normal operation.)
- [ ] The overlay constructs ALL child views eagerly in `init{}` + calls `installTouch()` and `installResizeTouch()` at init time. It does not defer any view creation to `show()`. This means 660 lines of init always runs whether or not the user ever opens chat. Acceptable for a lightweight overlay, but if other overlays grow, consider lazy view inflation.

---

## 2. WindowManager Integration (OverlayParams)

**Params setup (L297–299):**
```kotlin
params = OverlayParams.forChat(300.dp(), ViewGroup.LayoutParams.WRAP_CONTENT)
```

`forChat()` delegates to `forInput()`, which creates params with:
- `TYPE_APPLICATION_OVERLAY`
- `FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_NO_LIMITS` (NOT_FOCUSABLE is OFF — needed for EditText focus)
- `SOFT_INPUT_ADJUST_RESIZE | SOFT_INPUT_STATE_VISIBLE`

**Concerns:**
- [ ] `SOFT_INPUT_STATE_VISIBLE` means the keyboard will automatically show when the overlay is first displayed. The `show()` method does NOT explicitly request focus on the input field, so the initial state is "keyboard hinted but not forced". Is the keyboard actually popping on first show, or does `SOFT_INPUT_STATE_VISIBLE` only take effect when the view requests focus? This needs device testing.
- [ ] `FLAG_LAYOUT_NO_LIMITS` means the window can extend into the display cutout area. The clamp function only handles X/Y positioning but does NOT account for the cutout (notch/punch-hole). If the user resizes the bubble very wide, it could overlap the camera area. Consider `FLAG_LAYOUT_IN_SCREEN` alone or checking the cutout inset.
- [ ] `SOFT_INPUT_ADJUST_RESIZE` means the window's height changes when the keyboard appears. The chat bubble uses a weighted transcript (weight=1) with WRAP_CONTENT input row. When the keyboard appears, the window height should shrink and the transcript should absorb the reduction. Test this on a real device — the transcript may not scroll properly if `maxLines=6` clips it during resize.
- [ ] The `OverlayHost.add()` method (OverlayHelpers.kt L96–113) pre-measures the view and REPLACES `params.height` with `view.measuredHeight` when the original is `WRAP_CONTENT`. This means the initial `WRAP_CONTENT` is resolved to a concrete pixel value BEFORE the window is added. After that, the resize grip changes `params.height` from the concrete value. However, the initial measure for `WRAP_CONTENT` height uses `View.MeasureSpec.UNSPECIFIED` for height — meaning the view will measure its ideal height. Is that ideal height correct when no keyboard is visible and the transcript is empty? Check: the bubble has ~3-4 rows of widgets at minimum. Test on a small phone.

---

## 3. Layout Hierarchy

```
root: LinearLayout (VERTICAL)
  [background = tailDrawable (rounded rect + hairline)]
  [padding 6,4,6,6]
  |-- headerRow: LinearLayout (HORIZONTAL)
  |   |-- header: TextView ("CHAT", weight=1)   ← changed from "FR3K ▸ HERMES"
  |   |-- dismiss: Button ("×", 28dp)
  |-- transcript: TextView (weight=1, maxLines=6, MONOSPACE)
  |-- spacer: View (2dp)
  |-- toolbar: LinearLayout (HORIZONTAL)          ← NEW
  |   |-- spacer: View (weight=1, pushes buttons right)
  |   |-- modelButton: Button ("M", 24dp)
  |   |-- spacer: View (4dp)
  |   |-- ttsButton: Button ("TTS●" / "TTS", 32dp)
  |-- inputRow: LinearLayout (HORIZONTAL)
  |   |-- input: EditText (weight=1, hint="ask...")
  |   |-- spacer: View (6dp)
  |   |-- send: Button ("SEND")
  |-- spacer: View (1dp)
  |-- resizeGrip: View (14dp, accent-coloured, END gravity)
```

Additionally, two views are created but NEVER ATTACHED to the tree:
- `bubble: View` (L94) — `View(ctx).apply { background = tailDrawable }`
- `tail: View` (L95–101) — similarly styled GradientDrawable

**Review questions:**
- [ ] **L94: `bubble` is created and assigned a background drawable but never added to the root.** The comment at L86–93 explains the history: this view was formerly the shape container, and the content was a separate LinearLayout sibling. The refactor folded the shape background onto the root. `bubble` appears to be a leftover that went unnoticed — it is a dead object held as a field. Confirm: is `bubble` referenced anywhere else (particle-link anchor, external accessor, etc.)? If not, delete it.
- [ ] **L95–101: `tail` is created and styled but never added to the root.** Same question. Is the tail rendered elsewhere (e.g. a separate overlay window positioned alongside the bubble), or is this dead code from an earlier iteration where the tail was a separate View? The doc comment says "One WindowManager surface... Has its own tail" but the tail is not in the view tree.
- [ ] **L57: both `bubble` and `tail` are stored as `private val` fields.** If they're dead, removal saves ~20 lines and two drawable allocations.
- [ ] **Toolbar alignment:** the toolbar uses `weight=1` spacer on the LEFT to push M/TTS to the right. Combined with `Gravity.CENTER_VERTICAL`, this is correct. But the toolbar has zero padding — the M button sits flush against the right edge (followed by the spacer and TTS button). On narrow screens this may look cramped against the window edge. Consider a tiny `setPadding(0, 0, 2.dp(), 0)` so the buttons don't visually touch the bubble edge.
- [ ] **Resize grip position:** the grip sits inside the vertical LinearLayout, between the input row and the window bottom, with `END` gravity. On WRAP_CONTENT height, the grip sits immediately below the input row. On a sized window, it stays at the bottom because `sp2` is only 1dp. This is correct for a LinearLayout approach, but note the grip does NOT overlay the content — it occupies layout space. If the window is at minimum height, the grip eats into the input row's space. Verify minimum height accommodates all rows + grip.

---

## 4. Touch Handling

### 4a. Drag (`installTouch()`, L364–429)

**Architecture:**
- A `ScaleGestureDetector` for pinch-to-zoom wraps the whole listener.
- One shared `listener()` function returns an `OnTouchListener` that handles:
  - Scale events (any pointer count >= 2) → consumed by ScaleGestureDetector
  - Single-finger drag on root / header → delta-based window move
  - Tap-through for child buttons (dismiss, send, model, TTS) via return-false-on-down-then-check-dragging

**Concerns:**

- [ ] **L381: `resizeDetector.onTouchEvent(event)` is called for EVERY touch event, including single-finger.** The `ScaleGestureDetector` internally stores state. This is standard Android pattern, but worth noting that dragging the window always feeds events through the scale detector even though scale only fires on 2+ pointers. No known bug, but if a `ScaleGestureDetector` leaks memory (some implementations do), it stays alive as long as the overlay.
- [ ] **L393: `if (event.pointerCount >= 2) return@OnTouchListener true`** — After the scale detector processes the event, we return true immediately for multi-touch. This prevents the drag logic from seeing it. Correct.
- [ ] **L395–403 (ACTION_DOWN):** Returns `false` — critical for letting child views (Button, EditText) receive their own click events. The first DOWN is always passed through. The downside: if the DOWN lands on a child button, that button receives the DOWN, and then on MOVE the overlay claims the gesture by returning `true` from onTouch — but the child has already received its DOWN. For `Button`, this means the button's pressed state activates momentarily even when the gesture becomes a drag. This is the standard Android pattern for draggable containers with interactive children. Acceptable, but worth testing: does the SEND button flash-press during a drag that starts on it?
- [ ] **L408: `if (!dragging && (dx * dx + dy * dy) > 64)`** — 8px dead zone before drag activates. At 320dpi this is about 2mm. Standard feel.
- [ ] **L412–420 (ACTION_UP):** Returns `!dragging` — if it was a tap (not dragging), return false so child dispatch continues. The child's `OnClickListener` fires AFTER the parent's onTouch returns false. However: the parent's `onDragEnd()` (L344–349) has ALREADY run at this point, calling `clampToDisplay()` and `host.update()`. So a tap on the transcript area triggers a clamp-and-update even though no drag happened. This is harmless but unnecessary — consider early-returning in `onDragEnd()` if no movement occurred.
- [ ] **L427: `root.setOnTouchListener(listener())`** AND **L428: `header.setOnTouchListener(listener())`** — Two SEPARATE listener instances. Each creates its own `resizeDetector` (ScaleGestureDetector) and its own {startX, startY, dragging} closure. This means a drag that starts on the header can conflict with a drag that starts on the root — they have independent state. If the user touches down on the header, moves off it onto the root, the root's listener gets a fresh DOWN event (since each listener gets separate events). This could cause drag stutter if the finger drifts between the two views. Consider using a single touch listener on the root only (the root fills the whole bubble area, including behind the header — the header sits inside it). The `header.setOnTouchListener` is redundant unless the header text has spacing that extends beyond the root's bounds (it doesn't — LinearLayout clips to bounds). **Recommend: remove the header listener, rely on root-only drag.**

### 4b. Resize Grip (`installResizeTouch()`, L438–480)

**Mechanics:**
- Grip sits bottom-right. Drag delta applied to `params.width` and `params.height`.
- WRAP_CONTENT height is resolved to actual measured height on first drag (L448–450).
- Bounds: 180–580dp width, 260–760dp height.

**Concerns:**
- [ ] **Resize of WRAP_CONTENT height (L448–450, L461–463):** On the first drag, if `params.height == WRAP_CONTENT`, we seed from `root.height` (the currently measured height). This is correct for the initial open. However, if the user closes and reopens the bubble, and the window was resized in the previous session (now params has concrete values), WRAP_CONTENT is no longer hit. State persists in `params` across show/hide cycles (the overlay is never destroyed, only hidden). **After a resize, subsequent opens use the resized dimensions.** Is this intended? The initial default is 300dp wide × WRAP_CONTENT tall. After a resize the user gets concrete dimensions. Reopening after hide() retains those dimensions. This is consistent with the browser/terminal overlays which start with fixed sizes.
- [ ] **L460: `root.layoutParams = root.layoutParams.apply { width = newW }`** — This sets the root LinearLayout's layout params width to the new window width. The LinearLayout's `onMeasure` will then set its desired width to match. However, the root was created with a fixed `300.dp()` layout params (L252–255). After the first resize, this layout params gets overwritten to the new width. On subsequent resizes, it gets overwritten again. This is correct but redundant: `params.width` is what the WindowManager reads. Setting `root.layoutParams.width` also matters for the LinearLayout's self-measurement during `requestLayout()`. Keep this line.
- [ ] **Resize grip is bottom of the LinearLayout flow, not floating.** Because the grip is in the vertical flow (with END gravity), the window's `params.height` must be large enough to include it. The min height (260dp) is generous — the content (header ~28dp + transcript ~100dp + toolbar ~28dp + input ~36dp + grip ~14dp + padding ~12dp) fits in about 220dp. The extra 40dp provides breathing room. Good.
- [ ] **No upper bound on how small the transcript can get.** If the user drags the grip to minimum height (260dp) and the keyboard is open (SOFT_INPUT_ADJUST_RESIZE shrinks the window further), the transcript could be squished to zero height or hidden behind the keyboard. The window's resize is handled by WindowManager on keyboard show/hide — the transcript has a weight of 1 so it should absorb the delta. But the keyboard-triggered resize happens on the WindowManager side, not via our grip. **Test: open chat, focus input, keyboard appears, then resize small — does the layout survive?**
- [ ] **Resize grip has `alpha = 0.7f`** (L239). At 14dp it is already small. At 0.7 alpha it's barely visible on a dark background. Consider solid 1.0 alpha or increasing to 18dp — the browser overlay uses 14dp as well but its grip sits over the WebView (lighter area). On the dark chat bubble, the grip is hard to discover.

### 4c. Pinch-to-Zoom (`ScaleGestureDetector`, L369–385)

- Multiplies both width and height by `detector.scaleFactor`, clamped to bounds.
- Uses the same `resizeBounds()` as the grip.
- No separate listener needed — the `ScaleGestureDetector` is fed every event.

**Concern:**
- [ ] The scale factor is a ratio, and both dimensions scale proportionally. This is correct for a uniform zoom. However, the user may want to only scale width or only height — pinch doesn't allow this. The grip allows independent control. Acceptable trade-off.

---

## 5. Input Handling

### 5a. EditText (L184–209)

- Single-line, monospace, hint "ask…", IME action = SEND.
- Focus listener and click listener both pop the soft keyboard.
- `setOnEditorActionListener` for IME action triggers `onSend()`.

**Concerns:**
- [ ] **L196–208: onFocusChangeListener + onClickListener both call `showSoftInput()`.** When the user taps the input, click fires first (setting focus), then focus change fires. Two calls to `showSoftInput()` in quick succession. This is harmless (Android deduplicates) but redundant. Could simplify to only the focus listener.
- [ ] **The EditText has `isSingleLine = true`.** Long prompts will scroll horizontally. Consider allowing multi-line input for complex queries, or at least horizontally scrollable. The current single-line is intentional for compact overlay design — evaluate if the user ever types prompts > 50 chars.
- [ ] **No `maxLength` or input filter.** The prompt is sent as-is to Hermes/OpenCode. If the user pastes a very long string (e.g. a 10K character document), the coroutine will attempt to send it via the provider. The provider may have length limits. Consider a soft cap with a warning in the transcript.

### 5b. Send Button (L211–220)

- "SEND" text, accent background, triggers `onSend()`.
- The button has fixed padding 10dp horizontal, giving a ~60dp wide target. Adequate.
- `isAllCaps = false` needed because Button default is true. Correct.

### 5c. onSend() (L576–633)

**Flow:**
1. Read + trim prompt text
2. Append "you: $prompt" to transcript
3. Clear input field
4. Resolve provider: OpenCode > Hermes
5. Build `Fr3kContext` with identity + capabilities
6. Launch coroutine on `scope` (IO dispatcher)
7. Execute the command
8. Append result to transcript
9. Conditionally speak via TTS

**Concerns:**
- [ ] **L588–598: Provider resolution happens outside the coroutine but reads from `app.aiProviders` map.** This map is populated during app init and is read-only after that. Safe.
- [ ] **L602–607: Fr3kContext is built eagerly before launching.** `enabledCapabilities` reads from `app.fr3kCore.currentCapabilities()`. If this call has side effects, they run on the main thread (onSend is called from main thread via onClick). Verify `currentCapabilities()` is cheap.
- [ ] **L610–615: Result mapping.** Four sealed class branches: Ok, Failed, Cancelled, NeedsConfirmation. The `NeedsConfirmation` case sets `summary` as text — this may be confusing to the user if the system returns a confirmation prompt as display text. Consider appending " (awaiting confirmation)" or handling this case differently.
- [ ] **L618: TTS speaks the raw response text.** If the response contains markdown, code blocks, or URLs, TTS will read them verbatim ("backtick code backtick http colon slash slash..."). Consider stripping markdown before passing to `speakIfEnabled`.
- [ ] **L628–630: Exception handling.** If `execute()` throws, we append the Java class simple name. For some exceptions this is user-friendly; for others (e.g. `NullPointerException` at "someInternalMethod") it's not. Consider a catch for common types (Timeout, IOException, HTTP error) with better messages.
- [ ] **Threading:** `scope` is `SupervisorJob() + Dispatchers.IO`. The launch fires from main thread (onClick). After the coroutine completes, `appendLine()` posts to `mainExecutor` (L636). TTS is called from the IO dispatcher but `TtsPreference.speakIfEnabled()` calls `getEngine()` with `@Synchronized` and then `TextToSpeech.speak()` which is thread-safe according to Android docs. Correct.
- [ ] **Concurrent sends:** The user can tap SEND multiple times before a response arrives. Each tap appends its prompt and launches a new coroutine. The responses will interleave in the transcript in completion order, not send order. No locking or queue. If the user fires 3 prompts rapidly, responses could arrive out of order. Consider a simple request queue or disabling the send button while a request is in flight.

---

## 6. Lifecycle & State Management

### Show/Hide cycle:
```
show() → host.add() → isAttached=true
hide() → host.remove() → isAttached=false
```

- The overlay object persists in `OverlayManager` via `lazy` delegate. Never destroyed until `OverlayManager.shutdown()` calls `Fr3kChatBubble.shutdown()` → `hide()` + `scope.cancel()`.
- Position state (`bubbleX`, `bubbleY`, `params.width`, `params.height`) persists across show/hide cycles.
- `hasSeededWelcome` flag ensures welcome text shows only once per process lifetime.

**Concerns:**
- [ ] **`shutdown()` (L652–655) calls `scope.cancel()`.** This cancels all pending coroutines (ongoing sends). If the user sends a message then triggers shutdown (e.g. closing the app), the response coroutine is cancelled silently. No response will appear. This is correct cleanup behavior but the user may be confused if they sent a message and then the bubble closes before the response arrives. Consider adding a "dismissing pending requests" log or abort message.
- [ ] **No `onConfigurationChanged` handling.** If the device is rotated, the overlay position is in screen coordinates that may now be outside the new display bounds. `clampToDisplay()` runs on `onDragEnd()` and `show()` — so if the user rotates while the bubble is static, it could be off-screen until the user drags it. Consider re-clamping on configuration change via a callback.
- [ ] **No save/restore of position across process death.** The HUD service is a foreground service and typically survives, but if the process is killed, position state is lost and the bubble reopens at the default (0, 120dp). This is acceptable for v0.4.

---

## 7. Coroutines & Threading

- `scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)` — IO dispatcher for network calls.
- `SupervisorJob` means a failure in one child does not cancel siblings. Good.
- No `withContext(Dispatchers.Main)` — UI updates go through `host.context.mainExecutor.execute {}` inside `appendLine()`.
- `TTS.speak()` called from IO dispatcher — explicitly safe per Android docs (`TextToSpeech.speak()` is thread-safe).
- `scope.cancel()` in `shutdown()` — clean.

**Concern:**
- [ ] **`appendLine()` (L635–641) posts to `mainExecutor.execute {}`.** This uses the Activity/Service's main thread handler. If the service's main looper is under heavy load (e.g., processing multiple overlay updates), transcript updates could be delayed. Acceptable — the transcript is not real-time critical. However, the `mainExecutor` reference is obtained on every call via `host.context.mainExecutor`. Cache it in a field if performance-sensitive.

---

## 8. Memory & Leak Analysis

**Known retainers during life:**
- `root` view tree → held by `WindowManager` while attached → released after `hide()`
- `scope` → coroutine jobs while request in-flight
- `TtsPreference.cached` → TextToSpeech engine singleton (class-level, not chat-bubble-level — shared)
- `params` → in-memory layout params, no native ref

**Potential leaks:**
- [ ] **static `Fr3kApplication.get()`:** If the app process is killed but the `Fr3kChatBubble` instance is somehow retained (should not happen — it's owned by OverlayManager which is owned by the Service), the static ref is fine.
- [ ] **`OnTouchListener` lambdas:** They capture `this` (the `Fr3kChatBubble` instance) through the `resizeBounds()` call and the anonymous `ScaleGestureDetector.SimpleOnScaleGestureListener`. As long as the view is detached from WindowManager, the GC can collect the view → listeners → enclosing class. No leak.
- [ ] **CoroutineScope:** Not closed until `shutdown()`. If the overlay is hidden but the service is still alive (normal state), the scope stays active. A long-running coroutine (hanging HTTP request) would retain the scope. `SupervisorJob` does not prevent this — the coroutine itself must complete or be cancelled. Consider using `timeout` on the provider call.

**Leaked views:**
- [ ] `bubble` and `tail` are created but never added to the view tree. They are also never explicitly released. They accumulate in memory. If the overlay is opened/closed many times (via lazy init in OverlayManager it's only created once), this is a one-time leak of ~2 small views. If `Fr3kChatBubble` is re-created (not currently the case), these leak per instance. **Recommend removing them.**

---

## 9. Accessibility

- [ ] **contentDescription on dismiss:** "Dismiss bubble" (L120). Good.
- [ ] **contentDescription on modelButton:** "Cycle AI model (long-press for picker)" (L138). Good.
- [ ] **contentDescription on ttsButton:** "Toggle spoken responses" (L153). Good.
- [ ] **contentDescription on send:** "Send to Hermes" (L219). Good.
- [ ] **contentDescription on resizeGrip:** "Drag to resize" (L240). Good.
- [ ] **No contentDescription on root:** The root LinearLayout has no contentDescription. When a screen reader traverses the window, it will read children individually, which is acceptable for a complex container.
- [ ] **EditText has no contentDescription or label.** It has a `hint` ("ask…") which is read by TalkBack as both the label and the hint. The hint serves double duty. Acceptable for a compact overlay, but a11y best practice suggests an explicit label.
- [ ] **`head[er]` is just "CHAT"** — no contentDescription. The text is minimal and the style (bold, accent-coloured, 9sp) may be hard for low-vision users to read. Consider larger text or a11y scaling.
- [ ] **Resize grip at 14dp × 14dp is below the 48dp minimum touch target** recommended by Material Design / WCAG. The user can still drag it because drag precision is higher than tap precision, but users with motor impairments may struggle. Consider 20dp minimum or a larger invisible touch slop around the visible grip.

---

## 10. Regression Checklist (Post-Change Set)

Verify each of these on a real device (OnePlus GM1900 as reference):

**Heading:**
- [ ] Header shows "CHAT" (not "FR3K ▸ HERMES")
- [ ] Header has no M button or TTS toggle
- [ ] Dismiss × button works

**Toolbar:**
- [ ] M button and TTS toggle are visible between transcript and input
- [ ] They are right-aligned (spacer on left pushes them right)
- [ ] M button cycles model (transcript reports "fr3k: model → ...")
- [ ] M button long-press shows popup picker
- [ ] TTS toggle changes state and colour on tap

**Drag:**
- [ ] Drag from header moves the window
- [ ] Drag from empty area of transcript (not on buttons) moves the window
- [ ] Dragging from a button starts a drag (the button may flash-press)
- [ ] Tapping dismiss/send fires click without drag
- [ ] Window can't be dragged off-screen (clamped)

**Resize:**
- [ ] Resize grip drag changes width and height
- [ ] Pinch-to-zoom changes width and height proportionally
- [ ] Min width (180dp) prevents collapse
- [ ] Max width (580dp) prevents off-screen
- [ ] Reopening after resize retains the resized dimensions

**Keyboard:**
- [ ] Tapping input shows soft keyboard
- [ ] Keyboard resizes the window (SOFT_INPUT_ADJUST_RESIZE)
- [ ] Transcript scrolls when keyboard is open
- [ ] Dismissing keyboard (back button) restores window size

**Send:**
- [ ] Send button sends text to Hermes/OpenCode
- [ ] IME action send (keyboard enter) also sends
- [ ] Response appears in transcript
- [ ] TTS speaks response when enabled
- [ ] TTS skips response when disabled
- [ ] Rapid sends don't crash (responses may interleave)

**Rotation:**
- [ ] Rotating device does not put bubble off-screen
- [ ] Keyboard survives rotation

**Lifecycle:**
- [ ] Hiding and re-showing bubble preserves position + size
- [ ] Shutdown (service destroy) cancels in-flight requests
- [ ] Welcome text shows only once

---

## Summary of Action Items

| Priority | Item | Location | Description |
|---|---|---|---|
| HIGH | Remove dead `bubble` and `tail` views | L57, L94–101 | Two views created, styled, stored as fields, never attached. Dead code, minor memory leak. |
| HIGH | Verify redundant `header.setOnTouchListener` | L428 | Duplicate listener with independent state can cause drag stutter. Root-only drag is sufficient. |
| MEDIUM | Test keyboard resize + transcript weight | — | `maxLines=6` + weight=1 + `ADJUST_RESIZE` + min window height. Ensure transcript doesn't vanish with keyboard open. |
| MEDIUM | Test concurrent send ordering | L576–633 | Rapid taps produce out-of-order responses. Consider queue or disable button. |
| MEDIUM | Strip markdown before TTS | L618 | Code blocks, URLs, markdown syntax read verbatim by TTS. |
| LOW | Add contentDescription on root / input | — | a11y best practices. |
| LOW | Increase resize grip to 20dp | L237–242 | Currently 14dp, below touch target minimum. |
| LOW | Soft-cap input length | L184–209 | Prevent accidental 10K-char paste from hitting provider limits. |
| LOW | Cache `mainExecutor` ref in field | L636 | Avoid `host.context.mainExecutor` lookup on every `appendLine()`. |
| INFO | Verify `clampToDisplay` with display cutout | L352–361 | `FLAG_LAYOUT_NO_LIMITS` may overlap notch. Test on device with cutout. |
| INFO | `getSize()` deprecation warning | L355 | `display.getSize()` deprecated in API 33. `WindowMetrics` for API 33+ with fallback. |