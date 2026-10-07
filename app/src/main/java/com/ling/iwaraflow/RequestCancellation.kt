package com.ling.iwaraflow

import okhttp3.Call

/** Cancellation scope for blocking translator calls; unrelated player/comment requests stay alive. */
internal class RequestCancellation {
    @Volatile var cancelled = false
        private set
    private val calls = java.util.concurrent.ConcurrentHashMap.newKeySet<Call>()
    fun cancel() { cancelled = true; calls.forEach(Call::cancel) }
    fun register(call: Call) { calls.add(call); if (cancelled) call.cancel() }
    fun unregister(call: Call) { calls.remove(call) }
    fun <T> run(block: () -> T): T {
        val previous = current.get(); current.set(this)
        try { return block() } finally { current.set(previous) }
    }
    companion object { val current = ThreadLocal<RequestCancellation>() }
}
