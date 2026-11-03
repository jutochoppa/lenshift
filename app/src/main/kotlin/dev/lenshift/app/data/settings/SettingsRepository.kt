package dev.lenshift.app.data.settings

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import dev.lenshift.app.ai.ApiProtocol
import dev.lenshift.app.ai.ImageDetail
import dev.lenshift.app.ai.ModelSettings
import dev.lenshift.app.ai.ModelSpec
import dev.lenshift.app.ai.ProviderProfile
import dev.lenshift.app.ai.ReasoningMechanism
import dev.lenshift.app.ai.ReasoningMode
import dev.lenshift.app.ai.Tri
import dev.lenshift.app.data.security.SecretStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Single source of truth for user configuration.
 *
 * Storage split:
 *  - Provider *profiles* and preferences -> plain SharedPreferences (no secrets inside).
 *  - API keys -> [SecretStore] (Android Keystore, AES-GCM).
 *
 * The repository exposes a [StateFlow] so Compose screens and the capture activity all
 * observe the same snapshot without polling.
 */
class SettingsRepository(context: Context) {

    private val appContext = context.applicationContext
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val secrets = SecretStore(appContext)

    private val _state = MutableStateFlow(load())
    val state: StateFlow<AppSettings> = _state.asStateFlow()

    fun prime() = secrets.prime()

    val current: AppSettings get() = _state.value

    // ------------------------------------------------------------------ profiles

    fun upsertProfile(profile: ProviderProfile) {
        val existing = current.profiles.toMutableList()
        val index = existing.indexOfFirst { it.id == profile.id }
        if (index >= 0) existing[index] = profile else existing.add(profile)
        persist(current.copy(profiles = existing))
    }

    fun deleteProfile(id: String) {
        val remaining = current.profiles.filterNot { it.id == id }
        secrets.remove(id)
        val newActive = if (current.activeProfileId == id) {
            remaining.firstOrNull()?.id
        } else {
            current.activeProfileId
        }
        val imageModel = current.imageModel?.takeIf { it.profileId != id }
        persist(current.copy(profiles = remaining, activeProfileId = newActive, imageModel = imageModel))
    }

    /** The model answers' ```image blocks go to, or null to turn image generation off. */
    fun setImageModel(ref: ModelRef?) {
        persist(current.copy(imageModel = ref))
    }

    /**
     * The chosen image model as a profile whose current model is it, with its API key; null
     * when none is chosen or it no longer exists or creates images.
     */
    fun imageModel(): Pair<ProviderProfile, String?>? {
        val ref = current.imageModel ?: return null
        val profile = current.profiles.firstOrNull { it.id == ref.profileId } ?: return null
        if (ref.model !in profile.allModels || !profile.createsImages(ref.model)) return null
        return profile.selecting(ref.model) to apiKey(profile.id)
    }

    fun setActiveProfile(id: String) {
        persist(current.copy(activeProfileId = id))
    }

    /** Switches requests to [model] on provider [profileId] (from the chat's model picker). */
    fun selectModel(profileId: String, model: String) {
        val profiles = current.profiles.map { if (it.id == profileId) it.selecting(model) else it }
        persist(current.copy(profiles = profiles, activeProfileId = profileId))
    }

    fun newProfile(protocol: ApiProtocol): ProviderProfile {
        val id = UUID.randomUUID().toString()
        return ProviderProfile(
            id = id,
            displayName = protocol.label.substringBefore(" (").take(28),
            protocol = protocol,
            secretAlias = id
        )
    }

    fun activeProfile(): ProviderProfile? =
        current.profiles.firstOrNull { it.id == current.activeProfileId }
            ?: current.profiles.firstOrNull()

    // -------------------------------------------------------------------- secrets

    fun setApiKey(profileId: String, key: String?) {
        secrets.put(profileId, key)
    }

    fun apiKey(profileId: String): String? = secrets.get(profileId)

    fun hasApiKey(profileId: String): Boolean = secrets.has(profileId)

    // --------------------------------------------------------------- preferences

    fun updatePreferences(transform: (AppSettings) -> AppSettings) {
        persist(transform(current))
    }

    fun setDefaultQuickAction(action: QuickAction) {
        persist(current.copy(defaultQuickAction = action))
    }

    fun setRequireConfirmationBeforeSend(require: Boolean) {
        persist(current.copy(requireConfirmationBeforeSend = require))
    }

