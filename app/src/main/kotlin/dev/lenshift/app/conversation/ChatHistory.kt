package dev.lenshift.app.conversation

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.max
import kotlin.math.roundToInt

/** One question and its answer in a chat thread. */
data class ChatTurn(
    /** What the user sees as their message: typed text or the quick action's label. */
    val question: String,
    val answer: String,
    val reasoning: String? = null,
    /** Ids of images the user attached to this question (photos or extra screenshots). */
    val attachments: List<String> = emptyList(),
    /** Pictures the image model made for the answer's ```image blocks. */
    val images: List<GeneratedImage> = emptyList()
)

/** A picture made for the [index]th ```image block of an answer, saved under [id]. */
data class GeneratedImage(val index: Int, val id: String, val prompt: String, val model: String)

/** Which picture: the [index]th ```image block of the [turn]th answer. */
data class ImageKey(val turn: Int, val index: Int)

/** A picture an answer asked for, while it is made and after. */
sealed interface TurnImage {
    val prompt: String

    data class Pending(override val prompt: String) : TurnImage
    data class Ready(override val prompt: String, val id: String, val model: String) : TurnImage
    /** [needsModel]: nothing was tried, because no image model is chosen. */
    data class Failed(override val prompt: String, val message: String, val needsModel: Boolean = false) : TurnImage
}

/**
 * A saved conversation about one selection.
 *
 * [contextPrompt] is the first user message exactly as it was sent (screen text framed as
 * data, then the first question), so a reopened thread gives the model the same context.
 */
data class ChatThread(
    val id: String,
    val createdAt: Long,
    val updatedAt: Long,
    val title: String,
    val actionId: String,
    val modelLabel: String,
    val sourceLabel: String?,
    val contextPrompt: String,
    val hasImage: Boolean,
    val turns: List<ChatTurn>
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("createdAt", createdAt)
        put("updatedAt", updatedAt)
        put("title", title)
        put("actionId", actionId)
        put("modelLabel", modelLabel)
        put("sourceLabel", sourceLabel ?: JSONObject.NULL)
        put("contextPrompt", contextPrompt)
        put("hasImage", hasImage)
        put("turns", JSONArray().also { arr ->
            turns.forEach { t ->
                arr.put(JSONObject().apply {
                    put("question", t.question)
                    put("answer", t.answer)
                    put("reasoning", t.reasoning ?: JSONObject.NULL)
                    if (t.attachments.isNotEmpty()) put("attachments", JSONArray(t.attachments))
                    if (t.images.isNotEmpty()) put("images", JSONArray().also { arr ->
                        t.images.forEach { g ->
                            arr.put(JSONObject().put("index", g.index).put("id", g.id).put("prompt", g.prompt).put("model", g.model))
                        }
                    })
                })
            }
        })
    }

    companion object {
        fun fromJson(json: JSONObject): ChatThread? {
            val id = json.optString("id").takeIf { it.isNotBlank() } ?: return null
            val turnsJson = json.optJSONArray("turns") ?: JSONArray()
            val turns = buildList {
                for (i in 0 until turnsJson.length()) {
                    val t = turnsJson.optJSONObject(i) ?: continue
                    val answer = t.optString("answer")
                    if (answer.isBlank()) continue
                    add(
                        ChatTurn(
                            question = t.optString("question"),
                            answer = answer,
                            reasoning = t.optString("reasoning").takeIf { !t.isNull("reasoning") && it.isNotBlank() },
                            attachments = t.optJSONArray("attachments")?.let { arr ->
                                (0 until arr.length()).mapNotNull { arr.optString(it).takeIf(String::isNotBlank) }
                            }.orEmpty(),
                            images = t.optJSONArray("images")?.let { arr ->
                                (0 until arr.length()).mapNotNull { k ->
                                    val g = arr.optJSONObject(k) ?: return@mapNotNull null
                                    val id = g.optString("id").takeIf(String::isNotBlank) ?: return@mapNotNull null
                                    GeneratedImage(g.optInt("index"), id, g.optString("prompt"), g.optString("model"))
                                }
                            }.orEmpty()
                        )
                    )
                }
            }
            if (turns.isEmpty()) return null
            return ChatThread(
                id = id,
                createdAt = json.optLong("createdAt"),
                updatedAt = json.optLong("updatedAt"),
                title = json.optString("title").ifBlank { turns.first().question },
                actionId = json.optString("actionId"),
                modelLabel = json.optString("modelLabel"),
                sourceLabel = json.optString("sourceLabel").takeIf { !json.isNull("sourceLabel") && it.isNotBlank() },
                contextPrompt = json.optString("contextPrompt"),
                hasImage = json.optBoolean("hasImage"),
                turns = turns
            )
        }

        /** Serialises a thread list. Pure, so it is unit-tested. */
        fun encode(threads: List<ChatThread>): String =
            JSONObject().put("version", 1)
                .put("threads", JSONArray().also { arr -> threads.forEach { arr.put(it.toJson()) } })
                .toString()

        /** Parses a thread list, skipping damaged entries rather than failing. */
        fun decode(text: String): List<ChatThread> {
            val root = runCatching { JSONObject(text) }.getOrNull() ?: return emptyList()
            val arr = root.optJSONArray("threads") ?: return emptyList()
            return buildList {
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.let { runCatching { fromJson(it) }.getOrNull() }?.let(::add)
                }
            }
        }
    }
}

