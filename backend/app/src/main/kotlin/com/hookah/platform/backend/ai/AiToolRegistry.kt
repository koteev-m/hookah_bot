package com.hookah.platform.backend.ai

class AiToolRegistry(
    private val promotionDiagnosticsTool: PromotionDiagnosticsTool,
    private val venueSummaryTool: VenueSummaryTool? = null,
) {
    suspend fun runPromotionDiagnostics(request: PromotionDiagnosticsRequest): PromotionDiagnosticsResult =
        promotionDiagnosticsTool.run(request)

    suspend fun runVenueSummary(request: VenueSummaryRequest): VenueSummaryResult =
        checkNotNull(venueSummaryTool) { "venue summary tool is not configured" }.run(request)
}
