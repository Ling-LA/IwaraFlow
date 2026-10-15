package com.ling.iwaraflow

/** A finite queue for one viewing. Settings and player replacement cannot replay consumed comments. */
internal class DanmakuTimeline {
    data class Comment(val key: String, val text: String)
    data class Frame(val comment: Comment, val lane: Int, val fraction: Float)
    private data class Flight(val comment: Comment, val start: Long, val duration: Long)
    private var comments = emptyList<Comment>()
    private val shown = HashSet<String>()
    private val flights = LinkedHashMap<Int, Flight>()
    private var nextStart = 0L

    fun setComments(values: List<Comment>) {
        comments = values.distinctBy { it.key }.take(80)
    }

    fun reset() {
        shown.clear()
        rebase()
    }

    /** A seek or recreated player changes the clock, but does not un-consume old comments. */
    fun rebase() {
        flights.clear()
        nextStart = 0L
    }

    fun frames(clock: Long, lanes: Int, duration: Long): List<Frame> {
        flights.entries.removeAll { (lane, flight) -> lane >= lanes || clock - flight.start >= flight.duration }
        if (lanes <= 0 || duration <= 0) return emptyList()
        if (clock >= nextStart) {
            val lane = (0 until lanes).firstOrNull { it !in flights }
            val comment = comments.firstOrNull { it.key !in shown }
            if (lane != null && comment != null) {
                shown += comment.key
                flights[lane] = Flight(comment, clock, duration)
                nextStart = clock + (duration / lanes).coerceAtLeast(1L)
            }
        }
        return flights.map { (lane, flight) ->
            Frame(flight.comment, lane, ((clock - flight.start).coerceAtLeast(0) / flight.duration.toFloat()).coerceIn(0f, 1f))
        }
    }

    fun hasRemaining() = flights.isNotEmpty() || comments.any { it.key !in shown }

    fun clear() {
        comments = emptyList()
        reset()
    }
}
