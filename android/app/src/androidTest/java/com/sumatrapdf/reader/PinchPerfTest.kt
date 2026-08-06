package com.sumatrapdf.reader

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject
import androidx.test.uiautomator.UiSelector
import androidx.test.uiautomator.Until
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

// `adb shell input` cannot inject a second finger, so pinch smoothness
// can only be measured through UiAutomation. This drives a real
// two-finger pinch over a real document in the real activity and reads
// the frame timings the system recorded for the gesture.
@RunWith(AndroidJUnit4::class)
class PinchPerfTest {

    private val pkg = "com.sumatrapdf.reader"

    private fun device(): UiDevice =
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    private fun samplePath(): String? {
        val candidates = listOf(
            "/sdcard/Download/1540157347-HarlanEllison-IHaveNoMouthandIMustScream.pdf",
        )
        return candidates.firstOrNull { File(it).canRead() }
    }

    private fun launch(path: String) {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(ctx, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            putExtra("filePath", path)
        }
        ctx.startActivity(intent)
        device().wait(Until.hasObject(By.pkg(pkg).depth(0)), 10_000)
        device().wait(Until.hasObject(By.desc("page 1")), 15_000)
    }

    private fun pageObject(): UiObject =
        device().findObject(UiSelector().descriptionContains("page "))

    private fun gfxinfo(): String =
        device().executeShellCommand("dumpsys gfxinfo $pkg")

    private fun statOf(dump: String, label: String): Int {
        val line = dump.lineSequence().firstOrNull { it.trim().startsWith(label) } ?: return -1
        return Regex("(\\d+)ms").find(line)?.groupValues?.get(1)?.toIntOrNull() ?: -1
    }

    @Test
    fun pinchHoldsFrameRate() {
        val sample = samplePath()
        assumeTrue("no sample document on device", sample != null)
        launch(sample!!)
        device().executeShellCommand("dumpsys gfxinfo $pkg reset")
        Thread.sleep(500)

        val page = pageObject()
        assertTrue("page node not found", page.exists())
        repeat(3) {
            page.pinchOut(75, 40)
            Thread.sleep(400)
            page.pinchIn(75, 40)
            Thread.sleep(400)
        }

        val dump = gfxinfo()
        val total = Regex("Total frames rendered: (\\d+)").find(dump)
            ?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val janky = Regex("Janky frames: (\\d+) \\(([\\d.]+)%\\)").find(dump)
        val jankPct = janky?.groupValues?.get(2)?.toFloatOrNull() ?: 100f
        val p90 = statOf(dump, "90th percentile")
        val p95 = statOf(dump, "95th percentile")
        val p99 = statOf(dump, "99th percentile")
        val report = "frames=$total janky=$jankPct% p90=${p90}ms p95=${p95}ms p99=${p99}ms"
        println("PINCH-PERF $report")

        assertTrue("pinch produced almost no frames, gesture did not land: $report", total > 60)
        assertTrue("pinch dropped frames: $report", jankPct < 35f)
        assertTrue("pinch frames too slow: $report", p90 in 0..40)
    }
}
