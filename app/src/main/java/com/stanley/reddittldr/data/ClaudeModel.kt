package com.stanley.reddittldr.data

enum class ClaudeModel(val apiId: String, val displayName: String) {
    HAIKU_4_5("claude-haiku-4-5-20251001", "Haiku 4.5 (fastest, cheapest)"),
    SONNET_4_6("claude-sonnet-4-6", "Sonnet 4.6 (balanced)"),
    OPUS_4_7("claude-opus-4-7", "Opus 4.7 (best quality)");

    companion object {
        fun fromApiId(id: String?): ClaudeModel =
            values().firstOrNull { it.apiId == id } ?: HAIKU_4_5
    }
}
