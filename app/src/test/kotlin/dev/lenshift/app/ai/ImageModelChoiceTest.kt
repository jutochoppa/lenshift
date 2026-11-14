package dev.lenshift.app.ai

import dev.lenshift.app.ai.adapters.StreamErrors
import dev.lenshift.app.conversation.PictureTurns
import dev.lenshift.app.data.settings.AppSettings
import dev.lenshift.app.data.settings.ModelRef
import dev.lenshift.app.ui.markdown.MarkdownParser
import dev.lenshift.app.ui.markdown.MdBlock
import dev.lenshift.app.visual.ImageBlocks
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/** Choosing the image model, handing picture requests to it, and editing pictures. */
class ImageModelChoiceTest {

    private val relay = ProviderProfile(
        id = "relay", displayName = "Relay", protocol = ApiProtocol.OPENAI_RESPONSES,
        baseUrl = "https://relay.example/v1", modelId = "gpt-5.6", models = listOf("gpt-5.6")
    )

    @Test
    fun `a relay's own gpt-image names make pictures only`() {
        val withImage = relay.withModel("gpt-image-2.6-sunburst").withModel("codex-gpt-image-2")
        assertTrue(withImage.isImageOnly("gpt-image-2.6-sunburst"))
        assertTrue(withImage.isImageOnly("codex-gpt-image-2"))
        assertTrue(withImage.isImageOnly("gpt-image-1"))
        assertFalse(withImage.isImageOnly("gpt-5.6"))
        assertEquals(ImageGeneration.Route.IMAGES_API, ImageGeneration.route(withImage.selecting("gpt-image-2.6-sunburst")))
    }

    @Test
    fun `any saved or typed model can become the image model`() {
        val settings = AppSettings(profiles = listOf(relay))
        val chosen = settings.usingImageModel(ModelRef("relay", " my-painter "))
        val profile = chosen.profiles.single()
        assertEquals(ModelRef("relay", "my-painter"), chosen.imageModel)
        assertTrue("typed models are saved", "my-painter" in profile.allModels)
        assertTrue("and marked as making pictures", profile.createsImages("my-painter"))
        assertEquals("the chat model stays current", "gpt-5.6", profile.modelId)

        // The current model is marked in its own fields.
        val current = settings.usingImageModel(ModelRef("relay", "gpt-5.6")).profiles.single()
        assertEquals(Tri.YES, current.imageOutputOverride)

        // A model already known to make pictures is left as it is.
        val known = relay.withModel("gpt-image-1")
        assertEquals(known, known.markingCreatesImages("gpt-image-1"))

        // Unknown providers and blank ids change nothing.
        assertEquals(settings, settings.usingImageModel(ModelRef("gone", "x")))
        assertEquals(settings, settings.usingImageModel(ModelRef("relay", "  ")))
    }

    @Test
    fun `edits send the picture as a file, never in the log`() {
        val image = EncodedImage(Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3, 4)), "image/jpeg")
        val body = ImageGeneration.editsBody("gpt-image-1", ImageRequest("make it blue", "16:9", listOf(image)))
        val parts = body.parts.associate { part ->
            val disposition = part.headers!!["Content-Disposition"]!!
            Regex("""name="([^"]+)"""").find(disposition)!!.groupValues[1] to (disposition to part.body)
        }
        assertEquals(setOf("model", "prompt", "n", "size", "image"), parts.keys)
        fun text(name: String) = Buffer().also { parts.getValue(name).second.writeTo(it) }.readUtf8()
        assertEquals("make it blue", text("prompt"))
        assertEquals("1536x1024", text("size"))
        val (disposition, file) = parts.getValue("image")
        assertTrue(disposition, disposition.contains("filename=\"image-1.jpg\""))
        assertEquals("image/jpeg", file.contentType().toString())
        assertEquals(4L, file.contentLength())
        assertEquals("https://relay.example/v1/images/edits", ImageGeneration.editsUrl(relay))

        val two = ImageGeneration.editsBody("gpt-image-1", ImageRequest("x", null, listOf(image, image)))
        assertEquals(2, two.parts.count { it.headers!!["Content-Disposition"]!!.contains("name=\"image[]\"") })
    }

    @Test
    fun `an image model picked in the chat edits what the user means`() {
        assertEquals("attached", PictureTurns.input(attached = true, previous = true, selection = true))
        assertEquals("previous", PictureTurns.input(attached = false, previous = true, selection = true))
        assertEquals("selection", PictureTurns.input(attached = false, previous = false, selection = true))
        assertNull(PictureTurns.input(attached = false, previous = false, selection = false))

        val answer = PictureTurns.answer("  a fox in snow ", "attached")!!
        val block = MarkdownParser.parse(answer).single() as MdBlock.Code
        val request = ImageBlocks.parse(block.code)
        assertEquals("a fox in snow", request.prompt)
        assertEquals("attached", request.input)
        assertNull(PictureTurns.answer("   ", null))
        assertNull(ImageBlocks.parse("""{"prompt": "x", "input": "somewhere"}""").input)
    }

    @Test
    fun `chats offer pictures on request, with an example the parser reads`() {
        val text = PromptBuilder.visualInstructions(graphs = false, images = true)
        assertTrue(text, text.contains("only when the user asks"))
        assertTrue(text, text.contains("never say you can't create images"))
        val example = MarkdownParser.parse(text).filterIsInstance<MdBlock.Code>().single()
        assertEquals("image", example.language)
        assertTrue(ImageBlocks.parse(example.code).prompt.startsWith("A watercolour"))
    }

    @Test
    fun `a relay's own error event becomes its message`() {
        assertTrue(StreamErrors.isError("UpstreamHTTPError"))
        assertTrue(StreamErrors.isError("response.failed"))
        assertFalse(StreamErrors.isError("response.output_text.delta"))
        assertFalse(StreamErrors.isError(null))
        assertEquals(
            "upstream returned 429: rate limited",
            StreamErrors.message("""{"type":"UpstreamHTTPError","message":"upstream returned 429: rate limited"}""")
        )
        assertEquals("bad token", StreamErrors.message("""{"error":{"message":"bad token","code":401}}"""))
        assertEquals("plain words", StreamErrors.message("plain words"))
        assertNull(StreamErrors.message("  "))
        assertEquals(300, StreamErrors.message("x".repeat(500))!!.length)
    }
}
