package dev.lenshift.app.ui.capture

import dev.lenshift.app.data.settings.AppSettings
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleSearchTest {

    @Test
    fun `text search urls carry the tab parameter`() {
        assertEquals(
            "https://www.google.com/search?q=double+integral&hl=en-US",
            GoogleUrls.textSearch(" double integral ", GooglePage.ALL, "en-US")
        )
        assertEquals(
            "https://www.google.com/search?q=a%26b&hl=en&udm=2",
            GoogleUrls.textSearch("a&b", GooglePage.IMAGES, "en")
        )
        assertEquals(
            "https://www.google.com/search?q=x&hl=en&tbm=nws",
            GoogleUrls.textSearch("x", GooglePage.NEWS, "en")
        )
    }

    @Test
    fun `lens tabs switch udm, drop paging and add refinements`() {
        val session = "https://www.google.com/search?vsrid=ABC&udm=26&lns_mode=un&start=10&hl=en"
        val visual = GoogleUrls.lensPage(session, GooglePage.LENS_VISUAL, null)
        assertTrue(visual.contains("udm=44"))
        assertTrue(visual.contains("vsrid=ABC"))
        assertFalse(visual.contains("start="))
        assertFalse(visual.contains("udm=26"))

        val refined = GoogleUrls.lensPage(session, GooglePage.LENS_ALL, "red shoes")
        assertTrue(refined.contains("q=red+shoes"))
        assertTrue(refined.contains("udm=26"))
    }

    @Test
    fun `lens results and pane boundaries are recognised`() {
        assertTrue(GoogleUrls.isLensResults("https://www.google.com/search?vsrid=1&udm=26"))
        assertFalse(GoogleUrls.isLensResults("https://www.google.com/search?q=cats"))
        assertFalse(GoogleUrls.isLensResults("https://evil.example/search?vsrid=1"))

        assertTrue(GoogleUrls.staysInPane("https://www.google.com/search?q=x&udm=2"))
        assertTrue(GoogleUrls.staysInPane("https://www.google.co.uk/search?q=x"))
        assertTrue(GoogleUrls.staysInPane("https://lens.google.com/v3/upload"))
        assertFalse(GoogleUrls.staysInPane("https://en.wikipedia.org/wiki/Integral"))
        assertFalse(GoogleUrls.staysInPane("https://www.google.com.evil.example/search"))
        assertFalse(GoogleUrls.staysInPane("intent://scan#Intent;end"))
        // Click-tracking redirects leave the pane, unwrapped to their destination.
        assertFalse(GoogleUrls.staysInPane("https://www.google.com/url?q=https://example.org/a"))
        assertEquals(
            "https://example.org/a?b=1",
            GoogleUrls.unwrapRedirect("https://www.google.com/url?q=https%3A%2F%2Fexample.org%2Fa%3Fb%3D1&sa=U")
        )
    }

    @Test
    fun `pages map back to tabs, including Google's own navigation`() {
        val lens = "https://www.google.com/search?vsrid=A&lns_mode=un"
        assertEquals(GooglePage.LENS_ALL, GoogleUrls.pageOf("$lens&udm=26"))
        assertEquals(GooglePage.LENS_AI, GoogleUrls.pageOf("$lens&udm=50&q=more"))
        // An image search refined with text lands on udm=24, which is All.
        assertEquals(GooglePage.LENS_ALL, GoogleUrls.pageOf("$lens&udm=24&q=what+app"))
        assertEquals(GooglePage.ALL, GoogleUrls.pageOf("https://www.google.com/search?q=x"))
        assertEquals(GooglePage.AI_MODE, GoogleUrls.pageOf("https://www.google.com/search?q=x&udm=50"))
        assertEquals(GooglePage.NEWS, GoogleUrls.pageOf("https://www.google.com/search?q=x&tbm=nws"))
        assertEquals(null, GoogleUrls.pageOf("https://example.org/search?q=x"))
        assertTrue(GoogleUrls.isAiMode(GooglePage.LENS_AI))
        assertFalse(GoogleUrls.isAiMode(GooglePage.LENS_ALL))
    }

    @Test
    fun `tabs follow the selection type and the setting`() {
        assertEquals(
            listOf("Chat", "All", "Visual matches", "Exact matches", "AI Mode"),
            ResultTab.forSelection(imageSearch = true, googleEnabled = true).map { it.label }
        )
        assertEquals("Images", ResultTab.forSelection(imageSearch = false, googleEnabled = true)[2].label)
        assertEquals(listOf(ResultTab.Chat), ResultTab.forSelection(imageSearch = true, googleEnabled = false))
    }

    @Test
    fun `google preferences default on, chat first, and round trip`() {
        val defaults = AppSettings.fromJson(JSONObject())
        assertTrue(defaults.googleTabsEnabled)
        assertFalse(defaults.googleResultsFirst)

        val changed = defaults.copy(googleTabsEnabled = false, googleResultsFirst = true)
        val restored = AppSettings.fromJson(changed.toJson())
        assertFalse(restored.googleTabsEnabled)
        assertTrue(restored.googleResultsFirst)
    }
}
