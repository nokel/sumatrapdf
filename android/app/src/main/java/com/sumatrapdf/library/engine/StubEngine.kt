package com.sumatrapdf.library.engine

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.core.graphics.applyCanvas
import androidx.core.graphics.createBitmap

// The StubEngine is a polite placeholder for the three formats SumatraPDF
// renders on Windows that MuPDF does not handle in its AAR: DjVu, MOBI,
// AZW3. It opens successfully, reports 0 pages and an empty outline, and
// returns a single image per render() call that explains why the page
// is empty. The reader UI keeps working — tabs can be closed, recents
// still record the open, the format badge is visible — so the user is
// never trapped.
class StubEngine(override val spec: DocumentSpec) : DocumentEngine {

    @Volatile override var pageCount: Int = 0
        private set
    override val isReflowable: Boolean = false
    override val isPdf: Boolean = false
    override val isStub: Boolean = true

    override fun open(onReady: () -> Unit, onPassword: () -> Unit, onFailed: (String) -> Unit) {
        // Pretend the file is unreadable so the reader shows its own
        // "this format isn't built in this release" page rather than
        // silently opening a blank tab.
        onFailed("format ${spec.format.label} is not yet supported on Android")
    }

    override fun close() {}
    override fun outline(then: (List<DocumentOutlineEntry>) -> Unit) = then(emptyList())
    override fun pageText(index: Int, then: (String) -> Unit) = then("")
    override fun searchPage(index: Int, needle: String, then: (List<RectF>) -> Unit) = then(emptyList())
    override fun setReflow(widthPt: Float, heightPt: Float, em: Float) {}

    override fun render(index: Int, targetWidth: Int, night: Boolean, then: (Bitmap?) -> Unit) {
        then(stubPage(targetWidth, night))
    }

    override fun pageShape(index: Int, then: (Pair<Float, Float>?) -> Unit) =
        then(Pair(612f, 792f))

    private fun stubPage(targetWidth: Int, night: Boolean): Bitmap {
        val w = if (targetWidth > 0) targetWidth else 612
        val h = (w * 1.3f).toInt().coerceAtLeast(400)
        val bg = if (night) Color.rgb(20, 20, 26) else Color.rgb(245, 245, 245)
        val fg = if (night) Color.rgb(230, 225, 229) else Color.rgb(40, 40, 50)
        val dim = if (night) Color.rgb(154, 150, 160) else Color.rgb(90, 90, 100)
        val accent = if (night) Color.rgb(138, 180, 248) else Color.rgb(44, 90, 160)
        val bmp = createBitmap(w, h)
        bmp.applyCanvas {
            drawColor(bg)
            val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = fg
                textSize = w * 0.045f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            val sub = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = dim
                textSize = w * 0.028f
            }
            val tag = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = accent
                textSize = w * 0.024f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            val line = Paint().apply { color = dim; strokeWidth = 1f }
            val padX = w * 0.08f
            val midY = h * 0.5f
            drawLine(padX, midY, w - padX, midY, line)
            drawText("SumatraPDF", padX, midY - w * 0.10f, tag)
            drawText(spec.name, padX, midY - w * 0.04f, title)
            drawText(
                "Format: " + spec.format.label,
                padX,
                midY + w * 0.03f,
                sub,
            )
            drawText(
                "This format is not yet supported on Android.",
                padX,
                midY + w * 0.08f,
                sub,
            )
            drawText(
                "PDF, EPUB, XPS, FB2 and CBZ render through MuPDF.",
                padX,
                midY + w * 0.13f,
                sub,
            )
        }
        return bmp
    }
}
