package dev.lenshift.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Model specs: provider lists, the built-in table, and how they resolve into capabilities. */
class ModelSpecTest {

    // ------------------------------------------------------------ built-in table

    @Test
    fun `ids are normalised across vendors prefixes dates and variants`() {
        assertEquals("gpt-4-1", KnownModels.normalize("openai/gpt-4.1-2025-04-14"))
        assertEquals("claude-3-7-sonnet", KnownModels.normalize("anthropic/claude-3.7-sonnet:thinking"))
        assertEquals("claude-sonnet-4", KnownModels.normalize("claude-sonnet-4-20250514"))
        assertEquals("gpt-3-5-turbo", KnownModels.normalize("gpt-3.5-turbo-0125"))
        assertEquals("chatgpt-4o", KnownModels.normalize("chatgpt-4o-latest"))
        assertEquals(16_384, KnownModels.lookup("chatgpt-4o-latest")!!.maxOutputTokens)
    }

    @Test
    fun `known models resolve to their published limits`() {
        val mini = KnownModels.lookup("openai/gpt-4o-mini")!!
        assertEquals(128_000, mini.contextWindow)
        assertEquals(16_384, mini.maxOutputTokens)
        assertEquals(Tri.YES, mini.vision)
        assertEquals(Tri.NO, mini.reasoning)
        assertEquals(ModelSpec.Source.BUILT_IN, mini.source)

        assertEquals(1_047_576, KnownModels.lookup("gpt-4.1-nano")!!.contextWindow)
        assertEquals(64_000, KnownModels.lookup("anthropic/claude-3.7-sonnet")!!.maxOutputTokens)
        assertEquals(32_000, KnownModels.lookup("claude-opus-4-1-20250805")!!.maxOutputTokens)
        assertEquals(2_097_152, KnownModels.lookup("models/gemini-1.5-pro-002")!!.contextWindow)
    }

    @Test
    fun `more specific entries win and unknown models stay unknown`() {
        assertEquals(16_384, KnownModels.lookup("gpt-4o")!!.maxOutputTokens)
        assertTrue(ReasoningMode.OFF in KnownModels.lookup("gemini-2.5-flash-lite")!!.reasoningModes)
        assertFalse("2.5 Pro cannot turn thinking off", ReasoningMode.OFF in KnownModels.lookup("gemini-2.5-pro")!!.reasoningModes)
        assertEquals(listOf(ReasoningMode.MINIMAL, ReasoningMode.LOW, ReasoningMode.MEDIUM, ReasoningMode.HIGH),
            KnownModels.lookup("gpt-5-mini")!!.reasoningModes)
        assertEquals(Tri.NO, KnownModels.lookup("gpt-5-chat-latest")!!.reasoning)

        assertNull(KnownModels.lookup("mock-reasoner"))
        assertNull("a newer model must not inherit an older one's numbers", KnownModels.lookup("gpt-4o-mini-2"))
        assertNull(KnownModels.lookup("o3-deep-research"))
        assertNull(KnownModels.lookup(""))
    }

    @Test
    fun `openai reasoning models do not accept sampling parameters`() {
        val o3 = KnownModels.lookup("o3")!!
        assertFalse(o3.accepts(ModelSpec.TEMPERATURE))
        assertTrue(o3.accepts(ModelSpec.REASONING))
        assertFalse("o1-mini takes no effort parameter", KnownModels.lookup("o1-mini")!!.accepts(ModelSpec.REASONING))
    }

    @Test
    fun `token counts format the way vendors quote them`() {
        assertEquals("128K", ModelSpec.formatTokens(128_000))
        assertEquals("128K", ModelSpec.formatTokens(131_072))
        assertEquals("16K", ModelSpec.formatTokens(16_384))
        assertEquals("1M", ModelSpec.formatTokens(1_047_576))
        assertEquals("1M", ModelSpec.formatTokens(1_048_576))
        assertEquals("2M", ModelSpec.formatTokens(2_097_152))
        assertEquals("400K", ModelSpec.formatTokens(400_000))
    }

    // ---------------------------------------------------------------- list parsing

    @Test
    fun `openrouter lists keep context output modalities and parameters`() {
        val raw = """
            {"data":[
              {"id":"openai/gpt-4o","name":"OpenAI: GPT-4o","context_length":128000,
               "architecture":{"modality":"text+image->text","input_modalities":["text","image","file"],"output_modalities":["text"]},
               "top_provider":{"context_length":128000,"max_completion_tokens":16384,"is_moderated":true},
               "supported_parameters":["max_tokens","temperature","top_p","tools"]},
              {"id":"deepseek/deepseek-r1","name":"DeepSeek: R1","context_length":163840,
               "architecture":{"input_modalities":["text"]},
               "top_provider":{"context_length":163840,"max_completion_tokens":null},
               "supported_parameters":["max_tokens","reasoning","include_reasoning","temperature"]}
            ]}
        """.trimIndent()
        val models = ModelListParser.openAiStyle(raw)!!
        assertEquals(listOf("deepseek/deepseek-r1", "openai/gpt-4o"), models.map { it.id })

        val gpt = models.single { it.id == "openai/gpt-4o" }
        assertEquals("OpenAI: GPT-4o", gpt.name)
        assertEquals(128_000, gpt.spec!!.contextWindow)
        assertEquals(16_384, gpt.spec!!.maxOutputTokens)
        assertEquals(Tri.YES, gpt.spec!!.vision)
        assertEquals(Tri.NO, gpt.spec!!.reasoning)
        assertTrue(gpt.spec!!.accepts("tools"))
        assertFalse(gpt.spec!!.accepts(ModelSpec.TOP_K))

        val r1 = models.single { it.id == "deepseek/deepseek-r1" }.spec!!
        assertEquals(163_840, r1.contextWindow)
        assertNull("a null limit is not a limit", r1.maxOutputTokens)
        assertEquals(Tri.NO, r1.vision)
        assertEquals(Tri.YES, r1.reasoning)
    }

