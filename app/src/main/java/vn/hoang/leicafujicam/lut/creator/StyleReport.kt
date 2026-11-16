package vn.hoang.leicafujicam.lut.creator

import androidx.annotation.Keep
import org.json.JSONArray
import org.json.JSONObject

/**
 * Measured style of one colour band (sky / foliage / skin) inside a photo set.
 *
 * Hue and saturation are medians over the band pixels, following the same
 * measurement style as the hand-tuned Tomosaki spec (sky hue ~206 sat .64,
 * foliage hue ~110 sat .55, skin hue ~24 sat .41). A band whose pixel
 * coverage is below [MIN_COVERAGE] is reported as not having enough data
 * instead of inventing numbers.
 */
@Keep
data class BandMeasurement(
    val hueMedian: Float?,
    val saturationMedian: Float?,
    val hueStdDev: Float?,
    val coverage: Float,
    val hasEnoughData: Boolean
) {
    companion object {
        fun noData(): BandMeasurement =
            BandMeasurement(
                hueMedian = null,
                saturationMedian = null,
                hueStdDev = null,
                coverage = 0f,
                hasEnoughData = false
            )
    }
}

/**
 * Numeric style summary measured from a photo set, without any image data.
 *
 * This is the report shown to the user before a LUT is generated, and the
 * summary saved next to a generated LUT (see [PhotoSetStyleReportStore]).
 * It never contains photos, only numbers.
 */
@Keep
data class StyleReport(
    /** White-balance gains relative to green, as (R, G, B). */
    val whiteBalance: Triple<Float, Float, Float>,
    /** 1st percentile of luma: how deep the blacks go. */
    val blackPoint: Float,
    /** 99th percentile of luma: how bright the whites go. */
    val whitePoint: Float,
    /** Median luma. */
    val medianLuma: Float,
    /** Median HSV saturation vs the 0.5 reference, in percent (e.g. +22). */
    val saturationDeltaPercent: Int,
    val sky: BandMeasurement,
    val foliage: BandMeasurement,
    val skin: BandMeasurement,
    /** Mean (R - luma, G - luma, B - luma) of pixels with luma < 0.25. */
    val shadowTint: Triple<Float, Float, Float>,
    /** Mean (R - luma, G - luma, B - luma) of pixels with luma > 0.75. */
    val highlightTint: Triple<Float, Float, Float>,
    /** Number of control points in the recipe derived from this set. */
    val controlPointCount: Int,
    /**
     * Global match ratio of the recipe engine, or null when the engine does
     * not report one (the unpaired photo-set engine has no source/target
     * matching to score).
     */
    val globalMatchRatio: Float?,
    /** True when any measured band is widely spread, i.e. the set mixes styles. */
    val isMixedStyle: Boolean
) {
    fun toJson(): JSONObject {
        val json = JSONObject()
        json.put("whiteBalance", tripleToJson(whiteBalance))
        json.put("blackPoint", blackPoint.toDouble())
        json.put("whitePoint", whitePoint.toDouble())
        json.put("medianLuma", medianLuma.toDouble())
        json.put("saturationDeltaPercent", saturationDeltaPercent)
        json.put("sky", bandToJson(sky))
        json.put("foliage", bandToJson(foliage))
        json.put("skin", bandToJson(skin))
        json.put("shadowTint", tripleToJson(shadowTint))
        json.put("highlightTint", tripleToJson(highlightTint))
        json.put("controlPointCount", controlPointCount)
        if (globalMatchRatio != null) {
            json.put("globalMatchRatio", globalMatchRatio.toDouble())
        }
        json.put("isMixedStyle", isMixedStyle)
        return json
    }

    companion object {
        /** A band below this pixel coverage is reported as "not enough data". */
        const val MIN_COVERAGE = 0.02f

        private fun tripleToJson(triple: Triple<Float, Float, Float>): JSONArray =
            JSONArray().apply {
                put(triple.first.toDouble())
                put(triple.second.toDouble())
                put(triple.third.toDouble())
            }

        private fun bandToJson(band: BandMeasurement): JSONObject =
            JSONObject().apply {
                if (band.hueMedian != null) put("hueMedian", band.hueMedian.toDouble())
                if (band.saturationMedian != null) {
                    put("saturationMedian", band.saturationMedian.toDouble())
                }
                if (band.hueStdDev != null) put("hueStdDev", band.hueStdDev.toDouble())
                put("coverage", band.coverage.toDouble())
                put("hasEnoughData", band.hasEnoughData)
            }

        private fun jsonToTriple(json: JSONObject, key: String): Triple<Float, Float, Float> {
            val array = json.optJSONArray(key) ?: return Triple(1f, 1f, 1f)
            return Triple(
                array.optDouble(0, 1.0).toFloat(),
                array.optDouble(1, 1.0).toFloat(),
                array.optDouble(2, 1.0).toFloat()
            )
        }

        private fun jsonToBand(json: JSONObject, key: String): BandMeasurement {
            val obj = json.optJSONObject(key) ?: return BandMeasurement.noData()
            return BandMeasurement(
                hueMedian = if (obj.has("hueMedian")) obj.optDouble("hueMedian").toFloat() else null,
                saturationMedian =
                    if (obj.has("saturationMedian")) obj.optDouble("saturationMedian").toFloat() else null,
                hueStdDev =
                    if (obj.has("hueStdDev")) obj.optDouble("hueStdDev").toFloat() else null,
                coverage = obj.optDouble("coverage", 0.0).toFloat(),
                hasEnoughData = obj.optBoolean("hasEnoughData", false)
            )
        }

        fun fromJson(json: JSONObject): StyleReport? {
            return try {
                StyleReport(
                    whiteBalance = jsonToTriple(json, "whiteBalance"),
                    blackPoint = json.optDouble("blackPoint", 0.0).toFloat(),
                    whitePoint = json.optDouble("whitePoint", 1.0).toFloat(),
                    medianLuma = json.optDouble("medianLuma", 0.5).toFloat(),
                    saturationDeltaPercent = json.optInt("saturationDeltaPercent", 0),
                    sky = jsonToBand(json, "sky"),
                    foliage = jsonToBand(json, "foliage"),
                    skin = jsonToBand(json, "skin"),
                    shadowTint = jsonToTriple(json, "shadowTint"),
                    highlightTint = jsonToTriple(json, "highlightTint"),
                    controlPointCount = json.optInt("controlPointCount", 0),
                    globalMatchRatio =
                        if (json.has("globalMatchRatio")) json.optDouble("globalMatchRatio").toFloat() else null,
                    isMixedStyle = json.optBoolean("isMixedStyle", false)
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}
