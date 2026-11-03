package dev.lenshift.app.ai

import dev.lenshift.app.ai.transport.CallRegistry
import dev.lenshift.app.conversation.ChatThread
import dev.lenshift.app.conversation.ChatTurn
import dev.lenshift.app.conversation.GeneratedImage
import dev.lenshift.app.data.settings.AppSettings
import dev.lenshift.app.data.settings.ModelRef
import dev.lenshift.app.ui.markdown.MarkdownParser
import dev.lenshift.app.ui.markdown.MdBlock
import dev.lenshift.app.visual.ImageBlocks
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/** Image-capable models, the three generation routes, and pictures in answers and history. */
class ImageGenerationTest {

    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 1, 2, 3)
    private val b64 = Base64.getEncoder().encodeToString(png)

    private val openRouter = ProviderProfile(id = "or", displayName = "OpenRouter", baseUrl = "https://openrouter.ai/api/v1", modelId = "google/gemini-2.5-flash-image")
    private val openAi = ProviderProfile(id = "oa", displayName = "OpenAI", baseUrl = "https://api.openai.com/v1", modelId = "gpt-image-1")

    // ------------------------------------------------------------------- tags

    @Test
    fun `models are tagged from provider lists, the table and overrides`() {
        val listed = ModelListParser.openAiStyle(
            """{"data":[{"id":"x/painter","architecture":{"input_modalities":["text","image"],"output_modalities":["image","text"]}},
                        {"id":"x/writer","architecture":{"input_modalities":["text"],"output_modalities":["text"]}}]}"""
        )!!
        assertEquals(Tri.YES, listed[0].spec!!.imageOutput)
        assertEquals(Tri.YES, listed[0].spec!!.textOutput)
        assertEquals(Tri.NO, listed[1].spec!!.imageOutput)

        assertEquals(Tri.YES, KnownModels.lookup("openai/gpt-image-1")!!.imageOutput)
        assertEquals(Tri.NO, KnownModels.lookup("dall-e-3")!!.textOutput)
        assertEquals(Tri.YES, KnownModels.lookup("google/gemini-2.5-flash-image-preview")!!.imageOutput)
        assertEquals(Tri.UNKNOWN, KnownModels.lookup("gemini-2.5-flash")!!.imageOutput)
    }

    @Test
    fun `which models can make pictures`() {
        assertTrue(openRouter.createsImages(openRouter.modelId))
        assertTrue(openAi.isImageOnly("gpt-image-1"))
        assertFalse(openRouter.isImageOnly(openRouter.modelId))
        assertFalse(openRouter.copy(protocol = ApiProtocol.ANTHROPIC_MESSAGES).createsImages(openRouter.modelId))
        assertFalse(openRouter.copy(imageOutputOverride = Tri.NO).createsImages(openRouter.modelId))
        val custom = openRouter.copy(modelId = "my/own-model")
        assertFalse(custom.createsImages("my/own-model"))
        assertTrue(custom.copy(imageOutputOverride = Tri.YES).createsImages("my/own-model"))
    }

    // ---------------------------------------------------------------- routes

    @Test
    fun `each provider gets its own route`() {
        assertEquals(ImageGeneration.Route.CHAT, ImageGeneration.route(openRouter))
        assertEquals(ImageGeneration.Route.CHAT, ImageGeneration.route(openRouter.copy(protocol = ApiProtocol.OPENAI_RESPONSES)))
        assertEquals(ImageGeneration.Route.IMAGES_API, ImageGeneration.route(openAi))
        assertEquals(ImageGeneration.Route.GEMINI, ImageGeneration.route(openAi.copy(protocol = ApiProtocol.GEMINI, modelId = "models/gemini-2.5-flash-image")))
        assertNull(ImageGeneration.route(openAi.copy(protocol = ApiProtocol.OLLAMA)))
        assertEquals(
            "https://x/v1beta/models/gemini-2.5-flash-image:generateContent",
            ImageGeneration.url(openAi.copy(protocol = ApiProtocol.GEMINI, baseUrl = "https://x/v1beta", modelId = "models/gemini-2.5-flash-image"), ImageGeneration.Route.GEMINI)
        )
    }

    @Test
    fun `request bodies`() {
        val plain = ImageGeneration.chatBody("m", ImageRequest("a red fox", "16:9"), openRouter = true)
        assertEquals("image", plain.getJSONArray("modalities").getString(0))
        assertEquals("a red fox", plain.getJSONArray("messages").getJSONObject(0).getString("content"))
        assertEquals("16:9", plain.getJSONObject("image_config").getString("aspect_ratio"))
        assertFalse(ImageGeneration.chatBody("m", ImageRequest("x", "16:9"), openRouter = false).has("image_config"))

        val edit = ImageGeneration.chatBody("m", ImageRequest("make it blue", inputs = listOf(EncodedImage(b64, "image/png"))), openRouter = true)
        val parts = edit.getJSONArray("messages").getJSONObject(0).getJSONArray("content")
        assertEquals("image_url", parts.getJSONObject(1).getString("type"))
        assertTrue(parts.getJSONObject(1).getJSONObject("image_url").getString("url").startsWith("data:image/png;base64,"))

        assertEquals("1536x1024", ImageGeneration.imagesBody("gpt-image-1", ImageRequest("x", "16:9")).getString("size"))
        assertFalse(ImageGeneration.imagesBody("gpt-image-1", ImageRequest("x")).has("response_format"))
        val dalle = ImageGeneration.imagesBody("dall-e-3", ImageRequest("x", "9:16"))
        assertEquals("1024x1792", dalle.getString("size"))
        assertEquals("b64_json", dalle.getString("response_format"))
        assertEquals("1024x1024", ImageGeneration.imagesBody("dall-e-2", ImageRequest("x", "16:9")).getString("size"))

        val gemini = ImageGeneration.geminiBody(ImageRequest("x", "1:1", listOf(EncodedImage(b64, "image/jpeg"))))
        assertEquals("IMAGE", gemini.getJSONObject("generationConfig").getJSONArray("responseModalities").getString(1))
        assertEquals("1:1", gemini.getJSONObject("generationConfig").getJSONObject("imageConfig").getString("aspectRatio"))
        assertEquals("image/jpeg", gemini.getJSONArray("contents").getJSONObject(0).getJSONArray("parts").getJSONObject(1).getJSONObject("inlineData").getString("mimeType"))
    }

    @Test
    fun `replies are read on every route`() {
        val chat = ImageGeneration.parseChat(
            """{"choices":[{"message":{"role":"assistant","content":"Here it is.","images":[{"type":"image_url","image_url":{"url":"data:image/png;base64,$b64"}}]}}]}"""
        )
        assertArrayEquals(png, (chat.images.single() as ImageGeneration.ImageData.Bytes).data)
        assertEquals("Here it is.", chat.text)

        val parts = ImageGeneration.parseChat(
            """{"choices":[{"message":{"content":[{"type":"text","text":"ok"},{"type":"image_url","image_url":"https://cdn.example/p.png"}]}}]}"""
        )
        assertEquals("https://cdn.example/p.png", (parts.images.single() as ImageGeneration.ImageData.Url).url)

        val images = ImageGeneration.parseImages("""{"data":[{"b64_json":"$b64"},{"url":"https://x/y.png"}]}""")
        assertEquals(2, images.images.size)

        val gemini = ImageGeneration.parseGemini(
            """{"candidates":[{"content":{"parts":[{"text":"thinking","thought":true},{"text":"A fox."},{"inline_data":{"mime_type":"image/png","data":"$b64"}}]}}]}"""
        )
        assertArrayEquals(png, (gemini.images.single() as ImageGeneration.ImageData.Bytes).data)
        assertEquals("A fox.", gemini.text)
        assertNull(ImageGeneration.decodeDataUrl("data:image/png,notbase64"))
    }

    // ------------------------------------------------------------ in answers

    @Test
    fun `image blocks are numbered in order and known while streaming`() {
        val answer = "Look:\n```graph\n{\"fn\": \"x\"}\n```\n```image\nA fox in snow\n```\n```image\n{\"prompt\": \"Make it night\", \"input\": \"previous\", \"aspect\": \"landscape\"}\n```\n```image\nhalf writ"
        val blocks = ImageBlocks.find(answer)
        assertEquals(3, blocks.size)
        assertEquals("A fox in snow", blocks[0].prompt)
        assertTrue(blocks[0].complete)
        assertEquals("previous", blocks[1].input)
        assertEquals("16:9", blocks[1].aspect)
        assertEquals(2, blocks[2].index)
        assertFalse(blocks[2].complete)
    }

    @Test
    fun `markdown knows whether a fence is closed`() {
        val open = MarkdownParser.parse("```graph\n{\"fn\": \"x\"}").single() as MdBlock.Code
        assertFalse(open.complete)
        val closed = MarkdownParser.parse("```graph\n{\"fn\": \"x\"}\n```").single() as MdBlock.Code
        assertTrue(closed.complete)
    }

    @Test
    fun `pictures are saved with their turn`() {
        val thread = ChatThread(
            "t", 1, 2, "Fox", "ask", "m", null, "context", hasImage = false,
            turns = listOf(ChatTurn("draw a fox", "```image\nfox\n```", images = listOf(GeneratedImage(0, "img-1", "fox", "painter"))))
        )
        val back = ChatThread.decode(ChatThread.encode(listOf(thread))).single()
        assertEquals(thread.turns.single().images, back.turns.single().images)
    }

    @Test
    fun `settings keep the visual choices`() {
        val s = AppSettings(graphsEnabled = false, imageModel = ModelRef("or", "google/gemini-2.5-flash-image"))
        val back = AppSettings.fromJson(s.toJson())
        assertFalse(back.graphsEnabled)
        assertEquals(s.imageModel, back.imageModel)
        assertTrue(AppSettings.fromJson(AppSettings().toJson()).graphsEnabled)
        assertNull(AppSettings.fromJson(AppSettings().toJson()).imageModel)
    }

    @Test
    fun `the model is told only about what is on`() {
        assertEquals("", PromptBuilder.visualInstructions(graphs = false, images = false))
        val graphs = PromptBuilder.visualInstructions(graphs = true, images = false)
        assertTrue(graphs.contains("```graph") && graphs.contains("```graph3d"))
        assertFalse(graphs.contains("```image"))
        val both = PromptBuilder.visualInstructions(graphs = true, images = true)
        assertTrue(both.contains("```image") && both.contains("\$y=x^2-4\$"))
    }

    @Test
    fun `stopping the chat leaves pictures being made`() {
        val client = OkHttpClient()
        val chat = client.newCall(Request.Builder().url("https://example.invalid/chat").build())
        val picture = client.newCall(
            Request.Builder().url("https://example.invalid/image")
                .tag(CallRegistry.Background::class.java, CallRegistry.Background("job-1")).build()
        )
        CallRegistry.register(chat)
        val handle = CallRegistry.register(picture)
        CallRegistry.cancelAll()
        assertTrue(chat.isCanceled())
        assertFalse(picture.isCanceled())
        CallRegistry.cancelBackground("job-1")
        assertTrue(picture.isCanceled())
        handle.close()
    }
}