    @Test
    fun `plain openai lists carry ids only and other gateways their own fields`() {
        val plain = ModelListParser.openAiStyle("""{"object":"list","data":[{"id":"gpt-4o","object":"model","owned_by":"system"}]}""")!!
        assertEquals("gpt-4o", plain.single().id)
        assertNull(plain.single().spec)

        val groq = ModelListParser.openAiStyle("""{"data":[{"id":"llama-3.3-70b-versatile","context_window":131072}]}""")!!
        assertEquals(131_072, groq.single().spec!!.contextWindow)

        val mistral = ModelListParser.openAiStyle(
            """{"data":[{"id":"pixtral-large-latest","max_context_length":131072,"capabilities":{"vision":true}}]}"""
        )!!
        assertEquals(Tri.YES, mistral.single().spec!!.vision)

        val bare = ModelListParser.openAiStyle("""[{"id":"meta-llama/Llama-3-8b","context_length":8192}]""")!!
        assertEquals(8_192, bare.single().spec!!.contextWindow)

        assertNull(ModelListParser.openAiStyle("<html>not found</html>"))
    }

    @Test
    fun `gemini lists keep token limits and the thinking flag`() {
        val raw = """
            {"models":[
              {"name":"models/gemini-2.5-flash","displayName":"Gemini 2.5 Flash","inputTokenLimit":1048576,
               "outputTokenLimit":65536,"supportedGenerationMethods":["generateContent","countTokens"],
               "temperature":1,"topP":0.95,"topK":64,"thinking":true},
              {"name":"models/text-embedding-004","supportedGenerationMethods":["embedContent"]}
            ]}
        """.trimIndent()
        val models = ModelListParser.gemini(raw)!!
        val flash = models.single()
        assertEquals("gemini-2.5-flash", flash.id)
        assertEquals(1_048_576, flash.spec!!.contextWindow)
        assertEquals(65_536, flash.spec!!.maxOutputTokens)
        assertEquals(Tri.YES, flash.spec!!.reasoning)
        assertTrue(flash.spec!!.accepts(ModelSpec.TOP_K))
    }

    @Test
    fun `ollama show reports context and capabilities`() {
        val spec = ModelListParser.ollamaShow(
            """{"capabilities":["completion","vision"],"model_info":{"general.architecture":"gemma3","gemma3.context_length":131072}}"""
        )!!
        assertEquals(131_072, spec.contextWindow)
        assertEquals(Tri.YES, spec.vision)
        assertEquals(Tri.NO, spec.reasoning)
        assertEquals(listOf("llama3.2:3b", "qwen3:8b"), ModelListParser.ollamaTags("""{"models":[{"name":"qwen3:8b"},{"name":"llama3.2:3b"}]}"""))
    }

    @Test
    fun `anthropic lists keep display names`() {
        val models = ModelListParser.anthropic(
            """{"data":[{"type":"model","id":"claude-sonnet-4-20250514","display_name":"Claude Sonnet 4"}],"has_more":false}"""
        )!!
        assertEquals("Claude Sonnet 4", models.single().name)
        assertNull(models.single().spec)
    }

    // ------------------------------------------------------ capability resolution

    private val openRouter = ProviderProfile(
        id = "p", displayName = "OpenRouter", baseUrl = "https://openrouter.ai/api/v1", modelId = "openai/gpt-4o"
    )

    @Test
    fun `reported specs win over the table and the table over name guessing`() {
        val typical = CapabilityInference.forProfile(openRouter)
        assertEquals(128_000, typical.contextWindow)
        assertEquals(16_384, typical.maxOutputTokens)

        val reported = CapabilityInference.forProfile(
            openRouter.copy(modelSpecs = mapOf("openai/gpt-4o" to ModelSpec(contextWindow = 120_000)))
        )
        assertEquals(120_000, reported.contextWindow)
        assertEquals("gaps fill from the table", 16_384, reported.maxOutputTokens)

        val unknown = CapabilityInference.forProfile(openRouter.copy(modelId = "mock-reasoner"))
        assertNull(unknown.contextWindow)
        assertNull(unknown.parameters)
    }

    @Test
    fun `the user's overrides win over every spec`() {
        val caps = CapabilityInference.forProfile(
            openRouter.copy(contextWindowOverride = 50_000, visionOverride = Tri.NO)
        )
        assertEquals(50_000, caps.contextWindow)
        assertEquals(Tri.NO, caps.vision)
    }

    @Test
    fun `a spec without a reasoning parameter blocks effort unless the user forces it`() {
        val noParam = openRouter.copy(
            modelId = "x/thinker",
            modelSpecs = mapOf("x/thinker" to ModelSpec(reasoning = Tri.YES, parameters = setOf("max_tokens")))
        )
        assertTrue(CapabilityInference.forProfile(noParam).strictReasoningRejection)
        assertFalse(CapabilityInference.forProfile(noParam.copy(reasoningOverride = Tri.YES)).strictReasoningRejection)
    }

    @Test
    fun `reported limits are recorded only for saved models`() {
        val p = openRouter.withSpecs(
            listOf(
                ModelInfo("openai/gpt-4o", spec = ModelSpec(contextWindow = 1)),
                ModelInfo("other/model", spec = ModelSpec(contextWindow = 2))
            )
        )
        assertEquals(setOf("openai/gpt-4o"), p.modelSpecs.keys)
        assertNotNull(p.specFor("openai/gpt-4o"))
    }
}
