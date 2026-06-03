package com.aster.ondevice.service.handlers

import android.content.Context
import android.content.pm.PackageManager
import com.aster.ondevice.data.model.Command
import com.aster.ondevice.data.model.CommandResult
import com.aster.ondevice.service.CommandHandler
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PackageHandler @Inject constructor(
    @ApplicationContext private val context: Context
) : CommandHandler {
    override fun supportedActions() = listOf("list_packages")
    override suspend fun handle(command: Command): CommandResult {
        val inclSystem = command.params["includeSystem"]?.jsonPrimitive?.booleanOrNull ?: false
        val pm = context.packageManager
        val flags = if (inclSystem) 0 else PackageManager.GET_META_DATA
        val packages = pm.getInstalledPackages(0)
            .filter { inclSystem || (it.applicationInfo?.flags?.and(android.content.pm.ApplicationInfo.FLAG_SYSTEM) ?: android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0 }
            .map { mapOf("pkg" to it.packageName, "name" to (it.applicationInfo?.let { ai -> pm.getApplicationLabel(ai).toString() } ?: it.packageName), "version" to it.versionName) }
        return CommandResult.ok(packages)
    }
}