    fun setHistoryEnabled(enabled: Boolean) {
        persist(current.copy(historyEnabled = enabled))
    }

    fun clearHistory() {
        persist(current.copy(history = emptyList()))
    }

    fun addHistoryEntry(entry: HistoryEntry) {
        if (!current.historyEnabled) return
        val trimmed = (listOf(entry) + current.history).take(MAX_HISTORY)
        persist(current.copy(history = trimmed))
    }

    fun setOnboardingComplete(complete: Boolean) {
        persist(current.copy(onboardingComplete = complete))
    }

    // ------------------------------------------------------------------ internals

    private fun persist(settings: AppSettings) {
        prefs.edit()
            .putString(KEY_SETTINGS, settings.toJson().toString())
            .apply()
        _state.value = settings
    }

    private fun load(): AppSettings {
        val raw = prefs.getString(KEY_SETTINGS, null) ?: return AppSettings()
        return runCatching { AppSettings.fromJson(JSONObject(raw)) }
            .onFailure { Log.e(TAG, "Corrupt settings, falling back to defaults", it) }
            .getOrDefault(AppSettings())
    }

    private companion object {
        const val TAG = "Lenshift/Settings"
        const val PREFS = "lenshift_settings"
        const val KEY_SETTINGS = "settings_v1"
        const val MAX_HISTORY = 50
    }
}

// ======================================================================= models

