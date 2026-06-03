package com.aster.ondevice.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class Command(
    val type:   String,
    val id:     String,
    val action: String,
    val params: Map<String, JsonElement> = emptyMap()
)

data class CommandResult(
    val success: Boolean,
    val data:    Any?    = null,
    val error:   String? = null
) {
    companion object {
        fun ok(data: Any? = null)  = CommandResult(success = true,  data = data)
        fun err(msg: String)       = CommandResult(success = false, error = msg)
    }
}
