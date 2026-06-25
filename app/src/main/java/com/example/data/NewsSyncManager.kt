package com.example.data

import android.util.Log
import com.example.BuildConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object NewsSyncManager {

    private const val TAG = "NewsSyncManager"

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private fun getTodayDateString(): String {
        return SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
    }

    /**
     * Cleans and extracts raw JSON from potential markdown wrappers.
     */
    private fun cleanJsonString(input: String): String {
        var cleaned = input.trim()
        if (cleaned.startsWith("```json")) {
            cleaned = cleaned.substringAfter("```json")
        } else if (cleaned.startsWith("```")) {
            cleaned = cleaned.substringAfter("```")
        }
        if (cleaned.endsWith("```")) {
            cleaned = cleaned.substringBeforeLast("```")
        }
        return cleaned.trim()
    }

    /**
     * Synchronizes news items. It checks if a live News API key is available.
     * If so, it queries top headlines for the country and processes them through Gemini.
     * If not, it generates 15-20 highly realistic, everyday national news stories using Gemini.
     */
    suspend fun syncDailyNews(repository: AppRepository, forceRefresh: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        try {
            val today = getTodayDateString()
            
            // If not forcing refresh, check if we already have 15+ items for today.
            if (!forceRefresh) {
                val currentDigestCount = repository.getDigestItemsCount()
                if (currentDigestCount >= 15) {
                    Log.d(TAG, "Already have $currentDigestCount items. Skipping sync.")
                    return@withContext true
                }
            }

            val newsApiKey = BuildConfig.NEWS_API_KEY
            val hasNewsApiKey = newsApiKey.isNotEmpty() && newsApiKey != "MY_NEWS_API_KEY"

            var digestItems: List<DigestItem> = emptyList()

            if (hasNewsApiKey) {
                Log.d(TAG, "Live News API Key found. Fetching raw headlines...")
                val rawArticles = fetchRawNewsFromApi(newsApiKey)
                if (rawArticles.isNotEmpty()) {
                    Log.d(TAG, "Fetched ${rawArticles.size} articles. Sending to Gemini for cleaning and simplification...")
                    digestItems = cleanAndEnrichWithGemini(rawArticles, today)
                }
            }

            // Fallback: If no API key or API call returned nothing, generate 15-20 realistic daily news.
            if (digestItems.isEmpty() || digestItems.size < 15) {
                Log.d(TAG, "No live news key or empty feed. Triggering fallback daily news automation via Gemini...")
                digestItems = generateFallbackDailyNews(today)
            }

            if (digestItems.isNotEmpty()) {
                Log.d(TAG, "Successfully prepared ${digestItems.size} news stories. Inserting to database...")
                repository.deleteAllDigestItems()
                repository.insertDigestItems(digestItems)
                return@withContext true
            }

            return@withContext false
        } catch (e: Exception) {
            Log.e(TAG, "Error in news sync: ", e)
            return@withContext false
        }
    }

    /**
     * Fetches top headlines filtered for India (national region) and categories relevant to citizens.
     */
    private suspend fun fetchRawNewsFromApi(apiKey: String): List<RawArticle> = withContext(Dispatchers.IO) {
        val url = "https://newsapi.org/v2/top-headlines?country=in&pageSize=25&apiKey=$apiKey"
        val request = Request.Builder().url(url).build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "News API response failed: ${response.code}")
                    return@withContext emptyList()
                }
                val body = response.body?.string() ?: return@withContext emptyList()
                val json = JSONObject(body)
                val status = json.optString("status")
                if (status != "ok") {
                    Log.e(TAG, "News API returned non-ok status: $status")
                    return@withContext emptyList()
                }

                val articlesArray = json.optJSONArray("articles") ?: return@withContext emptyList()
                val list = mutableListOf<RawArticle>()
                for (i in 0 until articlesArray.length()) {
                    val art = articlesArray.getJSONObject(i)
                    val title = art.optString("title")
                    val description = art.optString("description", "")
                    val sourceName = art.optJSONObject("source")?.optString("name", "Unknown") ?: "Unknown"
                    if (title.isNotEmpty()) {
                        list.add(RawArticle(title = title, description = description, sourceName = sourceName))
                    }
                }
                return@withContext list
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching News API: ", e)
            return@withContext emptyList()
        }
    }

    /**
     * Uses Gemini to map raw articles, clean up description, simplify language, and enrich with perspectives.
     */
    private suspend fun cleanAndEnrichWithGemini(rawArticles: List<RawArticle>, dateStr: String): List<DigestItem> = withContext(Dispatchers.IO) {
        val geminiKey = BuildConfig.GEMINI_API_KEY
        if (geminiKey.isEmpty() || geminiKey == "MY_GEMINI_API_KEY") {
            Log.e(TAG, "Gemini API key missing. Cannot process news via Gemini.")
            return@withContext emptyList()
        }

        val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash:generateContent?key=$geminiKey"

        // Build a concise prompt with raw headlines
        val articlesJsonInput = JSONArray().apply {
            rawArticles.forEach { art ->
                put(JSONObject().apply {
                    put("title", art.title)
                    put("description", art.description)
                    put("source", art.sourceName)
                })
            }
        }

        val prompt = """
            You are a Senior Editor and News Simplification AI.
            I will give you a list of raw news articles fetched from a live News API.
            Please process at least 15 to 20 of these articles.
            For each article, map and output a structured JSON object matching the following instructions:
            
            1. 'category': Must be one of: Polity, Economy, Environment, InternationalRelations, Science, Other.
            2. 'headline': A clear, direct headline (feel free to clean up trailing source names).
            3. 'previewText': A highly simplified, plain-language summary of EXACTLY 2 to 3 sentences. No complex jargon, easy for any everyday citizen to read under 30 seconds.
            4. 'contextText': 1-2 paragraphs of clear background info.
            5. 'keyPointsText': 3-4 clear bullet points of critical details.
            6. 'whyItMattersText': 1-2 sentences explaining the direct, real-world impact of this news on the common public's everyday life.
            7. 'examAngleText': Brief connection to general knowledge or public exams.
            8. 'sourceAFraming': Source framing 1 (e.g., 'The Hindu (Center-Left): ...')
            9. 'sourceBFraming': Source framing 2 (e.g., 'Indian Express (Liberal-Neutral): ...')
            10. 'sourceCFraming': Source framing 3 (e.g., 'LiveMint (Fiscal-Conservative): ...')
            11. 'isDailyPulse': Boolean. Set to true for everyday citizen-impact briefs (such as consumer guidelines, sports, local technology, national measures) and false for long form deep dives.
            
            Format the output strictly as a JSON array of objects. 
            Do NOT include any markdown blocks, prefix or postfix text, or any explanations. Return only the raw JSON.
            
            Raw articles:
            $articlesJsonInput
        """.trimIndent()

        val requestJson = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply {
                            put("text", prompt)
                        })
                    })
                })
            })
        }

        val mediaType = "application/json; charset=utf-8".toMediaType()
        val requestBody = requestJson.toString().toRequestBody(mediaType)
        val request = Request.Builder().url(url).post(requestBody).build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext emptyList()
                val bodyString = response.body?.string() ?: return@withContext emptyList()
                val responseJson = JSONObject(bodyString)
                val text = responseJson.optJSONArray("candidates")
                    ?.optJSONObject(0)
                    ?.optJSONObject("content")
                    ?.optJSONArray("parts")
                    ?.optJSONObject(0)
                    ?.optString("text") ?: ""

                return@withContext parseJsonToDigestItems(text, dateStr)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning articles with Gemini: ", e)
            return@withContext emptyList()
        }
    }

    /**
     * Fallback dynamic content generator that prompts Gemini to synthesize 15-20 highly realistic, everyday national news briefs.
     */
    private suspend fun generateFallbackDailyNews(dateStr: String): List<DigestItem> = withContext(Dispatchers.IO) {
        val geminiKey = BuildConfig.GEMINI_API_KEY
        if (geminiKey.isEmpty() || geminiKey == "MY_GEMINI_API_KEY") {
            Log.e(TAG, "Gemini API key missing. Cannot generate fallback dynamic news.")
            return@withContext emptyList()
        }

        val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash:generateContent?key=$geminiKey"

        val prompt = """
            You are a Senior News Editor. Please generate exactly 15 to 20 highly realistic, real-world, everyday national news stories for India (country=in) for the date: $dateStr.
            
            These news briefs must feel concrete and directly impact the general public's daily life, cost of living, local technology, sports, national welfare, consumer business, and environment.
            
            Generate a variety of stories across these categories: Polity, Economy, Environment, InternationalRelations, Science, Other.
            
            For each news brief, output a JSON object with:
            - 'category': One of: Polity, Economy, Environment, InternationalRelations, Science, Other.
            - 'headline': A direct, highly realistic news headline (e.g. 'National Railways Upgrades Local Trains with Air-Conditioned General Coaches', 'Government Extends Direct Subsidies for Rooftop Solar Panels').
            - 'previewText': A plain-language summary of EXACTLY 2 to 3 sentences that anyone can read in 30 seconds.
            - 'contextText': 1-2 paragraphs of background info.
            - 'keyPointsText': 3-4 bullet points of critical details.
            - 'whyItMattersText': 1-2 sentences explaining the direct, real-world impact of this news on the common public's everyday life.
            - 'examAngleText': Brief connection to general knowledge or public exams.
            - 'sourceAFraming': Source framing 1 (e.g., 'The Hindu (Center-Left): ...')
            - 'sourceBFraming': Source framing 2 (e.g., 'Indian Express (Liberal-Neutral): ...')
            - 'sourceCFraming': Source framing 3 (e.g., 'LiveMint (Fiscal-Conservative): ...')
            - 'isDailyPulse': Boolean. Set to true for everyday citizen-impact briefs (make 12-15 of these true) and false for long form deep dives.
            
            Format the output strictly as a JSON array of objects. 
            Do NOT include any markdown blocks, code blocks, prefix or postfix text, or any explanations. Return only raw, valid JSON.
        """.trimIndent()

        val requestJson = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply {
                            put("text", prompt)
                        })
                    })
                })
            })
        }

        val mediaType = "application/json; charset=utf-8".toMediaType()
        val requestBody = requestJson.toString().toRequestBody(mediaType)
        val request = Request.Builder().url(url).post(requestBody).build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext emptyList()
                val bodyString = response.body?.string() ?: return@withContext emptyList()
                val responseJson = JSONObject(bodyString)
                val text = responseJson.optJSONArray("candidates")
                    ?.optJSONObject(0)
                    ?.optJSONObject("content")
                    ?.optJSONArray("parts")
                    ?.optJSONObject(0)
                    ?.optString("text") ?: ""

                return@withContext parseJsonToDigestItems(text, dateStr)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error generating fallback news via Gemini: ", e)
            return@withContext emptyList()
        }
    }

    private fun parseJsonToDigestItems(rawJson: String, dateStr: String): List<DigestItem> {
        val list = mutableListOf<DigestItem>()
        try {
            val cleaned = cleanJsonString(rawJson)
            val array = JSONArray(cleaned)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val catStr = obj.optString("category", "Other")
                val category = try {
                    Category.valueOf(catStr)
                } catch (e: Exception) {
                    Category.Other
                }

                list.add(
                    DigestItem(
                        category = category,
                        headline = obj.optString("headline", ""),
                        previewText = obj.optString("previewText", ""),
                        contextText = obj.optString("contextText", ""),
                        keyPointsText = obj.optString("keyPointsText", ""),
                        whyItMattersText = obj.optString("whyItMattersText", ""),
                        examAngleText = obj.optString("examAngleText", ""),
                        sourceAFraming = obj.optString("sourceAFraming", ""),
                        sourceBFraming = obj.optString("sourceBFraming", ""),
                        sourceCFraming = obj.optString("sourceCFraming", ""),
                        date = dateStr,
                        isBookmarked = false,
                        isRead = false,
                        isDailyPulse = obj.optBoolean("isDailyPulse", true)
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing JSON to digest items: ", e)
        }
        return list
    }

    private data class RawArticle(
        val title: String,
        val description: String,
        val sourceName: String
    )
}
