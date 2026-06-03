package com.aster.ondevice.service.handlers

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import com.aster.ondevice.data.model.Command
import com.aster.ondevice.data.model.CommandResult
import com.aster.ondevice.service.CommandHandler
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ContactHandler @Inject constructor(
    @ApplicationContext private val context: Context
) : CommandHandler {
    override fun supportedActions() = listOf("search_contacts")
    override suspend fun handle(command: Command): CommandResult {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED)
            return CommandResult.err("READ_CONTACTS permission not granted")
        val query = command.params["name"]?.jsonPrimitive?.content
            ?: command.params["number"]?.jsonPrimitive?.content ?: ""
        val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
        val cursor: Cursor? = context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
            selection, arrayOf("%$query%"), null)
        val results = mutableListOf<Map<String, String>>()
        cursor?.use {
            while (it.moveToNext()) {
                results += mapOf(
                    "name"   to (it.getString(0) ?: ""),
                    "number" to (it.getString(1) ?: ""))
            }
        }
        return CommandResult.ok(results)
    }
}
