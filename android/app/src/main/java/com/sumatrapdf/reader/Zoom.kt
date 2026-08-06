package com.sumatrapdf.reader

const val kZoomMax = 6400f
const val kZoomMin = 8.33f

private val defaultZoomLevels = floatArrayOf(
    8.33f, 12.5f, 18f, 25f, 33.33f, 50f, 66.67f, 75f,
    100f, 125f, 150f, 200f, 300f, 400f, 600f, 800f, 1000f,
    1200f, 1600f, 2000f, 2400f, 3200f, 4800f, 6400f,
)

fun nextZoomStep(currentPercent: Float, towardsLevel: Float): Float {
    if (currentPercent == towardsLevel) return towardsLevel

    val fuzz = 0.01f
    var newZoom = towardsLevel
    if (currentPercent + fuzz < towardsLevel) {
        for (zoom in defaultZoomLevels) {
            if (zoom - fuzz > currentPercent) {
                newZoom = zoom
                break
            }
        }
    } else if (currentPercent - fuzz > towardsLevel) {
        for (i in defaultZoomLevels.indices.reversed()) {
            val zoom = defaultZoomLevels[i]
            if (zoom + fuzz < currentPercent) {
                newZoom = zoom
                break
            }
        }
    }
    return newZoom.coerceIn(kZoomMin, kZoomMax)
}
