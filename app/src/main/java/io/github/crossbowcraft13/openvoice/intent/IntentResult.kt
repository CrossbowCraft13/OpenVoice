package io.github.crossbowcraft13.openvoice.intent

data class IntentResult(
    val intent: String,
    val confidence: Float,
    val entities: Map<String, String> = emptyMap(),
    val requiresClarification: Boolean = false,
    val clarificationPrompt: String? = null
)
