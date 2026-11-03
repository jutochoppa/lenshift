package dev.lenshift.app.visual

/**
 * Plain-text versions of the little bits of LaTeX models put in graph labels, for drawing
 * on a canvas: `$\theta = 45^\circ$` becomes "θ = 45°", `x^2` "x²", `\sqrt{2}` "√2".
 */
object MathText {

    private val SYMBOLS = mapOf(
        "alpha" to "α", "beta" to "β", "gamma" to "γ", "delta" to "δ", "epsilon" to "ε", "varepsilon" to "ε",
        "zeta" to "ζ", "eta" to "η", "theta" to "θ", "vartheta" to "ϑ", "iota" to "ι", "kappa" to "κ",
        "lambda" to "λ", "mu" to "μ", "nu" to "ν", "xi" to "ξ", "pi" to "π", "rho" to "ρ", "sigma" to "σ",
        "tau" to "τ", "upsilon" to "υ", "phi" to "φ", "varphi" to "φ", "chi" to "χ", "psi" to "ψ", "omega" to "ω",
        "Gamma" to "Γ", "Delta" to "Δ", "Theta" to "Θ", "Lambda" to "Λ", "Xi" to "Ξ", "Pi" to "Π",
        "Sigma" to "Σ", "Phi" to "Φ", "Psi" to "Ψ", "Omega" to "Ω",
        "cdot" to "·", "times" to "×", "div" to "÷", "pm" to "±", "mp" to "∓", "le" to "≤", "leq" to "≤",
        "ge" to "≥", "geq" to "≥", "neq" to "≠", "ne" to "≠", "approx" to "≈", "equiv" to "≡", "infty" to "∞",
        "circ" to "°", "degree" to "°", "to" to "→", "rightarrow" to "→", "leftarrow" to "←", "Rightarrow" to "⇒",
        "partial" to "∂", "nabla" to "∇", "int" to "∫", "sum" to "Σ", "prod" to "Π", "in" to "∈",
        "perp" to "⊥", "parallel" to "∥", "angle" to "∠", "triangle" to "△", "sqrt" to "√",
        "sin" to "sin", "cos" to "cos", "tan" to "tan", "ln" to "ln", "log" to "log", "exp" to "exp",
        "quad" to " ", "qquad" to "  ", "," to " ", ";" to " ", "!" to "", "left" to "", "right" to ""
    )

    private val SUPERSCRIPT = mapOf(
        '0' to '⁰', '1' to '¹', '2' to '²', '3' to '³', '4' to '⁴', '5' to '⁵', '6' to '⁶', '7' to '⁷',
        '8' to '⁸', '9' to '⁹', '+' to '⁺', '-' to '⁻', '−' to '⁻', '=' to '⁼', '(' to '⁽', ')' to '⁾',
        'n' to 'ⁿ', 'i' to 'ⁱ', 'x' to 'ˣ', 'y' to 'ʸ', 'a' to 'ᵃ', 'b' to 'ᵇ', 'k' to 'ᵏ', 't' to 'ᵗ'
    )

    private val SUBSCRIPT = mapOf(
        '0' to '₀', '1' to '₁', '2' to '₂', '3' to '₃', '4' to '₄', '5' to '₅', '6' to '₆', '7' to '₇',
        '8' to '₈', '9' to '₉', '+' to '₊', '-' to '₋', '=' to '₌', '(' to '₍', ')' to '₎',
        'a' to 'ₐ', 'e' to 'ₑ', 'i' to 'ᵢ', 'n' to 'ₙ', 'x' to 'ₓ', 'o' to 'ₒ', 'k' to 'ₖ', 't' to 'ₜ'
    )

    /** True when [text] is a single LaTeX formula, `$…$`. */
    fun isFormula(text: String): Boolean {
        val t = text.trim()
        return t.length > 2 && t.startsWith("$") && t.endsWith("$") && t.count { it == '$' } == 2
    }

    fun plain(text: String): String {
        if (!text.contains('$') && !text.contains('\\') && !text.contains('^') && !text.contains('_')) return text
        var s = text.replace("$", "")
        // \frac{a}{b} → a/b, \sqrt{x} → √x, \text{…} → …
        s = Regex("""\\[dt]?frac\{([^{}]*)\}\{([^{}]*)\}""").replace(s) { m ->
            val (a, b) = m.destructured
            "${wrap(a)}/${wrap(b)}"
        }
        s = Regex("""\\sqrt\{([^{}]*)\}""").replace(s) { "√${wrap(it.groupValues[1])}" }
        s = Regex("""\\(?:text|mathrm|mathbf|mathit|operatorname)\{([^{}]*)\}""").replace(s) { it.groupValues[1] }
        s = Regex("""\\([A-Za-z]+|[,;!])""").replace(s) { m -> SYMBOLS[m.groupValues[1]] ?: m.groupValues[1] }
        s = Regex("""\^\{([^{}]*)\}|\^(.)""").replace(s) { m ->
            val body = m.groupValues[1].ifEmpty { m.groupValues[2] }
            if (body == "°" || body == "\\circ") "°" else convert(body, SUPERSCRIPT) ?: "^($body)"
        }
        s = Regex("""_\{([^{}]*)\}|_(.)""").replace(s) { m ->
            val body = m.groupValues[1].ifEmpty { m.groupValues[2] }
            convert(body, SUBSCRIPT) ?: "_$body"
        }
        return s.replace("{", "").replace("}", "").replace("  ", " ").trim()
    }

    private fun wrap(s: String) = if (s.length <= 1 || s.all { it.isLetterOrDigit() }) s else "($s)"

    private fun convert(body: String, table: Map<Char, Char>): String? =
        if (body.all { it in table }) body.map { table.getValue(it) }.joinToString("") else null
}
