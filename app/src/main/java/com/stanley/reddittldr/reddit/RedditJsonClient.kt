package com.stanley.reddittldr.reddit

import com.stanley.reddittldr.util.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

class RedditJsonClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    suspend fun fetchPost(postId: String): Result<RedditPost> = withContext(Dispatchers.IO) {
        try {
            val url = "https://www.reddit.com/comments/$postId.json?raw_json=1&limit=1"
            DebugLog.logKv("redditJson", "GET" to url)
            val request = Request.Builder()
                .url(url)
                .addHeader("User-Agent", USER_AGENT)
                .addHeader("Accept", "application/json")
                .build()
            client.newCall(request).execute().use { resp ->
                DebugLog.logKv("redditJson", "status" to resp.code, "successful" to resp.isSuccessful)
                if (!resp.isSuccessful) {
                    return@withContext Result.failure(IOException("Reddit JSON returned ${resp.code}"))
                }
                val body = resp.body?.string().orEmpty()
                DebugLog.logKv("redditJson", "respLen" to body.length)
                val root = json.parseToJsonElement(body)
                val listing = root as? JsonArray ?: return@withContext Result.failure(
                    IOException("Unexpected Reddit response shape")
                )
                val postListing = listing.getOrNull(0)?.jsonObject
                val children = postListing?.get("data")?.jsonObject?.get("children")?.jsonArray
                val data = children?.getOrNull(0)?.jsonObject?.get("data")?.jsonObject
                    ?: return@withContext Result.failure(IOException("Reddit post not found"))
                val title = data["title"]?.jsonPrimitive?.contentOrNull
                val selftext = data["selftext"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val url2 = data["url"]?.jsonPrimitive?.contentOrNull
                val isSelf = data["is_self"]?.jsonPrimitive?.booleanOrNull ?: selftext.isNotBlank()
                val permalink = data["permalink"]?.jsonPrimitive?.contentOrNull
                Result.success(
                    RedditPost(
                        id = postId,
                        title = title,
                        selftext = selftext,
                        url = url2,
                        isSelfPost = isSelf,
                        permalink = permalink
                    )
                )
            }
        } catch (e: IOException) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(IOException("Reddit JSON parse failure: ${e.message}"))
        }
    }

    /**
     * Fetches the top-level comments for a post, sorted by score. Used by the
     * "Summarize comments" action — only invoked when the user explicitly asks.
     * Skips deleted/removed/AutoModerator/short comments.
     */
    suspend fun fetchComments(postId: String, limit: Int = 30): Result<List<Comment>> =
        withContext(Dispatchers.IO) {
            try {
                val url = "https://www.reddit.com/comments/$postId.json?raw_json=1&sort=top&limit=$limit"
                DebugLog.logKv("redditJson", "GET-comments" to url)
                val request = Request.Builder()
                    .url(url)
                    .addHeader("User-Agent", USER_AGENT)
                    .addHeader("Accept", "application/json")
                    .build()
                client.newCall(request).execute().use { resp ->
                    DebugLog.logKv("redditJson", "comments-status" to resp.code)
                    if (!resp.isSuccessful) {
                        return@withContext Result.failure(IOException("Reddit JSON returned ${resp.code}"))
                    }
                    val body = resp.body?.string().orEmpty()
                    val root = json.parseToJsonElement(body) as? JsonArray
                        ?: return@withContext Result.failure(IOException("Unexpected response shape"))
                    val commentsListing = root.getOrNull(1)?.jsonObject
                    val children = commentsListing?.get("data")?.jsonObject?.get("children")?.jsonArray
                        ?: return@withContext Result.success(emptyList())
                    val out = mutableListOf<Comment>()
                    for (child in children) {
                        val node = child.jsonObject
                        val kind = node["kind"]?.jsonPrimitive?.contentOrNull
                        if (kind != "t1") continue
                        val data = node["data"]?.jsonObject ?: continue
                        val author = data["author"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        val text = data["body"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
                        val score = data["score"]?.jsonPrimitive?.intOrNull ?: 0
                        if (text.isBlank()) continue
                        if (text == "[deleted]" || text == "[removed]") continue
                        if (author.equals("AutoModerator", ignoreCase = true)) continue
                        if (text.length < 30) continue
                        out += Comment(author = author, body = text, score = score)
                    }
                    DebugLog.logKv("redditJson", "comments-kept" to out.size)
                    Result.success(out.sortedByDescending { it.score })
                }
            } catch (e: IOException) {
                Result.failure(e)
            } catch (e: Exception) {
                Result.failure(IOException("Reddit JSON parse failure: ${e.message}"))
            }
        }

    /**
     * Reddit search fallback: when the post ID cannot be found in the accessibility tree,
     * we hit /r/{sub}/search with the visible title. Returns the best-matching post ID,
     * or failure if no candidate scores above the similarity floor.
     */
    suspend fun searchPostId(subreddit: String, title: String): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                val q = java.net.URLEncoder.encode(title, "UTF-8")
                val url = "https://www.reddit.com/r/$subreddit/search.json?q=$q&restrict_sr=1&limit=10&sort=relevance&raw_json=1"
                DebugLog.logKv("redditJson", "GET-search" to url)
                val request = Request.Builder()
                    .url(url)
                    .addHeader("User-Agent", USER_AGENT)
                    .addHeader("Accept", "application/json")
                    .build()
                client.newCall(request).execute().use { resp ->
                    DebugLog.logKv("redditJson", "search-status" to resp.code)
                    if (!resp.isSuccessful) {
                        return@withContext Result.failure(IOException("Search returned ${resp.code}"))
                    }
                    val body = resp.body?.string().orEmpty()
                    val root = json.parseToJsonElement(body).jsonObject
                    val children = root["data"]?.jsonObject?.get("children")?.jsonArray
                        ?: return@withContext Result.failure(IOException("No children in search response"))
                    val want = title.lowercase().trim()
                    var bestId: String? = null
                    var bestScore = 0
                    for (child in children) {
                        val data = child.jsonObject["data"]?.jsonObject ?: continue
                        val candTitle = data["title"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        val candId = data["id"]?.jsonPrimitive?.contentOrNull ?: continue
                        val score = scoreTitle(want, candTitle.lowercase().trim())
                        if (score > bestScore) {
                            bestScore = score
                            bestId = candId
                        }
                    }
                    DebugLog.logKv("redditJson", "search-best" to (bestId ?: "<none>"), "score" to bestScore)
                    if (bestId != null && bestScore >= 60) {
                        Result.success(bestId)
                    } else {
                        Result.failure(IOException("No good title match (best=$bestScore)"))
                    }
                }
            } catch (e: IOException) {
                Result.failure(e)
            } catch (e: Exception) {
                Result.failure(IOException("Search failure: ${e.message}"))
            }
        }

    private fun scoreTitle(want: String, candidate: String): Int {
        if (want.isEmpty() || candidate.isEmpty()) return 0
        if (want == candidate) return 100
        if (candidate.contains(want)) return 95
        if (want.contains(candidate) && candidate.length >= 20) return 90
        val wantWords = want.split(Regex("\\s+")).filter { it.length > 3 }.toSet()
        val candWords = candidate.split(Regex("\\s+")).filter { it.length > 3 }.toSet()
        if (wantWords.isEmpty() || candWords.isEmpty()) return 0
        val overlap = wantWords.intersect(candWords).size
        return (overlap * 100) / wantWords.size
    }

    data class RedditPost(
        val id: String,
        val title: String?,
        val selftext: String,
        val url: String?,
        val isSelfPost: Boolean,
        val permalink: String?
    )

    data class Comment(
        val author: String,
        val body: String,
        val score: Int
    )

    companion object {
        private const val USER_AGENT = "RedditTLDR/1.0 (Android; by /u/stanley)"
    }
}
