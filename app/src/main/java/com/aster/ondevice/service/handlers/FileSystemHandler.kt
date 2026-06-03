package com.aster.ondevice.service.handlers

import android.util.Base64
import android.util.Log
import com.aster.ondevice.data.model.Command
import com.aster.ondevice.data.model.CommandResult
import com.aster.ondevice.service.CommandHandler
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG     = "FileSystemHandler"
private const val MAX_MB  = 10

@Singleton
class FileSystemHandler @Inject constructor() : CommandHandler {
    override fun supportedActions() = listOf("list_files", "read_file", "write_file", "delete_file")

    override suspend fun handle(command: Command): CommandResult = when (command.action) {
        "list_files"  -> listFiles(command)
        "read_file"   -> readFile(command)
        "write_file"  -> writeFile(command)
        "delete_file" -> deleteFile(command)
        else          -> CommandResult.err("Unknown: ${command.action}")
    }

    private fun listFiles(cmd: Command): CommandResult {
        val path = cmd.params["path"]?.jsonPrimitive?.content ?: return CommandResult.err("Missing path")
        val dir  = File(path)
        if (!dir.exists() || !dir.isDirectory) return CommandResult.err("Not a directory: $path")
        val files = dir.listFiles()?.map { f ->
            mapOf("name" to f.name, "path" to f.absolutePath, "isDir" to f.isDirectory, "size" to f.length())
        } ?: emptyList()
        return CommandResult.ok(files)
    }

    private fun readFile(cmd: Command): CommandResult {
        val path = cmd.params["path"]?.jsonPrimitive?.content ?: return CommandResult.err("Missing path")
        val file = File(path)
        if (!file.exists()) return CommandResult.err("File not found: $path")
        if (file.length() > MAX_MB * 1_048_576) return CommandResult.err("File too large (>${MAX_MB}MB)")
        return try {
            val bytes = file.readBytes()
            if (bytes.isValidUtf8()) CommandResult.ok(mapOf("content" to String(bytes)))
            else CommandResult.ok(mapOf("base64" to Base64.encodeToString(bytes, Base64.NO_WRAP), "binary" to true))
        } catch (e: Exception) {
            CommandResult.err("Read failed: ${e.message}")
        }
    }

    private fun writeFile(cmd: Command): CommandResult {
        val path    = cmd.params["path"]?.jsonPrimitive?.content    ?: return CommandResult.err("Missing path")
        val content = cmd.params["content"]?.jsonPrimitive?.content ?: return CommandResult.err("Missing content")
        return try {
            val file = File(path)
            file.parentFile?.mkdirs()
            file.writeText(content)
            CommandResult.ok(mapOf("written" to true, "path" to path, "bytes" to content.length))
        } catch (e: Exception) { CommandResult.err("Write failed: ${e.message}") }
    }

    private fun deleteFile(cmd: Command): CommandResult {
        val path = cmd.params["path"]?.jsonPrimitive?.content ?: return CommandResult.err("Missing path")
        val file = File(path)
        if (!file.exists()) return CommandResult.err("Not found: $path")
        return if (file.deleteRecursively()) CommandResult.ok(mapOf("deleted" to true))
        else CommandResult.err("Delete failed")
    }

    private fun ByteArray.isValidUtf8(): Boolean = try {
        String(this, Charsets.UTF_8); true
    } catch (_: Exception) { false }
}