data class AppSettings(
    val profiles: List<ProviderProfile> = emptyList(),
    val activeProfileId: String? = null,

    /** Behaviour when Lenshift opens. */
    val defaultQuickAction: QuickAction = QuickAction.ASK,

    /** When true, nothing leaves the device until the user taps Send. */
    val requireConfirmationBeforeSend: Boolean = false,

    /**
     * Search as soon as something is selected. Off by default: a selection waits for a
     * typed question, an action, or Send.
     */
    val searchOnSelect: Boolean = false,

    /**
     * Offer Google results tabs (All, Visual matches, …) beside Chat. They load Google's
     * public pages without an account, and only when the user opens one.
     */
    val googleTabsEnabled: Boolean = true,

    /** Open the Google "All" tab first instead of Chat. */
    val googleResultsFirst: Boolean = false,

    /** Save chat conversations on this device so they can be reopened from History. */
    val chatHistoryEnabled: Boolean = true,

    /** Let answers include graphs and 3D models Lenshift draws (```graph / ```graph3d). */
    val graphsEnabled: Boolean = true,

    /** The model that makes the pictures answers ask for; null turns pictures off. */
    val imageModel: ModelRef? = null,

    val historyEnabled: Boolean = false,
    val history: List<HistoryEntry> = emptyList(),

    /** Custom system instructions, layered over the built-in prompt presets. */
    val customSystemInstructions: String = "",

    /** OCR tuning. */
    val ocrEnabled: Boolean = true,
    val ocrLanguages: List<String> = listOf("eng"),

    /** Which activation surfaces the user has opted into. */
    val enabledActivations: Set<String> = setOf(ACTIVATION_ASSISTANT),

    val onboardingComplete: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("profiles", JSONArray().also { arr -> profiles.forEach { arr.put(it.toJson()) } })
        put("activeProfileId", activeProfileId ?: JSONObject.NULL)
        put("defaultAction", defaultQuickAction.id)
        put("requireConfirmationBeforeSend", requireConfirmationBeforeSend)
        put("searchOnSelect", searchOnSelect)
        put("googleTabsEnabled", googleTabsEnabled)
        put("googleResultsFirst", googleResultsFirst)
        put("chatHistoryEnabled", chatHistoryEnabled)
        put("graphsEnabled", graphsEnabled)
        put("imageModel", imageModel?.let { JSONObject().put("profileId", it.profileId).put("model", it.model) } ?: JSONObject.NULL)
        put("historyEnabled", historyEnabled)
        put("history", JSONArray().also { arr -> history.forEach { arr.put(it.toJson()) } })
        put("customSystemInstructions", customSystemInstructions)
        put("ocrEnabled", ocrEnabled)
        put("ocrLanguages", JSONArray(ocrLanguages))
        put("enabledActivations", JSONArray(enabledActivations.toList()))
        put("onboardingComplete", onboardingComplete)
    }

    companion object {
        const val ACTIVATION_ASSISTANT = "assistant"
        const val ACTIVATION_TILE = "tile"
        const val ACTIVATION_SHORTCUT = "shortcut"
        const val ACTIVATION_ACCESSIBILITY = "accessibility"

        fun fromJson(json: JSONObject): AppSettings {
            // Each element is parsed on its own. Previously a single null or malformed
            // entry threw out of the whole parse, load() then fell back to defaults, and
            // every saved provider, the active selection and custom instructions were
            // silently lost — with their Keystore secrets orphaned.
            val profiles = json.optJSONArray("profiles")?.let { arr ->
                buildList {
                    for (i in 0 until arr.length()) {
                        val item = arr.optJSONObject(i) ?: continue
                        runCatching { ProviderProfile.fromJson(item) }
                            .onSuccess { add(it) }
                    }
                }
            } ?: emptyList()

            val history = json.optJSONArray("history")?.let { arr ->
                buildList {
                    for (i in 0 until arr.length()) {
                        val item = arr.optJSONObject(i) ?: continue
                        runCatching { HistoryEntry.fromJson(item) }.onSuccess { add(it) }
                    }
                }
            } ?: emptyList()

            val languages = json.optJSONArray("ocrLanguages")?.let { arr ->
                buildList {
                    for (i in 0 until arr.length()) {
                        arr.optString(i).takeIf { it.isNotBlank() }?.let(::add)
                    }
                }
            }?.ifEmpty { null } ?: listOf("eng")

            val activations = json.optJSONArray("enabledActivations")?.let { arr ->
                buildList { for (i in 0 until arr.length()) add(arr.getString(i)) }.toSet()
            } ?: setOf(ACTIVATION_ASSISTANT)

            return AppSettings(
                profiles = profiles,
                activeProfileId = json.optString("activeProfileId").takeIf {
                    it.isNotBlank() && it != "null"
                },
                defaultQuickAction = QuickAction.fromId(json.optString("defaultAction")),
                requireConfirmationBeforeSend = json.optBoolean("requireConfirmationBeforeSend", false),
                searchOnSelect = json.optBoolean("searchOnSelect", false),
                googleTabsEnabled = json.optBoolean("googleTabsEnabled", true),
                googleResultsFirst = json.optBoolean("googleResultsFirst", false),
                chatHistoryEnabled = json.optBoolean("chatHistoryEnabled", true),
                graphsEnabled = json.optBoolean("graphsEnabled", true),
                imageModel = json.optJSONObject("imageModel")?.let { o ->
                    val profileId = o.optString("profileId")
                    val model = o.optString("model")
                    if (profileId.isNotBlank() && model.isNotBlank()) ModelRef(profileId, model) else null
                },
                historyEnabled = json.optBoolean("historyEnabled", false),
                history = history,
                customSystemInstructions = json.optString("customSystemInstructions", ""),
                ocrEnabled = json.optBoolean("ocrEnabled", true),
                ocrLanguages = languages,
                enabledActivations = activations,
                onboardingComplete = json.optBoolean("onboardingComplete", false)
            )
        }
    }
}

/** One model of one saved provider. */
data class ModelRef(val profileId: String, val model: String)

data class HistoryEntry(
    val id: String,
    val timestamp: Long,
    val question: String,
    val answerPreview: String,
    val modelId: String
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("timestamp", timestamp)
        put("question", question)
        put("answerPreview", answerPreview)
        put("modelId", modelId)
    }

    companion object {
        fun fromJson(json: JSONObject) = HistoryEntry(
            id = json.optString("id"),
            timestamp = json.optLong("timestamp"),
            question = json.optString("question"),
            answerPreview = json.optString("answerPreview"),
            modelId = json.optString("modelId")
        )
    }
}

// ================================================================= serialisation

