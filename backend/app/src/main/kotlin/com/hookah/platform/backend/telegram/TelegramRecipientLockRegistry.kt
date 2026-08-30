package com.hookah.platform.backend.telegram

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class TelegramRecipientLockRegistry {
    private val authorityLock = Mutex()

    suspend fun <T> withRecipientLock(
        chatId: Long,
        block: suspend () -> T,
    ): T {
        require(chatId != 0L) { "chatId must be non-zero" }
        return authorityLock.withLock { block() }
    }
}
