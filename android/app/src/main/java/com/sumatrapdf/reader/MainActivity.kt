package com.sumatrapdf.reader

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.artifex.mupdf.fitz.Context as FitzContext
import java.io.File

// Launcher activity. The Compose UI is just a single ReaderScreen
// with a DocumentEngine instance and a Settings instance; the
// activity wires the file-picker launcher and the `ACTION_VIEW`
// intent that opens the app from the system file manager.
class MainActivity : ComponentActivity() {

    private val engine = DocumentEngine()
    private lateinit var settings: Settings
    private lateinit var readAloud: ReadAloud

    // Set from onCreate or onNewIntent so the Composable can react to
    // a new file even when the activity is already running (i.e. the
    // file-picker returned a Uri, or the user opened a file from a
    // file manager while the app was already up).
    private val pendingUri = mutableStateOf<OpenRequest?>(null)

    // The keyboard-shortcut layer writes here on a hardware-key event;
    // the Compose tree reads it via a LaunchedEffect and dispatches
    // the action to the same handler the hamburger menu uses.
    private val pendingShortcut = mutableStateOf<MenuAction?>(null)

    // Bumped when the back button has been held down for three seconds.
    // The Compose tree watches it, buzzes past every open menu and lands
    // on the frequently-read page, from where one more back press exits.
    private val backHeld = mutableStateOf(0)
    private val holdHandler = Handler(Looper.getMainLooper())
    private var holdTimer: Runnable? = null
    private var holdFired = false

    // The SearchBar reports its focus here so `dispatchKeyEvent` can let
    // KEYCODE_ENTER and KEYCODE_SPACE pass through to the OutlinedTextField
    // (which has ImeAction.Search / a spacebar). Without this, the global
    // shortcut layer swallows Enter as "ScrollDownPage" and the find-toolbar
    // submit is unreachable for hardware-keyboard users — and for adb-driven
    // testing, where there is no soft keyboard to tap. Mirrors Win32: in the
    // find toolbar, Enter and Space submit the search; outside of it, both
    // scroll a page.
    private val anyTextFieldFocused = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FitzContext.init()
        settings = Settings(this)
        readAloud = ReadAloud(this)
        pendingUri.value = pickUriFromIntent(intent)
        Log.i("SumatraMain", "onCreate: pendingUri=${pendingUri.value}")

