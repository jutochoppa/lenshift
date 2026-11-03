package dev.lenshift.app.data.settings

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Settings parsing must degrade per element, never all at once.
 *
 * The bug: `fromJson` used `getJSONObject(i)`, which throws on a null or non-object
 * element. One bad entry threw out of the whole parse, `load()` fell back to defaults, and
 * every saved provider, the active selection and the custom instructions vanished — while
 * their Keystore secrets were left orphaned. The UI then said "no provider configured".
 */
class SettingsParsingTest {

    private fun profileJson(id: String, name: String) = JSONObject().apply {
        put("id", id)
        put("displayName", name)
        put("protocol", "openai_chat")
        put("baseUrl", "https://api.example.com/v1")
        put("modelId", "model-$id")
    }

    @Test
    fun `a null profile entry does not erase the valid ones`() {
        val json = JSONObject().apply {
            put("profiles", JSONArray().apply {
                put(profileJson("a", "First"))
                put(JSONObject.NULL)
                put(profileJson("b", "Second"))
            })
            put("activeProfileId", "b")
            put("customSystemInstructions", "Be brief.")
        }

        val settings = AppSettings.fromJson(json)

        assertEquals(listOf("a", "b"), settings.profiles.map { it.id })
        assertEquals("b", settings.activeProfileId)
        assertEquals("Be brief.", settings.customSystemInstructions)
    }

    @Test
    fun `a non-object profile entry is skipped`() {
        val json = JSONObject().apply {
            put("profiles", JSONArray().apply {
                put("not an object")
                put(42)
                put(profileJson("a", "Only"))
            })
        }

        val settings = AppSettings.fromJson(json)

        assertEquals(listOf("a"), settings.profiles.map { it.id })
    }

    @Test
    fun `bad ocr language entries fall back rather than failing`() {
        val json = JSONObject().apply {
            put("ocrLanguages", JSONArray().apply {
                put(JSONObject.NULL)
                put("")
            })
        }

        val settings = AppSettings.fromJson(json)

        assertEquals(
            "an all-invalid language list must fall back to English, not be empty",
            listOf("eng"),
            settings.ocrLanguages
        )
    }

    @Test
    fun `a round trip preserves every provider`() {
        val original = AppSettings.fromJson(JSONObject().apply {
            put("profiles", JSONArray().apply {
                put(profileJson("a", "First"))
                put(profileJson("b", "Second"))
            })
            put("activeProfileId", "a")
        })

        val restored = AppSettings.fromJson(original.toJson())

        assertEquals(original.profiles.map { it.id }, restored.profiles.map { it.id })
        assertEquals(original.activeProfileId, restored.activeProfileId)
        assertTrue(restored.profiles.all { it.modelId.startsWith("model-") })
    }
}
