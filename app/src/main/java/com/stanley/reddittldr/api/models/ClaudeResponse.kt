package com.stanley.reddittldr.api.models

import kotlinx.serialization.Serializable

@Serializable
data class ClaudeResponse(
    val id: String? = null,
    val type: String? = null,
    val role: String? = null,
    val model: String? = null,
    val content: List<ContentBlock> = emptyList(),
    val stop_reason: String? = null
) {
    @Serializable
    data class ContentBlock(
        val type: String,
        val text: String? = null
    )
}

@Serializable
data class ClaudeErrorEnvelope(
    val type: String? = null,
    val error: ClaudeError? = null
) {
    @Serializable
    data class ClaudeError(
        val type: String? = null,
        val message: String? = null
    )
}
