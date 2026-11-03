package dev.lenshift.app.ai.transport

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import okhttp3.Response
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Minimal Server-Sent Events reader.
 *
 * Handles the parts of the SSE spec that model APIs actually use:
 *  - `data: <payload>` lines, possibly repeated before a blank line
 *  - `event: <name>` lines (kept, some providers use them)
 *  - `:comment` heartbeat lines (skipped)
 *  - `data: [DONE]` sentinel
 *
 * Line endings may be CRLF or LF. UTF-8 is decoded explicitly so multi-byte characters
 * split across buffer boundaries are not corrupted.
 */
object SseParser {

    data class Event(val name: String?, val data: String)

    fun parse(response: Response): Flow<Event> = flow {
        val source = response.body?.source() ?: return@flow
        val reader = BufferedReader(InputStreamReader(source.inputStream(), Charsets.UTF_8))

        var eventName: String? = null
        val dataBuffer = StringBuilder()

        reader.use { r ->
            while (true) {
                val line = r.readLine() ?: break

                when {
                    line.isEmpty() -> {
                        // Dispatch boundary.
                        if (dataBuffer.isNotEmpty()) {
                            val payload = dataBuffer.toString()
                            dataBuffer.setLength(0)
                            val name = eventName
                            eventName = null
                            if (payload.trim() != DONE) {
                                emit(Event(name, payload))
                            } else {
                                return@flow
                            }
                        } else {
                            eventName = null
                        }
                    }

                    line.startsWith(":") -> Unit // heartbeat / comment

                    line.startsWith("data:") -> {
                        val chunk = line.removePrefix("data:")
                        if (dataBuffer.isNotEmpty()) dataBuffer.append('\n')
                        dataBuffer.append(chunk.removePrefix(" "))
                    }

                    line.startsWith("event:") -> {
                        eventName = line.removePrefix("event:").trim()
                    }

                    // id:, retry: and unknown fields are not needed for stateless streams.
                    else -> Unit
                }
            }

            // Flush a trailing event that lacked a final blank line.
            if (dataBuffer.isNotEmpty()) {
                val payload = dataBuffer.toString()
                if (payload.trim() != DONE) emit(Event(eventName, payload))
            }
        }
    }

    private const val DONE = "[DONE]"
}

/**
 * Newline-delimited JSON reader, used by Ollama and a few OpenAI-compatible servers that
 * stream bare JSON objects instead of SSE frames.
 */
object NdjsonParser {

    fun parse(response: Response): Flow<String> = flow {
        val source = response.body?.source() ?: return@flow
        val reader = BufferedReader(InputStreamReader(source.inputStream(), Charsets.UTF_8))
        reader.use { r ->
            while (true) {
                val line = r.readLine() ?: break
                val trimmed = line.trim()
                if (trimmed.isNotEmpty()) emit(trimmed)
            }
        }
    }
}
