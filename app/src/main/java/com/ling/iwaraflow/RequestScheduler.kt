package com.ling.iwaraflow

import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody
import okio.ForwardingSource
import okio.buffer
import java.io.InterruptedIOException
import java.util.concurrent.atomic.AtomicBoolean

/** Process-wide admission for synchronous AND asynchronous calls. Playback/auth reserve slots. */
object RequestScheduler : Interceptor {
    enum class Priority { PLAYBACK, INTERACTIVE, BACKGROUND }
    private class Waiter(val priority: Priority)
    private val lock = Object()
    private val waiting = ArrayList<Waiter>()
    private var running = 0
    private var background = 0
    internal const val MAX_RUNNING = 8
    internal const val MAX_BACKGROUND = 4

    internal fun acquire(priority: Priority, cancelled: () -> Boolean = { false }): AutoCloseable {
        val waiter = Waiter(priority)
        synchronized(lock) {
            waiting.add(waiter)
            try {
                while (true) {
                    if (cancelled()) throw InterruptedIOException("请求已取消")
                    val eligible = running < MAX_RUNNING &&
                        (priority != Priority.BACKGROUND || background < MAX_BACKGROUND) &&
                        (priority == Priority.PLAYBACK || running < MAX_RUNNING - 1)
                    val next = waiting.filter {
                        (it.priority != Priority.BACKGROUND || background < MAX_BACKGROUND) &&
                        (it.priority == Priority.PLAYBACK || running < MAX_RUNNING - 1)
                    }.minByOrNull { it.priority.ordinal }
                    if (eligible && next === waiter) break
                    lock.wait(100)
                }
                waiting.remove(waiter); running++
                if (priority == Priority.BACKGROUND) background++
            } catch (e: InterruptedException) {
                waiting.remove(waiter); lock.notifyAll(); Thread.currentThread().interrupt()
                throw InterruptedIOException("请求已取消")
            } catch (e: Exception) { waiting.remove(waiter); lock.notifyAll(); throw e }
        }
        val released = AtomicBoolean(false)
        return AutoCloseable {
            if (released.compareAndSet(false, true)) synchronized(lock) {
                running--; if (priority == Priority.BACKGROUND) background--; lock.notifyAll()
            }
        }
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val path = request.url.encodedPath
        val priority = request.tag(Priority::class.java) ?: when {
            path == "/user/token" || path == "/user/login" -> Priority.PLAYBACK
            path.startsWith("/search") || request.method != "GET" -> Priority.INTERACTIVE
            else -> Priority.BACKGROUND
        }
        val scope = RequestCancellation.current.get()
        scope?.register(chain.call())
        val lease = try { acquire(priority) { chain.call().isCanceled() } }
            catch (error: Exception) { scope?.unregister(chain.call()); throw error }
        try {
            val response = chain.proceed(request)
            val body = response.body ?: run { lease.close(); return response }
            val source = object : ForwardingSource(body.source()) {
                override fun close() { try { super.close() } finally { lease.close(); scope?.unregister(chain.call()) } }
            }.buffer()
            return response.newBuilder().body(object : ResponseBody() {
                override fun contentType() = body.contentType()
                override fun contentLength() = body.contentLength()
                override fun source() = source
            }).build()
        } catch (e: Throwable) { lease.close(); scope?.unregister(chain.call()); throw e }
    }
}
