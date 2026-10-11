package com.simon.harmonichackernews.network

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Reads pre-computed comment translations from an optional self-hosted cache server
 * (see `translation-server/`). Any transport failure, non-200 response, or malformed
 * body is reported as a miss so callers fall back to the cloud chat endpoint.
 */
interface TranslationCacheClient {
    suspend fun fetchTranslations(baseUrl: String, storyId: Int, language: String): Map<Int, String>?
}

class KtorTranslationCacheClient(
    private val client: KtorHttpClient,
) : TranslationCacheClient {
    override suspend fun fetchTranslations(
        baseUrl: String,
        storyId: Int,
        language: String,
    ): Map<Int, String>? {
        val base = baseUrl.trim().trimEnd('/')
        if (!base.startsWith("http://") && !base.startsWith("https://")) return null
        if (language.isBlank()) return null
        val request = HttpRequest.Builder()
            .url("$base/v1/translations/$storyId?language=$language")
            .get()
            .build()
        val response = try {
            client.newBuilder()
                .readTimeoutMillis(TIMEOUT_MILLIS)
                .requestTimeoutMillis(TIMEOUT_MILLIS)
                .build()
                .execute(request, maxBytes = MAX_CACHE_RESPONSE_BYTES)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return null
        }
        return try {
            if (!response.isSuccessful) return null
            val body = response.body.readText(MAX_CACHE_RESPONSE_BYTES)
            parseTranslations(body)
        } catch (_: Exception) {
            null
        } finally {
            response.close()
        }
    }

    private fun parseTranslations(body: String): Map<Int, String>? {
        if (body.isBlank()) return null
        return try {
            val comments = jsonParser.parseToJsonElement(body)
                .jsonObject["comments"] as? JsonObject ?: return null
            val result = LinkedHashMap<Int, String>(comments.size)
            for ((key, value) in comments) {
                val id = key.toIntOrNull() ?: continue
                val html = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: continue
                if (html.isNotBlank()) result[id] = html
            }
            result
        } catch (_: Exception) {
            null
        }
    }

    private companion object {
        val jsonParser = Json { ignoreUnknownKeys = true }
        const val TIMEOUT_MILLIS = 2_500L
        const val MAX_CACHE_RESPONSE_BYTES = 4 * 1024 * 1024
    }
}
