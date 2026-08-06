package com.sumatrapdf.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.geometry.Offset
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.system.measureTimeMillis

@RunWith(AndroidJUnit4::class)
class PinchZoomTest {

    @get:Rule
    val rule = createComposeRule()

    private fun samplePdf(): String {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(ctx.cacheDir, "pinch-test.pdf")
        if (!file.exists()) file.writeBytes(kMinimalPdf.toByteArray(Charsets.ISO_8859_1))
        return file.absolutePath
    }

    private fun inkedPdf(): String {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(ctx.cacheDir, "pinch-inked.pdf")
        if (!file.exists()) file.writeBytes(kInkedPdf.toByteArray(Charsets.ISO_8859_1))
        return file.absolutePath
    }

    // The inked page is solid red, a colour the surface never draws by
    // itself: the canvas is grey (or black in dark theme), the page
    // sheet is white, the page edge is grey. So the fraction of red
    // pixels is the fraction of the screen showing an actual rendered
    // page bitmap rather than a bare sheet.
    private fun inkFraction(image: ImageBitmap): Float {
        val px = image.toPixelMap()
        var ink = 0
        var total = 0
        var y = 0
        while (y < px.height) {
            var x = 0
            while (x < px.width) {
                val c = px[x, y]
                total++
                if (c.red > 0.5f && c.green < 0.35f && c.blue < 0.35f) ink++
                x += 4
            }
            y += 4
        }
        return if (total == 0) 0f else ink.toFloat() / total
    }

    @Test
    fun pinchOutRaisesZoomAndPinchInLowersIt() {
        val engine = DocumentEngine()
        val opened = engine.open(samplePdf())
        assertTrue("engine failed to open sample: $opened", opened is DocumentEngine.Opened.Ok)
        engine.setActive((opened as DocumentEngine.Opened.Ok).handle)
        assertTrue("document not active", engine.isOpen)

        var zoom = ZoomLevel.FitWidth
        var customZoom = 1f
        var effectiveScale = 1f
        val factors = mutableListOf<Float>()

        rule.setContent {
            Box(Modifier.fillMaxSize()) {
                PageSurface(
                    engine = engine,
                    page = 0,
                    pageCount = engine.pageCount,
                    displayMode = DisplayMode.SinglePage,
                    continuous = true,
                    zoom = zoom,
                    customZoom = customZoom,
                    rotation = 0,
                    onTap = {},
                    onEdgeClick = {},
                    onEffectiveScale = { effectiveScale = it },
                    onPinchZoom = { factor, activeScale ->
                        factors.add(factor)
                        val base = if (zoom == ZoomLevel.Custom) customZoom else activeScale
                        zoom = ZoomLevel.Custom
                        customZoom = (base * factor)
                            .coerceIn(kZoomMin / 100f, kZoomMax / 100f)
                    },
                )
            }
        }
        rule.waitForIdle()

        val fitScale = effectiveScale
        assertTrue("fit scale should be positive", fitScale > 0f)

        rule.onRoot().performTouchInput {
            pinch(
                start0 = center + Offset(-40f, 0f),
                end0 = center + Offset(-320f, 0f),
                start1 = center + Offset(40f, 0f),
                end1 = center + Offset(320f, 0f),
            )
        }
        rule.waitForIdle()

        assertTrue("pinch produced no zoom events", factors.isNotEmpty())
        assertEquals(ZoomLevel.Custom, zoom)
        val zoomedIn = customZoom
        assertTrue("pinch out should raise zoom: $fitScale -> $zoomedIn", zoomedIn > fitScale * 1.5f)

        rule.onRoot().performTouchInput {
            pinch(
                start0 = center + Offset(-320f, 0f),
                end0 = center + Offset(-40f, 0f),
                start1 = center + Offset(320f, 0f),
                end1 = center + Offset(40f, 0f),
            )
        }
        rule.waitForIdle()

        assertTrue("pinch in should lower zoom: $zoomedIn -> $customZoom", customZoom < zoomedIn * 0.75f)
        assertTrue("zoom must stay in Win32 range", customZoom >= kZoomMin / 100f)
        assertTrue("zoom must stay in Win32 range", customZoom <= kZoomMax / 100f)

        engine.closeAll()
    }

