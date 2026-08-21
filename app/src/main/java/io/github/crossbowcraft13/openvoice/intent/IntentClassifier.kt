package io.github.crossbowcraft13.openvoice.intent

import io.github.crossbowcraft13.openvoice.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class IntentClassifier {

    private data class Rule(val pattern: Regex, val intent: String, val entityKeys: List<String>)

    private val rules = listOf(
        Rule(Regex("open\\s+(\\w+.*)", RegexOption.IGNORE_CASE), "LAUNCH_APP", listOf("app")),
        Rule(Regex("launch\\s+(\\w+.*)", RegexOption.IGNORE_CASE), "LAUNCH_APP", listOf("app")),
        Rule(Regex("start\\s+(\\w+.*)", RegexOption.IGNORE_CASE), "LAUNCH_APP", listOf("app")),
        Rule(Regex("send\\s+(?:a\\s+)?(?:text|message|sms)\\s+to\\s+(\\S+)(?:\\s+(?:saying|that|text|message)?\\s*(.*))?", RegexOption.IGNORE_CASE), "SEND_SMS", listOf("contact", "message")),
        Rule(Regex("text\\s+(\\S+)(?:\\s+(?:that|and\\ssay)?\\s*(.*))?", RegexOption.IGNORE_CASE), "SEND_SMS", listOf("contact", "message")),
        Rule(Regex("call\\s+(\\S+)", RegexOption.IGNORE_CASE), "MAKE_CALL", listOf("contact")),
        Rule(Regex("dial\\s+(\\S+)", RegexOption.IGNORE_CASE), "MAKE_CALL", listOf("contact")),
        Rule(Regex("set\\s+(?:a\\s+)?timer\\s+(?:for\\s+)?(.+)", RegexOption.IGNORE_CASE), "SET_TIMER", listOf("duration")),
        Rule(Regex("(?:start|begin)\\s+(?:a\\s+)?timer\\s+(?:for\\s+)?(.+)", RegexOption.IGNORE_CASE), "SET_TIMER", listOf("duration")),
        Rule(Regex("set\\s+(?:an\\s+)?alarm\\s+(?:for\\s+)?(.+)", RegexOption.IGNORE_CASE), "SET_ALARM", listOf("time")),
        Rule(Regex("wake\\s+me\\s+up\\s+(?:at\\s+)?(.+)", RegexOption.IGNORE_CASE), "SET_ALARM", listOf("time")),
        Rule(Regex("open\\s+settings(?:\\s+(\\w+))?", RegexOption.IGNORE_CASE), "OPEN_SETTINGS", listOf("target")),
        Rule(Regex("(?:set|change|adjust)\\s+volume\\s+(?:to\\s+)?(.+)", RegexOption.IGNORE_CASE), "ADJUST_VOLUME", listOf("level")),
        Rule(Regex("volume\\s+(up|down|mute|unmute)", RegexOption.IGNORE_CASE), "ADJUST_VOLUME", listOf("direction")),
        Rule(Regex("turn\\s+(?:the\\s+)?volume\\s+(up|down)", RegexOption.IGNORE_CASE), "ADJUST_VOLUME", listOf("direction")),
        Rule(Regex("(?:what\\s+do\\s+you\\s+see|what'?s\\s+on\\s+my\\s+screen)", RegexOption.IGNORE_CASE), "READ_SCREEN", emptyList()),
        Rule(Regex("(?:take\\s+a\\s+)?screenshot", RegexOption.IGNORE_CASE), "SCREENSHOT", emptyList()),
        Rule(Regex("scroll\\s+(up|down|left|right)", RegexOption.IGNORE_CASE), "SCROLL", listOf("direction")),
        Rule(Regex("go\\s+back", RegexOption.IGNORE_CASE), "NAVIGATE_BACK", emptyList()),
        Rule(Regex("go\\s+home", RegexOption.IGNORE_CASE), "NAVIGATE_HOME", emptyList()),
        Rule(Regex("notification(s)?", RegexOption.IGNORE_CASE), "OPEN_NOTIFICATIONS", emptyList()),
        Rule(Regex("help", RegexOption.IGNORE_CASE), "HELP", emptyList()),
        Rule(Regex("stop", RegexOption.IGNORE_CASE), "STOP", emptyList()),
        Rule(Regex("(?:what|who|where|when|why|how)(?:\\s+.*)?", RegexOption.IGNORE_CASE), "QUERY", emptyList()),
    )

    suspend fun classify(utterance: String): IntentResult = withContext(Dispatchers.Default) {
        val clean = utterance.trim()
        for (rule in rules) {
            val match = rule.pattern.find(clean) ?: continue
            val groups = match.groupValues.drop(1)
            val entities = mutableMapOf<String, String>()
            for ((i, key) in rule.entityKeys.withIndex()) {
                if (i < groups.size && groups[i].isNotBlank()) entities[key] = groups[i].trim()
            }
            Logger.d("Matched: ${rule.intent} → $entities", "Intent")
            return@withContext IntentResult(rule.intent, 0.9f, entities)
        }
        IntentResult("UNKNOWN", 0f, requiresClarification = true, clarificationPrompt = "I didn't understand that. Could you rephrase?")
    }
}
