package vn.hoang.leicafujicam.lut.creator

import android.graphics.Bitmap
import android.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import vn.hoang.leicafujicam.utils.PLog
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Photo-set analysis: runs the existing unpaired set engine
 * ([LocalImageAnalyzer.analyzeImages]) and measures a [StyleReport] from the
 * same source bitmaps.
 *
 * The analysis algorithm itself is not changed here. The report only measures
 * what is visible in the finished photos (white balance, tone percentiles,
 * saturation, and the sky / foliage / skin bands, each with its pixel
 * coverage), in the same measurement style as the hand-tuned Tomosaki spec.
 * A band with coverage below [StyleReport.MIN_COVERAGE] is reported as not
 * having enough data; no numbers are invented for it.
 */
object PhotoSetAnalyzer {

    private const val TAG = "PhotoSetAnalyzer"
    private const val MAX_DIM = 256
    private const val MIXED_HUE_STD_DEV = 30f

    // Band definitions (hue in degrees, saturation and luma in 0..1).
    private const val SKY_HUE_MIN = 200f
    private const val SKY_HUE_MAX = 220f
    private const val SKY_LUMA_MIN = 0.35f
    private const val SKY_LUMA_MAX = 0.85f
    private const val SKY_MIN_SAT = 0.25f

    private const val FOLIAGE_HUE_MIN = 90f
    private const val FOLIAGE_HUE_MAX = 130f
    private const val FOLIAGE_MIN_SAT = 0.25f

    private const val SKIN_HUE_MIN = 20f
    private const val SKIN_HUE_MAX = 35f
    private const val SKIN_LUMA_MIN = 0.35f
    private const val SKIN_LUMA_MAX = 0.8f
    private const val SKIN_SAT_MIN = 0.15f
    private const val SKIN_SAT_MAX = 0.6f

    private const val NEUTRAL_MAX_SAT = 0.15f
    private const val BRIGHT_LUMA_MIN = 0.6f
    private const val SHADOW_LUMA_MAX = 0.25f
    private const val HIGHLIGHT_LUMA_MIN = 0.75f

    /** Runs the set engine and measures the style report from the same bitmaps. */
    suspend fun analyzeImagesWithReport(bitmaps: List<Bitmap>): Pair<LutRecipe, StyleReport> =
        withContext(Dispatchers.Default) {
            val recipe = LocalImageAnalyzer.analyzeImages(bitmaps)
            val report = computeStyleReport(bitmaps, recipe.controlPoints.size)
            PLog.d(
                TAG,
                "Photo set: ${recipe.controlPoints.size} control points, " +
                    "sky coverage=${report.sky.coverage}, " +
                    "foliage coverage=${report.foliage.coverage}, " +
                    "skin coverage=${report.skin.coverage}"
            )
            recipe to report
        }

