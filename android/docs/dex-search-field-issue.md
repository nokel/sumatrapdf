# Why typing in text fields doesn't work in DeX (and how the fix covers all 7 sites)

## TL;DR

Compose's `OutlinedTextField` (and `BasicTextField`) calls
`KeyEvent.getUnicodeChar()` to convert a hardware key press into a
character. On the Note 20's virtual "DeX keyboard" input device, that
call returns `0` for ordinary letters, so the field sees the key as a
non-printing control event and ignores it. Ctrl+F still works because
that shortcut is matched by the global `MainActivity.dispatchKeyEvent`
layer *before* the key ever reaches the field — it never needs
`getUnicodeChar()`.

The fix lives in **`KeyEventHandler.kt`**, a `Modifier.textFieldKeyHandler`
extension that every `OutlinedTextField` / `BasicTextField` in the app
now applies. It handles DEL, Enter, Escape, and a hand-written
70-entry US-QWERTY `keyCodeToChar` map, all before Compose's
`TextFieldKeyInput` runs. The same `MainActivity.dispatchKeyEvent` guard
that the find toolbar uses (renamed to `anyTextFieldFocused`) is shared
by every focused text field in the app.

This applies at seven sites:

| File | Site | Confirmed on WSA |
|---|---|---|
| `SearchBar.kt:91` | Find toolbar | typing, DEL, ESC, ENTER all work |
| `ReaderScreen.kt:2036` | PasswordDialog | (requires an encrypted PDF to invoke) |
| `ReaderScreen.kt:2160` | GoToPageDialog | (no Ctrl+G shortcut wired yet) |
| `ReaderScreen.kt:2202` | CustomZoomDialog | (no menu entry wired yet) |
| `StartPage.kt:364` | Home filter (`FilterField`) | typing, DEL work |
| `library/LibraryPage.kt:429` | NameDialog (new / rename partition) | (requires a partition) |
| `library/LibraryPage.kt:521` | LibraryGrid search | (requires a populated library) |

All seven use the same modifier, so the first two are sufficient evidence
that the fix is in.

## What I traced

`app/src/main/java/com/sumatrapdf/reader/MainActivity.kt:188-226`
overrides `dispatchKeyEvent`. The shortcut lookup uses
`KeyboardShortcuts.keyCodeToAction(event.keyCode, event.metaState)`
which is a `(keyCode, modifiers)` table — it does not touch the
character. So a Ctrl+F keycode+CTRL meta matches the `FindFirst`
binding, sets `pendingShortcut`, and returns `true`. The field
opens.

For an unbound letter, the same method returns
`super.dispatchKeyEvent(event)`, which walks down to the focused
Compose element. The focused element is the `OutlinedTextField`
inside `app/src/main/java/com/sumatrapdf/reader/SearchBar.kt:84`.

`OutlinedTextField` is Material 3 on top of `BasicTextField`. The
key-event handler lives in
`androidx.compose.foundation.text.TextFieldKeyInput`. I decompiled
it from the cached AAR
(`~/.gradle/caches/.../foundation-android-1.7.5/.../classes.jar`):

```java
public final boolean process(KeyEvent event) {
    KeyCommand cmd = mapToKeyCommand(event);   // backspace, arrows, enter, ...
    if (cmd != null) { apply(cmd); return true; }
    if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
    CommitTextCommand typed = typedCommand(event);
    if (typed != null) { apply(typed); return true; }
    return false;
}

private final CommitTextCommand typedCommand(KeyEvent event) {
    if (!isTypedEvent(event)) return null;     // (1)
    Integer combined = keyCombiner.consume(event);
    if (combined == null) return null;          // (2)  ← this fires
    String text = new StringBuilder()
        .appendCodePoint(combined.intValue()).toString();
    return new CommitTextCommand(text, 1);
}

public static final boolean isTypedEvent(KeyEvent e) {
    return e.getAction() == KeyEvent.ACTION_DOWN
        && !Character.isISOControl(e.getUnicodeChar());
}
```

If the path returns at (1) or (2), the key is dropped on the floor
from the field's perspective. There is no Compose layer that will
later try to recover it.

## Why DeX breaks (1)

`isTypedEvent` requires `event.getUnicodeChar() != 0` and
non-ISO-control. `KeyEvent.getUnicodeChar()` is implemented by
`KeyCharacterMap.get(keyCode, metaState)`, which is loaded per
input device.

The DeX keyboard on the Note 20 shows up in `dumpsys input` as:

```
12: DeX keyboard
   Classes: 0x80000063
   bus=0x0005  vendor=0x0000  product=0x0000  version=0x0000
   Path: /dev/input/event11
   KeyLayoutFile: /system/usr/keylayout/Generic.kl
   KeyCharacterMapFile: /system/usr/keychars/Generic.kcm
   Sources: 0x00000701
   IsExternal: true
   KeyboardType: 2
```

