package dev.lenshift.app.ai.transport

import okhttp3.Call
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks in-flight HTTP calls so a Stop can abort the socket, not just the coroutine.
 *
 * OkHttp's blocking `Call.execute()` ignores coroutine cancellation. Cancelling the
 * coroutine therefore left the request running: the provider kept generating (and
 * billing) and the socket stayed open until the call timeout. [cancelAll] closes every
 * registered call, which makes the blocked `execute()`/read throw immediately.
 *
 * Lenshift has at most one live exchange, so cancelling all in-flight calls on Stop is
 * exactly the intended scope.
 */
object CallRegistry {

    private val live = ConcurrentHashMap.newKeySet<Call>()
    private val background = ConcurrentHashMap<Call, String>()

    /**
     * A request tag for calls that belong to a background job rather than the chat
     * exchange (a picture being made): Stop leaves them running, and [cancelBackground]
     * ends them by [key].
     */
    class Background(val key: String)

    /** Registers [call]; close the returned handle when the call finishes. */
    fun register(call: Call): Closeable {
        call.request().tag(Background::class.java)?.let { tag ->
            background[call] = tag.key
            return Closeable { background.remove(call) }
        }
        live.add(call)
        return Closeable { live.remove(call) }
    }

    /** Cancels every in-flight chat call (not background jobs). Safe to call when none are running. */
    fun cancelAll() {
        live.forEach { runCatching { it.cancel() } }
        live.clear()
    }

    /** Cancels background calls tagged [key], or all of them when [key] is null. */
    fun cancelBackground(key: String? = null) {
        background.entries.filter { key == null || it.value == key }.forEach { (call, _) ->
            runCatching { call.cancel() }
            background.remove(call)
        }
    }

    /** Number of calls currently in flight, for tests. */
    internal fun liveCount(): Int = live.size
}
