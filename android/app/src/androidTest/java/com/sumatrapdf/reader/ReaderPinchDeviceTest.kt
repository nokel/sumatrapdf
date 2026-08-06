package com.sumatrapdf.reader

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.json.JSONArray
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

// A two-finger spread over a real document on the real device must
// magnify the page and leave it drawn. The pinch runs as injected
// MotionEvents (no other route can put a second finger down), and the
// zoom it produced is read back out of the state the app persists.
@RunWith(AndroidJUnit4::class)
class ReaderPinchDeviceTest {

    private val pkg = "com.sumatrapdf.reader"

    private fun device(): UiDevice =
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    private fun prefs() =
        InstrumentationRegistry.getInstrumentation().targetContext
            .getSharedPreferences("sumatra", Context.MODE_PRIVATE)

    private fun samplePath(): String? = listOf(
        "/sdcard/Download/raspberry-pi-pico-python-sdk.pdf",
        "/sdcard/Download/11640630-Miele-User-Manual.pdf",
        "/sdcard/Download/basic-hospital-essential-extras.pdf",
    ).firstOrNull { File(it).canRead() }

    private fun savedZoom(path: String): Float? {
        val raw = prefs().getString("fileStates", "[]") ?: "[]"
        val states = JSONArray(raw)
        for (i in 0 until states.length()) {
            val state = states.optJSONObject(i) ?: continue
            if (state.optString("path") != path) continue
            return state.optString("zoom").toFloatOrNull()
        }
        return null
    }

    private fun pointer(id: Int): MotionEvent.PointerProperties =
        MotionEvent.PointerProperties().apply {
            this.id = id
            toolType = MotionEvent.TOOL_TYPE_FINGER
        }

    private fun at(x: Float, y: Float): MotionEvent.PointerCoords =
        MotionEvent.PointerCoords().apply {
            this.x = x
            this.y = y
            pressure = 1f
            size = 1f
        }

    private fun send(
        down: Long,
        action: Int,
        props: Array<MotionEvent.PointerProperties>,
        coords: Array<MotionEvent.PointerCoords>,
    ) {
        val event = MotionEvent.obtain(
            down, SystemClock.uptimeMillis(), action, props.size, props, coords,
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
        InstrumentationRegistry.getInstrumentation().uiAutomation.injectInputEvent(event, true)
        event.recycle()
    }

    private fun spread(shots: File?, tag: String) {
        val w = device().displayWidth.toFloat()
        val h = device().displayHeight.toFloat()
        val y = h * 0.5f
        val near = w * 0.06f
        val far = w * 0.40f
        val down = SystemClock.uptimeMillis()
        val one = arrayOf(pointer(0))
        val two = arrayOf(pointer(0), pointer(1))

        send(down, MotionEvent.ACTION_DOWN, one, arrayOf(at(w / 2 - near, y)))
        send(
            down,
            MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            two, arrayOf(at(w / 2 - near, y), at(w / 2 + near, y)),
        )
        for (i in 1..24) {
            val gap = near + (far - near) * i / 24f
            send(
                down, MotionEvent.ACTION_MOVE, two,
                arrayOf(at(w / 2 - gap, y), at(w / 2 + gap, y)),
            )
            SystemClock.sleep(16)
            if (shots != null && i == 12) {
                device().takeScreenshot(File(shots, "$tag-mid.png"))
            }
        }
        send(
            down,
            MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            two, arrayOf(at(w / 2 - far, y), at(w / 2 + far, y)),
        )
        send(down, MotionEvent.ACTION_UP, one, arrayOf(at(w / 2 - far, y)))
        SystemClock.sleep(800)
    }

    @Test
    fun spreadMagnifiesTheOpenDocument() {
        val sample = samplePath()
        assumeTrue("no readable document on the device", sample != null)
        val path = sample!!
        val shots = File("/sdcard/Download/pinch-shots").apply { mkdirs() }

        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        ctx.startActivity(
            Intent(ctx, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                putExtra("filePath", path)
            },
        )
        device().wait(Until.hasObject(By.pkg(pkg).depth(0)), 15_000)
        device().wait(Until.hasObject(By.descContains("page ")), 20_000)
        SystemClock.sleep(2000)
        device().takeScreenshot(File(shots, "reader-before.png"))

        device().pressHome()
        SystemClock.sleep(1200)
        val before = savedZoom(path)

        ctx.startActivity(
            Intent(ctx, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra("filePath", path)
            },
        )
        device().wait(Until.hasObject(By.descContains("page ")), 20_000)
        SystemClock.sleep(1500)

        spread(shots, "reader")
        device().takeScreenshot(File(shots, "reader-after.png"))

        device().pressHome()
        SystemClock.sleep(1500)
        val after = savedZoom(path)

        println("READER-PINCH zoom $before -> $after")
        assertTrue("no zoom recorded after the spread", after != null)
        assertTrue(
            "spread did not magnify the page: $before -> $after",
            after!! > (before ?: 100f) * 1.4f,
        )
    }
}
