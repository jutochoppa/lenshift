package dev.lenshift.app.ui.markdown

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Log
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.agog.mathdisplay.MTFontManager
import com.agog.mathdisplay.parse.MTLineStyle
import com.agog.mathdisplay.parse.MTMathList
import com.agog.mathdisplay.parse.MTMathListBuilder
import com.agog.mathdisplay.parse.MTParseError
import com.agog.mathdisplay.parse.MTParseErrors
import com.agog.mathdisplay.render.CGPoint
import com.agog.mathdisplay.render.MTTypesetter
import kotlin.math.ceil
import kotlin.math.max

/**
 * Typesets LaTeX with AndroidMath (Latin Modern Math) into bitmaps.
 *
 * Results are cached by source, size, colour and style, so a streaming answer that is
 * re-rendered on every token only typesets each formula once. Failures are cached too.
 * Calls are confined to one thread at a time because the font manager is not thread-safe.
 */
object MathRenderer {

    /** A typeset formula as a bitmap with its pixel size. */
    class Rendered(val image: ImageBitmap, val widthPx: Int, val heightPx: Int)

    private const val TAG = "Lenshift/Math"
    private val FAILED = Any()
    private val cache = object : LruCache<String, Any>(192) {}
    private var fontsReady = false

    /**
     * @param sizePx font size in pixels.
     * @param textCenterAboveBaselinePx for inline use: how far above the baseline the
     *   surrounding text's vertical centre lies. The bitmap is padded so its own centre sits
     *   that far above the formula's baseline. Pass null for display (block) formulas.
     */
    @Synchronized
    fun render(
        context: Context,
        tex: String,
        sizePx: Float,
        color: Int,
        display: Boolean,
        textCenterAboveBaselinePx: Float?
    ): Rendered? {
        val key = "$display|$sizePx|$color|$textCenterAboveBaselinePx|$tex"
        cache.get(key)?.let { return it as? Rendered }
        val result = runCatching { typeset(context, tex, sizePx, color, display, textCenterAboveBaselinePx) }
            .onFailure { Log.w(TAG, "Typesetting failed", it) }
            .getOrNull()
        cache.put(key, result ?: FAILED)
        return result
    }

    private fun typeset(
        context: Context,
        tex: String,
        sizePx: Float,
        color: Int,
        display: Boolean,
        centerAbove: Float?
    ): Rendered? {
        if (!fontsReady) {
            MTFontManager.setContext(context.applicationContext)
            MathExtensions.install()
            fontsReady = true
        }
        val font = MTFontManager.latinModernFontWithSize(sizePx) ?: return null
        val style = if (display) MTLineStyle.KMTLineStyleDisplay else MTLineStyle.KMTLineStyleText

        val normalized = TexNormalizer.normalize(tex)
        val list = listOfNotNull(normalized, TexNormalizer.wrapLines(normalized))
            .firstNotNullOfOrNull(::parseWithFallback) ?: return null
        run {
            val line = MTTypesetter.createLineForMathList(list, font, style)
            line.textColor = color

            val ascent = max(line.ascent, 0f)
            val descent = max(line.descent, 0f)
            val padX = sizePx * 0.06f
            var padTop = sizePx * 0.08f
            var padBottom = sizePx * 0.08f
            if (centerAbove != null) {
                // Grow the shorter side so the bitmap centre is `centerAbove` over the baseline.
                val up = padTop + ascent - centerAbove
                val down = padBottom + descent + centerAbove
                if (up > down) padBottom += up - down else padTop += down - up
            }
            val width = ceil(line.width + 2 * padX).toInt().coerceAtLeast(1)
            val height = ceil(ascent + descent + padTop + padBottom).toInt().coerceAtLeast(1)

            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            // AndroidMath draws in a y-up coordinate system, like the view it ships with.
            canvas.translate(0f, height.toFloat())
            canvas.scale(1f, -1f)
            line.position = CGPoint(padX, padBottom + descent)
            line.draw(canvas)
            return Rendered(bitmap.asImageBitmap(), width, height)
        }
    }

    /**
     * Parses [source]; when it fails on an unknown command, shows that command's name as text
     * (see [TexNormalizer.replaceUnknownCommand]) and tries again, a few times, so one
     * unsupported command does not turn the whole formula back into source text.
     */
    private fun parseWithFallback(source: String): MTMathList? {
        var current = source
        repeat(MAX_UNKNOWN_COMMANDS + 1) {
            val error = MTParseError()
            val list = MTMathListBuilder.buildFromString(current, error)
            if (list != null && error.errorcode == MTParseErrors.ErrorNone) return list
            if (error.errorcode != MTParseErrors.InvalidCommand) return null
            val command = error.errordesc.orEmpty().substringAfter("Invalid command ", "").trim()
            current = TexNormalizer.replaceUnknownCommand(current, command) ?: return null
            Log.i(TAG, "Unsupported \\$command shown as text")
        }
        return null
    }

    private const val MAX_UNKNOWN_COMMANDS = 4
}
