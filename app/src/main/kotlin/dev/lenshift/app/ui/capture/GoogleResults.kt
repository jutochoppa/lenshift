package dev.lenshift.app.ui.capture

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.util.Base64
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import dev.lenshift.app.ui.theme.AssistantCaptureColors
import java.io.ByteArrayOutputStream
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Shows Google's own results pages for a selection, in a WebView, without any account.
 *
 * Image selections are uploaded the way google.com's "search by image" upload button does
 * it: a normal browser form post to Lens, which redirects to a results session. Tabs then
 * switch by changing the session URL's `udm` (see [GoogleUrls]). Text selections load
 * plain search URLs. Pages are never parsed or altered; links that leave Google open in
 * the user's browser.
 */
class GoogleResultsController(private val context: Context) {

    var loading by mutableStateOf(false)
        private set
    var progress by mutableIntStateOf(0)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    /** Called when the user scrolls down a results page, so the sheet can expand. */
    var onScrollDown: (() -> Unit)? = null

    /** Called with the tab a finished page belongs to, when Google navigates by itself. */
    var onPageShown: ((GooglePage) -> Unit)? = null

    private val language = Locale.getDefault().toLanguageTag()
    private var lensKey: Any? = null
    private var lensResultsUrl: String? = null
    private var pendingPage: GooglePage? = null
    private var pendingRefine: String? = null
    private var lastRequest: (() -> Unit)? = null

    @SuppressLint("SetJavaScriptEnabled")
    val webView: WebView = WebView(context).apply {
        setBackgroundColor(AssistantCaptureColors.Sheet.toArgb())
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mediaPlaybackRequiresUserGesture = true
        settings.setSupportMultipleWindows(false)
        CookieManager.getInstance().setAcceptCookie(true)
        setOnScrollChangeListener { _, _, y, _, oldY ->
            if (y > oldY && y > SCROLL_EXPAND_PX) onScrollDown?.invoke()
        }

        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                if (GoogleUrls.staysInPane(url)) return false
                openExternally(GoogleUrls.unwrapRedirect(url))
                return true
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                loading = true
                error = null
            }

