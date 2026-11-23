package tv.scenichome.launcher

import android.graphics.Bitmap
import kotlin.math.pow

internal fun frostedWallpaper(photo: Bitmap): Bitmap {
    val width = minOf(photo.width, 192)
    val height = (photo.height * width / photo.width).coerceAtLeast(1)
    val thumbnail = Bitmap.createScaledBitmap(photo, width, height, true)
    val pixels = IntArray(width * height)
    thumbnail.getPixels(pixels, 0, width, 0, 0, width, height)
    if (thumbnail !== photo) thumbnail.recycle()
    return Bitmap.createBitmap(blurArgb(pixels, width, height, 3), width, height, Bitmap.Config.ARGB_8888)
}

internal fun glassTint(peakLuminance: Float): Float {
    // Convert linear luminance to an sRGB level before tinting down to a readable gray.
    val level = if (peakLuminance <= 0.0031308f) {
        12.92f * peakLuminance
    } else {
        1.055f * peakLuminance.pow(1f / 2.4f) - 0.055f
    }
    return (1f - 0.44f / level.coerceAtLeast(0.44f)).coerceIn(0.12f, 0.56f)
}

internal fun blurArgb(pixels: IntArray, width: Int, height: Int, radius: Int): IntArray {
    val result = pixels.copyOf()
    val intermediate = IntArray(pixels.size)
    repeat(2) {
        blurLines(result, intermediate, width, height, 1, width, radius)
        blurLines(intermediate, result, height, width, width, 1, radius)
    }
    return result
}

private fun blurLines(
    source: IntArray,
    destination: IntArray,
    length: Int,
    lines: Int,
    stride: Int,
    lineStride: Int,
    radius: Int,
) {
    val samples = radius * 2 + 1
    for (line in 0 until lines) {
        val start = line * lineStride
        for (position in 0 until length) {
            var alpha = 0
            var red = 0
            var green = 0
            var blue = 0
            for (offset in -radius..radius) {
                val index = (position + offset).coerceIn(0, length - 1)
                val color = source[start + index * stride]
                alpha += color ushr 24
                red += (color ushr 16) and 255
                green += (color ushr 8) and 255
                blue += color and 255
            }
            destination[start + position * stride] =
                (alpha / samples shl 24) or (red / samples shl 16) or
                    (green / samples shl 8) or (blue / samples)
        }
    }
}