fun ProviderProfile.toJson(): JSONObject = JSONObject().apply {
    put("id", id)
    put("displayName", displayName)
    put("protocol", protocol.id)
    put("baseUrl", baseUrl)
    put("modelId", modelId)
    put("models", JSONArray(models))
    put("secretAlias", secretAlias)
    put("customHeaders", JSONObject(customHeaders))
    put("streaming", streaming)
    put("temperature", temperature?.toDouble() ?: JSONObject.NULL)
    put("topP", topP?.toDouble() ?: JSONObject.NULL)
    put("topK", topK ?: JSONObject.NULL)
    put("maxOutputTokens", maxOutputTokens ?: JSONObject.NULL)
    put("contextWindowOverride", contextWindowOverride ?: JSONObject.NULL)
    put("reasoningMode", reasoningMode.id)
    put("reasoningTokenBudget", reasoningTokenBudget ?: JSONObject.NULL)
    put("imageDetail", imageDetail.id)
    put("jpegQuality", jpegQuality)
    put("maxImageEdgePx", maxImageEdgePx)
    put("timeoutMs", timeoutMs)
    put("maxRetries", maxRetries)
    put("extraJsonParameters", extraJsonParameters)
    put("visionOverride", visionOverride.name)
    put("reasoningOverride", reasoningOverride.name)
    put("allowCleartext", allowCleartext)
    put("mechanismOverride", mechanismOverride?.name ?: JSONObject.NULL)
    put("imageOutputOverride", imageOutputOverride.name)
    put("modelSettings", JSONObject().also { obj -> modelSettings.forEach { (id, s) -> obj.put(id, s.toJson()) } })
    put("modelSpecs", JSONObject().also { obj -> modelSpecs.forEach { (id, s) -> obj.put(id, s.toJson()) } })
}

private fun ModelSettings.toJson(): JSONObject = JSONObject().apply {
    put("temperature", temperature?.toDouble() ?: JSONObject.NULL)
    put("topP", topP?.toDouble() ?: JSONObject.NULL)
    put("topK", topK ?: JSONObject.NULL)
    put("maxOutputTokens", maxOutputTokens ?: JSONObject.NULL)
    put("contextWindowOverride", contextWindowOverride ?: JSONObject.NULL)
    put("reasoningMode", reasoningMode.id)
    put("reasoningTokenBudget", reasoningTokenBudget ?: JSONObject.NULL)
    put("visionOverride", visionOverride.name)
    put("reasoningOverride", reasoningOverride.name)
    put("mechanismOverride", mechanismOverride?.name ?: JSONObject.NULL)
    put("imageOutputOverride", imageOutputOverride.name)
}

private fun modelSettingsFromJson(json: JSONObject) = ModelSettings(
    temperature = json.optDoubleOrNull("temperature")?.toFloat(),
    topP = json.optDoubleOrNull("topP")?.toFloat(),
    topK = json.optIntOrNull("topK"),
    maxOutputTokens = json.optIntOrNull("maxOutputTokens"),
    contextWindowOverride = json.optIntOrNull("contextWindowOverride"),
    reasoningMode = ReasoningMode.fromId(json.optString("reasoningMode")),
    reasoningTokenBudget = json.optIntOrNull("reasoningTokenBudget"),
    visionOverride = runCatching { Tri.valueOf(json.optString("visionOverride", "UNKNOWN")) }
        .getOrDefault(Tri.UNKNOWN),
    reasoningOverride = runCatching { Tri.valueOf(json.optString("reasoningOverride", "UNKNOWN")) }
        .getOrDefault(Tri.UNKNOWN),
    mechanismOverride = runCatching { ReasoningMechanism.valueOf(json.optString("mechanismOverride")) }
        .getOrNull(),
    imageOutputOverride = runCatching { Tri.valueOf(json.optString("imageOutputOverride", "UNKNOWN")) }
        .getOrDefault(Tri.UNKNOWN)
)

private fun ModelSpec.toJson(): JSONObject = JSONObject().apply {
    put("contextWindow", contextWindow ?: JSONObject.NULL)
    put("maxOutputTokens", maxOutputTokens ?: JSONObject.NULL)
    put("vision", vision.name)
    put("reasoning", reasoning.name)
    put("reasoningModes", JSONArray(reasoningModes.map { it.id }))
    parameters?.let { put("parameters", JSONArray(it.sorted())) }
    put("imageOutput", imageOutput.name)
    put("textOutput", textOutput.name)
}

