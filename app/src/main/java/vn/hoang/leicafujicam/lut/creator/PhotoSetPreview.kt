package vn.hoang.leicafujicam.lut.creator

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.core.graphics.scale
import kotlin.math.max
import kotlin.math.min

/**
 * CPU preview for a generated LUT: applies a [LutGenerator] FloatArray grid
 * to a downscaled bitmap with trilinear interpolation.
 *
 * The grid layout is the one [LutGenerator] produces: node index
 * `(b * size + g) * size + r`, each node holding R, G, B in that order.
 * Preview only; the real photo/video pipeline is untouched.
 */
object PhotoSetPreview {

    /** Loads a bitmap downscaled so its longest edge is at most [maxDim]. */
    fun loadPreviewBitmap(context: Context, uri: Uri, maxDim: Int = 480): Bitmap? {
        val bitmap = try {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE)
            }
        } catch (e: Exception) {
            try {
                context.contentResolver.openInputStream(uri).use {
                    BitmapFactory.decodeStream(it)
                }
            } catch (e2: Exception) {
                null
            }
        } ?: return null
        if (bitmap.width <= 0 || bitmap.height <= 0) {
            bitmap.recycle()
            return null
        }
        if (max(bitmap.width, bitmap.height) <= maxDim) return bitmap
        val scale = maxDim.toFloat() / max(bitmap.width, bitmap.height)
        val scaled = bitmap.scale(
            (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1)
        )
        bitmap.recycle()
        return scaled
    }

    /** Returns a new bitmap with [lutData] applied; the source is not modified. */
    fun applyLutToBitmap(source: Bitmap, lutData: FloatArray, size: Int = 33): Bitmap {
        require(lutData.size == size * size * size * 3) {
            "LUT data size ${lutData.size} does not match a ${size}x${size}x${size} grid"
        }
        val width = source.width
        val height = source.height
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)
        val out = IntArray(width * height)
        for (i in pixels.indices) {
            val color = pixels[i]
            val sampled = sample(
                lutData, size,
                Color.red(color) / 255f,
                Color.green(color) / 255f,
                Color.blue(color) / 255f
            )
            out[i] = Color.argb(
                Color.alpha(color),
                (sampled[0].coerceIn(0f, 1f) * 255f).toInt(),
                (sampled[1].coerceIn(0f, 1f) * 255f).toInt(),
                (sampled[2].coerceIn(0f, 1f) * 255f).toInt()
            )
        }
        return Bitmap.createBitmap(out, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun sample(lut: FloatArray, size: Int, r: Float, g: Float, b: Float): FloatArray {
        val x = (r.coerceIn(0f, 1f) * (size - 1))
        val y = (g.coerceIn(0f, 1f) * (size - 1))
        val z = (b.coerceIn(0f, 1f) * (size - 1))
        val x0 = x.toInt().coerceIn(0, size - 1)
        val y0 = y.toInt().coerceIn(0, size - 1)
        val z0 = z.toInt().coerceIn(0, size - 1)
        val x1 = min(x0 + 1, size - 1)
        val y1 = min(y0 + 1, size - 1)
        val z1 = min(z0 + 1, size - 1)
        val fx = x - x0
        val fy = y - y0
        val fz = z - z0

        fun node(bi: Int, gi: Int, ri: Int): FloatArray {
            val offset = ((bi * size + gi) * size + ri) * 3
            return floatArrayOf(lut[offset], lut[offset + 1], lut[offset + 2])
        }

        val c000 = node(z0, y0, x0)
        val c001 = node(z0, y0, x1)
        val c010 = node(z0, y1, x0)
        val c011 = node(z0, y1, x1)
        val c100 = node(z1, y0, x0)
        val c101 = node(z1, y0, x1)
        val c110 = node(z1, y1, x0)
        val c111 = node(z1, y1, x1)

        val result = FloatArray(3)
        for (c in 0..2) {
            val c00 = c000[c] * (1f - fx) + c001[c] * fx
            val c01 = c010[c] * (1f - fx) + c011[c] * fx
            val c10 = c100[c] * (1f - fx) + c101[c] * fx
            val c11 = c110[c] * (1f - fx) + c111[c] * fx
            val c0 = c00 * (1f - fy) + c01 * fy
            val c1 = c10 * (1f - fy) + c11 * fy
            result[c] = c0 * (1f - fz) + c1 * fz
        }
        return result
    }
}
