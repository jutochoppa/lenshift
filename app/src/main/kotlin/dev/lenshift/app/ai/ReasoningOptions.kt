package dev.lenshift.app.ai

/**
 * The reasoning levels a model's settings offer: its built-in levels (those the user hasn't
 * deleted) followed by levels of the user's own, which are sent exactly as written. All
 * changes apply to the profile's current model.
 */
object ReasoningOptions {

    /** Sends nothing: the model uses its own default. */
    const val AUTOMATIC = "auto"
    const val MAX_LENGTH = 40

    /** One level: built-in ([mode]) or the user's own ([custom]). [key] identifies it. */
    data class Option(val key: String, val label: String, val mode: ReasoningMode?, val custom: String?)

    fun options(profile: ProviderProfile, capabilities: ModelCapabilities): List<Option> {
        val edits = profile.customReasoning
        val builtIn = ReasoningMapper.levels(capabilities)
            .filter { it.id !in edits.hidden }
            .map { Option(levelKey(it), it.label, it, null) }
        return builtIn + edits.added.map { Option(ownKey(it), it, null, it) }
    }

    /** The level in use: the user's own pick, else the built-in level actually sent, else Automatic. */
    fun selectedKey(profile: ProviderProfile, capabilities: ModelCapabilities): String {
        profile.customReasoning.selected?.let { return ownKey(it) }
        val level = ReasoningMapper.effectiveMode(profile.reasoningMode, capabilities)
        return if (level == ReasoningMode.AUTO) AUTOMATIC else levelKey(level)
    }

    fun select(profile: ProviderProfile, key: String): ProviderProfile {
        val edits = profile.customReasoning
        return when {
            key == AUTOMATIC -> profile.copy(reasoningMode = ReasoningMode.AUTO, customReasoning = edits.copy(selected = null))
            key.startsWith(LEVEL) -> profile.copy(
                reasoningMode = ReasoningMode.fromId(key.removePrefix(LEVEL)),
                customReasoning = edits.copy(selected = null)
            )
            key.startsWith(OWN) -> key.removePrefix(OWN).takeIf { it in edits.added }
                ?.let { profile.copy(customReasoning = edits.copy(selected = it)) } ?: profile
            else -> profile
        }
    }

    /** Deletes a level; if it was in use, the model goes back to Automatic. */
    fun remove(profile: ProviderProfile, key: String, capabilities: ModelCapabilities): ProviderProfile {
        val edits = profile.customReasoning
        return when {
            key.startsWith(LEVEL) -> {
                val id = key.removePrefix(LEVEL)
                val inUse = edits.selected == null && ReasoningMapper.effectiveMode(profile.reasoningMode, capabilities).id == id
                profile.copy(
                    reasoningMode = if (inUse) ReasoningMode.AUTO else profile.reasoningMode,
                    customReasoning = edits.copy(hidden = edits.hidden + id)
                )
            }
            key.startsWith(OWN) -> {
                val value = key.removePrefix(OWN)
                val inUse = edits.selected == value
                profile.copy(
                    reasoningMode = if (inUse) ReasoningMode.AUTO else profile.reasoningMode,
                    customReasoning = edits.copy(added = edits.added - value, selected = edits.selected.takeUnless { inUse })
                )
            }
            else -> profile
        }
    }

    /**
     * Adds a level of the user's own and picks it. A built-in level's name brings that
     * level back instead, and a value already added is just picked.
     */
    fun add(profile: ProviderProfile, value: String, capabilities: ModelCapabilities): ProviderProfile {
        val text = value.trim().replace(Regex("""\s+"""), " ").take(MAX_LENGTH)
        if (text.isEmpty()) return profile
        val edits = profile.customReasoning
        ReasoningMapper.levels(capabilities)
            .firstOrNull { it.id.equals(text, ignoreCase = true) || it.label.equals(text, ignoreCase = true) }
            ?.let { level ->
                return select(profile.copy(customReasoning = edits.copy(hidden = edits.hidden - level.id)), levelKey(level))
            }
        val existing = edits.added.firstOrNull { it.equals(text, ignoreCase = true) }
        return profile.copy(
            customReasoning = edits.copy(added = if (existing == null) edits.added + text else edits.added, selected = existing ?: text)
        )
    }

    /** Brings back the deleted built-in levels; the user's own stay. */
    fun restoreDefaults(profile: ProviderProfile): ProviderProfile =
        profile.copy(customReasoning = profile.customReasoning.copy(hidden = emptySet()))

    private const val LEVEL = "level:"
    private const val OWN = "own:"
    private fun levelKey(mode: ReasoningMode) = LEVEL + mode.id
    private fun ownKey(value: String) = OWN + value
}