private fun modelSpecFromJson(json: JSONObject) = ModelSpec(
    contextWindow = json.optIntOrNull("contextWindow"),
    maxOutputTokens = json.optIntOrNull("maxOutputTokens"),
    vision = runCatching { Tri.valueOf(json.optString("vision", "UNKNOWN")) }.getOrDefault(Tri.UNKNOWN),
    reasoning = runCatching { Tri.valueOf(json.optString("reasoning", "UNKNOWN")) }.getOrDefault(Tri.UNKNOWN),
    reasoningModes = json.optJSONArray("reasoningModes")?.let { arr ->
        (0 until arr.length()).mapNotNull { i -> ReasoningMode.entries.firstOrNull { it.id == arr.optString(i) } }
    }.orEmpty(),
    parameters = json.optJSONArray("parameters")?.let { arr ->
        (0 until arr.length()).mapNotNull { arr.optString(it).takeIf(String::isNotBlank) }.toSet()
    },
    imageOutput = runCatching { Tri.valueOf(json.optString("imageOutput", "UNKNOWN")) }.getOrDefault(Tri.UNKNOWN),
    textOutput = runCatching { Tri.valueOf(json.optString("textOutput", "UNKNOWN")) }.getOrDefault(Tri.UNKNOWN)
)

private fun <T> JSONObject.optMap(key: String, read: (JSONObject) -> T): Map<String, T> {
    val obj = optJSONObject(key) ?: return emptyMap()
    return buildMap {
        obj.keys().forEach { id -> obj.optJSONObject(id)?.let { runCatching { read(it) }.onSuccess { v -> put(id, v) } } }
    }
}

fun ProviderProfile.Companion.fromJson(json: JSONObject): ProviderProfile = ProviderProfile(
    id = json.optString("id"),
    displayName = json.optString("displayName", "Provider"),
    protocol = ApiProtocol.fromId(json.optString("protocol")),
    baseUrl = json.optString("baseUrl", ""),
    modelId = json.optString("modelId", ""),
    models = json.optJSONArray("models")?.let { arr ->
        (0 until arr.length()).mapNotNull { arr.optString(it).trim().takeIf(String::isNotEmpty) }
    }.orEmpty(),
    secretAlias = json.optString("secretAlias", json.optString("id")),
    customHeaders = json.optJSONObject("customHeaders")?.let { obj ->
        buildMap { obj.keys().forEach { k -> put(k, obj.optString(k)) } }
    } ?: emptyMap(),
    streaming = json.optBoolean("streaming", true),
    temperature = json.optDoubleOrNull("temperature")?.toFloat(),
    topP = json.optDoubleOrNull("topP")?.toFloat(),
    topK = json.optIntOrNull("topK"),
    maxOutputTokens = json.optIntOrNull("maxOutputTokens"),
    contextWindowOverride = json.optIntOrNull("contextWindowOverride"),
    reasoningMode = ReasoningMode.fromId(json.optString("reasoningMode")),
    reasoningTokenBudget = json.optIntOrNull("reasoningTokenBudget"),
    imageDetail = ImageDetail.entries.firstOrNull { it.id == json.optString("imageDetail") }
        ?: ImageDetail.AUTO,
    jpegQuality = json.optInt("jpegQuality", 85),
    maxImageEdgePx = json.optInt("maxImageEdgePx", 1568),
    timeoutMs = json.optLong("timeoutMs", 90_000L),
    maxRetries = json.optInt("maxRetries", 1),
    extraJsonParameters = json.optString("extraJsonParameters", ""),
    visionOverride = runCatching { Tri.valueOf(json.optString("visionOverride", "UNKNOWN")) }
        .getOrDefault(Tri.UNKNOWN),
    reasoningOverride = runCatching { Tri.valueOf(json.optString("reasoningOverride", "UNKNOWN")) }
        .getOrDefault(Tri.UNKNOWN),
    allowCleartext = json.optBoolean("allowCleartext", false),
    mechanismOverride = runCatching {
        ReasoningMechanism.valueOf(json.optString("mechanismOverride"))
    }.getOrNull(),
    imageOutputOverride = runCatching { Tri.valueOf(json.optString("imageOutputOverride", "UNKNOWN")) }
        .getOrDefault(Tri.UNKNOWN),
    modelSettings = json.optMap("modelSettings", ::modelSettingsFromJson),
    modelSpecs = json.optMap("modelSpecs", ::modelSpecFromJson)
)

private fun JSONObject.optDoubleOrNull(key: String): Double? =
    if (isNull(key)) null else optDouble(key).takeIf { !it.isNaN() }

private fun JSONObject.optIntOrNull(key: String): Int? =
    if (isNull(key)) null else optInt(key)
