package vn.hoang.leicafujicam.lut.creator

import android.content.Context
import android.graphics.Bitmap
import org.json.JSONObject
import vn.hoang.leicafujicam.utils.PLog
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max

/**
 * Persists the numeric [StyleReport] summary next to a generated LUT.
 *
 * Storage is a sidecar JSON file `<lutId>.style.json` inside the custom LUT
 * directory (`filesDir/custom_luts`, the same directory
 * [vn.hoang.leicafujicam.data.CustomImportManager] writes its `.plut`
 * files to). No image data is stored in the report. An example thumbnail is
 * only stored when the user opts in, as `<lutId>_example.jpg` next to it,
 * and its file name is recorded in the sidecar so a later change can find it.
 *
 * This store never touches the custom LUT config or the `.plut` files.
 */
object PhotoSetStyleReportStore {

    private const val TAG = "PhotoSetStyleReport"
    private const val CUSTOM_LUT_DIR = "custom_luts"
    private const val MAX_EXAMPLE_WIDTH = 560
    private const val EXAMPLE_JPEG_QUALITY = 78

    private fun customLutDir(context: Context): File =
        File(context.filesDir, CUSTOM_LUT_DIR)

    private fun reportFile(context: Context, lutId: String): File =
        File(customLutDir(context), "$lutId.style.json")

    /** Saves the report summary for [lutId]. Returns false on I/O failure. */
    fun saveReport(
        context: Context,
        lutId: String,
        lutName: String,
        report: StyleReport,
        exampleFileName: String?
    ): Boolean {
        return try {
            val dir = customLutDir(context)
            if (!dir.exists()) dir.mkdirs()
            val json = JSONObject().apply {
                put("lutId", lutId)
                put("lutName", lutName)
                put("createdAtMillis", System.currentTimeMillis())
                if (exampleFileName != null) put("exampleFileName", exampleFileName)
                put("report", report.toJson())
            }
            reportFile(context, lutId).writeText(json.toString())
            true
        } catch (e: Exception) {
            PLog.e(TAG, "Failed to save style report for $lutId", e)
            false
        }
    }

    /** Loads the saved report for [lutId], or null when none exists. */
    fun loadReport(context: Context, lutId: String): StyleReport? {
        return try {
            val file = reportFile(context, lutId)
            if (!file.exists()) return null
            val json = JSONObject(file.readText())
            json.optJSONObject("report")?.let { StyleReport.fromJson(it) }
        } catch (e: Exception) {
            PLog.e(TAG, "Failed to load style report for $lutId", e)
            null
        }
    }

    /**
     * Saves a downscaled JPEG copy of [bitmap] as the LUT example thumbnail.
     * Returns the stored file name, or null on failure.
     */
    fun saveExampleThumbnail(context: Context, lutId: String, bitmap: Bitmap): String? {
        return try {
            val dir = customLutDir(context)
            if (!dir.exists()) dir.mkdirs()
            val fileName = "${lutId}_example.jpg"
            val scaled = if (bitmap.width > MAX_EXAMPLE_WIDTH) {
                val scale = MAX_EXAMPLE_WIDTH.toFloat() / bitmap.width
                Bitmap.createScaledBitmap(
                    bitmap,
                    MAX_EXAMPLE_WIDTH,
                    max(1, (bitmap.height * scale).toInt()),
                    true
                )
            } else {
                bitmap
            }
            try {
                FileOutputStream(File(dir, fileName)).use { out ->
                    if (!scaled.compress(Bitmap.CompressFormat.JPEG, EXAMPLE_JPEG_QUALITY, out)) {
                        return null
                    }
                }
            } finally {
                if (scaled != bitmap) scaled.recycle()
            }
            fileName
        } catch (e: Exception) {
            PLog.e(TAG, "Failed to save example thumbnail for $lutId", e)
            null
        }
    }
}
