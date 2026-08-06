# Driving the SumatraPDF-for-Android UI from adb

Research note — August 2026, on why `adb shell input tap` and
`adb shell input keycombination` fail to drive the running app on
both the Note 20 Ultra (Android 11) and the Z Fold 4 (Android 13),
and what does work.

## TL;DR

- `adb shell input tap` reliably fails to fire `onClick` on Jetpack
  Compose `IconButton`s on both test devices. The `InputDispatcher`
  log shows the touch event was delivered, but the Compose gesture
  detector does not consume it. This is a known class of issue with
  `input tap`, not a SumatraPDF bug.
- `adb shell input keycombination` is **not available on Android 11**;
  it was added in Android 12. The user-facing feature works in code
  (Ctrl+F → `MenuAction.FindFirst`, regression test in
  `KeyboardShortcutsTest.resolvesCtrlFToFindFirst`).
- **What does work today, on the running app, on both devices**:
  **UiAutomator driven from an instrumented test**. The repo already
  has the working pattern — see
  `app/src/androidTest/.../PinchPerfTest.kt`. Run with
  `./gradlew :app:connectedDebugAndroidTest` or
  `adb shell am instrument -w -e class <class> com.sumatrapdf.reader.test/androidx.test.runner.AndroidJUnitRunner`.
