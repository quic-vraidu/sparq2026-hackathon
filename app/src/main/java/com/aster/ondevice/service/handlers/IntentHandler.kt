package com.aster.ondevice.service.handlers

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import com.aster.ondevice.data.model.Command
import com.aster.ondevice.data.model.CommandResult
import com.aster.ondevice.service.CommandHandler
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class IntentHandler @Inject constructor(
    @ApplicationContext private val context: Context
) : CommandHandler {
    override fun supportedActions() = listOf("launch_intent")
    override suspend fun handle(command: Command): CommandResult {
        val pkg    = command.params["packageName"]?.jsonPrimitive?.content
        val action = command.params["action"]?.jsonPrimitive?.content
        val data   = command.params["data"]?.jsonPrimitive?.content
        return try {
            val intent = if (pkg != null) {
                context.packageManager.getLaunchIntentForPackage(pkg)
                    ?: return CommandResult.err("Package not found: $pkg")
            } else {
                Intent(action ?: Intent.ACTION_VIEW).apply {
                    if (data != null) setData(Uri.parse(data))
                }
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            CommandResult.ok(mapOf("launched" to (pkg ?: action ?: "intent")))
        } catch (e: Exception) { CommandResult.err("Launch failed: ${e.message}") }
    }
}