    // The page must keep drawing while the fingers are still down. The
    // regression this covers: every pointer frame changed the render
    // scale, which changed the render cache key, which blanked the page
    // until a fresh full-page mupdf render finished — so the document
    // vanished for the whole gesture and only came back on release.
    @Test
    fun pageKeepsDrawingDuringPinch() {
        val engine = DocumentEngine()
        val opened = engine.open(inkedPdf())
        assertTrue("engine failed to open sample: $opened", opened is DocumentEngine.Opened.Ok)
        engine.setActive((opened as DocumentEngine.Opened.Ok).handle)

        var zoom by mutableStateOf(ZoomLevel.FitPage)
        var customZoom by mutableFloatStateOf(1f)
        var effectiveScale = 1f

        rule.setContent {
            Box(Modifier.fillMaxSize()) {
                PageSurface(
                    engine = engine,
                    page = 0,
                    pageCount = engine.pageCount,
                    displayMode = DisplayMode.SinglePage,
                    continuous = false,
                    zoom = zoom,
                    customZoom = customZoom,
                    rotation = 0,
                    onTap = {},
                    onEdgeClick = {},
                    onEffectiveScale = { effectiveScale = it },
                    onPinchZoom = { factor, activeScale ->
                        val base = if (zoom == ZoomLevel.Custom) customZoom else activeScale
                        zoom = ZoomLevel.Custom
                        customZoom = (base * factor).coerceIn(kZoomMin / 100f, kZoomMax / 100f)
                    },
                )
            }
        }
        rule.waitForIdle()
        rule.waitUntil(timeoutMillis = 10_000) {
            inkFraction(rule.onRoot().captureToImage()) > 0.2f
        }

        val settled = inkFraction(rule.onRoot().captureToImage())

        val root = rule.onRoot()
        val bounds = root.fetchSemanticsNode().size
        val cx = bounds.width / 2f
        val cy = bounds.height / 2f
        root.performTouchInput {
            down(0, Offset(cx - 40f, cy))
            down(1, Offset(cx + 40f, cy))
        }

        var worst = 1f
        var slowestStepMs = 0L
        val rendersBefore = engine.renderCount
        for (step in 1..8) {
            val spread = 40f + step * 45f
            val elapsed = measureTimeMillis {
                root.performTouchInput {
                    moveTo(0, Offset(cx - spread, cy))
                    moveTo(1, Offset(cx + spread, cy))
                }
                rule.waitForIdle()
            }
            slowestStepMs = max(slowestStepMs, elapsed)
            val ink = inkFraction(rule.onRoot().captureToImage())
            worst = min(worst, ink)
        }

        val rendersDuringGesture = engine.renderCount - rendersBefore

        root.performTouchInput {
            up(0)
            up(1)
        }
        rule.waitForIdle()

        assertTrue(
            "pinch queued a render per pointer frame: $rendersDuringGesture renders over 8 moves",
            rendersDuringGesture <= 2,
        )
        assertTrue("page blanked mid-pinch: worst ink=$worst (at rest $settled)", worst > 0.2f)
        assertTrue("pinch step stalled on a render: ${slowestStepMs}ms", slowestStepMs < 400L)
        assertEquals(ZoomLevel.Custom, zoom)
        assertTrue(
            "pinch out should raise zoom: $effectiveScale -> $customZoom",
            customZoom > effectiveScale * 3f,
        )

        engine.closeAll()
    }
}

private const val kMinimalPdf = """%PDF-1.4
1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj
2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj
3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 612 792]/Resources<</Font<</F1 5 0 R>>>>/Contents 4 0 R>>endobj
4 0 obj<</Length 58>>stream
BT /F1 36 Tf 72 700 Td (Pinch zoom parity test) Tj ET
endstream
endobj
5 0 obj<</Type/Font/Subtype/Type1/BaseFont/Helvetica>>endobj
trailer<</Root 1 0 R>>"""

private const val kInkedPdf = """%PDF-1.4
1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj
2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj
3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 612 792]/Contents 4 0 R>>endobj
4 0 obj<</Length 26>>stream
1 0 0 rg 0 0 612 792 re f
endstream
endobj
trailer<</Root 1 0 R>>"""
