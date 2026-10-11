package com.simon.harmonichackernews.ui.content

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CommentTranslationIndicatorTest {
    @Test
    fun idleCommentsShowNoIndicator() {
        assertNull(commentTranslationIndicator(
            translated = false, loading = false, failed = false, showingOriginal = false,
        ))
    }

    @Test
    fun loadingTakesPrecedence() {
        assertEquals(
            CommentTranslationIndicator.LOADING,
            commentTranslationIndicator(
                translated = true, loading = true, failed = true, showingOriginal = true,
            ),
        )
    }

    @Test
    fun failureOffersRetry() {
        assertEquals(
            CommentTranslationIndicator.FAILED,
            commentTranslationIndicator(
                translated = false, loading = false, failed = true, showingOriginal = false,
            ),
        )
    }

    @Test
    fun installedTranslationTogglesBetweenStates() {
        assertEquals(
            CommentTranslationIndicator.SHOWING_TRANSLATION,
            commentTranslationIndicator(
                translated = true, loading = false, failed = false, showingOriginal = false,
            ),
        )
        assertEquals(
            CommentTranslationIndicator.SHOWING_ORIGINAL,
            commentTranslationIndicator(
                translated = true, loading = false, failed = false, showingOriginal = true,
            ),
        )
    }
}