    private fun computeStyleReport(bitmaps: List<Bitmap>, controlPointCount: Int): StyleReport {
        var totalPixels = 0
        for (bitmap in bitmaps) {
            totalPixels += downscaledSize(bitmap).let { (w, h) -> w * h }
        }
        if (totalPixels == 0) {
            return StyleReport(
                whiteBalance = Triple(1f, 1f, 1f),
                blackPoint = 0f,
                whitePoint = 1f,
                medianLuma = 0.5f,
                saturationDeltaPercent = 0,
                sky = BandMeasurement.noData(),
                foliage = BandMeasurement.noData(),
                skin = BandMeasurement.noData(),
                shadowTint = Triple(0f, 0f, 0f),
                highlightTint = Triple(0f, 0f, 0f),
                controlPointCount = controlPointCount,
                globalMatchRatio = null,
                isMixedStyle = false
            )
        }

        val lumas = FloatArray(totalPixels)
        val saturations = FloatArray(totalPixels)
        var index = 0

        var neutralBrightR = 0.0
        var neutralBrightG = 0.0
        var neutralBrightB = 0.0
        var neutralBrightCount = 0
        var neutralR = 0.0
        var neutralG = 0.0
        var neutralB = 0.0
        var neutralCount = 0

        var shadowR = 0.0
        var shadowG = 0.0
        var shadowB = 0.0
        var shadowCount = 0
        var highlightR = 0.0
        var highlightG = 0.0
        var highlightB = 0.0
        var highlightCount = 0

        val skyHues = ArrayList<Float>()
        val skySats = ArrayList<Float>()
        val foliageHues = ArrayList<Float>()
        val foliageSats = ArrayList<Float>()
        val skinHues = ArrayList<Float>()
        val skinSats = ArrayList<Float>()

        for (bitmap in bitmaps) {
            val (w, h) = downscaledSize(bitmap)
            val scaled = if (w != bitmap.width || h != bitmap.height) {
                Bitmap.createScaledBitmap(bitmap, w, h, true)
            } else {
                bitmap
            }
            val pixels = IntArray(w * h)
            scaled.getPixels(pixels, 0, w, 0, 0, w, h)
            if (scaled != bitmap) {
                scaled.recycle()
            }

            for (color in pixels) {
                val r = Color.red(color) / 255f
                val g = Color.green(color) / 255f
                val b = Color.blue(color) / 255f
                val luma = 0.2126f * r + 0.7152f * g + 0.0722f * b
                val (hue, sat) = hueAndSaturation(r, g, b)

                lumas[index] = luma
                saturations[index] = sat
                index++

                if (sat < NEUTRAL_MAX_SAT) {
                    neutralR += r
                    neutralG += g
                    neutralB += b
                    neutralCount++
                    if (luma > BRIGHT_LUMA_MIN) {
                        neutralBrightR += r
                        neutralBrightG += g
                        neutralBrightB += b
                        neutralBrightCount++
                    }
                }

                if (luma < SHADOW_LUMA_MAX) {
                    shadowR += r - luma
                    shadowG += g - luma
                    shadowB += b - luma
                    shadowCount++
                } else if (luma > HIGHLIGHT_LUMA_MIN) {
                    highlightR += r - luma
                    highlightG += g - luma
                    highlightB += b - luma
                    highlightCount++
                }

                if (hue in SKY_HUE_MIN..SKY_HUE_MAX && luma in SKY_LUMA_MIN..SKY_LUMA_MAX && sat > SKY_MIN_SAT) {
                    skyHues.add(hue)
                    skySats.add(sat)
                }
                if (hue in FOLIAGE_HUE_MIN..FOLIAGE_HUE_MAX && sat > FOLIAGE_MIN_SAT) {
                    foliageHues.add(hue)
                    foliageSats.add(sat)
                }
                if (hue in SKIN_HUE_MIN..SKIN_HUE_MAX && luma in SKIN_LUMA_MIN..SKIN_LUMA_MAX &&
                    sat in SKIN_SAT_MIN..SKIN_SAT_MAX
                ) {
                    skinHues.add(hue)
                    skinSats.add(sat)
                }
            }
        }

        val sortedLumas = lumas.sortedArray()
        val sortedSats = saturations.sortedArray()
        val medianSat = medianOfSorted(sortedSats)
        val saturationDeltaPercent = ((medianSat / 0.5f - 1f) * 100f).toInt()

        val whiteBalance = whiteBalanceFromNeutrals(
            neutralBrightR, neutralBrightG, neutralBrightB, neutralBrightCount,
            neutralR, neutralG, neutralB, neutralCount
        )
        val shadowTint = tintFromSums(shadowR, shadowG, shadowB, shadowCount)
        val highlightTint = tintFromSums(highlightR, highlightG, highlightB, highlightCount)

        val sky = measureBand(skyHues, skySats, totalPixels)
        val foliage = measureBand(foliageHues, foliageSats, totalPixels)
        val skin = measureBand(skinHues, skinSats, totalPixels)
        val isMixedStyle = listOf(sky, foliage, skin).any { band ->
            band.hasEnoughData && (band.hueStdDev ?: 0f) > MIXED_HUE_STD_DEV
        }

        return StyleReport(
            whiteBalance = whiteBalance,
            blackPoint = percentileOfSorted(sortedLumas, 0.01f),
            whitePoint = percentileOfSorted(sortedLumas, 0.99f),
            medianLuma = medianOfSorted(sortedLumas),
            saturationDeltaPercent = saturationDeltaPercent,
            sky = sky,
            foliage = foliage,
            skin = skin,
            shadowTint = shadowTint,
            highlightTint = highlightTint,
            controlPointCount = controlPointCount,
            globalMatchRatio = null,
            isMixedStyle = isMixedStyle
        )
    }

