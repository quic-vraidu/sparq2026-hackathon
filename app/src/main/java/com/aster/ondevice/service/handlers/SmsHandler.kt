package com.aster.ondevice.service.handlers

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Telephony
import android.telephony.SmsManager
import com.aster.ondevice.data.model.Command
import com.aster.ondevice.data.model.CommandResult
import com.aster.ondevice.service.CommandHandler
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SmsHandler @Inject constructor(
    @ApplicationContext private val context: Context
) : CommandHandler {

    override fun supportedActions() = listOf("read_sms", "send_sms", "make_call", "make_call_with_voice", "delete_sms")

    override suspend fun handle(command: Command): CommandResult = when (command.action) {
        "read_sms"             -> readSms(command)
        "send_sms"             -> sendSms(command)
        "make_call"            -> makeCall(command)
        "make_call_with_voice" -> makeCallWithVoice(command)
        "delete_sms"           -> deleteSms(command)
        else                   -> CommandResult.err("Unknown action: ${command.action}")
    }

    private fun readSms(cmd: Command): CommandResult {
        val limit     = cmd.params["limit"]?.jsonPrimitive?.content?.toIntOrNull() ?: 20
        val sinceDate = cmd.params["sinceDate"]?.jsonPrimitive?.content?.toLongOrNull()

        val selection     = if (sinceDate != null) "${Telephony.Sms.DATE} >= ?" else null
        val selectionArgs = if (sinceDate != null) arrayOf(sinceDate.toString()) else null

        val cursor = context.contentResolver.query(
            Telephony.Sms.CONTENT_URI, null, selection, selectionArgs, "date DESC"
        ) ?: return CommandResult.err("Cannot read SMS")
        val messages = mutableListOf<Map<String, String>>()
        var count = 0
        cursor.use {
            while (it.moveToNext() && count < limit) {
                val id      = it.getLong(it.getColumnIndexOrThrow(Telephony.Sms._ID))
                val address = it.getString(it.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)) ?: ""
                val body    = it.getString(it.getColumnIndexOrThrow(Telephony.Sms.BODY)) ?: ""
                val date    = it.getLong(it.getColumnIndexOrThrow(Telephony.Sms.DATE))
                val typeInt = it.getInt(it.getColumnIndexOrThrow(Telephony.Sms.TYPE))
                val type    = if (typeInt == Telephony.Sms.MESSAGE_TYPE_INBOX) "received" else "sent"
                messages += mapOf("id" to id.toString(), "address" to address, "body" to body, "date" to date.toString(), "type" to type)
                count++
            }
        }
        return CommandResult.ok(messages)
    }

    /** Reads SMS for the given date range and returns them split into JSON-array chunks
     *  of [chunkSize] messages each.  Each element is a valid JSON array string. */
    fun readSmsInChunks(sinceDate: Long, toDate: Long? = null, chunkSize: Int = 20): List<String> {
        val conditions = mutableListOf("${Telephony.Sms.DATE} >= ?")
        val args       = mutableListOf(sinceDate.toString())
        if (toDate != null) {
            conditions += "${Telephony.Sms.DATE} < ?"
            args       += toDate.toString()
        }
        val cursor = context.contentResolver.query(
            Telephony.Sms.CONTENT_URI, null,
            conditions.joinToString(" AND "), args.toTypedArray(), "date DESC"
        ) ?: return emptyList()

        val chunks  = mutableListOf<String>()
        val buf     = StringBuilder()
        var inChunk = 0
        var total   = 0

        cursor.use {
            while (it.moveToNext() && total < 500) {
                val address = (it.getString(it.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)) ?: "")
                    .replace("\\", "\\\\").replace("\"", "'")
                val body    = (it.getString(it.getColumnIndexOrThrow(Telephony.Sms.BODY)) ?: "")
                    .replace("\\", "\\\\").replace("\"", "'").replace("\n", " ").replace("\r", "")
                val date    = it.getLong(it.getColumnIndexOrThrow(Telephony.Sms.DATE))

                if (inChunk == 0) buf.append("[") else buf.append(",")
                buf.append("{\"from\":\"").append(address)
                    .append("\",\"body\":\"").append(body)
                    .append("\",\"date\":").append(date).append("}")
                inChunk++
                total++

                if (inChunk >= chunkSize) {
                    buf.append("]")
                    chunks.add(buf.toString())
                    buf.clear()
                    inChunk = 0
                }
            }
        }
        if (inChunk > 0) {
            buf.append("]")
            chunks.add(buf.toString())
        }
        return chunks
    }

    fun readSmsDirect(sinceDate: Long, toDate: Long? = null, limit: Int = 300): String {
        val conditions = mutableListOf("${Telephony.Sms.DATE} >= ?")
        val args       = mutableListOf(sinceDate.toString())
        if (toDate != null) {
            conditions += "${Telephony.Sms.DATE} < ?"
            args       += toDate.toString()
        }
        val cursor = context.contentResolver.query(
            Telephony.Sms.CONTENT_URI, null,
            conditions.joinToString(" AND "), args.toTypedArray(), "date DESC"
        ) ?: return "[]"
        val sb = StringBuilder("[")
        var count = 0
        cursor.use {
            while (it.moveToNext() && count < limit) {
                val address = (it.getString(it.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)) ?: "")
                    .replace("\\", "\\\\").replace("\"", "'")
                val body    = (it.getString(it.getColumnIndexOrThrow(Telephony.Sms.BODY)) ?: "")
                    .replace("\\", "\\\\").replace("\"", "'").replace("\n", " ").replace("\r", "")
                val date    = it.getLong(it.getColumnIndexOrThrow(Telephony.Sms.DATE))
                if (count > 0) sb.append(",")
                sb.append("{\"from\":\"").append(address)
                    .append("\",\"body\":\"").append(body)
                    .append("\",\"date\":").append(date).append("}")
                count++
            }
        }
        sb.append("]")
        return sb.toString()
    }

    private fun sendSms(cmd: Command): CommandResult {
        val number  = cmd.params["number"]?.jsonPrimitive?.content  ?: return CommandResult.err("Missing number")
        val message = cmd.params["message"]?.jsonPrimitive?.content ?: return CommandResult.err("Missing message")
        return try {
            SmsManager.getDefault().sendTextMessage(number, null, message, null, null)
            CommandResult.ok(mapOf("sent" to true, "to" to number))
        } catch (e: Exception) {
            CommandResult.err("Send SMS failed: ${e.message}")
        }
    }

    private fun deleteSms(cmd: Command): CommandResult {
        val id = cmd.params["id"]?.jsonPrimitive?.content?.toLongOrNull()
            ?: return CommandResult.err("Missing id — use read_sms first to get SMS IDs")
        return try {
            val uri  = Uri.withAppendedPath(Telephony.Sms.CONTENT_URI, id.toString())
            val rows = context.contentResolver.delete(uri, null, null)
            if (rows > 0) CommandResult.ok(mapOf("deleted" to id))
            else CommandResult.err("SMS $id not found")
        } catch (e: Exception) {
            CommandResult.err("Delete SMS failed: ${e.message}")
        }
    }

    /**
     * Used by auto-delete: find SMS from [sender] received within the last [windowMs]
     * milliseconds and delete them all. Returns the number of messages deleted.
     */
    fun findAndDeleteBySender(sender: String, windowMs: Long = 5_000): Int {
        val since = System.currentTimeMillis() - windowMs
        val cursor = context.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(Telephony.Sms._ID),
            "${Telephony.Sms.ADDRESS} = ? AND ${Telephony.Sms.DATE} >= ?",
            arrayOf(sender, since.toString()),
            "${Telephony.Sms.DATE} DESC"
        ) ?: return 0
        var deleted = 0
        cursor.use {
            while (it.moveToNext()) {
                val id  = it.getLong(0)
                val uri = Uri.withAppendedPath(Telephony.Sms.CONTENT_URI, id.toString())
                deleted += context.contentResolver.delete(uri, null, null)
            }
        }
        return deleted
    }

    private fun makeCall(cmd: Command): CommandResult {
        val number = cmd.params["number"]?.jsonPrimitive?.content ?: return CommandResult.err("Missing number")
        val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$number")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            CommandResult.ok(mapOf("calling" to number))
        } catch (e: Exception) {
            CommandResult.err("Call failed: ${e.message}")
        }
    }

    private fun makeCallWithVoice(cmd: Command): CommandResult {
        // Call + TTS via accessibility — simplified for Phase 1
        val number = cmd.params["number"]?.jsonPrimitive?.content ?: return CommandResult.err("Missing number")
        val text   = cmd.params["text"]?.jsonPrimitive?.content   ?: ""
        return try {
            val callIntent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$number")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(callIntent)
            // TTS will be triggered by the service after call connects (simplified)
            CommandResult.ok(mapOf("calling" to number, "willSpeak" to text))
        } catch (e: Exception) {
            CommandResult.err("Call failed: ${e.message}")
        }
    }
}
