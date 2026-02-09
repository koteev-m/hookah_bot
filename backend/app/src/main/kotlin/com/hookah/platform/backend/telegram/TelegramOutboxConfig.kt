package com.hookah.platform.backend.telegram

data class TelegramOutboxConfig(
    val pollIntervalMillis: Long = 500,
    val batchSize: Int = 25,
    val visibilityTimeoutSeconds: Long = 30,
    val maxAttempts: Int = 10,
    val maxConcurrency: Int = 4,
    val perChatMinIntervalMillis: Long = 1000,
    val minBackoffSeconds: Long = 1,
    val maxBackoffSeconds: Long = 60,
) {
    fun normalized(): TelegramOutboxConfig =
        copy(
            pollIntervalMillis = pollIntervalMillis.coerceAtLeast(100),
            batchSize = batchSize.coerceIn(1, 200),
            visibilityTimeoutSeconds = visibilityTimeoutSeconds.coerceIn(5, 300),
            maxAttempts = maxAttempts.coerceIn(1, 100),
            maxConcurrency = maxConcurrency.coerceIn(1, 20),
            perChatMinIntervalMillis = perChatMinIntervalMillis.coerceAtLeast(200),
            minBackoffSeconds = minBackoffSeconds.coerceAtLeast(1),
            maxBackoffSeconds = maxBackoffSeconds.coerceAtLeast(minBackoffSeconds),
        )
}
