package com.aster.ondevice.service.handlers

import android.app.Activity
import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.webkit.WebView
import android.widget.FrameLayout
import com.aster.ondevice.data.model.Command
import com.aster.ondevice.data.model.CommandResult
import com.aster.ondevice.service.CommandHandler
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OverlayHandler @Inject constructor(
    @ApplicationContext private val context: Context
) : CommandHandler {
    override fun supportedActions() = listOf("show_overlay", "show_toast")

    override suspend fun handle(command: Command): CommandResult = when (command.action) {
        "show_toast" -> {
            val msg = command.params["message"]?.jsonPrimitive?.content ?: return CommandResult.err("Missing message")
            Handler(Looper.getMainLooper()).post {
                android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
            }
            CommandResult.ok(mapOf("shown" to true))
        }
        "show_overlay" -> {
            val html    = command.params["html"]?.jsonPrimitive?.content
            val url     = command.params["url"]?.jsonPrimitive?.content
            val timeout = command.params["timeout"]?.jsonPrimitive?.intOrNull
            Handler(Looper.getMainLooper()).post {
                val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                val params = WindowManager.LayoutParams(
                    800, 600, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    PixelFormat.TRANSLUCENT).apply { gravity = Gravity.CENTER }
                val webView = WebView(context)
                if (html != null) webView.loadData(html, "text/html", "UTF-8")
                else if (url != null) webView.loadUrl(url)
                wm.addView(webView, params)
                if (timeout != null) {
                    Handler(Looper.getMainLooper()).postDelayed({ runCatching { wm.removeView(webView) } }, timeout * 1000L)
                }
            }
            CommandResult.ok(mapOf("shown" to true))
        }
        else -> CommandResult.err("Unknown: ${command.action}")
    }
}
