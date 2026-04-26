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
        comments: List<RedditJsonClient.Comment>,
        postTitle: String?
    ): Result<String> = withContext(Dispatchers.IO) {
        val apiKey = settings.apiKey
        if (apiKey.isBlank()) {
            return@withContext Result.failure(IllegalStateException("No API key set."))
        }
        if (comments.isEmpty()) {
            return@withContext Result.failure(IOException("No usable comments found."))
        }
        val joined = buildString {
            postTitle?.takeIf { it.isNotBlank() }?.let {
                append("Post title: ").append(it).append("\n\n")
            }
            append("Top comments (sorted by score):\n\n")
            var budget = MAX_COMMENT_CHARS
            for ((idx, c) in comments.withIndex()) {
                val entry = "[${c.score} pts] ${c.body}\n\n"
                if (entry.length > budget) break
                append(entry)
                budget -= entry.length
                if (idx >= 24) break
            }
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
            "commentCount" to comments.size,
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

    private fun systemPromptFor(length: SummaryLength): String = when (length) {
        SummaryLength.SHORT ->
            "Summarize this Reddit post in 1-2 sentences. Lead with the core point. No preamble."
        SummaryLength.MEDIUM ->
            "Summarize this Reddit post. One-sentence TL;DR, then 3-4 bullets with key details. No preamble, no throat-clearing."
        SummaryLength.DETAILED ->
            "Summarize this Reddit post thoroughly. Start with a one-sentence TL;DR. Then give 5-7 bullets covering context, main points, any key details or caveats. Skip preamble."
    }

    companion object {
        private const val ENDPOINT = "https://api.anthropic.com/v1/messages"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        private const val MAX_COMMENT_CHARS = 12_000
        private const val COMMENTS_SYSTEM_PROMPT =
            "You are summarizing the discussion in a Reddit comment thread. " +
                "Lead with one sentence on the overall sentiment / consensus. Then 4-6 bullets covering " +
                "the main themes, common opinions, notable disagreements, and any especially upvoted takes. " +
                "Be specific about what people are actually saying — not generic. Skip preamble."
    }
}
