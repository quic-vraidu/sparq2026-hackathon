package com.aster.ondevice.service.handlers

import com.aster.ondevice.data.model.Command
import com.aster.ondevice.data.model.CommandResult
import com.aster.ondevice.service.CommandHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

private const val TIMEOUT_MS = 30_000L
private const val MAX_OUTPUT = 1_048_576   // 1 MB

@Singleton
class ShellHandler @Inject constructor() : CommandHandler {
    override fun supportedActions() = listOf("execute_shell")

    override suspend fun handle(command: Command): CommandResult = withContext(Dispatchers.IO) {
        val cmd = command.params["command"]?.jsonPrimitive?.content
            ?: return@withContext CommandResult.err("Missing command")
        try {
            val process = ProcessBuilder("sh", "-c", cmd)
                .redirectErrorStream(true)
                .start()
            val output = StringBuilder()
            val reader = process.inputStream.bufferedReader()
            val deadline = System.currentTimeMillis() + TIMEOUT_MS
            while (System.currentTimeMillis() < deadline) {
                val line = reader.readLine() ?: break
                output.appendLine(line)
                if (output.length > MAX_OUTPUT) { output.append("\n[truncated]"); break }
            }
            val exited = process.waitFor()
            CommandResult.ok(mapOf("output" to output.toString().trimEnd(), "exitCode" to exited))
        } catch (e: Exception) { CommandResult.err("Shell error: ${e.message}") }
    }
}
