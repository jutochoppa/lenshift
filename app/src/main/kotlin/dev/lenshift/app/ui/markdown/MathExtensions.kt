package dev.lenshift.app.ui.markdown

import android.util.Log
import com.agog.mathdisplay.parse.MTFontStyle
import com.agog.mathdisplay.parse.MTMathAtom
import com.agog.mathdisplay.parse.MTMathAtomFactory
import com.agog.mathdisplay.parse.MTMathAtomType

/**
 * Teaches AndroidMath LaTeX commands models commonly emit that it does not know.
 *
 * AndroidMath parses through one shared factory ([MTMathAtom.Factory]) whose command tables
 * are private maps. They are extended here once, with glyphs that exist in the bundled
 * Latin Modern Math font (checked against its cmap). If the library changes and the maps
 * cannot be reached, rendering simply keeps its previous coverage.
 */
internal object MathExtensions {

    /** Accents: command to the combining character placed over the nucleus. */
    private val accents = mapOf(
        "dddot" to "\u20DB",
        "ddddot" to "\u20DC",
        "mathring" to "\u030A",
        "overleftarrow" to "\u20D6",
        "overrightarrow" to "\u20D7",
        "overleftrightarrow" to "\u20E1"
    )

    private val relations = mapOf(
        "therefore" to "\u2234",
        "because" to "\u2235",
        "nmid" to "\u2224",
        "nparallel" to "\u2226",
        "nleq" to "\u2270",
        "ngeq" to "\u2271",
        "nless" to "\u226E",
        "ngtr" to "\u226F",
        "nsim" to "\u2241",
        "ncong" to "\u2247",
        "nsubseteq" to "\u2288",
        "nsupseteq" to "\u2289",
        "lesssim" to "\u2272",
        "gtrsim" to "\u2273",
        "triangleq" to "\u225C",
        "coloneqq" to "\u2254",
        "varpropto" to "\u221D",
        "rightleftharpoons" to "\u21CC",
        "hookrightarrow" to "\u21AA",
        "twoheadrightarrow" to "\u21A0",
        "longmapsto" to "\u27FC"
    )

    private val ordinaries = mapOf(
        "checkmark" to "\u2713",
        "complement" to "\u2201",
        "nexists" to "\u2204",
        "blacksquare" to "\u25A0",
        "Box" to "\u25A1",
        "diamondsuit" to "\u2662"
    )

    private val fontStyles = mapOf(
        "boldsymbol" to MTFontStyle.KMTFontStyleBoldItalic,
        "pmb" to MTFontStyle.KMTFontStyleBold
    )

    @Volatile
    private var installed = false

    @Synchronized
    fun install() {
        if (installed) return
        installed = true
        runCatching {
            val factory: MTMathAtomFactory = MTMathAtom.Factory
            val type = MTMathAtomFactory::class.java

            @Suppress("UNCHECKED_CAST")
            fun <T> map(name: String) =
                type.getDeclaredField(name).apply { isAccessible = true }.get(factory) as HashMap<String, T>

            val accentMap = map<String>("accents")
            accents.forEach { (k, v) -> accentMap.putIfAbsent(k, v) }

            val symbols = map<MTMathAtom>("supportedLatexSymbols")
            relations.forEach { (k, v) -> symbols.putIfAbsent(k, MTMathAtom(MTMathAtomType.KMTMathAtomRelation, v)) }
            ordinaries.forEach { (k, v) -> symbols.putIfAbsent(k, MTMathAtom(MTMathAtomType.KMTMathAtomOrdinary, v)) }

            fontStyles.forEach { (k, v) -> factory.fontStyleWithName.putIfAbsent(k, v) }
        }.onFailure { Log.w("Lenshift/Math", "Could not extend LaTeX commands", it) }
    }
}
