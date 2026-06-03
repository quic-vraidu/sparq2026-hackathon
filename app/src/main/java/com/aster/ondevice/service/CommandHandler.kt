package com.aster.ondevice.service

import com.aster.ondevice.data.model.Command
import com.aster.ondevice.data.model.CommandResult

interface CommandHandler {
    fun supportedActions(): List<String>
    suspend fun handle(command: Command): CommandResult
}
