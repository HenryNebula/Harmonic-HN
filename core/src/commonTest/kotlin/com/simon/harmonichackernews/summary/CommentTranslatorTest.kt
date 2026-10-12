package com.simon.harmonichackernews.summary

import com.simon.harmonichackernews.data.CommentPresentationSnapshot
import com.simon.harmonichackernews.data.CommentSnapshot
import com.simon.harmonichackernews.data.StoryPresentationSnapshot
import com.simon.harmonichackernews.data.StorySnapshot
import com.simon.harmonichackernews.network.CacheServerCheck
import com.simon.harmonichackernews.network.CloudSummaryConfig
import com.simon.harmonichackernews.network.CloudSummaryEvent
import com.simon.harmonichackernews.network.CloudSummaryRepository
import com.simon.harmonichackernews.network.SummaryUseCase
import com.simon.harmonichackernews.network.TranslationCacheClient
import com.simon.harmonichackernews.platform.CredentialIds
import com.simon.harmonichackernews.presentation.PortableCommentItem
import com.simon.harmonichackernews.presentation.StoryListItemSnapshot
import com.simon.harmonichackernews.settings.AiSummarySettingsRepository
import com.simon.harmonichackernews.settings.TestCredentialStore
import com.simon.harmonichackernews.settings.TestKeyValueStore
import com.simon.harmonichackernews.settings.TranslationLanguage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class CommentTranslatorTest {
    private fun story() = StoryListItemSnapshot(
        StorySnapshot(900, title = "Post title", text = "<p>Post body</p>"),
        StoryPresentationSnapshot(),
    )

    private fun comment(
        id: Int,
        parentId: Int = 900,
        text: String = "<p>Comment $id</p>",
    ) = PortableCommentItem(
        CommentSnapshot(
            id = id,
            author = "user$id",
            parentId = parentId,
            text = text,
            expandedAnchorText = text,
        ),
        CommentPresentationSnapshot(),
    )

    private fun repository(answer: (String) -> String = { "<p>Translated</p>" }) = object : CloudSummaryRepository {
        val requests = mutableListOf<String>()
        val configs = mutableListOf<CloudSummaryConfig>()
        override suspend fun fetchModelIds(baseUrl: String, apiKey: String) = emptyList<String>()
        override suspend fun extractMainContent(url: String) = error("Unexpected article extraction")
        override fun summarize(config: CloudSummaryConfig, text: String?) = flow {
            requests += text.orEmpty()
            configs += config
            emit(CloudSummaryEvent.Success(answer(text.orEmpty())))
        }
    }

    private fun settings(
        store: TestKeyValueStore = TestKeyValueStore(),
        language: TranslationLanguage = TranslationLanguage.GERMAN,
        cacheUrl: String = "",
        dispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
    ): AiSummarySettingsRepository = AiSummarySettingsRepository(
        store,
        TestCredentialStore(mapOf(CredentialIds.AI_SUMMARY_API_KEY to "cloud-key")),
        emptyFlow(),
        dispatcher,
    ).also {
        it.setTranslationLanguage(language)
        if (cacheUrl.isNotBlank()) it.setTranslationCacheUrl(cacheUrl)
    }

    @Test
    fun singleTranslationBuildsContextAndUsesConfig() = runTest {
        val repo = repository()
        val parent = comment(800, parentId = 900, text = "<p>Parent &amp; context</p>")
        val child = comment(801, parentId = 800, text = "<p>Child <b>body</b></p>")
        val translator = CommentTranslator(
            scope = this,
            summaries = SummaryUseCase(repo),
            settings = settings(),
            systemLanguage = { "de-DE" },
        )
        translator.translate(child, story(), listOf(parent, child))
        advanceUntilIdle()

        assertEquals(1, repo.requests.size)
        val payload = Json.parseToJsonElement(repo.requests.single()).jsonObject
        assertEquals("Post title", payload["post_title"]!!.jsonPrimitive.content)
        val parents = payload["parents_oldest_first"]!!.jsonArray
        assertEquals(1, parents.size)
        assertEquals("user800", parents.single().jsonObject["author"]!!.jsonPrimitive.content)
        assertEquals("<p>Child <b>body</b></p>", payload["comment_html"]!!.jsonPrimitive.content)
        assertEquals("Post body", payload["post_body"]!!.jsonPrimitive.content)

        val config = repo.configs.single()
        assertFalse(config.streamResponses)
        assertTrue(config.systemPrompt.contains("German"))
        assertEquals(4_000, config.maxOutputTokens)

        val state = translator.state.value
        assertEquals("<p>Translated</p>", state.texts[801])
        assertEquals(801, state.sourceRevisions.keys.single())
    }

    @Test
    fun explicitLanguageWinsOverSystemTag() = runTest {
        val repo = repository()
        val translator = CommentTranslator(
            scope = this,
            summaries = SummaryUseCase(repo),
            settings = settings(language = TranslationLanguage.JAPANESE),
            systemLanguage = { "de-DE" },
        )
        translator.translate(comment(1), story(), listOf(comment(1)))
        advanceUntilIdle()
        assertTrue(repo.configs.single().systemPrompt.contains("Japanese"))
    }

    @Test
    fun systemLanguageResolvesChineseScriptVariants() {
        assertEquals("zh-Hant", TranslationLanguage.resolve(TranslationLanguage.SYSTEM, "zh-TW"))
        assertEquals("zh-Hant", TranslationLanguage.resolve(TranslationLanguage.SYSTEM, "zh-Hant-CN"))
        assertEquals("zh-Hans", TranslationLanguage.resolve(TranslationLanguage.SYSTEM, "zh-SG"))
        assertEquals("zh-Hans", TranslationLanguage.resolve(TranslationLanguage.SYSTEM, "zh"))
        assertEquals("de", TranslationLanguage.resolve(TranslationLanguage.SYSTEM, "de-DE"))
        assertEquals("sv", TranslationLanguage.resolve(TranslationLanguage.SYSTEM, "sv-SE"))
        assertEquals("en", TranslationLanguage.resolve(TranslationLanguage.SYSTEM, ""))
        assertEquals("Simplified Chinese", TranslationLanguage.promptNameFor("zh-Hans"))
        assertEquals("Traditional Chinese", TranslationLanguage.promptNameFor("zh-Hant"))
        assertEquals("sv", TranslationLanguage.promptNameFor("sv"))
    }

    @Test
    fun fenceWrappedAndBlankOutputsAreRejected() = runTest {
        // Fence-wrapped valid HTML is unwrapped and accepted.
        for (answer in listOf("   ", "<p></p>")) {
            val repo = repository(answer = { answer })
            val translator = CommentTranslator(
                scope = this,
                summaries = SummaryUseCase(repo),
                settings = settings(),
            )
            val item = comment(5)
            translator.translate(item, story(), listOf(item))
            advanceUntilIdle()
            assertTrue(5 in translator.state.value.failed, "expected failure for: $answer")
            assertNull(translator.state.value.texts[5])
        }
        val repo = repository(answer = { "```html\n<p>Guter Text</p>\n```" })
        val translator = CommentTranslator(
            scope = this,
            summaries = SummaryUseCase(repo),
            settings = settings(),
        )
        val item = comment(6)
        translator.translate(item, story(), listOf(item))
        advanceUntilIdle()
        assertEquals("<p>Guter Text</p>", translator.state.value.texts[6])
    }

    @Test
    fun sanitizedOutputUnwrapsCodeFences() {
        assertEquals(
            "<p>Guter Text</p>",
            CommentTranslator.sanitizeTranslation("\n```html\n<p>Guter Text</p>\n```\n"),
        )
    }

    @Test
    fun translateAllTranslatesEveryCommentAndReportsFailures() = runTest {
        var calls = 0
        val repo = repository(answer = {
            calls++
            if (it.contains("Comment 2")) error("boom") else "<p>Ok</p>"
        })
        val translator = CommentTranslator(
            scope = this,
            summaries = SummaryUseCase(repo),
            settings = settings(),
        )
        val thread = listOf(comment(1), comment(2), comment(3))
        val messages = mutableListOf<String>()
        val messageJob = launch { translator.messages.collect { messages += it } }
        translator.translateAll(story(), thread)
        advanceUntilIdle()

        val state = translator.state.value
        assertFalse(state.threadRunning)
        assertEquals(setOf(1, 3), state.texts.keys)
        assertEquals(setOf(2), state.failed)
        assertTrue(messages.any { it.startsWith("1 of 3") })
        messageJob.cancel()
    }

    @Test
    fun translateAllSkipsAlreadyTranslatedComments() = runTest {
        val repo = repository()
        val translator = CommentTranslator(
            scope = this,
            summaries = SummaryUseCase(repo),
            settings = settings(),
        )
        val thread = listOf(comment(1), comment(2))
        translator.translateAll(story(), thread)
        advanceUntilIdle()
        assertEquals(2, repo.requests.size)
        repo.requests.clear()
        translator.translateAll(story(), thread)
        advanceUntilIdle()
        assertEquals(0, repo.requests.size)
        assertEquals(2, translator.state.value.texts.size)
    }

    @Test
    fun visibleCommentsAreTranslatedFirst() = runTest {
        val repo = repository()
        val translator = CommentTranslator(
            scope = this,
            summaries = SummaryUseCase(repo),
            settings = settings(),
        )
        val thread = listOf(comment(1), comment(2), comment(3), comment(4))
        translator.updateVisibleCommentIds(setOf(3))
        translator.translateAll(story(), thread)
        advanceUntilIdle()

        assertEquals(4, repo.requests.size)
        val firstHtml = Json.parseToJsonElement(repo.requests.first())
            .jsonObject["comment_html"]!!.jsonPrimitive.content
        assertEquals("<p>Comment 3</p>", firstHtml)
        val secondHtml = Json.parseToJsonElement(repo.requests[1])
            .jsonObject["comment_html"]!!.jsonPrimitive.content
        assertEquals("<p>Comment 1</p>", secondHtml)
    }

    @Test
    fun translateAllCountsProgressOverPendingCommentsOnly() = runTest {
        val repo = repository()
        val translator = CommentTranslator(
            scope = this,
            summaries = SummaryUseCase(repo),
            settings = settings(),
        )
        val thread = listOf(comment(1), comment(2), comment(3))
        translator.translate(comment(1), story(), thread)
        advanceUntilIdle()
        translator.translateAll(story(), thread)
        advanceUntilIdle()

        val state = translator.state.value
        assertFalse(state.threadRunning)
        assertEquals(2, state.threadTotal)
        assertEquals(2, state.threadCompleted)
    }

    @Test
    fun secondTranslateAllSupersedesTheFirst() = runTest {
        val repo = repository(answer = {
            // The first batch is slow; the second invocation must not wait for it.
            if (it.contains("Comment 1")) "<p>First</p>" else "<p>Second</p>"
        })
        val translator = CommentTranslator(
            scope = this,
            summaries = SummaryUseCase(repo),
            settings = settings(),
        )
        val thread = listOf(comment(1), comment(2), comment(3))
        translator.translateAll(story(), thread)
        translator.translateAll(story(), thread)
        advanceUntilIdle()
        assertFalse(translator.state.value.threadRunning)
        // The second batch re-translates everything the first had not yet installed.
        assertTrue(repo.requests.size >= 3)
    }

    @Test
    fun languageChangeDiscardsExistingTranslations() = runTest {
        val store = TestKeyValueStore()
        val settingsRepository = settings(store = store)
        val repo = repository()
        val translator = CommentTranslator(
            scope = this,
            summaries = SummaryUseCase(repo),
            settings = settingsRepository,
        )
        val item = comment(7)
        translator.translate(item, story(), listOf(item))
        advanceUntilIdle()
        assertEquals(1, translator.state.value.texts.size)

        settingsRepository.setTranslationLanguage(TranslationLanguage.FRENCH)
        translator.translate(comment(8, text = "<p>Comment 8</p>"), story(),
            listOf(item, comment(8, text = "<p>Comment 8</p>")))
        advanceUntilIdle()

        val state = translator.state.value
        assertEquals(setOf(8), state.texts.keys)
        assertTrue(repo.configs.last().systemPrompt.contains("French"))
    }

    @Test
    fun cachePrefillsMatchingCommentsAndIsConsultedOnce() = runTest {
        val store = TestKeyValueStore()
        val settingsRepository = settings(store = store, cacheUrl = "http://cache.local")
        val repo = repository()
        val cacheLookups = mutableListOf<Pair<Int, String>>()
        val cache = object : TranslationCacheClient {
            override suspend fun fetchTranslations(baseUrl: String, storyId: Int, language: String): Map<Int, String> {
                cacheLookups += storyId to language
                return mapOf(1 to "<p>Aus dem Cache 1</p>", 2 to "<p>Aus dem Cache 2</p>", 99 to "<p>Unrelated</p>")
            }

            override suspend fun checkServer(baseUrl: String) = CacheServerCheck.Reachable
        }
        val translator = CommentTranslator(
            scope = this,
            summaries = SummaryUseCase(repo),
            settings = settingsRepository,
            cache = cache,
        )
        val thread = listOf(comment(1), comment(2), comment(3))
        translator.translateAll(story(), thread)
        advanceUntilIdle()

        val state = translator.state.value
        assertEquals("<p>Aus dem Cache 1</p>", state.texts[1])
        assertEquals("<p>Aus dem Cache 2</p>", state.texts[2])
        assertNull(state.texts[99])
        // Comment 3 was not cached and went through the cloud endpoint.
        assertEquals("<p>Translated</p>", state.texts[3])
        assertEquals(listOf(900 to "de"), cacheLookups)
        assertEquals(1, repo.requests.size)

        // A second pass consults neither the cache nor the endpoint again.
        repo.requests.clear()
        translator.translateAll(story(), thread)
        advanceUntilIdle()
        assertEquals(0, repo.requests.size)
        assertEquals(1, cacheLookups.size)
    }

    @Test
    fun cacheFailureFallsBackToDirectRequests() = runTest {
        val settingsRepository = settings(cacheUrl = "http://cache.local")
        val repo = repository()
        val cache = object : TranslationCacheClient {
            override suspend fun fetchTranslations(baseUrl: String, storyId: Int, language: String): Map<Int, String>? = null

            override suspend fun checkServer(baseUrl: String) = CacheServerCheck.Reachable
        }
        val translator = CommentTranslator(
            scope = this,
            summaries = SummaryUseCase(repo),
            settings = settingsRepository,
            cache = cache,
        )
        val thread = listOf(comment(1), comment(2))
        translator.translateAll(story(), thread)
        advanceUntilIdle()
        assertEquals(setOf(1, 2), translator.state.value.texts.keys)
        assertEquals(2, repo.requests.size)
    }

    @Test
    fun mockAnswersBypassTheRepository() = runTest {
        val repo = repository()
        val translator = CommentTranslator(
            scope = this,
            summaries = SummaryUseCase(repo),
            settings = settings(),
            mockAnswers = { true },
        )
        val item = comment(4)
        translator.translate(item, story(), listOf(item))
        advanceUntilIdle()
        assertEquals(0, repo.requests.size)
        assertEquals("<p>[Sample German translation for testing.]</p>", translator.state.value.texts[4])
    }

    @Test
    fun toggleAndClearResetViewingState() = runTest {
        val repo = repository()
        val translator = CommentTranslator(
            scope = this,
            summaries = SummaryUseCase(repo),
            settings = settings(),
        )
        val item = comment(6)
        translator.translate(item, story(), listOf(item))
        advanceUntilIdle()
        translator.toggle(6)
        assertTrue(6 in translator.state.value.showingOriginal)
        translator.toggle(6)
        assertFalse(6 in translator.state.value.showingOriginal)
        translator.clear()
        assertTrue(translator.state.value.texts.isEmpty())
        assertFalse(translator.state.value.hasTranslations)
    }
}
