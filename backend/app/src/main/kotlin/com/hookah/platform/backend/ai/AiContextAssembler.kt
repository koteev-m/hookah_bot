package com.hookah.platform.backend.ai

class AiContextAssembler {
    fun buildPromotionDiagnosticsPrompt(result: PromotionDiagnosticsResult): String =
        buildString {
            append(
                "Проверь настройки акции и объясни владельцу, почему она может быть не видна " +
                    "гостям или не применяться.\n\n",
            )
            append("Диагностика:\n")
            result.summaryLines.forEach { line ->
                if (line.isBlank()) {
                    append('\n')
                } else {
                    append("- ").append(line).append('\n')
                }
            }
            append("\nВероятные причины:\n")
            result.likelyReasons.forEach { reason -> append("- ").append(reason).append('\n') }
            append(
                "\nПравила безопасности: только объяснение, без изменения настроек и без обещаний " +
                    "автоматического исправления.",
            )
        }

    fun buildDraftTextPrompt(
        type: AiDraftTextType,
        sanitizedBrief: String,
    ): String =
        buildString {
            when (type) {
                AiDraftTextType.PROMOTION_TEXT -> {
                    append("Подготовь черновик текста акции для владельца заведения.\n")
                    append("Нужно вернуть структуру: Название, Описание, Условия.\n")
                }
                AiDraftTextType.FEEDBACK_REPLY -> {
                    append("Подготовь вежливый черновик ответа на отзыв. Ничего не отправляй гостю.\n")
                    append(
                        "Ответ должен быть спокойным, без обещаний компенсации и без признания " +
                            "юридической ответственности.\n",
                    )
                }
                AiDraftTextType.BANNER_TEXT -> {
                    append("Подготовь короткий черновик текста для баннера или афиши.\n")
                    append("Нужно вернуть короткий заголовок и компактное описание.\n")
                }
            }
            append("\nВвод пользователя:\n")
            append(sanitizedBrief)
            append(
                "\n\nПравила безопасности: это только черновик, без сохранения в БД, без " +
                    "изменения настроек и без выполнения действий.",
            )
        }

    fun buildVenueSummaryPrompt(result: VenueSummaryResult): String =
        buildString {
            append("Подготовь короткую управленческую сводку для владельца или менеджера заведения.\n")
            append("Раздел: ").append(result.title).append("\n\n")
            append("Детерминированные данные:\n")
            result.summaryLines.forEach { line -> append("- ").append(line).append('\n') }
            append("\nЧто требует внимания:\n")
            result.attentionLines.forEach { line -> append("- ").append(line).append('\n') }
            if (result.sourceNotes.isNotEmpty()) {
                append("\nОграничения данных:\n")
                result.sourceNotes.forEach { line -> append("- ").append(line).append('\n') }
            }
            append(
                "\nПравила безопасности: это read-only сводка. Не предлагай, что настройки уже " +
                    "изменены, сообщения отправлены или суммы счёта поменялись.",
            )
        }
}
