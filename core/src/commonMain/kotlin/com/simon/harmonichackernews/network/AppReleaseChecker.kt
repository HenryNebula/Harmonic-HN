package com.simon.harmonichackernews.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Version label parsed from GitHub tag or version-name strings such as `v3.2` or `3.1.2`.
 * Missing segments compare as zero so `3.2` and `3.2.0` are equal.
 */
data class ReleaseVersion(private val segments: List<Int>) : Comparable<ReleaseVersion> {
    override fun compareTo(other: ReleaseVersion): Int {
        val size = maxOf(segments.size, other.segments.size)
        for (index in 0 until size) {
            val left = segments.getOrElse(index) { 0 }
            val right = other.segments.getOrElse(index) { 0 }
            if (left != right) return left.compareTo(right)
        }
        return 0
    }

    override fun toString(): String = segments.joinToString(".")

    companion object {
        private val leadingDigits = Regex("^\\d+")

        fun parse(value: String): ReleaseVersion? {
            val trimmed = value.trim()
            val withoutPrefix = trimmed.removePrefix("v").removePrefix("V")
            if (withoutPrefix.isEmpty()) return null
            val segments = withoutPrefix.split('.', '-', '_').mapNotNull { part ->
                leadingDigits.find(part)?.value?.toIntOrNull()
            }
            if (segments.isEmpty()) return null
            return ReleaseVersion(segments)
        }
    }
}

/** A single user-facing outcome of a release poll; each warning is presented separately. */
sealed interface AppReleaseWarning {
    val openUrl: String

    /** The fork published a release newer than the running build. */
    data class ForkUpdate(
        val installed: ReleaseVersion,
        val latest: ReleaseVersion,
        override val openUrl: String,
    ) : AppReleaseWarning

    /** Upstream shipped a release the fork has not baked into any of its releases yet. */
    data class UpstreamNotBakedIn(
        val forkLatest: ReleaseVersion,
        val upstreamLatest: ReleaseVersion,
        override val openUrl: String,
    ) : AppReleaseWarning
}

data class AppReleaseCheck(
    val warnings: List<AppReleaseWarning> = emptyList(),
)

/**
 * Polls GitHub for the latest published release of this fork and of upstream Harmonic. The
 * running build learns two independent facts: whether a newer fork release exists, and whether
 * upstream moved ahead of everything the fork has baked in so far. Both are best-effort: any
 * network, rate-limit, or parsing failure simply yields no warnings.
 */
interface AppReleaseChecker {
    suspend fun checkInstalled(installedVersionName: String): AppReleaseCheck
}

class KtorAppReleaseChecker(
    private val client: KtorHttpClient,
) : AppReleaseChecker {
    override suspend fun checkInstalled(installedVersionName: String): AppReleaseCheck {
        val installed = ReleaseVersion.parse(installedVersionName) ?: return AppReleaseCheck()
        val (forkLatest, upstreamLatest) = coroutineScope {
            val fork = async { fetchLatestRelease(FORK_REPOSITORY) }
            val upstream = async { fetchLatestRelease(UPSTREAM_REPOSITORY) }
            fork.await() to upstream.await()
        }
        val warnings = buildList {
            if (forkLatest != null && forkLatest > installed) {
                add(
                    AppReleaseWarning.ForkUpdate(
                        installed = installed,
                        latest = forkLatest,
                        openUrl = FORK_RELEASES_URL,
                    ),
                )
            }
            if (forkLatest != null && upstreamLatest != null && upstreamLatest > forkLatest) {
                add(
                    AppReleaseWarning.UpstreamNotBakedIn(
                        forkLatest = forkLatest,
                        upstreamLatest = upstreamLatest,
                        openUrl = UPSTREAM_COMPARE_URL,
                    ),
                )
            }
        }
        return AppReleaseCheck(warnings)
    }

    /** Returns the latest published release tag, or null when unknown, unpublished, or failing. */
    private suspend fun fetchLatestRelease(repository: String): ReleaseVersion? {
        val request = HttpRequest.Builder()
            .url("https://api.github.com/repos/$repository/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .get()
            .build()
        val response = try {
            client.newBuilder()
                .readTimeoutMillis(TIMEOUT_MILLIS)
                .requestTimeoutMillis(TIMEOUT_MILLIS)
                .build()
                .execute(request, maxBytes = MAX_RELEASE_RESPONSE_BYTES)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return null
        }
        return try {
            if (!response.isSuccessful) return null
            val body = jsonParser.parseToJsonElement(
                response.body.readText(MAX_RELEASE_RESPONSE_BYTES),
            )
            val tag = (body.jsonObject["tag_name"] as? JsonPrimitive)
                ?.takeIf { it.isString }
                ?.content
            tag?.let(ReleaseVersion::parse)
        } catch (_: Exception) {
            null
        } finally {
            response.close()
        }
    }

    private companion object {
        const val FORK_REPOSITORY = "HenryNebula/Harmonic-HN"
        const val UPSTREAM_REPOSITORY = "SimonHalvdansson/Harmonic-HN"
        const val FORK_RELEASES_URL = "https://github.com/$FORK_REPOSITORY/releases"
        const val UPSTREAM_COMPARE_URL =
            "https://github.com/$FORK_REPOSITORY/compare/main...SimonHalvdansson:main"
        val jsonParser = Json { ignoreUnknownKeys = true }
        const val TIMEOUT_MILLIS = 5_000L
        const val MAX_RELEASE_RESPONSE_BYTES = 256 * 1024
    }
}
