package com.simon.harmonichackernews.ui.comments

import com.simon.harmonichackernews.presentation.CommentsState
import com.simon.harmonichackernews.presentation.PortableCommentItem
import com.simon.harmonichackernews.presentation.StoryLoadFailure
import com.simon.harmonichackernews.summary.CommentTranslator

data class CommentsPlatformPresentation(
    val adBlockActive: Boolean,
    val readerModeAvailable: Boolean,
    val readerModeEnabled: Boolean,
    val showSheetControls: Boolean = true,
    val topInsetPx: Int,
    val contentInsetLeftPx: Int,
    val contentInsetRightPx: Int,
    val integratedWebViewAvailable: Boolean = true,
)

/** Builds the portable comments rendering snapshot from the common feature state. */
object CommentsScreenStateFactory {
    fun create(
        feature: CommentsState,
        platform: CommentsPlatformPresentation,
    ): CommentsScreenState? {
        val story = feature.story ?: return null
        val settings = feature.settings ?: return null
        val state = feature.presenter
        val thread = state.thread
        return CommentsScreenState(
            story = story,
            accountUser = feature.accountUser,
            comments = thread.filteredComments,
            displaySettings = settings.displaySettings,
            commentsLoaded = state.loaded,
            showingCached = state.showingCached,
            initialThreadCached = feature.initialThreadCached,
            commentsRefreshInProgress = state.refreshing,
            loadingFailed = state.failure != null,
            loadingFailedServerError = state.failure ==
                StoryLoadFailure.NOT_FOUND,
            usingOfficialApiFallback = state.usingOfficialApiFallback,
            showRefreshPrompt = state.showRefreshPrompt,
            lastRefreshed = state.lastLoadedMillis,
            opThreadFilterEnabled = thread.opThreadFilterEnabled,
            hasCommentsByOp = thread.hasCommentsByOp,
            adBlockActive = platform.adBlockActive,
            integratedWebView = settings.integratedWebView && platform.integratedWebViewAvailable,
            readerModeAvailable = platform.readerModeAvailable,
            readerModeEnabled = platform.readerModeEnabled,
            showSheetControls = platform.showSheetControls,
            currentSorting = thread.sorting,
            topInsetPx = platform.topInsetPx,
            contentInsetLeftPx = platform.contentInsetLeftPx,
            contentInsetRightPx = platform.contentInsetRightPx,
            storyVoteLoading = state.storyVoteLoading,
            storyFavoriteLoading = state.storyFavoriteLoading,
            pollVoteInFlightOptionId = state.pollVoteInFlightOptionId,
            storySummaryLoading = feature.summaryLoading,
            summaryDiagnostics = feature.summaryDiagnostics,
            headerPreviewResource = feature.headerPreviewResource,
            commentFavoriteLoadingId = state.commentFavoriteLoadingId,
            commentVoteLoadingId = state.commentVoteLoadingId,
            commentVoteLoadingAction = state.commentVoteLoadingAction,
            downvotedCommentIds = state.downvotedCommentIds,
            searchQuery = thread.searchQuery,
            searchPreparing = thread.searchPreparing,
            searchResults = thread.searchResults,
            visibleComments = thread.visibleComments,
            translation = filteredTranslation(feature.translation, thread.allComments),
        )
    }

    /**
     * Drops translations whose source comment changed after they were produced, so edited or
     * reloaded comments fall back to their original text instead of a stale translation.
     */
    private fun filteredTranslation(
        translation: CommentTranslator.State,
        comments: List<PortableCommentItem>,
    ): CommentTranslator.State {
        if (translation.texts.isEmpty()) return translation
        val revisions = translation.sourceRevisions
        if (revisions.isEmpty()) return translation
        val current = comments.associate { item ->
            item.id to (item.expandedAnchorText ?: item.text.orEmpty()).hashCode()
        }
        val validIds = translation.texts.keys.filter { id -> revisions[id] == current[id] }
        if (validIds.size == translation.texts.size) return translation
        val validIdSet = validIds.toSet()
        return translation.copy(
            texts = translation.texts.filterKeys { it in validIdSet },
            sourceRevisions = revisions.filterKeys { it in validIdSet },
            showingOriginal = translation.showingOriginal.filter { it in validIdSet },
        )
    }
}