            override fun onPageFinished(view: WebView, url: String?) {
                loading = false
                val shown = GoogleUrls.pageOf(url)
                if (shown != null) {
                    // AI Mode is a conversation whose answer and follow-up box build up after
                    // load; its page is never modified.
                    if (!GoogleUrls.isAiMode(shown)) view.evaluateJavascript(HIDE_DUPLICATE_BAR_JS, null)
                    onPageShown?.invoke(shown)
                }
                val wasUploading = lensResultsUrl == null
                // Track the latest page of the session, including refinements Google made.
                if (!wasUploading && GoogleUrls.isLensResults(url)) lensResultsUrl = url
                if (wasUploading && GoogleUrls.isLensResults(url)) {
                    lensResultsUrl = url
                    val page = pendingPage
                    val refine = pendingRefine
                    pendingPage = null
                    pendingRefine = null
                    // The upload always lands on "All"; hop to the tab that was asked for.
                    if (url != null && ((page != null && page != GooglePage.LENS_ALL) || !refine.isNullOrBlank())) {
                        view.loadUrl(GoogleUrls.lensPage(url, page ?: GooglePage.LENS_ALL, refine))
                    }
                }
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, err: WebResourceError) {
                if (request.isForMainFrame) {
                    loading = false
                    error = err.description?.toString()?.takeIf { it.isNotBlank() } ?: "The page could not load."
                }
            }
        }
        webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                this@GoogleResultsController.progress = newProgress
            }
        }
        // Start on a blank page: an unloaded WebView draws nothing, not even its background.
        loadUrl("about:blank")
    }

    /** Text search on [page]. */
    fun showText(query: String, page: GooglePage) {
        lastRequest = { showText(query, page) }
        error = null
        webView.loadUrl(GoogleUrls.textSearch(query, page, language))
    }

    /**
     * Image search on [page]. [key] identifies the selection: the same key reuses the
     * existing Lens session, so switching tabs does not upload again.
     */
    fun showImage(image: Bitmap, key: Any, page: GooglePage, refine: String?) {
        lastRequest = { showImage(image, key, page, refine) }
        error = null
        val session = lensResultsUrl
        if (key == lensKey && session != null) {
            webView.loadUrl(GoogleUrls.lensPage(session, page, refine))
            return
        }
        lensKey = key
        lensResultsUrl = null
        pendingPage = page
        pendingRefine = refine
        upload(image)
    }

    fun retry() {
        lastRequest?.invoke()
    }

    /**
     * Continues the AI Mode conversation on screen by typing [question] into its own
     * "Ask anything" box and pressing Send, so earlier turns stay in context. Calls
     * [fallback] (a fresh AI Mode search) when that box cannot be found.
     */
    fun followUpInAiMode(question: String, fallback: () -> Unit) {
        val script = """
            (function () {
              var q = ${JSONObject.quote(question)};
              var box = Array.prototype.slice.call(document.querySelectorAll('textarea')).find(function (t) {
                return t.getBoundingClientRect().height > 0 &&
                  /ask anything/i.test(t.getAttribute('aria-label') || t.getAttribute('placeholder') || '');
              });
              if (!box) return 'none';
              box.focus();
              Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value').set.call(box, q);
              box.dispatchEvent(new Event('input', { bubbles: true }));
              setTimeout(function () {
                var send = Array.prototype.slice.call(document.querySelectorAll('button,[role=button]')).find(function (b) {
                  return b.getAttribute('aria-label') === 'Send' && b.getBoundingClientRect().height > 0;
                });
                if (send) send.click();
                else box.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', code: 'Enter', keyCode: 13, bubbles: true }));
              }, 200);
              return 'sent';
            })();
        """.trimIndent()
        webView.evaluateJavascript(script) { result ->
            if (result?.contains("sent") != true) fallback()
        }
    }

    /**
     * Refines the image search on screen with [text] the way Google's own "Add to your
     * search" box does: through the page's search form, which carries the session fields
     * a plain URL lacks. Calls [fallback] when the form is not there.
     */
    fun refineInPage(text: String, fallback: () -> Unit) {
        val script = """
            (function () {
              var box = document.querySelector('form textarea[name=q], form input[name=q]');
              if (!box || !box.form) return 'none';
              var proto = box.tagName === 'TEXTAREA' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
              Object.getOwnPropertyDescriptor(proto, 'value').set.call(box, ${JSONObject.quote(text)});
              box.dispatchEvent(new Event('input', { bubbles: true }));
              if (box.form.requestSubmit) box.form.requestSubmit(); else box.form.submit();
              return 'sent';
            })();
        """.trimIndent()
        if (!GoogleUrls.isLensResults(webView.url)) {
            fallback()
            return
        }
        webView.evaluateJavascript(script) { result ->
            if (result?.contains("sent") != true) fallback()
        }
    }

    /** Opens the page currently shown in the user's browser. */
    fun openInBrowser() {
        webView.url?.takeIf { it.startsWith("http") }?.let(::openExternally)
    }

    /** Forgets the current results, e.g. when the selection is cleared. */
    fun reset() {
        lensKey = null
        lensResultsUrl = null
        pendingPage = null
        pendingRefine = null
        lastRequest = null
        error = null
        loading = false
        webView.stopLoading()
        webView.loadUrl("about:blank")
    }

    fun destroy() {
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
    }

    private fun upload(image: Bitmap) {
        val scaled = scaleForUpload(image)
        val jpeg = ByteArrayOutputStream().use { out ->
            scaled.compress(Bitmap.CompressFormat.JPEG, 88, out)
            out.toByteArray()
        }
        val action = GoogleUrls.lensUpload(language, scaled.width, scaled.height, System.currentTimeMillis())
        val b64 = Base64.encodeToString(jpeg, Base64.NO_WRAP)
        // A local page that submits a real multipart form, exactly like choosing a file
        // in Google's upload dialog. Nothing is sent until this form posts.
        val html = """
            <!doctype html><html><head><meta name="viewport" content="width=device-width">
            <style>html,body{margin:0;background:#22242A}</style></head><body>
            <form id="f" method="POST" enctype="multipart/form-data" action="$action">
              <input id="i" type="file" name="encoded_image">
            </form>
            <script>
              const bytes = Uint8Array.from(atob("$b64"), c => c.charCodeAt(0));
              const dt = new DataTransfer();
              dt.items.add(new File([bytes], "selection.jpg", { type: "image/jpeg" }));
              document.getElementById("i").files = dt.files;
              document.getElementById("f").submit();
            </script></body></html>
        """.trimIndent()
        loading = true
        webView.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
    }

    private fun scaleForUpload(image: Bitmap): Bitmap {
        val longest = max(image.width, image.height)
        if (longest <= MAX_UPLOAD_EDGE) return image
        val f = MAX_UPLOAD_EDGE.toFloat() / longest
        return Bitmap.createScaledBitmap(image, (image.width * f).roundToInt(), (image.height * f).roundToInt(), true)
    }

    private fun openExternally(url: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            // No browser: stay on the results page.
        }
    }

    private companion object {
        const val MAX_UPLOAD_EDGE = 1200
        const val SCROLL_EXPAND_PX = 24

        /**
         * The sheet already provides Google's search field and tabs (wired to the same
         * URLs), so on results pages the duplicate search box (with its image preview on
         * Lens pages) and tab row are hidden. Each must be a small full-width strip, so a
         * misidentified element can never blank the page. Google's own header, results
         * and links are untouched, and the pane is labelled "Results from Google".
         * Retries briefly because Google builds parts of the page after load.
         */
        val HIDE_DUPLICATE_BAR_JS: String = run {
            val labels = (GooglePage.entries.map { it.label } + "Feedback").distinct()
                .joinToString(",", "[", "]") { "\"$it\"" }
            """
            (function () {
              var LABELS = $labels;
              function textOf(el) { return ((el && (el.innerText || el.getAttribute('aria-label'))) || '').trim(); }
              function widen(el) {
                for (var i = 0; i < 8 && el.parentElement; i++) {
                  if (el.getBoundingClientRect().width >= window.innerWidth * 0.6) break;
                  el = el.parentElement;
                }
                return el;
              }
              // Google's tab row: the narrow full-width strip holding the most tab labels.
              function findTabRow() {
                var nodes = document.querySelectorAll('a,[role=link],[role=tab],[role=listitem],span,div');
                var best = null, bestCount = 0;
                for (var i = 0; i < nodes.length; i++) {
                  var n = nodes[i];
                  if (n.children.length > 3 || LABELS.indexOf(textOf(n)) < 0) continue;
                  var row = widen(n);
                  var r = row.getBoundingClientRect();
                  if (r.height <= 0 || r.height > 120) continue;
                  var text = row.innerText || '';
                  var count = 0;
                  for (var j = 0; j < LABELS.length; j++) if (text.indexOf(LABELS[j]) >= 0) count++;
                  if (count > bestCount) { best = row; bestCount = count; }
                }
                return bestCount >= 3 ? best : null;
              }
              // Google's search box, with the image preview above it on Lens pages.
              function findSearchBlock(row) {
                var box = document.querySelector('textarea,input[name=q],input[type=search],[role=combobox]');
                var block = null;
                for (var el = box; el && el !== document.body; el = el.parentElement) {
                  if (row && el.contains(row)) break;
                  if (!safeToHide(el)) break;
                  var r = el.getBoundingClientRect();
                  if (r.height >= 320) break;
                  if (r.height > 0 && r.width >= window.innerWidth * 0.6) block = el;
                }
                return block;
              }
              // Only chrome may be hidden: no scripts, no main results, little text. Pages
              // that build their content after load can look small at first.
              function safeToHide(el) {
                if (el.querySelector('script,iframe,[role=main],#search,#rso,#center_col')) return false;
                return (el.innerText || '').length < 240;
              }
              function run() {
                if (window.__lenshiftBar) return;
                if (new URLSearchParams(location.search).get('udm') === '50') return;
                var row = findTabRow();
                if (!row || !safeToHide(row)) return;
                var block = findSearchBlock(row);
                window.__lenshiftBar = true;
                [row, block].forEach(function (el) { if (el) el.style.setProperty('display', 'none', 'important'); });
              }
              [150, 600, 1400, 2600].forEach(function (ms) { setTimeout(run, ms); });
            })();
            """.trimIndent()
        }
    }
}

/** Hosts the controller's WebView under a "Results from Google" attribution line. */
@Composable
fun GoogleResultsPane(controller: GoogleResultsController, modifier: Modifier = Modifier) {
    Column(modifier.background(AssistantCaptureColors.Sheet)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 12.dp, top = 6.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Results from Google",
                color = AssistantCaptureColors.Muted,
                fontSize = 13.sp,
                modifier = Modifier.weight(1f)
            )
            Row(
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(role = Role.Button, onClick = controller::openInBrowser)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Open in browser", color = AssistantCaptureColors.Muted, fontSize = 13.sp)
                Spacer(Modifier.width(6.dp))
                Icon(
                    Icons.AutoMirrored.Outlined.OpenInNew,
                    contentDescription = null,
                    tint = AssistantCaptureColors.Muted,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
        AndroidView(
            factory = {
                (controller.webView.parent as? ViewGroup)?.removeView(controller.webView)
                controller.webView
            },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        )
    }
}
