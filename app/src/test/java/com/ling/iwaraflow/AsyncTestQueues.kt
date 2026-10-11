package com.ling.iwaraflow

/** Flush render jobs and the callbacks that can enqueue the next search page. */
internal fun awaitSearchResults(activity: SearchActivity) {
    val executor = activity.javaClass.getDeclaredField("resultWorker").apply { isAccessible = true }
        .get(activity) as java.util.concurrent.ExecutorService
    repeat(32) {
        executor.submit {}.get(5, java.util.concurrent.TimeUnit.SECONDS)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
    }
}
