package com.stanley.reddittldr.api.models

import kotlinx.serialization.Serializable

@Serializable
data class ClaudeRequest(
    val model: String,
    val max_tokens: Int,
    val system: String? = null,
    val messages: List<Message>
) {
    @Serializable
    data class Message(
        val role: String,
        val content: String
    )
}
