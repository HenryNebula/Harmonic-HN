package com.simon.harmonichackernews.ui.content

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CommentTranslationLabelTest {
    @Test
    fun idleCommentsShowNoLabel() {
        assertNull(commentTranslationLabel(
            translated = false, loading = false, failed = false, showingOriginal = false,
        ))
    }

    @Test
    fun loadingTakesPrecedence() {
        assertEquals(
            "Translating…",
            commentTranslationLabel(
                translated = true, loading = true, failed = true, showingOriginal = true,
            ),
        )
    }

    @Test
    fun failureOffersRetry() {
        assertEquals(
            "Translation failed · Retry",
            commentTranslationLabel(
                translated = false, loading = false, failed = true, showingOriginal = false,
            ),
        )
    }

    @Test
    fun translatedCommentsOfferOriginalAndBack() {
        assertEquals(
            "Show original",
            commentTranslationLabel(
                translated = true, loading = false, failed = false, showingOriginal = false,
            ),
        )
        assertEquals(
            "Show translation",
            commentTranslationLabel(
                translated = true, loading = false, failed = false, showingOriginal = true,
            ),
        )
    }
}
