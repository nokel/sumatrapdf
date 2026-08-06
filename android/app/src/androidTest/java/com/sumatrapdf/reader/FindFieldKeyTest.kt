package com.sumatrapdf.reader

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class FindFieldKeyTest {

    private val pkg = "com.sumatrapdf.reader"

    private fun device(): UiDevice =
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    private fun samplePath(): String {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(ctx.cacheDir, "find-key-test.pdf")
        if (!file.exists()) {
            file.writeBytes(kMinimalPdf.toByteArray(Charsets.ISO_8859_1))
        }
        return file.absolutePath
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

    private fun findButton(): UiObject2? =
        device().findObject(By.desc("Find"))

    private fun findField(): UiObject2? =
        device().findObject(By.clazz("android.widget.EditText"))

    @Test
    fun hardwareKeysLandInFindField() {
        val path = samplePath()
        launch(path)

        val btn = findButton()
        assertNotNull("Find button not in UI hierarchy", btn)
        btn!!.click()
        device().wait(Until.hasObject(By.clazz("android.widget.EditText")), 5_000)

        val field = findField()
        assertNotNull("find field not in UI hierarchy after open", field)
        field!!.click()
        device().waitForIdle()

        device().pressKeyCode(android.view.KeyEvent.KEYCODE_H)
        device().pressKeyCode(android.view.KeyEvent.KEYCODE_E)
        device().pressKeyCode(android.view.KeyEvent.KEYCODE_L)
        device().pressKeyCode(android.view.KeyEvent.KEYCODE_L)
        device().pressKeyCode(android.view.KeyEvent.KEYCODE_O)
        device().waitForIdle()

        val text = field.text
        assertNotNull("find field has no text", text)
        assertTrue(
            "expected find field to contain 'hello', got '$text'",
            text!!.contains("hello"),
        )

        device().pressKeyCode(android.view.KeyEvent.KEYCODE_DEL)
        device().waitForIdle()
        val after = field.text
        assertTrue(
            "DEL should shorten 'hello' by one char, got '$after'",
            after != null && after.length == 4,
        )
    }
}

private const val kMinimalPdf = """%PDF-1.4
1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj
2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj
3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 612 792]/Resources<</Font<</F1 5 0 R>>>>/Contents 4 0 R>>endobj
4 0 obj<</Length 58>>stream
BT /F1 36 Tf 72 700 Td (Hello world SumatraPDF typing test) Tj ET
endstream
endobj
5 0 obj<</Type/Font/Subtype/Type1/BaseFont/Helvetica>>endobj
trailer<</Root 1 0 R>>"""