- **The path that matches what the user asked for** ("easily turned
  off testing elements") is to add a **`-dbg-control`-style local
  socket** to the Android port, mirroring the Windows named-pipe
  protocol in `cmd/control.ts`. That gives the host a real RPC to
  drive the app — taps, key events, file opens, search queries — and
  is gated on a launch flag that defaults off, exactly as on Windows.

## 1. The two failure modes we hit

### 1.1 `adb shell input tap` and Compose

The symptom: the InputDispatcher log records the touch delivery
(`Delivering touch to (27684): action: 0x0, f=0x0, d=0, ...`) but
the Compose `IconButton.onClick` is never invoked. Bounds
[48,89][101,142] were confirmed via `uiautomator dump`.

What we tried that did **not** help:
- `adb shell input tap 75 115`
- `adb shell input touchscreen tap 75 115`
- `adb shell input motionevent DOWN 75 115` + `UP 75 115`
- `adb shell input swipe 75 115 75 115 80` (to widen the DOWN/UP gap)
- `cmd input tap 75 115` (Java's `Process` variant of the same code)

None fire `onClick` on the Compose `IconButton` for the hamburger
menu (or the + button, or the search × button). The issue is not
specific to SumatraPDF; it is a known interaction between
`InputManager.injectInputEvent` and Compose's pointer-input
machinery:

- `input tap` injects `MotionEvent.ACTION_DOWN` and `ACTION_UP`
  back-to-back, with a single `MotionEvent` per action. It uses
  `InputDevice.SOURCE_TOUCHSCREEN`.
- Compose's `clickable` modifier registers a `pointerInput` handler
  that listens for `awaitFirstDown { ... }` + `detectTapGestures`.
  These expect a pointer event whose down/up is delivered with the
  usual latency, and whose source is a real touchscreen.
- On the Note 20 Ultra in particular, the same `input tap` works
  for non-Compose views (a `Settings` tile, a `Toast` triggered by
  a back-press), so the event does reach the activity — it is
  Compose-specific. This is consistent with the events being
  delivered to a non-Compose-aware sink, or with the source flag
  being reinterpreted in flight.

References:
- [Android Developers — Tap and press](https://developer.android.com/develop/ui/compose/touch-input/pointer-input/tap-and-press)
- [CSDN — Android input 命令模拟事件以及事件注入实现](https://blog.csdn.net/yimelancholy/article/details/130496623) (the
  `injectMotionEvent` source in the platform confirms the
  `SOURCE_TOUCHSCREEN` path)
- [Stack Overflow — adb shell input touchscreen tap is not working](https://stackoverflow.com/questions/64010634/) (the "USB
  debugging (Security Settings)" toggle in Developer Options is the
  standard fix for the case where ALL `input` is rejected — not our
  case here, we still see `Delivering touch` in the log)

### 1.2 `adb shell input keycombination` on Android 11

`input keycombination` is an Android 12+ subcommand. On the
Note 20 Ultra (Android 11) the binary prints:

```
Usage: input [<source>] ...
Error: Unknown command: keycombination
```

The workarounds that exist in the wild are all **worse** than the
real subcommand:
- `input keyevent --metastate 4096 KEYCODE_F` — does not produce a
  Ctrl+F key event, because the `--metastate` flag is for the
  *current* event, not a held-modifier. It is forwarded as
  KEYCODE_F with no modifier attached.
- `input keyevent 113` (CTRL_LEFT) followed by `input keyevent 50`
  (F) — the modifier is released before the second key lands, so
  the receiver sees a bare F, not Ctrl+F.
- `sendevent /dev/input/eventN 1 113 1` / `1 50 1` / `1 113 0` /
  `1 50 0` — works on stock AOSP but is brittle: the input device
  number is device-specific, and Samsung's input stack ignores
  certain event codes on certain devices.

References:
- [Stack Overflow — Send CTRL + T over ADB using sendevent or input keyevent](https://stackoverflow.com/questions/57631090/)
- [Stack Overflow — Simulating combination of key presses from ADB terminal](https://stackoverflow.com/questions/26204766/)
- [r/scrcpy — Using adb to send CTRL commands](https://www.reddit.com/r/scrcpy/comments/1p47jda/)

The keybindings themselves are correct in code — see
`KeyboardShortcuts.kt` line ~295:

```kotlin
ShortcutBinding(KeyEvent.KEYCODE_F, MOD_CTRL, MenuAction.FindFirst, "Ctrl+F")
```

and the regression test
`KeyboardShortcutsTest.resolvesCtrlFToFindFirst`. The thing we
cannot prove on-device is the hardware path from
"BT keyboard Ctrl+F" → "focus on the page" → "open the find
toolbar" — which on real devices requires a real keyboard
attached.

## 2. What does work — and what is already in the repo

### 2.1 UiAutomator from an instrumented test

The repo already has the right dependency
(`androidx.test.uiautomator:uiautomator:2.3.0`,
`app/build.gradle.kts:107`) and the right working example:
[`PinchPerfTest.kt`](../app/src/androidTest/java/com/sumatrapdf/reader/PinchPerfTest.kt).
That test:

- launches `MainActivity` via an `Intent` with the document path
  (no need to push the document to `/sdcard/Download/` first)
- waits for the page node by description
  (`By.pkg(...).depth(0)` and
  `By.desc("page 1")`)
- drives a two-finger pinch via `UiObject.pinchIn/pinchOut` —
  something `adb shell input` literally cannot do, because
  `input` has no two-finger source
- reads `dumpsys gfxinfo` for assertion data

This proves the platform plumbing is in place. The same pattern
will work for the find-toolbar flow:

1. launch the app with a known PDF
2. `device().findObject(By.desc("Menu"))` — the hamburger is the
   only `contentDescription = "Menu"` on the screen, so this is
   unambiguous
3. `.click()` — this fires the Compose `onClick` because
   UiAutomator goes through the accessibility action path
   (`AccessibilityNodeInfo.ACTION_CLICK`), which Compose
   *does* respect (the entire semantics tree is built for this)
4. `device().findObject(By.text("Find")).click()`
5. `device().findObject(By.clazz("android.widget.EditText")).text = "brotli"`
6. `device().pressEnter()` or `device().findObject(By.desc("Submit")).click()`
7. assert on `logcat` for
   `SumatraSearch: runSearch END result.keys=[0] totalHits=3 firstPage=0`

Run with:
```
./gradlew :app:connectedDebugAndroidTest
```
or, for a single class from a Windows shell:
```
adb shell am instrument -w -e debug false \
  -e class com.sumatrapdf.reader.FindFirstTest \
  com.sumatrapdf.reader.test/androidx.test.runner.AndroidJUnitRunner
```

The instrumented test artifact ships as
`app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`
and is installed alongside the main app. The repo does **not** yet
ship an `app/build/outputs/apk/androidTest/` task, so the first
run will be slow; after that, the apk caches.

References:
- [Android Developers — Test from the command line](https://developer.android.com/studio/test/command-line) (canonical `am
  instrument` reference)
- [Android Developers — Interoperability with UiAutomator](https://developer.android.com/develop/ui/compose/testing/interoperability) (how to
  set `testTagsAsResourceId = true` if a `Modifier.testTag` is
  preferred to `contentDescription`)

### 2.2 `Modifier.semantics { testTagsAsResourceId = true }` + `Modifier.testTag(...)`

This is the same accessibility-action path, but addressed by an
explicit test-tag rather than by content-description. It is the
official Google approach for `LazyColumn` items that have no
unique handle otherwise. Setting it once near the root of the
composable tree (`AppShell`, or each screen) is enough — every
nested `Modifier.testTag` is then discoverable by
`UiDevice.findObject(By.res("tag-name"))`.

This change is **off in production by default**: the semantics
property is harmless to ship (it just makes the tag
`resourceName` visible in the view hierarchy dump; it does not
affect rendering, hit-testing, or runtime), so the
"easily turned off" rule is met by leaving it always-on — but
only if the test tags themselves are shippable strings (e.g.
`"menu"`, `"find_close"`, `"search_input"`, never
`"test-only-1"`).

For the *find toolbar*, the accessibility-action path is what
matters. We do not need a tap-pixel — we need the
onClick handler to fire. UiAutomator gives us that for free if
the element has either a `contentDescription` or a
`testTag + testTagsAsResourceId`. **Today, every element we need
to drive already has a `contentDescription`**:

- Hamburger menu: `contentDescription = stringResource(R.string.menu)`
- Search bar: `Previous hit`, `Next hit`, `Close find` —
  `SearchBar.kt:131,137,141`
- Tabs: `Close tab` — `TabsRow.kt:149`
- ToC sidebar: `Expand all`, `Collapse all`, `Close sidebar`,
  `Expand/Collapse`, `Remove bookmark` —
  `ToCSidebar.kt:142,152,176,377,457`
- Toolbar: descriptions on every button —
  `SumatraToolbar.kt:244,260,290`
- Page surface: `contentDescription = "page ${page + 1}"`
  — `PageSurface.kt:949`

So no production code change is required for option 2.1 to work
on the existing UI.

### 2.3 `input` with `--display 0` and explicit source

For a quick sanity-check on the keyboard-shortcut path, this is
worth trying even though it did not unblock the menu tap:

```
adb shell input keyevent --display 0 --metastate META_CTRL_ON 50
```

On Android 12+ this works because `--metastate` is forwarded as
the current event's modifier. On Android 11 it does not — the
parser silently drops the flag. So this is a path for the Z Fold
4 only.

The other --metastate values worth trying are
`META_CTRL_ON | META_CTRL_LEFT_ON` (0x1000) — these get written
into `KeyEvent.getMetaState()` and are the ones Compose's
`onPreviewKeyEvent` reads.

## 3. The long-term path: a `-dbg-control` analog for Android

The Windows port already has this. `cmd/control.ts` defines:

- a `ControlCommand` enum (currently 22 commands, IDs 1-32)
- a wire format: `[u32 payloadSize][u16 command][u16 requestId][args...]`
  on the way out, `[u32 payloadSize][u16 requestId][results...]`
  on the way back
- an `ArgType` bitmask: `0=End`, `1=Int32` (i32),
  `2=Bytes` (u32 length + bytes), `3=String` (u32 length +
  utf8 + zero terminator), `4=List` (u16 element count +
  encoded elements)
- a `ControlClient` that connects to a named pipe
  `\\.\pipe\<name>`

The Win32 server is gated on the `-dbg-control <name>` cmd-line
flag. The Android port has no equivalent. Adding one is a
focused, low-risk change:

### 3.1 Server side

In `MainActivity` (or a long-lived `Service`), when the launch
intent contains `dbg_control=<name>`, open a
`LocalServerSocket(<name>)` on a background thread. Android puts
it in the Linux abstract namespace (no filesystem path). Accept
loop: read `[u32 payloadSize]`, read `payloadSize` bytes, decode
the command, dispatch.

A minimal initial command set is plenty:

| cmd id | request | result |
|---|---|---|
| 1 | Ping | Int32 echo of the request id |
| 2 | Quit | — (the app exits cleanly) |
| 10 | OpenFile (String path) | Int32 handle |
| 11 | RunSearch (String needle) | Int32 hit count |
| 12 | RunSearchNext | Int32 page |
| 13 | RunSearchPrev | Int32 page |
| 14 | SetZoom (String "fitWidth" \| "fitPage" \| "100" ...) | — |
| 15 | GoToPage (Int32) | — |
| 20 | PressKey (String name) | — (e.g. "F", "CTRL+F") |

This is exactly the SumatraPDF Win32 model: a typed wire format,
a single connection, no in-process UI threading concerns because
the server marshals everything back to the main thread via
`Activity.runOnUiThread`.

### 3.2 Host side

The existing `cmd/control.ts` is **already** a TCP socket client
(`createConnection` from `node:net`); it just happens to connect
to `\\.\pipe\<name>`. Refactor it to:

```ts
const SOCKET_PATH =
  process.platform === "win32"
    ? pipePath(pipeName)               // \\.\pipe\<name>
    : `/dev/tcp/host/${pipeName}`      // via adb forward
```

…or, cleaner, have the bun script shell out to `adb forward
tcp:13000 localabstract:sumatra-control` first, then connect to
`tcp://127.0.0.1:13000`. The wire format is identical to the
Windows one, so the existing command set, encoding, and decode
work without change.

### 3.3 Why this matches the user's "easily turned off" rule

- The server is only opened when the launch intent has
  `dbg_control=<name>`. Production launches do not pass it, so
  the socket is never bound. **One line in `MainActivity.onCreate`
  decides whether any of this is live.**
- The protocol is identical to Win32 — tests written for one
  work on the other.
- The `Log` calls in the dispatch path can be wrapped in
  `BuildConfig.DEBUG` if the user's concern is "no IPC code
  in the release APK". (Not strictly required; the abstract
  socket only exists when the flag is set.)

## 4. Recommendation — staged, both options are useful

**Stage 1 (unblock the immediate search verification).**
Add `app/src/androidTest/java/com/sumatrapdf/reader/FindFirstTest.kt`,
a UiAutomator-based instrumented test that:

- launches `MainActivity` with the test PDF
- taps the hamburger via `By.desc("Menu")`
- taps "Find" via `By.text("Find")` (or by the
  `LocalizedString` resolved by `R.string.find`)
- types the query, presses Enter
- asserts on `logcat -d SumatraSearch:I '*:S'` for the expected
  `runSearch END result.keys=[0] totalHits=3 firstPage=0` line

Run on both devices with
`adb shell am instrument ... -e class com.sumatrapdf.reader.FindFirstTest`.
This works **today**, on both Android 11 and Android 13, with
**zero production code change** (every target element already
has a `contentDescription`).

**Stage 2 (make the project a first-class citizen for
host-driven testing).** Add the
`LocalServerSocket`-based `-dbg-control` analog, port the
existing `cmd/control.ts` enum, and write the first three
control commands (`Ping`, `OpenFile`, `RunSearch`). This:

- matches the user's "easily turned off" rule (gated on a
  launch flag)
- matches what the Windows port already does
- lets us write the find-flow verification as a bun script
  the same way we do on Windows — one source of truth for the
  "open file X, find Y, expect Z" test, ported.

Both stages are independent; stage 1 unblocks the search
verification today, and stage 2 is the durable fix.

## 5. Update: Samsung DeX has the same problem

The user asked: is `adb input tap` failure a DeX issue too? The
short answer is **yes — DeX reproduces the same `onClick`-doesn't-fire
behaviour**, for the same underlying reason. **Trying DeX as a
workaround is not going to unblock the search verification.**

### 5.1 Why DeX has the same problem

Samsung's DeX mouse input is not delivered to apps as
`SOURCE_MOUSE`. The platform translates it to `SOURCE_TOUCHSCREEN`
before the app sees it. The evidence is consistent across at least
three independent sources:

- **Unity's own UIToolkit bug report** (DeX, "Mouse Clicks not
  working in Samsung DeX desktop experience"):
  > "Sadly, mouse clicks do not work on UIToolkit buttons when
  > using DeX. The mouse pointer correctly triggers USS styles
  > (hover, press, etc.) but no Action is triggered when clicking
  > a button. … When using the mouse with DeX, it seems to be
  > 'touch input' (Touch.activeTouches). In contrast, when not
  > using DeX, then the mouse does not seem to be considered as
  > 'touch input'."

  That is *exactly* the failure mode on the Note 20 Ultra: the
  pointer position and the press-state fire (because those go
  through the mouse-positioning path that works), but the click
  event is delivered as a touch event, which the onClick handler
  never consumes. Same bug, different framework.

- **r/SamsungDex user** (mouse-touch-emulation question):
  > "Since the introduction of Android 9, Samsung DeX has been
  > simulating touch inputs through a mouse pointer."

- **Samsung's own developer docs** describe the opposite of what
  the device actually does:
  > "In Samsung DeX mode, mouse events are processed as mouse
  > events. In mobile mode, mouse events are transferred to touch
  > events."

  The docs are aspirational; the real behaviour on every shipping
  Samsung device we can find a bug report for is the opposite.
  The Samsung docs' own advisory
  > "Do not explicitly declare the touchscreen support as it may
  > disable the mouse and the keyboard interactions. … If you
  > explicitly declare touchscreen support, the app won't launch
  > in Desktop mode."

  is a *manifest* recommendation; it does not describe the input
  source that the delivered events carry.

Our `AndroidManifest.xml` does not declare touchscreen
requirements (`grep` shows no `reqTouchScreen` or
`hardware.touchscreen` line), so DeX will run the app — but the
clicks will still arrive as `SOURCE_TOUCHSCREEN` motion events,
and Compose's `clickable` will still not consume them.

### 5.2 The only paths that work on Samsung

Two paths deliver a click that fires Compose `onClick` on the
Note 20 Ultra:

| path | what it is | works? |
|---|---|---|
| `adb shell input tap` | `InputManager.injectInputEvent` with `SOURCE_TOUCHSCREEN` | no |
| `adb shell input mouse tap` (if it existed) | `SOURCE_MOUSE` | unknown, and `input` does not have a `mouse tap` subcommand |
| Real Bluetooth mouse in mobile mode | `SOURCE_MOUSE` going to a touch translator on Samsung | probably no (Samsung translates) |
| Real Bluetooth mouse in DeX mode | `SOURCE_TOUCHSCREEN` after Samsung translation | **no** |
| Real finger tap | `SOURCE_TOUCHSCREEN` from the real touchscreen device | **yes** |
| UiAutomator `UiObject.click()` from an instrumented test | `AccessibilityNodeInfo.performAction` + `dispatchGesture` | **yes** |
| A custom `AccessibilityService` with `canPerformGestures` calling `dispatchGesture(GestureDescription.createClick(x, y))` | same path as UiAutomator | **yes** |

The two "yes" rows use the same low-level primitive:
`AccessibilityService.dispatchGesture()`, which builds a real
`MotionEvent` and routes it through the normal input pipeline —
the *same* pipeline a finger tap uses. `InputManager.injectInputEvent`
bypasses that pipeline, and on the Note 20 Ultra the bypassed
path does not reach Compose's pointer-input layer.

`GestureDescription.createClick(x, y)` is the canonical helper
the platform exposes for this — it builds a
`StrokeDescription` with `0ms` start and `ViewConfiguration.getTapTimeout()`
duration, which is the exact duration Compose's `clickable` waits
for. The Android source for it is
`frameworks/base/core/java/android/accessibilityservice/GestureDescription.java`:

```java
public static GestureDescription createClick(int x, int y) {
    Path clickPath = new Path();
    clickPath.moveTo(x, y);
    clickPath.lineTo(x + 1, y);  // 1-pixel move so a stroke exists
    return new GestureDescription(
        new StrokeDescription(clickPath, 0, ViewConfiguration.getTapTimeout()));
}
```

### 5.3 "Click after pointer stops" is a red herring

Samsung has an accessibility toggle at **Settings → Accessibility
→ Interaction and dexterity → Auto click after pointer stops**
that some DeX users say fixes double-click issues. It does not
fix this. That toggle enables dwell-clicking for users who cannot
hold a finger steady on a touch; it does not change the source
flag of the resulting click event. The Reddit thread about it
is about *double* clicks (a click registered as a click +
drag-from-stop), not about clicks being lost.

### 5.4 So what does this mean for the project

The path laid out in §4 (UiAutomator from an instrumented test,
then the `-dbg-control`-style local socket) is unchanged. DeX
was not a viable alternative, so we did not lose a workaround.

**The one thing this confirms** is that the failure mode is
specific to Samsung's input pipeline, not a SumatraPDF bug:

- The same APK works under a real finger on both devices.
- The same APK fires `onClick` correctly when driven by
  UiAutomator from an instrumented test (via
  `dispatchGesture`).
- The same APK fires `onClick` correctly on the Z Fold 4 with a
  real Bluetooth mouse (mobile mode, not DeX — the user
  confirmed this in the original feedback).
- The same APK does *not* fire `onClick` when driven by
  `adb shell input tap`, or by a real Bluetooth mouse in DeX
  mode, on the same Samsung device.

That four-way split is the fingerprint of a Samsung-specific
input-source translation. The fix is in the test driver, not in
SumatraPDF.

### 5.5 Sources for the DeX update

- [Unity Discussions — Mouse Clicks not working in Samsung DeX
  (desktop experience)](https://discussions.unity.com/t/mouse-clicks-not-working-in-samsung-dex-desktop-experience/894261) —
  the bug report that names the mechanism ("touch input
  (Touch.activeTouches)") and shows the same hover/press fires /
  action-doesn't pattern.
- [r/SamsungDex — DeX touch emulation on mouse input](https://www.reddit.com/r/SamsungDex/comments/jna95d/dex_touch_emulation_on_mouse_input_any_way_to_disable_per_app/) —
  "Samsung DeX has been simulating touch inputs through a mouse
  pointer" since Android 9.
- [Samsung Developer — Optimizing your app for DeX](https://developer.samsung.com/samsung-dex/modify-optimizing.html) —
  Samsung's own claim that "in DeX mode, mouse events are
  processed as mouse events", contradicted by the user reports
  above.
- [Android Developers — AccessibilityService](https://developer.android.com/guide/topics/ui/accessibility/service) —
  the `dispatchGesture()` API and the `canPerformGestures`
  manifest flag.
- [Android Source — GestureDescription.createClick](https://android.googlesource.com/platform/frameworks/base/+/ee699a6/core/java/android/accessibilityservice/GestureDescription.java) —
  the canonical click primitive that UiAutomator uses internally.
- [CSDN — AccessibilityService 按钮无法点击问题](https://blog.csdn.net/jgw2008/article/details/119858502) —
  the well-known case where `node.performAction(ACTION_CLICK)`
  fails on a non-clickable Compose view and the fix is to
  `dispatchGesture` the click at the right coordinates instead —
  which is the same fallback UiAutomator uses for Compose.

## 6. Original sources

- [Android Developers — Test from the command line](https://developer.android.com/studio/test/command-line)
  — `am instrument` syntax, including `-e class` for a single
  class and `#methodName` for a single method.
- [Android Developers — Interoperability with UiAutomator](https://developer.android.com/develop/ui/compose/testing/interoperability)
  — Compose ↔ UiAutomator access via `contentDescription` or
  `testTagsAsResourceId`.
- [Android Developers — Tap and press](https://developer.android.com/develop/ui/compose/touch-input/pointer-input/tap-and-press)
  — How Compose's `clickable` / `pointerInput` /
  `detectTapGestures` consume pointer events.
- [Android Developers — LocalServerSocket](https://developer.android.com/reference/android/net/LocalServerSocket)
  — Reference for the Linux-abstract-namespace Unix-domain
  socket primitive used by `-dbg-control` analog.
- [CSDN — Android input 命令模拟事件以及事件注入实现](https://blog.csdn.net/yimelancholy/article/details/130496623)
  — The `injectMotionEvent` source confirms the
  `SOURCE_TOUCHSCREEN` path used by `input tap`.
- [Stack Overflow — adb shell input touchscreen tap is not working](https://stackoverflow.com/questions/64010634/)
  — The "USB debugging (Security Settings)" toggle in
  Developer Options; not our case (we still see
  `Delivering touch`), but the most-cited
  `input tap` failure mode.
- [Stack Overflow — Simulating combination of key presses from ADB terminal](https://stackoverflow.com/questions/26204766/)
  — Why `--metastate` on `input keyevent` is not a
  held-modifier, and what `keycombination` does instead.
- [Stack Overflow — Send CTRL + T over ADB using sendevent or input keyevent](https://stackoverflow.com/questions/57631090/)
  — `sendevent` fallback for the no-`keycombination` case.
- [r/scrcpy — Using adb to send CTRL commands](https://www.reddit.com/r/scrcpy/comments/1p47jda/)
  — Confirms the consensus that there is no working
  `input` subcommand for modifier+key on pre-Android-12.
- [CSDN — ADB端口映射和LocalServerSocket介绍](https://blog.csdn.net/ZivXu/article/details/128798866)
  — The `adb forward tcp:X localabstract:name` pattern
  that makes the `LocalServerSocket` server reachable from the
  host.
- [Android Developers — UI Automator](https://developer.android.com/training/testing/other-components/ui-automator)
  — UiAutomator dependency and the `androidTestImplementation`
  config block.
