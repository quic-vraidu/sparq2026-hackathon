package com.aster.ondevice.service.handlers

import android.content.Context
import android.os.StatFs
import com.aster.ondevice.data.model.Command
import com.aster.ondevice.data.model.CommandResult
import com.aster.ondevice.service.CommandHandler
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StorageHandler @Inject constructor(
    @ApplicationContext private val context: Context
) : CommandHandler {
    override fun supportedActions() = listOf("analyze_storage", "find_large_files", "search_media", "index_media_metadata")

    override suspend fun handle(command: Command): CommandResult = when (command.action) {
        "analyze_storage"     -> analyzeStorage(command)
        "find_large_files"    -> findLargeFiles(command)
        "search_media"        -> searchMedia(command)
        "index_media_metadata"-> CommandResult.ok(mapOf("note" to "Index media via search_media with EXIF support in full implementation"))
        else -> CommandResult.err("Unknown: ${command.action}")
    }

    private fun analyzeStorage(cmd: Command): CommandResult {
        val path = cmd.params["path"]?.jsonPrimitive?.content ?: "/sdcard"
        val stat = StatFs(path)
        return CommandResult.ok(mapOf(
            "path"          to path,
            "totalGB"       to stat.totalBytes / 1_073_741_824,
            "freeGB"        to stat.availableBytes / 1_073_741_824,
            "usedGB"        to (stat.totalBytes - stat.availableBytes) / 1_073_741_824
        ))
    }

    private fun findLargeFiles(cmd: Command): CommandResult {
        val minMb  = cmd.params["minSizeMB"]?.jsonPrimitive?.intOrNull ?: 50
        val path   = cmd.params["path"]?.jsonPrimitive?.content ?: "/sdcard"
        val minBytes = minMb.toLong() * 1_048_576
        val results = mutableListOf<Map<String, Any>>()
        File(path).walkTopDown().filter { it.isFile && it.length() >= minBytes }.take(50).forEach {
            results += mapOf("path" to it.absolutePath, "sizeMB" to it.length() / 1_048_576)
        }
        return CommandResult.ok(results)
    }

    private fun searchMedia(cmd: Command): CommandResult {
        val query = cmd.params["query"]?.jsonPrimitive?.content ?: ""
        val path  = cmd.params["path"]?.jsonPrimitive?.content ?: "/sdcard/DCIM"
        val extensions = setOf("jpg", "jpeg", "png", "mp4", "mov", "heic")
        val files = File(path).walkTopDown()
            .filter { it.isFile && it.extension.lowercase() in extensions }
            .take(50)
            .map { mapOf("path" to it.absolutePath, "size" to it.length(), "modified" to it.lastModified()) }
            .toList()
        return CommandResult.ok(mapOf("query" to query, "count" to files.size, "files" to files))
    }
}