/** History titles and date grouping. Pure, so they are unit-tested. */
object ChatHistoryFormat {

    /** How recent a chat must be for the activated screen to offer continuing it. */
    const val RESUME_WINDOW_MS = 24 * 60 * 60 * 1000L

    /** The newest chat if it was updated within [window] before [now]; otherwise null. */
    fun resumable(threads: List<ChatThread>, now: Long, window: Long = RESUME_WINDOW_MS): ChatThread? =
        threads.maxByOrNull { it.updatedAt }?.takeIf { now - it.updatedAt <= window }

    /** How long ago [time] was, as short as a chip needs: "now", "5m", "2h", "yesterday", "3d". */
    fun ago(time: Long, now: Long): String {
        val minutes = (now - time) / 60_000
        return when {
            minutes < 1 -> "now"
            minutes < 60 -> "${minutes}m"
            minutes < 24 * 60 -> "${minutes / 60}h"
            minutes < 48 * 60 -> "yesterday"
            else -> "${minutes / (24 * 60)}d"
        }
    }

    /**
     * A short title: the question when the user typed one, otherwise the first sentence of
     * the answer with Markdown and LaTeX markers removed.
     */
    fun title(firstQuestion: String, typed: Boolean, firstAnswer: String): String {
        val source = if (typed && firstQuestion.isNotBlank()) {
            strip(firstQuestion)
        } else {
            firstSentence(dev.lenshift.app.ai.LeakedToolCode.strip(firstAnswer))
        }
        val clean = source.ifBlank { firstQuestion.trim() }
        return if (clean.length <= 64) clean else clean.take(61).trimEnd() + "…"
    }

