package com.stanley.reddittldr.api

import com.stanley.reddittldr.api.models.ClaudeErrorEnvelope
import com.stanley.reddittldr.api.models.ClaudeRequest
import com.stanley.reddittldr.api.models.ClaudeResponse
import com.stanley.reddittldr.data.SettingsRepository
import com.stanley.reddittldr.data.SummaryLength
import com.stanley.reddittldr.reddit.PostContent
import com.stanley.reddittldr.reddit.RedditJsonClient
import com.stanley.reddittldr.util.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

class ClaudeRepository(private val settings: SettingsRepository) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    suspend fun summarize(post: PostContent): Result<String> = withContext(Dispatchers.IO) {
        val apiKey = settings.apiKey
        if (apiKey.isBlank()) {
            return@withContext Result.failure(IllegalStateException("No API key set."))
        }
        val userContent = buildString {
            post.title?.takeIf { it.isNotBlank() }?.let {
                append("Title: ").append(it).append("\n\n")
            }
            append("Body:\n")
            append(post.body)
        }
        val requestBody = ClaudeRequest(
            model = settings.model.apiId,
            max_tokens = 1024,
            system = systemPromptFor(settings.summaryLength),
            messages = listOf(ClaudeRequest.Message("user", userContent))
        )
        DebugLog.logKv(
            "claude",
            "model" to settings.model.apiId,
            "length" to settings.summaryLength.name,
            "userContentLen" to userContent.length,
            "titleLen" to (post.title?.length ?: 0),
            "bodyLen" to post.body.length,
            "isPartial" to post.isPartial,
            "extractor" to post.extractionMethod.name
        )
        callClaude(apiKey, requestBody).mapCatching { response ->
            val text = response.content.firstOrNull { it.type == "text" }?.text?.trim()
                ?: throw IOException("Empty response from Claude.")
            text
        }.onSuccess { text ->
            DebugLog.logKv("claude", "result" to "SUCCESS", "summaryLen" to text.length)
        }.onFailure {
            DebugLog.logKv("claude", "result" to "FAIL", "err" to (it.message ?: it.javaClass.simpleName))
        }
    }

    suspend fun summarizeComments(
        capturedText: String,
        postTitle: String?
    ): Result<String> = withContext(Dispatchers.IO) {
        val apiKey = settings.apiKey
        if (apiKey.isBlank()) {
            return@withContext Result.failure(IllegalStateException("No API key set."))
        }
        if (capturedText.isBlank()) {
            return@withContext Result.failure(IOException("No content captured."))
        }
        val joined = buildString {
            postTitle?.takeIf { it.isNotBlank() }?.let {
                append("Post title: ").append(it).append("\n\n")
            }
            append("Captured screen content from a Reddit post (the post body ")
            append("followed by the comment section if any was present). Focus on ")
            append("the COMMENTS — what people are saying in the discussion below ")
            append("the post — NOT what the original poster wrote:\n\n")
            append(capturedText.take(MAX_COMMENT_CHARS))
        }
        val requestBody = ClaudeRequest(
            model = settings.model.apiId,
            max_tokens = 1024,
            system = COMMENTS_SYSTEM_PROMPT,
            messages = listOf(ClaudeRequest.Message("user", joined))
        )
        DebugLog.logKv(
            "claude-comments",
            "model" to settings.model.apiId,
            "capturedTextLen" to capturedText.length,
            "userContentLen" to joined.length
        )
        callClaude(apiKey, requestBody).mapCatching { response ->
            response.content.firstOrNull { it.type == "text" }?.text?.trim()
                ?: throw IOException("Empty response from Claude.")
        }.onSuccess { text ->
            DebugLog.logKv("claude-comments", "result" to "SUCCESS", "summaryLen" to text.length)
        }.onFailure {
            DebugLog.logKv("claude-comments", "result" to "FAIL", "err" to (it.message ?: it.javaClass.simpleName))
        }
    }

    suspend fun testApiKey(): Result<Unit> = withContext(Dispatchers.IO) {
        val apiKey = settings.apiKey
        if (apiKey.isBlank()) {
            return@withContext Result.failure(IllegalStateException("No API key set."))
        }
        val requestBody = ClaudeRequest(
            model = settings.model.apiId,
            max_tokens = 10,
            messages = listOf(ClaudeRequest.Message("user", "hi"))
        )
        callClaude(apiKey, requestBody).map { }
    }

    private fun callClaude(apiKey: String, body: ClaudeRequest): Result<ClaudeResponse> {
        return try {
            val bodyJson = json.encodeToString(ClaudeRequest.serializer(), body)
            val request = Request.Builder()
                .url(ENDPOINT)
                .addHeader("x-api-key", apiKey)
                .addHeader("anthropic-version", "2023-06-01")
                .addHeader("content-type", "application/json")
                .post(bodyJson.toRequestBody(JSON_MEDIA))
                .build()
            client.newCall(request).execute().use { resp ->
                val respBody = resp.body?.string().orEmpty()
                if (resp.isSuccessful) {
                    Result.success(json.decodeFromString(ClaudeResponse.serializer(), respBody))
                } else {
                    Result.failure(IOException(errorMessageFor(resp.code, respBody)))
                }
            }
        } catch (e: IOException) {
            Result.failure(IOException("No internet."))
        } catch (e: Exception) {
            // Swallow the original message defensively — the request body never contains
            // the API key, but future changes shouldn't risk surfacing it through a toast.
            Result.failure(IOException("Request failed."))
        }
    }

    private fun errorMessageFor(code: Int, body: String): String {
        return when (code) {
            401 -> "Invalid API key. Check settings."
            429 -> "Rate limited. Wait a moment."
            else -> parseApiMessage(body) ?: "Claude API error ($code)."
        }
    }

    private fun parseApiMessage(body: String): String? {
        return try {
            json.decodeFromString(ClaudeErrorEnvelope.serializer(), body).error?.message
        } catch (_: Exception) {
            null
        }
    }

    private fun systemPromptFor(length: SummaryLength): String {
        // Every captured input may include comment-section text after the
        // post body — make this rule loud so it doesn't bleed into the
        // summary. The user gets comments separately by tapping the
        // "Summarize comments" button on the card.
        val ignoreCommentsRule =
            "The input is text captured directly from the user's screen and " +
                "will likely include comment-section content from other Reddit " +
                "users after the post body. IGNORE the comment-section content " +
                "entirely and summarize ONLY what the original poster (OP) wrote " +
                "in the post body itself. "
        return when (length) {
            SummaryLength.SHORT ->
                "${ignoreCommentsRule}Summarize the post in 1-2 sentences. Lead with the core point. No preamble."
            SummaryLength.MEDIUM ->
                "${ignoreCommentsRule}Summarize the post. One-sentence TL;DR, then 3-4 bullets with key details. No preamble, no throat-clearing."
            SummaryLength.DETAILED ->
                "${ignoreCommentsRule}Summarize the post thoroughly. Start with a one-sentence TL;DR. Then give 5-7 bullets covering context, main points, any key details or caveats. Skip preamble."
        }
    }

    companion object {
        private const val ENDPOINT = "https://api.anthropic.com/v1/messages"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        private const val MAX_COMMENT_CHARS = 12_000
        private const val COMMENTS_SYSTEM_PROMPT =
            "You are summarizing the comment-section discussion of a Reddit post. " +
                "The input is captured screen text that begins with the post body and " +
                "is followed by the comments — focus ONLY on the COMMENTS, not on " +
                "what the original poster wrote. Lead with one sentence on the overall " +
                "sentiment / consensus among commenters. Then 3-6 bullets covering distinct " +
                "themes, opinions, or notable disagreements you actually see in the comments. " +
                "If a particular comment seems especially substantive or upvoted, point to it " +
                "directly with a short paraphrase or quote. Stick strictly to what is in the " +
                "input — do not invent opinions. If the captured content has no comments " +
                "(e.g., the post had zero comments, or only the body was captured before the " +
                "scroll cap), say plainly that there are no comments to summarize. Skip preamble."
    }
}