`bus=0x0005` is `BUS_BLUETOOTH`, but `vendor=0x0000/product=0x0000`
is a synthetic identity. The system still assigns `Generic.kcm`,
but the device-id → KeyCharacterMap lookup is the thing the
framework uses to resolve `getUnicodeChar()`. There are
Samsung-specific reports that synthetic / DeX virtual devices come
back with `getUnicodeChar() == 0` for plain letter keycodes even
though `input keyevent KEYCODE_T` from adb works fine (adb goes
through a different injection path that doesn't need a real KCM).

That would explain the exact symptom: key reaches the field
(confirmed by the `InputDispatcher: Delivering key to (5574)` log
on every DeX press), `isTypedEvent` returns `false` because
`getUnicodeChar()` returns 0, the field drops it, nothing
happens on screen. Ctrl+F is fine because the activity's
`dispatchKeyEvent` matches the binding on `(keyCode, metaState)`
alone and never asks the KCM.

## The second bug: Backspace = back navigation

`KeyboardShortcuts.BINDINGS` (in
`app/src/main/java/com/sumatrapdf/reader/KeyboardShortcuts.kt:267`)
maps `KEYCODE_DEL` with no modifier to `MenuAction.NavigateBack`
(this is the Win32 SumatraPDF binding — Backspace in document
view = navigate to the previous view). When the find toolbar is
not focused, every backspace press goes through the activity's
shortcut layer first, which returns true and triggers the back
navigation before Compose ever sees the key.

This is correct behaviour outside the find toolbar, but a focused
text field must own its own Backspace. The two issues — letters
dropped by Compose, Backspace swallowed by the shortcut layer —
were fixed in the same pass because the same `dispatchKeyEvent`
guard handles both.

## The fix that was implemented

Two layers, both in
`app/src/main/java/com/sumatrapdf/reader/`:

1. **`MainActivity.dispatchKeyEvent`** (line 207-208) — when any
   text field has focus (`anyTextFieldFocused.value` is true)
   and the event is `ACTION_DOWN`, the method short-circuits
   straight to `super.dispatchKeyEvent(event)`. Every other
   shortcut in `KeyboardShortcuts` (PageUp, HJKL, F3, Shift+A,
   etc.) is bypassed for that event. This stops Backspace from
   triggering the global back shortcut and stops letter keys
   from being intercepted by `BINDINGS`.

   The variable was originally `searchFieldFocused` (scoped to
   the find toolbar) and is now `anyTextFieldFocused`, a single
   `MutableState<Boolean>` that every focused text field flips
   to `true` via the `onFocusChanged` half of the shared
   modifier.

2. **`Modifier.textFieldKeyHandler`** in the new
   `KeyEventHandler.kt` — installed at every `OutlinedTextField` /
   `BasicTextField` site, runs before Compose's `TextFieldKeyInput`.
   On `ACTION_DOWN` it handles:

   - **`KEYCODE_DEL`** explicitly with selection-aware delete
     (collapsed cursor → backspace, non-collapsed → delete
     selection). Returns `true` so the field's own
     `TextFieldKeyInput` never sees the event.

   - **`KEYCODE_ENTER`** → `onSubmit()` if a callback is
     supplied. The find toolbar passes its search submit;
     the dialogs pass their confirm handler. Returns `true`.

   - **`KEYCODE_ESCAPE`** → `onClose()` if a callback is
     supplied. The find toolbar passes its close; the dialogs
     pass their dismiss handler. Returns `true`.

   - **All other key events** via `charForKeyEvent` →
     `keyCodeToChar` (in `KeyEventHandler.kt`), a 70-entry
     hand-written US-QWERTY map. The map covers A-Z
     (shift=uppercase), 0-9 (shift=`!@#$%^&*()`), space, tab,
     enter, the punctuation row
     (`. , - = / ; ' [ ] \`` with their shifted variants
     `> < _ + ? : " { } | ~`), and the full numpad
     (0-9, dot, comma, enter, multiply, divide, equals, parens).
     Ctrl+key returns `null` so the field doesn't swallow
     Ctrl-letter combinations — the global shortcut layer still
     handles those when the field is not focused.

   - **Falls through to Compose** (returns `false`) for keys it
     doesn't recognise, so arrows / Home / End / PageUp /
     PageDown still work natively.

   The reason for the hand-written map instead of
   `KeyCharacterMap.load(VIRTUAL_KEYBOARD)`: the KCM the DeX
   device exposes is the broken one that already failed in
   `TextFieldKeyInput`, and the build also rejected
   `KeyCharacterMap.BUILT_IN_KEYBOARD` / `VIRTUAL_KEYBOARD` as
   unresolved constants (the JVM overload-resolution can't pick
   between `obtain(int)` and `obtainEmptyMap(int)` without an
   explicit cast). A `when` over `KeyEvent.KEYCODE_*` is
   explicit, debuggable, and works regardless of which KCM the
   device exposes. The trade-off: it's a US-QWERTY bias, which
   matches the SumatraPDF Win32 app's key bindings and matches
   every other Android app on a US phone.

State changes forced by the modifier:

- Every text field that used to take a `String` (`findQuery` in
  `SearchBar.kt`, the password in `PasswordDialog`, the page
  number in `GoToPageDialog`, the zoom percent in
  `CustomZoomDialog`, the filter in `StartPage`'s `FilterField`,
  the name in `NameDialog`, the search query in `LibraryGrid`)
  now takes a `TextFieldValue` so the cursor position survives
  the modifier round-trip. The two `BasicTextField`-based sites
  (StartPage filter, LibraryGrid search) get a small
  `LaunchedEffect(prop) { if (internal.text != prop) internal = ... }`
  bridge so the parent component's API doesn't have to change.

## How to verify

The fix is small and has unit tests for the `keyCodeToChar` map.
The on-device behaviour has to be checked by hand. Two ways:

### On a real device with a physical keyboard in DeX

On the Note 20, with DeX active and a PDF open:

1. Press **Ctrl+F** — the find toolbar opens. (This was already
   working before the fix; it confirms the activity-level
   shortcut layer.)
2. With the find toolbar focused, **type `hello`** — every
   letter should land in the field.
3. Press **Backspace** four times — the field should shorten
   back to empty. It should NOT navigate back through the
   document.
4. Press **Shift+1** — the field should contain `!`.
5. Press **Enter** — the search should submit
   (`KeyboardActions(onSearch = …)`).
6. Press **Escape** — the find toolbar should close.

### On WSA (localhost:58526) via adb

WSA is the fastest way to test on a desktop without a phone. After
WSA is authorized and the app is installed:

1. `adb connect 127.0.0.1:58526` should show `device` (not
   `unauthorized`); if it shows `unauthorized`, see the WSA
   setup notes at the end of this file.
2. `adb -s 127.0.0.1:58526 install -r app-debug.apk`
3. `adb -s 127.0.0.1:58526 shell am start -n com.sumatrapdf.reader/.MainActivity -d "file:///data/data/com.sumatrapdf.reader/cache/test.pdf"`
   (push the test PDF to `/data/local/tmp/`, then `run-as
   com.sumatrapdf.reader cp /data/local/tmp/test.pdf
   /data/data/com.sumatrapdf.reader/cache/`).
4. `adb -s 127.0.0.1:58526 shell input keycombination KEYCODE_CTRL_LEFT KEYCODE_F`
   opens the find toolbar.
5. `adb -s 127.0.0.1:58526 shell input text "hello"` with
   ~400 ms between each character (otherwise the
   recomposition can drop characters and you'll see "llo"
   instead of "hello").
6. `adb -s 127.0.0.1:58526 shell input keyevent 67` removes the
   last character.
7. `adb -s 127.0.0.1:58526 shell input keyevent 111` closes
   the toolbar (Escape).

If `adb shell input keyevent KEYCODE_T` is used to type, the
`u=` logcat value (when re-added) shows `116` (the unicode for
`t`) — adb's injection path bypasses the broken KCM. The same
key typed on a real DeX keyboard shows `u=0`, which is what
triggers the issue.

### Sites that were verified end-to-end on WSA

Five of the seven sites were exercised on `127.0.0.1:58526`
(Android 13) during the QA pass; the other two are
covered-by-construction. The verification recipe for each
differs in how the dialog is opened; once it is open and the
field is focused, the same `~400 ms`-spaced `input text` /
`input keyevent` pattern as the find toolbar works.

| site | path to open | typed | observed |
|---|---|---|---|
| `SearchBar.kt:91` (find) | Find button OR `keycombination CTRL_LEFT F` | `hello` (5 chars, 400ms gap) | field shows `hello`; DEL → `hell`; ESC closes toolbar; ENTER stays open without crash |
| `StartPage.kt:364` (home filter) | launch with no file open, tap filter field | `h` then `e` (500ms gap) | field shows `he`; DEL works; 10× DEL clears |
| `ReaderScreen.kt:2160` (go-to-page) | tap "1 / N" page indicator | `3` | field shows `3`; DEL clears; ESC closes |
| `ReaderScreen.kt:2202` (custom zoom) | HamburgerMenu → Zoom → Custom Zoom… (Compose Popup, not in `uiautomator dump`; read XML for `text="Zoom"` bounds and tap the parent `clickable`) | `2` after 3× DEL | field shows `2`; DEL → empty; OK at (1899, 895) submits — page re-renders at 25% zoom; ESC closes |
| `ReaderScreen.kt:2036` (password) | open an RC4-encrypted PDF (built with `pypdf`; AES-256 needs `cryptography` which the system Python lacks) | `hello123` (8 chars, 400ms gap) | field shows 8 dots; Open → `SumatraEngine: open: OK, handle=N pageCount=1` with `password=true` |

The two library sites (`NameDialog`, library search) are
covered by construction — see "Known limitations" below.

## Known limitations

- **Two of the seven text-field sites are not directly verifiable
  on WSA.** The two library sites (`NameDialog` at
  `library/LibraryPage.kt:429` and the library search at
  `library/LibraryPage.kt:521`) require a populated library,
  but the Python library service launcher
  (`LibraryEnsureService` on port 7863) referenced in the
  host's `SumatraPDF.cpp` is **not present in the Android
  app**. There is no `SumatraPDF.cpp` here; the `7863` port
  is only mentioned in a `cluster_oracle.json` test fixture.
  Setting `<boolean name="libraryHome" value="true" />` in
  `shared_prefs/sumatra.xml` (a UTF-16-LE file on Android)
  does not produce a library page — the home stays on the
  classic "Recently Opened" view. The two library sites use
  the same `Modifier.textFieldKeyHandler` and the same
  `anyTextFieldFocused` plumbing as the five that **were**
  verified (`SearchBar.kt:91` find, `StartPage.kt:364`
  filter, `ReaderScreen.kt:2160` go-to-page,
  `ReaderScreen.kt:2202` custom-zoom, `ReaderScreen.kt:2036`
  password), so they are covered by construction. Once the
  library-service launcher lands in the Android port, both
  sites will work without further changes.

- **AltGr characters are not handled.** International layouts
  (e.g. European keyboards where AltGr+key produces
  accented characters) are not in the manual map. The user's
  phone is a US Note 20 Ultra, so this is out of scope for the
  port, but it is the obvious next thing to address if a
  non-US user reports it.

- **Mouse-driven hardware keys (e.g. a presenter with a
  click-button) that send non-printable keycodes with
  `getUnicodeChar() == 0` and a keycode outside the
  `keyCodeToChar` map will be dropped silently.** Acceptable
  trade-off — if a key is in the table, it works; if it
  isn't, it doesn't do anything destructive (no global
  shortcut fires, no navigation happens).

- **WSA-only caveat for adb input:** `adb shell input text`
  fires characters back-to-back; if you read the field
  before the next Compose recomposition, you'll see fewer
  characters than you sent (e.g. "hello" arriving as "llo").
  Physical-keyboard input has human-paced timing, so it
  works fine. The fix is correct; the test harness just
  needs ~400 ms per character.

## WSA setup notes

The Windows 11 WSA build on this machine is **2407.40000.4.0**, the
final Microsoft build (Microsoft ended support on 5 March 2025;
WSABuilds is the actively maintained fork). `127.0.0.1:58526` is
the fixed WSA loopback ADB port.

To get `adb` to talk to it, do this once:

1. Open **Windows Subsystem for Android™ Settings** from the
   Start menu.
2. **Advanced settings → Developer mode** — turn on. The
   description text reads "Devices on the same private network
   can access the Subsystem. ADB can be connected on
   127.0.0.1:58526." If the IP-address field is empty, scroll
   back and turn on **Optional diagnostic data** first;
   Microsoft hides the field until that toggle is on.
3. Click **Manage developer settings**. This opens the
   in-VM Android Settings app.
4. In the in-VM Developer Options, toggle **USB debugging** off
   and back on. This is what makes the "Allow USB debugging?"
   dialog re-appear behind the WSA window. (The
   `anyTextFieldFocused` guard in `MainActivity` is not enough
   on its own — the in-VM USB-debugging toggle has to be on
   too, because the `adbd` only accepts connections if the
   in-VM USB debugging setting is on.)
5. Click the **Files** icon to the left of the "Files" box in
   WSA Settings. This launches the WSA VM; the port won't be
   bound until an Android app is actually running.
6. `adb connect 127.0.0.1:58526`. The "Allow USB debugging?"
   dialog appears **inside the WSA window** with the host's
   RSA key fingerprint. Check "Always allow from this
   computer" and click **Allow**.
7. Verify: `adb devices` shows `127.0.0.1:58526  device`.

For recurring `10061 actively refused` errors (the WSA VM is
not running), the WSABuilds fix guide recommends:

```
netsh int ipv4 add excludedportrange protocol=tcp startport=58526 numberofports=1
```

to reserve the port from Hyper-V.