        setContent {
            var nightMode by remember { mutableStateOf(settings.nightMode) }
            var immersive by remember { mutableStateOf(false) }
            SumatraTheme(nightMode = nightMode) {
                AppShell(
                    engine = engine,
                    settings = settings,
                    readAloud = readAloud,
                    pendingUriFlow = pendingUri,
                    onUriHandled = { pendingUri.value = null },
                    pendingShortcutFlow = pendingShortcut,
                    onShortcutHandled = { pendingShortcut.value = null },
                    searchFieldFocusedFlow = anyTextFieldFocused,
                    backHeld = backHeld.value,
                    nightMode = nightMode,
                    onNightModeChange = {
                        nightMode = it
                        settings.nightMode = it
                    },
                    immersive = immersive,
                    onImmersiveChange = {
                        immersive = it
                        applyImmersive(it)
                    },
                    onExit = { finish() },
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        readAloud.shutdown()
        engine.closeAll()
    }

    override fun onStop() {
        super.onStop()
        readAloud.pause()
    }

    private fun applyImmersive(on: Boolean) {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        WindowCompat.setDecorFitsSystemWindows(window, !on)
        if (on) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val u = pickUriFromIntent(intent)
        Log.i("SumatraMain", "onNewIntent: uri=$u")
        if (u != null) {
            pendingUri.value = u
        }
    }

    // Hardware-key dispatch for the keyboard-shortcut layer. A
    // physical / Bluetooth keyboard (the Fold's outer display has
    // one; a BT page-turner for reading in bed sends Ctrl+arrow etc.)
    // routes its key events through `dispatchKeyEvent` rather than
    // through the Compose tree, so the lookup has to happen at the
    // Activity level. We do the lookup, write the resulting
    // MenuAction to `pendingShortcut`, and let the Compose tree
    // consume it through a LaunchedEffect.
    //
    // The toggle rule (agents.md §7): this method is the only path
    // the keyboard layer uses. The toggle on the activity side is
    // `settings.keyboardShortcuts` (default true, controllable by
    // the user); when off, the method returns false so the event
    // continues to the system and Compose, untouched.
    // Three-button navigation delivers a real KEYCODE_BACK, so the
    // three-second hold is measured here: the down is swallowed, the
    // timer runs, and the up either fires the ordinary one-step back or
    // is eaten because the hold already did its thing.
    private fun startBackHold() {
        holdFired = false
        val timer = Runnable {
            holdFired = true
            buzz(500L)
            backHeld.value++
        }
        holdTimer = timer
        holdHandler.postDelayed(timer, 3000L)
    }

    private fun endBackHold(): Boolean {
        holdTimer?.let { holdHandler.removeCallbacks(it) }
        holdTimer = null
        return holdFired
    }

    private fun buzz(ms: Long) {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Vibrator::class.java)
        }
        try {
            vibrator?.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (t: Throwable) {
            Log.w("SumatraMain", "vibrate failed: ${t.message}")
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> {
                    if (event.repeatCount == 0) startBackHold()
                    return true
                }
                KeyEvent.ACTION_UP -> {
                    if (!endBackHold()) onBackPressedDispatcher.onBackPressed()
                    return true
                }
            }
        }
        // When the find toolbar's text field has focus, Return and Space
        // are part of the search submission, not page-scroll shortcuts.
        // Pass them through to the Compose tree so the OutlinedTextField's
        // ImeAction.Search / standard key handling wins. (Win32 SumatraPDF
        // behaves the same way: inside the find toolbar, Return submits
        // the search; outside, it scrolls a page.)
        if (anyTextFieldFocused.value && event.action == KeyEvent.ACTION_DOWN) {
            return super.dispatchKeyEvent(event)
        }
        if (!settings.keyboardShortcuts) return super.dispatchKeyEvent(event)
        // We only act on key-down — key-up is just the release of
        // the same key, and acting on it would fire the action twice.
        if (event.action != KeyEvent.ACTION_DOWN) {
            return super.dispatchKeyEvent(event)
        }
        val action = KeyboardShortcuts.keyCodeToAction(event.keyCode, event.metaState)
        if (action != null) {
            Log.i("SumatraMain", "shortcut: ${event.keyCode}/${event.metaState} -> $action")
            pendingShortcut.value = action
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    // Prefer Intent.data (system file manager / share). The `filePath`
    // String extra is the adb-driven path: it lets `adb shell am start
    // --es filePath /sdcard/...` open a file without the file picker.
    // We DO NOT route the filePath through contentResolver
    // (Uri.fromFile() + openInputStream) because that requires
    // MANAGE_EXTERNAL_STORAGE on Android 11+; the path is a real path
    // the app's UID can read directly, so we hand it to mupdf as-is.
    private fun pickUriFromIntent(intent: Intent?): OpenRequest? {
        val fromIntent = intent?.data
        if (fromIntent != null) return OpenRequest.ContentUri(fromIntent)
        val shared = intent?.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        if (shared != null) return OpenRequest.ContentUri(shared)
        val fromExtra = intent?.getStringExtra("filePath")
        if (!fromExtra.isNullOrEmpty()) {
            return OpenRequest.DirectFile(File(fromExtra).absolutePath)
        }
        return null
    }
}

// What the AppShell should open. Two cases:
//   * ContentUri — from the system file picker / share / `Intent.data`.
//     Routed through engine.openUri() (contentResolver copy then mupdf).
//   * DirectFile — from the adb `--es filePath` extra. We bypass
//     contentResolver and let mupdf's native Document.openDocument
//     read the path directly. This is the only way to open a file
//     from adb without granting MANAGE_EXTERNAL_STORAGE; any path the
//     app's UID can read (its own external files dir, a /sdcard path
//     the app was granted access to, etc.) just works.
sealed class OpenRequest {
    data class ContentUri(val uri: Uri) : OpenRequest()
    data class DirectFile(val absolutePath: String) : OpenRequest()
}

@Composable
private fun AppShell(
    engine: DocumentEngine,
    settings: Settings,
    readAloud: ReadAloud,
    pendingUriFlow: MutableState<OpenRequest?>,
    onUriHandled: () -> Unit,
    pendingShortcutFlow: MutableState<MenuAction?>,
    onShortcutHandled: () -> Unit,
    searchFieldFocusedFlow: MutableState<Boolean>,
    backHeld: Int,
    nightMode: Boolean,
    onNightModeChange: (Boolean) -> Unit,
    immersive: Boolean,
    onImmersiveChange: (Boolean) -> Unit,
    onExit: () -> Unit,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var copySource by remember { mutableStateOf<String?>(null) }

    val openLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) {
            pendingUriFlow.value = OpenRequest.ContentUri(uri)
        }
    }

    val saveLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*"),
    ) { target: Uri? ->
        val source = copySource
        copySource = null
        if (target == null || source == null) return@rememberLauncherForActivityResult
        try {
            ctx.contentResolver.openOutputStream(target)?.use { out ->
                File(source).inputStream().use { input -> input.copyTo(out) }
            }
        } catch (t: Throwable) {
            Log.e("SumatraMain", "save copy failed", t)
        }
    }

    ReaderScreen(
        engine = engine,
        settings = settings,
        readAloud = readAloud,
        onOpenFile = {
            openLauncher.launch(
                arrayOf(
                    "application/pdf",
                    "application/epub+zip",
                    "application/oxps",
                    "application/vnd.ms-xpsdocument",
                    "application/x-fictionbook+xml",
                    "application/vnd.comicbook+zip",
                    "application/x-cbz",
                    "*/*",
                ),
            )
        },
        pendingOpen = pendingUriFlow.value,
        onOpenHandled = onUriHandled,
        onSaveCopy = { path ->
            copySource = path
            saveLauncher.launch(path.substringAfterLast('/'))
        },
        nightMode = nightMode,
        onNightModeChange = onNightModeChange,
        immersive = immersive,
        onImmersiveChange = onImmersiveChange,
        onCloseFile = onExit,
        pendingShortcut = pendingShortcutFlow.value,
        onShortcutHandled = onShortcutHandled,
        onSearchFieldFocusChange = { searchFieldFocusedFlow.value = it },
        anyTextFieldFocused = searchFieldFocusedFlow,
        backHeld = backHeld,
    )
}
