package com.simon.harmonichackernews.network

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.time.TimeSource

/** Outcome of a connection test against a configured cache server base URL. */
sealed interface CacheServerCheck {
    /** Any HTTP response arrived: something is listening at the address. */
    data object Reachable : CacheServerCheck

    data class Unreachable(val reason: String) : CacheServerCheck
}

/**
 * Reads pre-computed comment translations from an optional self-hosted cache server
 * (the standalone translation server project). Any transport failure, non-200 response, or malformed
 * body is reported as a miss so callers fall back to the cloud chat endpoint.
 */
interface TranslationCacheClient {
    suspend fun fetchTranslations(baseUrl: String, storyId: Int, language: String): Map<Int, String>?

    /**
     * Probes the server with a short-lived health request before the URL is accepted as a
     * setting. Enabling an untested address would otherwise tax every translated thread
     * with the full fetch timeout while the app falls back.
     */
    suspend fun checkServer(baseUrl: String): CacheServerCheck
}

class KtorTranslationCacheClient(
    private val client: KtorHttpClient,
    nowMillis: (() -> Long)? = null,
) : TranslationCacheClient {
    private val startupMark = TimeSource.Monotonic.markNow()
    private val nowMillis: () -> Long = nowMillis
        ?: { startupMark.elapsedNow().inWholeMilliseconds }

    // Consecutive transport failures trip a short cooldown so a server that died after it was
    // configured stops adding a timeout to every story open for the rest of the session.
    private var consecutiveTransportFailures = 0
    private var blockedUntilMillis = 0L

    override suspend fun fetchTranslations(
        baseUrl: String,
        storyId: Int,
        language: String,
    ): Map<Int, String>? {
        val base = baseUrl.trim().trimEnd('/')
        if (!base.startsWith("http://") && !base.startsWith("https://")) return null
        if (language.isBlank()) return null
        if (nowMillis() < blockedUntilMillis) return null
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
            recordTransportFailure()
            return null
        }
        recordTransportSuccess()
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

    override suspend fun checkServer(baseUrl: String): CacheServerCheck {
        val base = baseUrl.trim().trimEnd('/')
        if (!base.startsWith("http://") && !base.startsWith("https://")) {
            return CacheServerCheck.Unreachable("Address must start with http:// or https://")
        }
        val request = HttpRequest.Builder()
            .url("$base/health")
            .get()
            .build()
        val response = try {
            client.newBuilder()
                .readTimeoutMillis(CHECK_TIMEOUT_MILLIS)
                .requestTimeoutMillis(CHECK_TIMEOUT_MILLIS)
                .build()
                .execute(request, maxBytes = MAX_CHECK_RESPONSE_BYTES)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            return CacheServerCheck.Unreachable(error.message ?: "connection failed")
        }
        response.close()
        // Any status counts, including errors: the goal is proving reachability, not the payload.
        return CacheServerCheck.Reachable
    }

    private fun recordTransportFailure() {
        consecutiveTransportFailures++
        if (consecutiveTransportFailures >= TRANSPORT_FAILURES_BEFORE_BLOCKING) {
            blockedUntilMillis = nowMillis() + TRANSPORT_BLOCK_MILLIS
        }
    }

    private fun recordTransportSuccess() {
        consecutiveTransportFailures = 0
        blockedUntilMillis = 0L
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
        const val CHECK_TIMEOUT_MILLIS = 4_000L
        const val MAX_CACHE_RESPONSE_BYTES = 4 * 1024 * 1024
        const val MAX_CHECK_RESPONSE_BYTES = 64 * 1024
        const val TRANSPORT_FAILURES_BEFORE_BLOCKING = 4
        const val TRANSPORT_BLOCK_MILLIS = 10 * 60_000L
    }
}
