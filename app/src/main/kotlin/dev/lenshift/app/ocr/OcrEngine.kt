package dev.lenshift.app.ocr

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.googlecode.tesseract.android.TessBaseAPI
import dev.lenshift.app.data.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Fully offline OCR using Tesseract4Android (Apache-2.0).
 *
 * Why Tesseract rather than ML Kit:
 *  - No Google Play services dependency, bundled or otherwise.
 *  - No network access at all, ever. ML Kit's bundled variant is offline after install
 *    but is still Google-proprietary code with an opaque update path.
 *  - Apache-2.0 licensing is compatible with redistribution.
 *
 * The trained data is copied from `assets/tessdata` into app-private storage once, then
 * reused. Nothing is written outside `filesDir`.
 *
 * The engine is created lazily and kept in a single mutex-guarded instance because
 * [TessBaseAPI] is not thread-safe.
 */
class OcrEngine(
    private val context: Context,
    private val settings: SettingsRepository
) {

    private val tessDir: File by lazy {
        File(context.filesDir, "tesseract").apply { mkdirs() }
    }

    private val tessdataDir: File by lazy {
        File(tessDir, "tessdata").apply { mkdirs() }
    }

    private val initialised = AtomicBoolean(false)
    private val initMutex = Mutex()
    private val apiMutex = Mutex()

    @Volatile
    private var api: TessBaseAPI? = null

    @Volatile
    private var loadedLanguage: String? = null

    @Volatile
    private var initFailure: String? = null

    val isAvailable: Boolean get() = initFailure == null

    /**
     * Runs OCR over [bitmap].
     *
     * @param language Tesseract language code, defaulting to the configured set.
     * @param region optional sub-rectangle; OCR runs on the crop only, which is both
     *        faster and avoids leaking surrounding screen content.
     */
    suspend fun recognise(
        bitmap: Bitmap,
        language: String? = null,
        region: android.graphics.Rect? = null
    ): OcrResult = withContext(Dispatchers.Default) {
        val started = System.currentTimeMillis()
        val lang = language ?: settings.current.ocrLanguages.firstOrNull() ?: "eng"

        val ready = ensureInitialised(lang)
        if (!ready) {
            return@withContext OcrResult(
                blocks = emptyList(),
                fullText = "",
                languageTag = lang,
                durationMs = System.currentTimeMillis() - started,
                unavailable = true,
                unavailableReason = initFailure ?: "OCR engine could not start"
            )
        }

        val source = if (region != null) {
            val safe = clamp(region, bitmap.width, bitmap.height)
            if (safe.width() <= 0 || safe.height() <= 0) bitmap
            else Bitmap.createBitmap(bitmap, safe.left, safe.top, safe.width(), safe.height())
        } else {
            bitmap
        }

        apiMutex.withLock {
            val engine = api ?: return@withLock OcrResult(
                blocks = emptyList(),
                fullText = "",
                languageTag = lang,
                durationMs = System.currentTimeMillis() - started,
                unavailable = true,
                unavailableReason = "OCR engine was released"
            )

            try {
                engine.setImage(source)
                // PSM 6 = "assume a single uniform block of text", which suits a
                // screenshot crop far better than the default page-segmentation mode.
                engine.pageSegMode = TessBaseAPI.PageSegMode.PSM_AUTO

                val fullText = engine.getUTF8Text().orEmpty().trim()
                val blocks = parseBlocks(engine, region)

                OcrResult(
                    blocks = blocks,
                    fullText = fullText,
                    languageTag = lang,
                    durationMs = System.currentTimeMillis() - started
                )
            } catch (t: Throwable) {
                Log.e(TAG, "OCR failed", t)
                OcrResult(
                    blocks = emptyList(),
                    fullText = "",
                    languageTag = lang,
                    durationMs = System.currentTimeMillis() - started,
                    unavailable = true,
                    unavailableReason = t.message ?: "OCR failed"
                )
            } finally {
                runCatching { engine.clear() }
                if (source !== bitmap) source.recycle()
            }
        }
    }

    /** Maps Tesseract's iterator results into our coordinate-preserving model. */
    private fun parseBlocks(engine: TessBaseAPI, offset: android.graphics.Rect?): List<OcrBlock> {
        val dx = offset?.left ?: 0
        val dy = offset?.top ?: 0
        val blocks = mutableListOf<OcrBlock>()
        var lineWords = mutableListOf<OcrWord>()

        // Each text line becomes both a block and a line (Tesseract's paragraph grouping is
        // unreliable on screenshots), filled with real per-word boxes so text selection
        // highlights land on the actual words.
        fun flushLine() {
            if (lineWords.isEmpty()) return
            val bounds = android.graphics.Rect(
                lineWords.minOf { it.bounds.left },
                lineWords.minOf { it.bounds.top },
                lineWords.maxOf { it.bounds.right },
                lineWords.maxOf { it.bounds.bottom }
            )
            val text = lineWords.joinToString(" ") { it.text }
            blocks += OcrBlock(text, bounds, listOf(OcrLine(text, bounds, lineWords.toList())))
            lineWords = mutableListOf()
        }

        val iterator = engine.resultIterator ?: return emptyList()
        val line = TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE
        val word = TessBaseAPI.PageIteratorLevel.RIL_WORD
        try {
            iterator.begin()
            do {
                if (iterator.isAtBeginningOf(line)) flushLine()
                val text = iterator.getUTF8Text(word)?.trim().orEmpty()
                val box = iterator.getBoundingRect(word)
                if (text.isNotEmpty() && box != null) {
                    lineWords += OcrWord(
                        text = text,
                        bounds = android.graphics.Rect(
                            box.left + dx, box.top + dy, box.right + dx, box.bottom + dy
                        ),
                        confidence = iterator.confidence(word)
                    )
                }
            } while (iterator.next(word))
            flushLine()
        } finally {
            runCatching { iterator.delete() }
        }
        return blocks
    }

    private suspend fun ensureInitialised(language: String): Boolean {
        if (initialised.get() && loadedLanguage == language) return true

        return initMutex.withLock {
            if (initialised.get() && loadedLanguage == language) return@withLock true

            try {
                copyTrainedData(language)

                val dataPath = tessDir.absolutePath
                val engine = TessBaseAPI()
                val started = System.currentTimeMillis()
                val ok = engine.init(dataPath, language)
                if (!ok) {
                    initFailure = "Tesseract could not load language data for '$language'"
                    Log.e(TAG, initFailure!!)
                    return@withLock false
                }
                Log.i(TAG, "OCR initialised in ${System.currentTimeMillis() - started} ms ($language)")

                api?.recycle()
                api = engine
                loadedLanguage = language
                initialised.set(true)
                initFailure = null
                true
            } catch (t: Throwable) {
                initFailure = t.message ?: "OCR initialisation failed"
                Log.e(TAG, "OCR init failed", t)
                false
            }
        }
    }

    /** Copies `assets/tessdata/<lang>.traineddata` into app storage if absent. */
    private fun copyTrainedData(language: String) {
        val target = File(tessdataDir, "$language.traineddata")
        if (target.exists() && target.length() > 1024) return

        val assetPath = "tessdata/$language.traineddata"
        context.assets.open(assetPath).use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        Log.i(TAG, "Installed traineddata for '$language' (${target.length()} bytes)")
    }

    /** Languages actually bundled in this build. */
    fun availableLanguages(): List<String> = try {
        context.assets.list("tessdata")
            ?.mapNotNull { it.removeSuffix(".traineddata").takeIf { _ -> it.endsWith(".traineddata") } }
            ?.sorted()
            ?: emptyList()
    } catch (t: Throwable) {
        emptyList()
    }

    fun release() {
        runCatching { api?.recycle() }
        api = null
        initialised.set(false)
    }

    private fun clamp(rect: android.graphics.Rect, w: Int, h: Int): android.graphics.Rect {
        val left = rect.left.coerceIn(0, w)
        val top = rect.top.coerceIn(0, h)
        val right = rect.right.coerceIn(0, w)
        val bottom = rect.bottom.coerceIn(0, h)
        return android.graphics.Rect(left, top, right, bottom)
    }

    private companion object {
        const val TAG = "Lenshift/OCR"
    }
}
