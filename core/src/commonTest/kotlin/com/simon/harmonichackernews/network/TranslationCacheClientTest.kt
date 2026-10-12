package com.simon.harmonichackernews.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TranslationCacheClientTest {
    @Test
    fun checkServerAcceptsAnyHttpResponseAsReachable() = runTest {
        val client = KtorTranslationCacheClient(
            KtorHttpClient(
                HttpClient(
                    MockEngine {
                        respond(content = "moved", status = HttpStatusCode.NotFound)
                    },
                ),
            ),
        )

        assertEquals(CacheServerCheck.Reachable, client.checkServer("http://192.168.1.10:8362/"))
    }

    @Test
    fun checkServerRejectsNonHttpAddressWithoutSendingAnything() = runTest {
        var requests = 0
        val client = KtorTranslationCacheClient(
            KtorHttpClient(
                HttpClient(
                    MockEngine {
                        requests++
                        respond("{}")
                    },
                ),
            ),
        )

        val result = client.checkServer("192.168.1.10:8362")
        assertTrue(result is CacheServerCheck.Unreachable)
        assertEquals(0, requests)
    }

    @Test
    fun checkServerReportsTransportFailureAsUnreachable() = runTest {
        val client = KtorTranslationCacheClient(
            KtorHttpClient(
                HttpClient(
                    MockEngine { error("server unreachable") },
                ),
            ),
        )

        val result = client.checkServer("http://192.168.1.10:8362")
        assertTrue(result is CacheServerCheck.Unreachable)
        assertTrue(result.reason.isNotEmpty())
    }

    @Test
    fun repeatedTransportFailuresTripFetchCooldown() = runTest {
        var clock = 0L
        var fail = true
        var fetchRequests = 0
        val client = KtorTranslationCacheClient(
            KtorHttpClient(
                HttpClient(
                    MockEngine { request ->
                        fetchRequests++
                        if (fail) {
                            error("connection refused")
                        }
                        respond(
                            content = """{"comments": {"3": "<p>x</p>"}}""",
                            status = HttpStatusCode.OK,
                        )
                    },
                ),
            ),
            nowMillis = { clock },
        )

        repeat(4) { assertNull(client.fetchTranslations("http://cache", 1, "zh-Hans")) }
        assertEquals(4, fetchRequests)

        // Blocked: further fetches skip the network entirely.
        assertNull(client.fetchTranslations("http://cache", 2, "zh-Hans"))
        assertEquals(4, fetchRequests)

        // The cooldown expires and the server recovered: fetches resume and unblock the client.
        fail = false
        clock = 10 * 60_000L + 1
        assertEquals(
            mapOf(3 to "<p>x</p>"),
            client.fetchTranslations("http://cache", 3, "zh-Hans"),
        )
        assertEquals(5, fetchRequests)

        // A single success cleared the failure streak, so one new failure does not re-block.
        fail = true
        assertNull(client.fetchTranslations("http://cache", 4, "zh-Hans"))
        assertNull(client.fetchTranslations("http://cache", 5, "zh-Hans"))
        assertEquals(7, fetchRequests)
    }

    @Test
    fun parsesCachedTranslations() = runTest {
        val client = KtorTranslationCacheClient(
            KtorHttpClient(
                HttpClient(
                    MockEngine {
                        respond(
                            content = """{"comments": {"11": "<p>hi</p>", "12": ""}}""",
                            status = HttpStatusCode.OK,
                        )
                    },
                ),
            ),
        )

        val translations = client.fetchTranslations("http://cache", 1, "zh-Hans")
        assertEquals(mapOf(11 to "<p>hi</p>"), translations)
    }
}