    private fun downscaledSize(bitmap: Bitmap): Pair<Int, Int> {
        var w = bitmap.width
        var h = bitmap.height
        if (w > MAX_DIM || h > MAX_DIM) {
            val scale = MAX_DIM.toFloat() / max(w, h)
            w = (w * scale).toInt().coerceAtLeast(1)
            h = (h * scale).toInt().coerceAtLeast(1)
        }
        return w to h
    }

    /** Standard RGB -> hue (degrees 0..360) and HSV saturation. */
    private fun hueAndSaturation(r: Float, g: Float, b: Float): Pair<Float, Float> {
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        val d = mx - mn
        val sat = if (mx > 0f) d / mx else 0f
        if (d <= 0f) return 0f to sat
        var hue = when (mx) {
            r -> ((g - b) / d) % 6f
            g -> (b - r) / d + 2f
            else -> (r - g) / d + 4f
        } * 60f
        if (hue < 0f) hue += 360f
        return hue to sat
    }

    private fun whiteBalanceFromNeutrals(
        brightR: Double, brightG: Double, brightB: Double, brightCount: Int,
        allR: Double, allG: Double, allB: Double, allCount: Int
    ): Triple<Float, Float, Float> {
        val (r, g, b, count) = if (brightCount > 0) {
            Quad(brightR, brightG, brightB, brightCount)
        } else {
            Quad(allR, allG, allB, allCount)
        }
        if (count == 0 || g <= 0.0) return Triple(1f, 1f, 1f)
        return Triple((r / g).toFloat(), 1f, (b / g).toFloat())
    }

    private fun tintFromSums(
        sumR: Double, sumG: Double, sumB: Double, count: Int
    ): Triple<Float, Float, Float> {
        if (count == 0) return Triple(0f, 0f, 0f)
        return Triple(
            (sumR / count).toFloat(),
            (sumG / count).toFloat(),
            (sumB / count).toFloat()
        )
    }

    private fun measureBand(
        hues: List<Float>, sats: List<Float>, totalPixels: Int
    ): BandMeasurement {
        val coverage = if (totalPixels > 0) hues.size.toFloat() / totalPixels else 0f
        if (coverage < StyleReport.MIN_COVERAGE || hues.isEmpty()) {
            return BandMeasurement(
                hueMedian = null,
                saturationMedian = null,
                hueStdDev = null,
                coverage = coverage,
                hasEnoughData = false
            )
        }
        val sortedHues = hues.sorted()
        val hueMedian = medianOfSortedList(sortedHues)
        var variance = 0.0
        for (hue in hues) {
            val diff = (hue - hueMedian).toDouble()
            variance += diff * diff
        }
        val hueStdDev = sqrt(variance / hues.size).toFloat()
        return BandMeasurement(
            hueMedian = hueMedian,
            saturationMedian = medianOfSortedList(sats.sorted()),
            hueStdDev = hueStdDev,
            coverage = coverage,
            hasEnoughData = true
        )
    }

    private fun medianOfSorted(sorted: FloatArray): Float {
        if (sorted.isEmpty()) return 0f
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            (sorted[mid - 1] + sorted[mid]) / 2f
        } else {
            sorted[mid]
        }
    }

    private fun medianOfSortedList(sorted: List<Float>): Float {
        if (sorted.isEmpty()) return 0f
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            (sorted[mid - 1] + sorted[mid]) / 2f
        } else {
            sorted[mid]
        }
    }

    private fun percentileOfSorted(sorted: FloatArray, fraction: Float): Float {
        if (sorted.isEmpty()) return 0f
        val pos = fraction * (sorted.size - 1)
        val lo = pos.toInt().coerceIn(0, sorted.size - 1)
        val hi = min(lo + 1, sorted.size - 1)
        val frac = pos - lo
        return sorted[lo] * (1f - frac) + sorted[hi] * frac
    }

    private data class Quad(val r: Double, val g: Double, val b: Double, val count: Int)
}
