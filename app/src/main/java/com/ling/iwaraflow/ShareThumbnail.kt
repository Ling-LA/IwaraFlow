package com.ling.iwaraflow

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.core.content.FileProvider
import androidx.core.graphics.drawable.toBitmap
import coil.ImageLoader
import coil.request.ImageRequest
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

/** Bounded public thumbnails, never an original video or an authenticated API response. */
object ShareThumbnail {
    private val io = Executors.newSingleThreadExecutor()
    private val main by lazy { Handler(Looper.getMainLooper()) }
    @Volatile private var shared: ImageLoader? = null

    private fun load(context: Context, url: String, callback: (Drawable?) -> Unit) {
        if (url.isBlank()) { callback(null); return }
        val app = context.applicationContext
        val loader = shared ?: synchronized(this) {
            shared ?: AvatarImages.createLoader(app, File(app.cacheDir, "share_image_cache")).also { shared = it }
        }
        loader.enqueue(ImageRequest.Builder(app).data(url).size(1024).allowHardware(false)
            .target(onError = { callback(null) }, onSuccess = { callback(it) }).build())
    }

    fun prepare(context: Context, url: String, callback: (Uri?) -> Unit) {
        load(context, url) { drawable ->
            if (drawable == null) callback(null)
            else save(context, callback) { drawable.toBitmap() }
        }
    }

    /** Avatar failure still produces a useful author card with the original biography and link. */
    fun prepareAuthor(context: Context, author: IwaraAuthor, callback: (Uri?) -> Unit) {
        val app = context.applicationContext
        load(app, author.avatarUrl) { avatar ->
            save(app, callback, recycle = true) { AuthorShareCard.render(author, avatar?.toBitmap()) }
        }
    }

    private fun save(context: Context, callback: (Uri?) -> Unit, recycle: Boolean = false, bitmap: () -> Bitmap) {
        val app = context.applicationContext
        io.execute {
            val uri = runCatching {
                val directory = File(app.cacheDir, "share_thumbnails").apply { mkdirs() }
                // Leave recent grants readable when returning to the receiving app.
                directory.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 86_400_000L }
                    ?.forEach { it.delete() }
                directory.listFiles()?.sortedByDescending(File::lastModified)?.drop(31)?.forEach { it.delete() }
                val file = File(directory, "${UUID.randomUUID()}.jpg")
                val image = bitmap()
                try { file.outputStream().use { check(image.compress(Bitmap.CompressFormat.JPEG, 88, it)) } }
                finally { if (recycle) image.recycle() }
                FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", file)
            }.getOrNull()
            main.post { callback(uri) }
        }
    }
}