    /** Removes Markdown links and Markdown/LaTeX markers, and collapses whitespace. */
    private fun strip(text: String): String =
        text.replace(Regex("""\(https?://[^)]*\)"""), "")
            .replace(Regex("""[*_`#>$\\]|\[|\]"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()

    /** The first sentence of the first prose line (outside code blocks), formatting removed. */
    private fun firstSentence(text: String): String {
        var inFence = false
        val line = text.lineSequence().map { it.trim() }
            .firstOrNull { l ->
                if (l.startsWith("```")) {
                    inFence = !inFence
                    false
                } else !inFence && l.isNotEmpty()
            }
            ?.let(::strip)
            .orEmpty()
        val end = Regex("""[.!?](\s|$)""").find(line)?.range?.first
        return if (end != null) line.substring(0, end + 1) else line
    }

    /** Groups threads (newest first) into Today, Yesterday, Previous 7 days, Previous 30 days and Older. */
    fun group(threads: List<ChatThread>, now: Long, zone: ZoneId = ZoneId.systemDefault()): List<Pair<String, List<ChatThread>>> {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        fun bucket(t: ChatThread): String {
            val day: LocalDate = Instant.ofEpochMilli(t.updatedAt).atZone(zone).toLocalDate()
            return when {
                !day.isBefore(today) -> "Today"
                day == today.minusDays(1) -> "Yesterday"
                day.isAfter(today.minusDays(7)) -> "Previous 7 days"
                day.isAfter(today.minusDays(30)) -> "Previous 30 days"
                else -> "Older"
            }
        }
        val order = listOf("Today", "Yesterday", "Previous 7 days", "Previous 30 days", "Older")
        return threads.sortedByDescending { it.updatedAt }
            .groupBy(::bucket)
            .toList()
            .sortedBy { order.indexOf(it.first) }
    }
}

/**
 * Saved chat threads, kept in app-private storage only: an index file, one JPEG of each
 * thread's selection and one per attached image (scaled down), so a reopened thread can
 * show them and send them again. Nothing here leaves the device.
 */
class ChatHistoryStore(private val dir: File) {

    private val index get() = File(dir, "threads.json")

    @Synchronized
    fun list(): List<ChatThread> =
        runCatching { if (index.exists()) ChatThread.decode(index.readText()) else emptyList() }
            .getOrDefault(emptyList())
            .sortedByDescending { it.updatedAt }

    /**
     * Adds or replaces [thread]; writes [image] once for a new thread and any [attachments]
     * (attachment id to image) not saved yet.
     */
    @Synchronized
    fun save(thread: ChatThread, image: Bitmap?, attachments: Map<String, Bitmap> = emptyMap()) {
        dir.mkdirs()
        if (image != null && !imageFile(thread.id).exists()) writeImage(imageFile(thread.id), image)
        attachments.forEach { (id, bitmap) ->
            val file = attachmentFile(id)
            if (!file.exists()) writeImage(file, bitmap)
        }
        val others = list().filter { it.id != thread.id }
        val kept = (listOf(thread) + others).take(MAX_THREADS)
        others.drop(MAX_THREADS - 1).forEach(::deleteFiles)
        writeAtomically(ChatThread.encode(kept))
    }

    fun image(id: String): Bitmap? = decode(imageFile(id))

    fun attachment(id: String): Bitmap? = decode(attachmentFile(id))

    @Synchronized
    fun delete(id: String) {
        val all = list()
        all.firstOrNull { it.id == id }?.let(::deleteFiles) ?: imageFile(id).delete()
        writeAtomically(ChatThread.encode(all.filter { it.id != id }))
    }

    private fun deleteFiles(thread: ChatThread) {
        imageFile(thread.id).delete()
        thread.turns.flatMap { it.attachments + it.images.map { g -> g.id } }.forEach { attachmentFile(it).delete() }
    }

    private fun decode(file: File): Bitmap? =
        file.takeIf { it.exists() }?.let { runCatching { BitmapFactory.decodeFile(it.path) }.getOrNull() }

    @Synchronized
    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
    }

    private fun imageFile(id: String) = File(dir, "${safe(id)}.jpg")

    private fun attachmentFile(id: String) = File(dir, "att-${safe(id)}.jpg")

    private fun safe(id: String) = id.filter { it.isLetterOrDigit() || it == '-' }

    private fun writeImage(file: File, image: Bitmap) {
        val longest = max(image.width, image.height)
        val scaled = if (longest <= MAX_IMAGE_EDGE) image else {
            val f = MAX_IMAGE_EDGE.toFloat() / longest
            Bitmap.createScaledBitmap(image, (image.width * f).roundToInt(), (image.height * f).roundToInt(), true)
        }
        file.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 85, it) }
    }

    private fun writeAtomically(text: String) {
        dir.mkdirs()
        val tmp = File(dir, "threads.json.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(index)) {
            index.delete()
            tmp.renameTo(index)
        }
    }

    companion object {
        const val MAX_THREADS = 200
        const val MAX_IMAGE_EDGE = 1600
    }
}
