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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

// A two-finger spread on the library wall must make the books bigger
// (fewer columns) and a pinch must make them smaller. `adb shell input`
// cannot inject a second finger and SELinux blocks sendevent on the
// touchscreen node, so the gesture is injected as real MotionEvents
// through UiAutomation, the same path the window manager feeds fingers
// down.
@RunWith(AndroidJUnit4::class)
class LibraryPinchDeviceTest {

    private val pkg = "com.sumatrapdf.reader"

    private fun device(): UiDevice =
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    private fun prefs() =
        InstrumentationRegistry.getInstrumentation().targetContext
            .getSharedPreferences("sumatra", Context.MODE_PRIVATE)

    private fun columns(): Int = prefs().getInt("libraryColumns", 0)

    private fun openLibrary() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        ctx.startActivity(
            Intent(ctx, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            },
        )
        device().wait(Until.hasObject(By.pkg(pkg).depth(0)), 10_000)
        val link = device().wait(Until.findObject(By.text("Library")), 5_000)
        link?.click()
        device().wait(Until.hasObject(By.textContains("book")), 15_000)
        device().pressBack()
        SystemClock.sleep(1500)
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

    private fun twoFingerDrag(
        ax0: Float, ay0: Float, bx0: Float, by0: Float,
        ax1: Float, ay1: Float, bx1: Float, by1: Float,
        steps: Int,
    ) {
        val down = SystemClock.uptimeMillis()
        val one = arrayOf(pointer(0))
        val two = arrayOf(pointer(0), pointer(1))

        // A hand never puts both fingers down on the same frame, and the
        // first one always slides a little while the second is landing.
        // That is enough for a scrollable grid underneath to claim the
        // gesture, so the pinch has to survive it.
        send(down, MotionEvent.ACTION_DOWN, one, arrayOf(at(ax0, ay0)))
        for (i in 1..6) {
            send(down, MotionEvent.ACTION_MOVE, one, arrayOf(at(ax0, ay0 - i * 3f)))
            SystemClock.sleep(16)
        }
        send(
            down,
            MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            two, arrayOf(at(ax0, ay0), at(bx0, by0)),
        )
        for (i in 1..steps) {
            val t = i.toFloat() / steps
            send(
                down, MotionEvent.ACTION_MOVE, two,
                arrayOf(
                    at(ax0 + (ax1 - ax0) * t, ay0 + (ay1 - ay0) * t),
                    at(bx0 + (bx1 - bx0) * t, by0 + (by1 - by0) * t),
                ),
            )
            SystemClock.sleep(16)
        }
        send(
            down,
            MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            two, arrayOf(at(ax1, ay1), at(bx1, by1)),
        )
        send(down, MotionEvent.ACTION_UP, one, arrayOf(at(ax1, ay1)))
        SystemClock.sleep(600)
    }

    @Test
    fun spreadAndPinchChangeTheColumnCount() {
        openLibrary()
        val w = device().displayWidth.toFloat()
        val h = device().displayHeight.toFloat()
        val y = h * 0.62f
        val start = w * 0.10f
        val wide = w * 0.42f

        val before = columns()
        twoFingerDrag(
            w / 2 - start, y, w / 2 + start, y,
            w / 2 - wide, y, w / 2 + wide, y,
            20,
        )
        val afterSpread = columns()
        println("LIB-PINCH spread: $before -> $afterSpread")

        twoFingerDrag(
            w / 2 - wide, y, w / 2 + wide, y,
            w / 2 - start, y, w / 2 + start, y,
            20,
        )
        val afterPinch = columns()
        println("LIB-PINCH pinch: $afterSpread -> $afterPinch")

        assertTrue(
            "a two-finger spread did not change the column count (stayed $before)",
            afterSpread != before,
        )
        assertTrue(
            "a two-finger pinch did not change the column count (stayed $afterSpread)",
            afterPinch != afterSpread,
        )
    }
}
