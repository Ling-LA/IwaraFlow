package com.ling.iwaraflow

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.View
import android.widget.TextView
import android.widget.Toast
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeoutException
import javax.net.ssl.SSLException

/** Add optional links only to explicit connection failures; never open them automatically. */
class StartupNetworkHelp {
    fun bind(view: TextView, message: String, failure: Throwable?) {
        // A reused status view must never retain spans or touch handling from an earlier failure.
        view.movementMethod = null
        view.linksClickable = false
        view.isClickable = false
        view.isLongClickable = false
        view.text = message
        view.visibility = if (message.isBlank()) View.GONE else View.VISIBLE
        if (message.isBlank() || !isConnectionFailure(message, failure)) return

        val text = SpannableStringBuilder(message)
        AppLinks.NETWORK_PROMOTIONS.forEachIndexed { index, url ->
            text.append("\n\n")
            val start = text.length
            text.append(UiText.text(R.string.ui_59a72b244dc7, "网络服务推广链接 %1\$s（可选）", index + 1))
            text.setSpan(object : ClickableSpan() {
                override fun onClick(widget: View) {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        .addCategory(Intent.CATEGORY_BROWSABLE)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try {
                        widget.context.startActivity(intent)
                    } catch (_: ActivityNotFoundException) {
                        Toast.makeText(widget.context, UiText.text(R.string.ui_89b1a5b2bb8e, "没有可打开链接的浏览器"), Toast.LENGTH_SHORT).show()
                    } catch (_: SecurityException) {
                        Toast.makeText(widget.context, UiText.text(R.string.ui_917512cfe37f, "无法打开链接，请检查浏览器设置"), Toast.LENGTH_SHORT).show()
                    }
                }
            }, start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        view.text = text
        view.linksClickable = true
        view.movementMethod = LinkMovementMethod.getInstance()
    }

    companion object {
        private val httpStatus = Regex("\\bHTTP(?:/\\d(?:\\.\\d)?)?\\s*[:=]?\\s*[45]\\d{2}\\b", RegexOption.IGNORE_CASE)
        private val cancelled = Regex("\\b(cancelled|canceled|cancellation|interrupted)\\b|已取消|取消请求", RegexOption.IGNORE_CASE)

        private fun isConnectionFailure(message: String, failure: Throwable?): Boolean {
            val causes = ArrayList<Throwable>()
            val visited = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
            var next = failure
            while (next != null && causes.size < 32 && visited.add(next)) {
                causes += next
                next = next.cause
            }
            val messages = listOf(message) + causes.mapNotNull { it.message }
            // HTTP replies (including a server body saying "timeout") and cancellation are not
            // evidence that the user's connection is blocked. Check these before network words.
            if (causes.any { it is CancellationException || it is InterruptedException ||
                    IwaraApi.isHttpFailure(it) || it is PlaylistHttpException } ||
                messages.any { httpStatus.containsMatchIn(it) || cancelled.containsMatchIn(it) }) return false

            return causes.any { it is SocketTimeoutException || it is TimeoutException || it is UnknownHostException ||
                it is SocketException || it is SSLException } ||
                messages.any { NetworkProxy.explain(it) != null }
        }
    }
}
