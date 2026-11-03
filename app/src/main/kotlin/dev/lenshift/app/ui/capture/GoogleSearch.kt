package dev.lenshift.app.ui.capture

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * A Google results page reachable without signing in.
 *
 * Image selections use Google Lens: one upload creates a results session, and its tabs
 * are the same session URL with a different `udm` value. Text selections use ordinary
 * web search tabs. The `udm`/`tbm` values are the ones google.com itself links to.
 */
enum class GooglePage(val label: String, val udm: String?, val tbm: String? = null, val lens: Boolean = false) {
    LENS_ALL("All", "26", lens = true),
    LENS_VISUAL("Visual matches", "44", lens = true),
    LENS_EXACT("Exact matches", "48", lens = true),
    LENS_AI("AI Mode", "50", lens = true),

    ALL("All", null),
    IMAGES("Images", "2"),
    VIDEOS("Videos", "vids"),
    SHORT_VIDEOS("Short videos", "short_vids"),
    SHOPPING("Shopping", "28"),
    FORUMS("Forums", "forums"),
    NEWS("News", null, tbm = "nws"),
    WEB("Web", "web"),
    BOOKS("Books", "36"),
    AI_MODE("AI Mode", "50");

    companion object {
        val forImage = listOf(LENS_ALL, LENS_VISUAL, LENS_EXACT, LENS_AI)
        val forText = listOf(ALL, IMAGES, VIDEOS, SHORT_VIDEOS, SHOPPING, FORUMS, NEWS, WEB, BOOKS, AI_MODE)
    }
}

/** A tab in the results sheet: Lenshift's own chat, or a Google results page. */
sealed interface ResultTab {
    val label: String

    data object Chat : ResultTab {
        override val label = "Chat"
    }

    data class Google(val page: GooglePage) : ResultTab {
        override val label get() = page.label
    }

    companion object {
        /** Chat first, then the Google pages that fit the selection. */
        fun forSelection(imageSearch: Boolean, googleEnabled: Boolean): List<ResultTab> =
            buildList {
                add(Chat)
                if (googleEnabled) {
                    (if (imageSearch) GooglePage.forImage else GooglePage.forText).forEach { add(Google(it)) }
                }
            }
    }
}

/** Builds and recognises Google search and Lens URLs. Pure, so it is unit-tested. */
object GoogleUrls {

    const val LENS_UPLOAD = "https://lens.google.com/v3/upload"
    private const val SEARCH = "https://www.google.com/search"

    fun textSearch(query: String, page: GooglePage, language: String): String = buildString {
        append(SEARCH).append("?q=").append(encode(query.trim()))
        append("&hl=").append(encode(language))
        page.udm?.let { append("&udm=").append(it) }
        page.tbm?.let { append("&tbm=").append(it) }
    }

    /** The upload endpoint Google's own "search by image" form posts to. */
    fun lensUpload(language: String, width: Int, height: Int, nowMillis: Long): String =
        "$LENS_UPLOAD?hl=${encode(language)}&st=$nowMillis&vpw=$width&vph=$height"

    /**
     * Switches a Lens results session to [page], optionally refining it with typed text
     * (Google's "add to your search"). Paging offsets from the previous tab are dropped.
     */
    fun lensPage(resultsUrl: String, page: GooglePage, refine: String?): String {
        val uri = URI(resultsUrl)
        val params = parse(uri.rawQuery).filterKeys { it != "start" && it != "udm" && (refine == null || it != "q") }
        val updated = LinkedHashMap(params)
        page.udm?.let { updated["udm"] = it }
        if (!refine.isNullOrBlank()) updated["q"] = refine.trim()
        val query = updated.entries.joinToString("&") { (k, v) -> "${encode(k)}=${encode(v)}" }
        return URI(uri.scheme, uri.authority, uri.path, null, null).toString() + "?" + query
    }

    /**
     * The tab a Google results URL corresponds to, so the sheet can follow navigation that
     * Google makes itself (a Lens search plus text becomes AI Mode, for example).
     */
    fun pageOf(url: String?): GooglePage? {
        val uri = runCatching { URI(url ?: return null) }.getOrNull() ?: return null
        if (!isGoogleHost(uri.host) || uri.path != "/search") return null
        val params = parse(uri.rawQuery)
        val udm = params["udm"]
        val tbm = params["tbm"]
        val lens = "vsrid" in params
        // An image search refined with text ("multisearch") is Google's All page for it.
        if (lens && udm == "24") return GooglePage.LENS_ALL
        return GooglePage.entries.firstOrNull { page ->
            page.lens == lens && when {
                page.tbm != null -> page.tbm == tbm
                page.udm != null -> page.udm == udm
                else -> udm == null && tbm == null
            }
        }
    }

    /** AI Mode is a conversation: follow-ups go into its own box, not a new search. */
    fun isAiMode(page: GooglePage): Boolean = page == GooglePage.LENS_AI || page == GooglePage.AI_MODE

    /** A Lens results session page (what the upload redirects to). */
    fun isLensResults(url: String?): Boolean {
        val uri = runCatching { URI(url ?: return false) }.getOrNull() ?: return false
        return isGoogleHost(uri.host) && uri.path == "/search" && "vsrid" in parse(uri.rawQuery)
    }

    /**
     * Whether a navigation stays inside the results pane. Google pages (search, Lens,
     * consent) do; anything else opens in the user's browser.
     */
    fun staysInPane(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        if (uri.scheme != "https" && uri.scheme != "http") return false
        return isGoogleHost(uri.host) && uri.path != "/url"
    }

    /** Google's `/url?q=…` click-tracking redirect, unwrapped to its destination. */
    fun unwrapRedirect(url: String): String {
        val uri = runCatching { URI(url) }.getOrNull() ?: return url
        if (!isGoogleHost(uri.host) || uri.path != "/url") return url
        val params = parse(uri.rawQuery)
        return params["q"] ?: params["url"] ?: url
    }

    private fun isGoogleHost(host: String?): Boolean {
        val h = host?.lowercase() ?: return false
        return h == "lens.google.com" || h == "consent.google.com" ||
            GOOGLE_HOST.matches(h)
    }

    /** google.com and its country domains (google.de, google.co.uk, google.com.au). */
    private val GOOGLE_HOST = Regex("""^(www\.)?google\.(com|[a-z]{2}|co\.[a-z]{2}|com\.[a-z]{2})$""")

    private fun parse(rawQuery: String?): Map<String, String> =
        rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }.associate { pair ->
            val k = pair.substringBefore('=')
            val v = if ('=' in pair) pair.substringAfter('=') else ""
            decode(k) to decode(v)
        }

    private fun encode(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun decode(s: String) = URLDecoder.decode(s, "UTF-8")
}
