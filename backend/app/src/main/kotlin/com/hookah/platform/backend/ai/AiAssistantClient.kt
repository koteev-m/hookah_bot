package com.hookah.platform.backend.ai

interface AiAssistantClient {
    suspend fun complete(request: AiAssistantCompletionRequest): AiAssistantCompletionResponse
}

data class AiAssistantCompletionRequest(
    val systemPromptVersion: String,
    val toolName: String,
    val sanitizedPrompt: String,
    val maxOutputTokens: Int,
)

data class AiAssistantCompletionResponse(
    val text: String,
)

class FakeAiAssistantClient : AiAssistantClient {
    override suspend fun complete(request: AiAssistantCompletionRequest): AiAssistantCompletionResponse =
        AiAssistantCompletionResponse(
            text =
                when (request.toolName) {
                    AiAssistantService.TOOL_DRAFT_PROMOTION_TEXT -> fakePromotionTextDraft(request.sanitizedPrompt)
                    AiAssistantService.TOOL_DRAFT_FEEDBACK_REPLY -> fakeFeedbackReplyDraft(request.sanitizedPrompt)
                    AiAssistantService.TOOL_DRAFT_BANNER_TEXT -> fakeBannerTextDraft(request.sanitizedPrompt)
                    AiAssistantService.TOOL_SUMMARY_PROMOTION,
                    AiAssistantService.TOOL_SUMMARY_FEEDBACK,
                    AiAssistantService.TOOL_SUMMARY_LOYALTY,
                    AiAssistantService.TOOL_SUMMARY_ORDERS,
                    -> fakeVenueSummary(request.sanitizedPrompt)
                    else -> fakePromotionDiagnostics(request.sanitizedPrompt)
                },
        )

    private fun fakePromotionDiagnostics(prompt: String): String =
        buildString {
            append("🤖 Диагностика акции\n\n")
            append(prompt.trim())
            append("\n\n")
            append(
                "Я ничего не изменил в настройках. Если нужно исправить проблему, откройте " +
                    "соответствующий экран акции и подтвердите действие вручную.",
            )
        }

    private fun fakePromotionTextDraft(prompt: String): String =
        buildString {
            append("Черновик. Проверьте текст перед публикацией.\n\n")
            append("Название: Акция для гостей\n")
            append("Описание: ").append(shortInput(prompt)).append('\n')
            append(
                "Условия: Действует по правилам заведения. Перед публикацией уточните дни, время и состав предложения.",
            )
        }

    private fun fakeFeedbackReplyDraft(prompt: String): String =
        buildString {
            append("Черновик. Проверьте текст перед отправкой.\n\n")
            append("Здравствуйте! Спасибо за обратную связь. Нам важно разобраться в ситуации и улучшить сервис. ")
            append("Передадим комментарий команде и учтём его в работе.")
        }

    private fun fakeBannerTextDraft(prompt: String): String =
        buildString {
            append("Черновик. Проверьте текст перед публикацией.\n\n")
            append("Заголовок: Афиша недели\n")
            append("Описание: ").append(shortInput(prompt))
        }

    private fun fakeVenueSummary(prompt: String): String {
        val summary = parseVenueSummaryPrompt(prompt)
        return buildString {
            append(summary.title.ifBlank { "🤖 Сводка" }).append("\n\n")
            append("Главное:\n")
            summary.summaryLines
                .ifEmpty { listOf("Данных для сводки пока нет.") }
                .take(SUMMARY_MAX_LINES)
                .forEach { append("• ").append(it).append('\n') }
            append("\nТребует внимания:\n")
            summary.attentionLines
                .ifEmpty { listOf("Критичных блокеров не найдено.") }
                .take(SUMMARY_MAX_LINES)
                .forEach { append("• ").append(it).append('\n') }
            append("\nЧто можно улучшить:\n")
            append("• ").append(summary.recommendation()).append('\n')
            if (summary.sourceNotes.isNotEmpty()) {
                append("\nДанные:\n")
                summary.sourceNotes
                    .take(SUMMARY_MAX_NOTES)
                    .forEach { append("• ").append(it).append('\n') }
            }
        }.trimEnd()
    }

    private fun shortInput(prompt: String): String {
        val marker = "Ввод пользователя:"
        val userInput = prompt.substringAfter(marker, prompt).substringBefore("Правила безопасности:").trim()
        return userInput.ifBlank { "Опишите предложение коротко и понятно для гостей." }.take(240)
    }

    private fun parseVenueSummaryPrompt(prompt: String): ParsedVenueSummary {
        val title =
            prompt
                .lineSequence()
                .firstOrNull { it.startsWith("Раздел:") }
                ?.removePrefix("Раздел:")
                ?.trim()
                .orEmpty()
        return ParsedVenueSummary(
            title = title,
            summaryLines = prompt.extractListSection("Детерминированные данные:", "Что требует внимания:"),
            attentionLines =
                prompt.extractListSection(
                    "Что требует внимания:",
                    "Ограничения данных:",
                    "Правила безопасности:",
                ),
            sourceNotes = prompt.extractListSection("Ограничения данных:", "Правила безопасности:"),
        )
    }

    private fun String.extractListSection(
        startMarker: String,
        vararg endMarkers: String,
    ): List<String> {
        val afterStart = substringAfter(startMarker, missingDelimiterValue = "")
        if (afterStart.isBlank()) return emptyList()
        val section =
            endMarkers.fold(afterStart) { current, marker ->
                val index = current.indexOf(marker)
                if (index >= 0) current.substring(0, index) else current
            }
        return section
            .lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("-") }
            .map { it.removePrefix("-").trim().removePrefix("-").trim() }
            .filter { it.isNotBlank() }
            .toList()
    }

    private fun ParsedVenueSummary.recommendation(): String =
        when {
            title.contains("продвиж", ignoreCase = true) ->
                "Проверьте черновики, заявки на размещения и ссылку на отзывы, если они отмечены выше."
            title.contains("отзыв", ignoreCase = true) ->
                "Сначала ответьте на отзывы с низкой оценкой или пометкой «требует ответа»."
            title.contains("лояль", ignoreCase = true) ->
                "Проверьте статус программы, условия накопления и список доступных бонусов."
            title.contains("заказ", ignoreCase = true) ->
                "Посмотрите активные заказы и вызовы персонала, если они есть в сводке."
            else ->
                "Проверьте пункты, отмеченные в блоке «Требует внимания»."
        }

    private data class ParsedVenueSummary(
        val title: String,
        val summaryLines: List<String>,
        val attentionLines: List<String>,
        val sourceNotes: List<String>,
    )

    private companion object {
        const val SUMMARY_MAX_LINES = 8
        const val SUMMARY_MAX_NOTES = 3
    }
}
