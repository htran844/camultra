package vn.hoang.leicafujicam.lut.creator

import android.graphics.Color
import androidx.annotation.Keep

/**
 * Manual fine-tuning applied on top of a photo-set recipe, before the LUT is
 * generated. Every slider edits the recipe's control points; no LUT node is
 * edited directly, so the result always goes through [LutGenerator] again.
 *
 * Ranges follow the design: strength 0..100% (default 100%), all other
 * sliders -50..+50, except skin, whose effective range is capped at
 * +/- [SKIN_LIMIT] so skin tones cannot be pushed into a blotchy look.
 */
@Keep
data class PhotoSetTuning(
    /** 0..1, default 1. 0 means the identity LUT. */
    val strength: Float = 1f,
    /** -50..+50. */
    val saturation: Float = 0f,
    /** -50..+50. */
    val contrast: Float = 0f,
    /** -50..+50. Positive is warmer. */
    val warmCool: Float = 0f,
    /** -50..+50. Only control points whose target hue is sky blue. */
    val sky: Float = 0f,
    /** -50..+50. Only control points whose target hue is foliage green. */
    val foliage: Float = 0f,
    /** -50..+50 in the UI, but capped to +/- [SKIN_LIMIT] when applied. */
    val skin: Float = 0f
) {
    companion object {
        const val SKIN_LIMIT = 15f
        val DEFAULT = PhotoSetTuning()
    }
}

object PhotoSetTuningApplier {

    private const val SKY_HUE_MIN = 200f
    private const val SKY_HUE_MAX = 220f
    private const val FOLIAGE_HUE_MIN = 90f
    private const val FOLIAGE_HUE_MAX = 130f
    private const val SKIN_HUE_MIN = 20f
    private const val SKIN_HUE_MAX = 35f

    /** Hue shift in degrees for a slider at full +50, and saturation scale. */
    private const val BAND_HUE_SHIFT_PER_FULL = 15f
    private const val BAND_SAT_SCALE_PER_FULL = 0.5f

    fun applyTuning(recipe: LutRecipe, tuning: PhotoSetTuning): LutRecipe {
        if (tuning == PhotoSetTuning.DEFAULT) return recipe
        val points = recipe.controlPoints.map { point ->
            applyToPoint(point, tuning)
        }
        return recipe.copy(controlPoints = points)
    }

    private fun applyToPoint(point: ControlPoint, tuning: PhotoSetTuning): ControlPoint {
        var r = point.targetR
        var g = point.targetG
        var b = point.targetB

        // 1. Strength: interpolate the target back towards the source.
        val strength = tuning.strength.coerceIn(0f, 1f)
        if (strength < 1f) {
            r = point.sourceR + (r - point.sourceR) * strength
            g = point.sourceG + (g - point.sourceG) * strength
            b = point.sourceB + (b - point.sourceB) * strength
        }

        // 2. Saturation: scale the distance from the target luma.
        if (tuning.saturation != 0f) {
            val luma = 0.2126f * r + 0.7152f * g + 0.0722f * b
            val scale = 1f + tuning.saturation / 100f
            r = luma + (r - luma) * scale
            g = luma + (g - luma) * scale
            b = luma + (b - luma) * scale
        }

        // 3. Contrast: pivot every channel around 0.5.
        if (tuning.contrast != 0f) {
            val scale = 1f + tuning.contrast / 100f
            r = 0.5f + (r - 0.5f) * scale
            g = 0.5f + (g - 0.5f) * scale
            b = 0.5f + (b - 0.5f) * scale
        }

        // 4. Warm / Cool: shift the R/B balance of the target.
        if (tuning.warmCool != 0f) {
            val delta = tuning.warmCool / 100f * 0.25f
            r *= 1f + delta
            b *= 1f - delta
        }

        r = r.coerceIn(0f, 1f)
        g = g.coerceIn(0f, 1f)
        b = b.coerceIn(0f, 1f)

        // 5. Per-band hue / saturation, only for points in that hue band.
        if (tuning.sky != 0f || tuning.foliage != 0f || tuning.skin != 0f) {
            val hsv = FloatArray(3)
            Color.RGBToHSV(
                (r * 255f).toInt().coerceIn(0, 255),
                (g * 255f).toInt().coerceIn(0, 255),
                (b * 255f).toInt().coerceIn(0, 255),
                hsv
            )
            val hue = hsv[0]
            val amount = when {
                hue in SKY_HUE_MIN..SKY_HUE_MAX -> tuning.sky
                hue in FOLIAGE_HUE_MIN..FOLIAGE_HUE_MAX -> tuning.foliage
                hue in SKIN_HUE_MIN..SKIN_HUE_MAX ->
                    tuning.skin.coerceIn(-PhotoSetTuning.SKIN_LIMIT, PhotoSetTuning.SKIN_LIMIT)
                else -> 0f
            }
            if (amount != 0f) {
                hsv[0] = (hue + amount / 50f * BAND_HUE_SHIFT_PER_FULL + 360f) % 360f
                hsv[1] = (hsv[1] * (1f + amount / 100f * BAND_SAT_SCALE_PER_FULL)).coerceIn(0f, 1f)
                val packed = Color.HSVToColor(hsv)
                r = Color.red(packed) / 255f
                g = Color.green(packed) / 255f
                b = Color.blue(packed) / 255f
            }
        }

        return point.copy(
            targetR = r.coerceIn(0f, 1f),
            targetG = g.coerceIn(0f, 1f),
            targetB = b.coerceIn(0f, 1f)
        )
    }
}
