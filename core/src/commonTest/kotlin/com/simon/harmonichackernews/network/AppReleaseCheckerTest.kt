package com.simon.harmonichackernews.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReleaseVersionTest {
    @Test
    fun parsesTagAndPlainVersionStrings() {
        assertEquals("3.2", ReleaseVersion.parse("v3.2").toString())
        assertEquals("3.1.2", ReleaseVersion.parse("3.1.2").toString())
        assertEquals("4", ReleaseVersion.parse("V4").toString())
    }

    @Test
    fun comparesWithMissingSegmentsAsZero() {
        assertTrue(ReleaseVersion.parse("v3.2")!! > ReleaseVersion.parse("3.1.2")!!)
        assertTrue(ReleaseVersion.parse("3.2.1")!! > ReleaseVersion.parse("3.2")!!)
        assertEquals(0, ReleaseVersion.parse("v3.2")!!.compareTo(ReleaseVersion.parse("3.2.0")!!))
        assertFalse(ReleaseVersion.parse("v3.2")!! > ReleaseVersion.parse("v3.2")!!)
    }

    @Test
    fun rejectsNonNumericLabels() {
        assertNull(ReleaseVersion.parse("latest"))
        assertNull(ReleaseVersion.parse(""))
        assertNull(ReleaseVersion.parse("v"))
    }

    @Test
    fun rendersWithoutPrefix() {
        assertEquals("3.2", ReleaseVersion.parse("v3.2").toString())
    }
}

class AppReleaseCheckerTest {
    /** Serves `releases/latest` for the fork and upstream with the configured tags (null = 404). */
    private class ReleaseApi(
        forkTag: String?,
        upstreamTag: String?,
    ) {
        var requestCount = 0
            private set

        val client: HttpClient = HttpClient(
            MockEngine { request ->
                requestCount++
                val tag = when (request.url.toString()) {
                    "https://api.github.com/repos/HenryNebula/Harmonic-HN/releases/latest" -> forkTag
                    "https://api.github.com/repos/SimonHalvdansson/Harmonic-HN/releases/latest" -> upstreamTag
                    else -> null
                }
                if (tag == null) {
                    respond(
                        content = """{"message": "Not Found"}""",
                        status = HttpStatusCode.NotFound,
                    )
                } else {
                    respond(
                        content = """{"tag_name": "$tag", "name": "$tag"}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(
                            "Content-Type",
                            ContentType.Application.Json.toString(),
                        ),
                    )
                }
            },
        )
    }

    private fun checker(
        forkTag: String?,
        upstreamTag: String?,
    ): Pair<KtorAppReleaseChecker, ReleaseApi> {
        val api = ReleaseApi(forkTag, upstreamTag)
        return KtorAppReleaseChecker(KtorHttpClient(api.client)) to api
    }

    @Test
    fun warnsOnNewerForkReleaseAndSeparatelyOnUpstreamReleaseNotBakedIn() = runTest {
        val (checker, api) = checker(forkTag = "v3.2", upstreamTag = "v3.4")
        val check = checker.checkInstalled("3.1")

        assertEquals(2, api.requestCount)
        assertEquals(2, check.warnings.size)
        val update = check.warnings.filterIsInstance<AppReleaseWarning.ForkUpdate>().single()
        assertEquals("3.1", update.installed.toString())
        assertEquals("3.2", update.latest.toString())
        val behind = check.warnings.filterIsInstance<AppReleaseWarning.UpstreamNotBakedIn>().single()
        assertEquals("3.4", behind.upstreamLatest.toString())
        assertEquals("3.2", behind.forkLatest.toString())
    }

    @Test
    fun forkAheadOfUpstreamOnlyWarnsAboutForkUpdate() = runTest {
        val (checker, _) = checker(forkTag = "v3.2", upstreamTag = "v3.1.2")
        val check = checker.checkInstalled("3.1")

        assertEquals(1, check.warnings.size)
        assertEquals(1, check.warnings.filterIsInstance<AppReleaseWarning.ForkUpdate>().size)
    }

    @Test
    fun upToDateBuildStaysSilent() = runTest {
        val (checker, _) = checker(forkTag = "v3.2", upstreamTag = "v3.1.2")
        assertEquals(0, checker.checkInstalled("3.2").warnings.size)
    }

    @Test
    fun unknownForkReleaseSuppressesBothWarnings() = runTest {
        val (checker, api) = checker(forkTag = null, upstreamTag = "v9.9")
        assertEquals(0, checker.checkInstalled("3.2").warnings.size)
        assertEquals(2, api.requestCount)
    }

    @Test
    fun unparseableInstalledVersionSuppressesRequests() = runTest {
        val (checker, api) = checker(forkTag = "v3.2", upstreamTag = "v3.1.2")
        assertEquals(0, checker.checkInstalled("dev").warnings.size)
        assertEquals(0, api.requestCount)
    }
}
