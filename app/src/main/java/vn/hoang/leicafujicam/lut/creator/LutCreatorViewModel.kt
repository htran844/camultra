package vn.hoang.leicafujicam.lut.creator

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import vn.hoang.leicafujicam.R
import vn.hoang.leicafujicam.data.CustomImportManager
import vn.hoang.leicafujicam.data.ContentRepository
import vn.hoang.leicafujicam.lut.LutManager
import vn.hoang.leicafujicam.utils.PLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LutCreatorViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        const val PHOTO_SET_MIN_PHOTOS = 2
        const val PHOTO_SET_MAX_PHOTOS = 12
    }

    private val contentRepository = ContentRepository.getInstance(application)
    private val userPreferencesRepository = contentRepository.userPreferencesRepository
    private val billingManager = vn.hoang.leicafujicam.billing.BillingManagerImpl(application)
    private val importManager = CustomImportManager(application)
    private val lutManager = LutManager(application)

    private val _uiState = MutableStateFlow<LutCreatorUiState>(LutCreatorUiState.Idle)
    val uiState: StateFlow<LutCreatorUiState> = _uiState

    val aiAnalysisEnabled: MutableStateFlow<Boolean> = MutableStateFlow(false)
    val isPurchased = billingManager.isPurchased
    val openAIApiKey = userPreferencesRepository.userPreferences.map { it.openAIApiKey }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")
    var showPaymentDialog by mutableStateOf(false)

    // --- Photo set state (third LUT Creator tab, on-device only) ---
    private val _photoSetReport = MutableStateFlow<StyleReport?>(null)
    val photoSetReport: StateFlow<StyleReport?> = _photoSetReport
    private val _photoSetUris = MutableStateFlow<List<Uri>>(emptyList())
    val photoSetUris: StateFlow<List<Uri>> = _photoSetUris
    private val _photoSetTuning = MutableStateFlow(PhotoSetTuning.DEFAULT)
    val photoSetTuning: StateFlow<PhotoSetTuning> = _photoSetTuning
    private val _photoSetPreviewLut = MutableStateFlow<FloatArray?>(null)
    val photoSetPreviewLut: StateFlow<FloatArray?> = _photoSetPreviewLut
    private val _photoSetProgress = MutableStateFlow<Pair<Int, Int>?>(null)
    val photoSetProgress: StateFlow<Pair<Int, Int>?> = _photoSetProgress
    private val _photoSetSkippedCount = MutableStateFlow(0)
    val photoSetSkippedCount: StateFlow<Int> = _photoSetSkippedCount
    private val _lastExportedCubeFile = MutableStateFlow<File?>(null)
    val lastExportedCubeFile: StateFlow<File?> = _lastExportedCubeFile
    private var photoSetBaseRecipe: LutRecipe? = null
    private var photoSetTuningJob: Job? = null

    fun purchase(activity: android.app.Activity) {
        billingManager.purchase(activity)
    }

    fun analyzeAiImage(uri: Uri, customPrompt: String = "") {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = LutCreatorUiState.Analyzing

            try {
                val bitmap = loadBitmapFromUri(uri)
                if (bitmap == null) {
                    _uiState.value = LutCreatorUiState.Error("Failed to decode image(s)")
                    return@launch
                }

                val context = getApplication<Application>()
                val client = OpenAIApiClient()
                client.initialize(context)
                val preparedBitmap = AiImagePreprocessor.prepareForVisionAnalysis(bitmap)

                PLog.d(
                    "LutCreatorViewModel",
                    "Calling AI text recipe generation for single-image LUT creation..."
                )

                val recipe = client.generateLutRecipeFromImage(
                    bitmap = preparedBitmap,
                    customPrompt = customPrompt
                ).getOrThrow()

                PLog.d("LutCreatorViewModel", "AI text recipe: $recipe")

                _uiState.value = LutCreatorUiState.AnalysisComplete(recipe)
            } catch (e: Exception) {
                _uiState.value = LutCreatorUiState.Error("Analysis failed: ${e.message}")
            }
        }
    }

    fun analyzeAiImageWithImageEdit(uri: Uri, customPrompt: String = "") {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = LutCreatorUiState.Analyzing

            try {
                val bitmap = loadBitmapFromUri(uri)
                if (bitmap == null) {
                    _uiState.value = LutCreatorUiState.Error("Failed to decode image(s)")
                    return@launch
                }

                val context = getApplication<Application>()
                val client = OpenAIApiClient()
                client.initialize(context)

                PLog.d(
                    "LutCreatorViewModel",
                    "Calling Gemini 3.1 for direct image-to-image restoration..."
                )

                val preparedBitmap = AiImagePreprocessor.prepareForImageToImage(bitmap)
                val sourceBitmap = client.generateOriginalImage(
                    bitmap = preparedBitmap,
                    model = OpenAIApiClient.BUILT_IN_IMAGE_MODEL,
                    customPrompt = customPrompt
                ).getOrThrow()

                PLog.d("LutCreatorViewModel", "AI generated original image, analyzing pair locally...")
                val recipe = LocalImageAnalyzer.analyzeSourceTargetImages(sourceBitmap, preparedBitmap)

                PLog.d("LutCreatorViewModel", "Recipe: $recipe")

                _uiState.value = LutCreatorUiState.AnalysisComplete(recipe, sourceBitmap)
            } catch (e: Exception) {
                _uiState.value = LutCreatorUiState.Error("Analysis failed: ${e.message}")
            }
        }
    }

    fun analyzeLocalImagePairs(pairs: List<LocalImagePairInput>) {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = LutCreatorUiState.Analyzing

            try {
                if (pairs.isEmpty()) {
                    _uiState.value = LutCreatorUiState.Error("No image pairs selected")
                    return@launch
                }

                val bitmaps = pairs.mapIndexed { index, pair ->
                    val sourceBitmap = loadBitmapFromUri(pair.sourceUri)
                    val targetBitmap = loadBitmapFromUri(pair.targetUri)

                    if (sourceBitmap == null || targetBitmap == null) {
                        _uiState.value = LutCreatorUiState.Error("Failed to decode image pair ${index + 1}")
                        return@launch
                    }
                    sourceBitmap to targetBitmap
                }

                val recipe = LocalImageAnalyzer.analyzeSourceTargetImagePairs(bitmaps)
                PLog.d("LutCreatorViewModel", "Local multi-pair recipe: $recipe")
                _uiState.value = LutCreatorUiState.AnalysisComplete(recipe)
            } catch (e: Exception) {
                _uiState.value = LutCreatorUiState.Error("Analysis failed: ${e.message}")
            }
        }
    }

    /**
     * Analyzes a set of already-styled photos (no before/after pairs needed)
     * with the existing unpaired set engine and measures a [StyleReport]
     * from the same bitmaps. Runs fully on-device, no network calls.
     * Photos that fail to decode are skipped and counted; the analysis only
     * runs when at least [PHOTO_SET_MIN_PHOTOS] photos decoded.
     */
    fun analyzePhotoSet(uris: List<Uri>) {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = LutCreatorUiState.Analyzing
            _photoSetProgress.value = 0 to uris.size
            try {
                val app = getApplication<Application>()
                if (uris.size < PHOTO_SET_MIN_PHOTOS) {
                    _photoSetProgress.value = null
                    _uiState.value = LutCreatorUiState.Error(
                        app.getString(R.string.lut_creator_photo_set_too_few)
                    )
                    return@launch
                }
                val limited = uris.take(PHOTO_SET_MAX_PHOTOS)
                val bitmaps = mutableListOf<Bitmap>()
                val failed = mutableListOf<Uri>()
                limited.forEachIndexed { index, uri ->
                    val bitmap = loadBitmapFromUri(uri)
                    if (bitmap == null) {
                        failed.add(uri)
                    } else {
                        bitmaps.add(bitmap)
                    }
                    _photoSetProgress.value = (index + 1) to limited.size
                }
                _photoSetSkippedCount.value = failed.size
                if (bitmaps.size < PHOTO_SET_MIN_PHOTOS) {
                    bitmaps.forEach { it.recycle() }
                    _uiState.value = LutCreatorUiState.Error(
                        app.getString(R.string.lut_creator_photo_set_no_valid_photos)
                    )
                    return@launch
                }
                val (recipe, report) = try {
                    PhotoSetAnalyzer.analyzeImagesWithReport(bitmaps)
                } finally {
                    bitmaps.forEach { it.recycle() }
                }
                photoSetBaseRecipe = recipe
                _photoSetUris.value = limited
                _photoSetReport.value = report
                _photoSetTuning.value = PhotoSetTuning.DEFAULT
                _photoSetPreviewLut.value = LutGenerator.generateLut(recipe)
                _photoSetProgress.value = null
                PLog.d(
                    "LutCreatorViewModel",
                    "Photo set recipe: ${recipe.controlPoints.size} control points, " +
                        "skipped ${failed.size} unreadable photo(s)"
                )
                _uiState.value = LutCreatorUiState.AnalysisComplete(recipe)
            } catch (e: Exception) {
                _photoSetProgress.value = null
                _uiState.value = LutCreatorUiState.Error("Analysis failed: ${e.message}")
            }
        }
    }

    /** Applies [tuning] and regenerates the preview LUT, debounced by 150 ms. */
    fun updatePhotoSetTuning(tuning: PhotoSetTuning) {
        _photoSetTuning.value = tuning
        val base = photoSetBaseRecipe ?: return
        photoSetTuningJob?.cancel()
        photoSetTuningJob = viewModelScope.launch(Dispatchers.Default) {
            delay(150)
            val tuned = PhotoSetTuningApplier.applyTuning(base, tuning)
            _photoSetPreviewLut.value = LutGenerator.generateLut(tuned)
        }
    }

    fun resetPhotoSetTuning() {
        updatePhotoSetTuning(PhotoSetTuning.DEFAULT)
    }

    /** True when a LUT with this display name already exists (any language). */
    fun isLutNameTaken(name: String): Boolean {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return false
        return contentRepository.getAvailableLuts().any { lut ->
            lut.nameMap.values.any { it.equals(trimmed, ignoreCase = true) }
        }
    }

    /** Default name for a photo-set LUT, e.g. "My Set LUT 16/11". */
    fun defaultPhotoSetLutName(): String {
        val date = SimpleDateFormat("dd/MM", Locale.getDefault()).format(Date())
        val prefix = getApplication<Application>()
            .getString(R.string.lut_creator_photo_set_default_name_prefix)
        return "$prefix $date"
    }

    /**
     * Generates and imports the tuned photo-set LUT, then saves its numeric
     * [StyleReport] summary next to it. When [useFirstPhotoAsExample] is
     * true, the first photo of the set is also stored as the example
     * thumbnail; otherwise no image is stored.
     */
    fun generateAndImportPhotoSetLut(name: String, useFirstPhotoAsExample: Boolean) {
        val base = photoSetBaseRecipe ?: return
        val report = _photoSetReport.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = LutCreatorUiState.Generating
            try {
                val tuned = PhotoSetTuningApplier.applyTuning(base, _photoSetTuning.value)
                val lutId = importGeneratedLut(name, tuned, category = "Photo Set")
                if (lutId == null) {
                    _uiState.value = LutCreatorUiState.Error("Import failed")
                    return@launch
                }
                var exampleFileName: String? = null
                if (useFirstPhotoAsExample) {
                    val firstUri = _photoSetUris.value.firstOrNull()
                    val bitmap = firstUri?.let { loadBitmapFromUri(it) }
                    if (bitmap != null) {
                        try {
                            exampleFileName = PhotoSetStyleReportStore.saveExampleThumbnail(
                                getApplication(), lutId, bitmap
                            )
                        } finally {
                            bitmap.recycle()
                        }
                    }
                }
                PhotoSetStyleReportStore.saveReport(
                    getApplication(), lutId, name, report, exampleFileName
                )
                // Initialize to ensure memory cache and disk are synced
                lutManager.initialize()
                _uiState.value = LutCreatorUiState.Success(lutId)
            } catch (e: Exception) {
                _uiState.value = LutCreatorUiState.Error(e.message ?: "Unknown error")
            }
        }
    }

    private fun loadBitmapFromUri(uri: Uri): android.graphics.Bitmap? {
        val context = getApplication<Application>()
        return try {
            val source = android.graphics.ImageDecoder.createSource(context.contentResolver, uri)
            val bitmap = android.graphics.ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                decoder.setAllocator(android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE) // Needs mutable maybe later, software allows fast reading
            }
            val totalPixels = bitmap.width * bitmap.height
            if (totalPixels == 0) {
                PLog.w("LutCreatorViewModel", "Loaded bitmap has 0 total pixels for URI: $uri")
                return null
            }
            bitmap
        } catch (e: Exception) {
            PLog.e("LutCreatorViewModel", "decode failed, fallback to BitmapFactory: $uri", e)
            try {
                context.contentResolver.openInputStream(uri).use {
                    BitmapFactory.decodeStream(it)
                }
            } catch (e: Exception) {
                PLog.e("LutCreatorViewModel", "decode failed again: $uri", e)
                null
            }
        }
    }

    fun generateAndImportLut(name: String, recipe: LutRecipe, category: String = "AI LUT") {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = LutCreatorUiState.Generating

            try {
                val lutId = importGeneratedLut(name, recipe, category)

                if (lutId != null) {
                    // Initialize to ensure memory cache and disk are synced
                    lutManager.initialize()
                    _uiState.value = LutCreatorUiState.Success(lutId)
                } else {
                    _uiState.value = LutCreatorUiState.Error("Import failed")
                }
            } catch (e: Exception) {
                _uiState.value = LutCreatorUiState.Error(e.message ?: "Unknown error")
            }
        }
    }

    /**
     * Generates a 33x33x33 LUT from [recipe], exports it as `.cube` to a
     * uniquely named temp file (never a fixed name, so two generations in a
     * row cannot overwrite each other), keeps an exportable copy under the
     * cache `shared/` folder covered by the app FileProvider, and imports it
     * as a custom LUT. Returns the new LUT id, or null on failure.
     */
    private suspend fun importGeneratedLut(
        name: String,
        recipe: LutRecipe,
        category: String
    ): String? {
        // 1. Generate FloatArray 33x33x33x3
        val lutData = LutGenerator.generateLut(recipe)
        // 2. Export to .cube format
        val cubeContent = LutGenerator.exportToCubeString(lutData, 33, name)

        // 3. Write to a uniquely named temp file and use CustomImportManager
        val cacheDir = getApplication<Application>().cacheDir
        val tempFile = File(cacheDir, "lut_gen_${System.currentTimeMillis()}.cube")
        tempFile.writeText(cubeContent)

        // 4. Keep an exportable copy where the FileProvider can share it
        val exportDir = File(cacheDir, "shared").apply { mkdirs() }
        val exportFile = File(exportDir, sanitizeCubeFileName(name))
        exportFile.writeText(cubeContent)
        _lastExportedCubeFile.value = exportFile

        val uri = Uri.fromFile(tempFile)
        return importManager.importLut(uri, name, category)
    }

    private fun sanitizeCubeFileName(name: String): String {
        val sanitized = name.trim()
            .replace(Regex("[^A-Za-z0-9._-]+"), "_")
            .trim('_')
            .take(64)
            .ifBlank { "lut" }
        return "$sanitized.cube"
    }

    fun resetToIdle() {
        _uiState.value = LutCreatorUiState.Idle
        _photoSetReport.value = null
        _photoSetUris.value = emptyList()
        _photoSetTuning.value = PhotoSetTuning.DEFAULT
        _photoSetPreviewLut.value = null
        _photoSetProgress.value = null
        _photoSetSkippedCount.value = 0
        photoSetBaseRecipe = null
        photoSetTuningJob?.cancel()
    }
}

data class LocalImagePairInput(
    val sourceUri: Uri,
    val targetUri: Uri
)

sealed class LutCreatorUiState {
    object Idle : LutCreatorUiState()
    object Analyzing : LutCreatorUiState()
    data class AnalysisComplete(
        val recipe: LutRecipe,
        val generatedSourceBitmap: android.graphics.Bitmap? = null
    ) : LutCreatorUiState()
    object Generating : LutCreatorUiState()
    data class Success(val lutId: String) : LutCreatorUiState()
    data class Error(val message: String) : LutCreatorUiState()
}
