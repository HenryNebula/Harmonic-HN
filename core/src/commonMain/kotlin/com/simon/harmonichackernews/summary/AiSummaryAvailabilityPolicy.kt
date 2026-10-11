package com.simon.harmonichackernews.summary

object AiSummaryAvailabilityPolicy {
    const val MODE_LOCAL = "local"

    fun canProvideSummary(
        explicitlyEnabled: Boolean?,
        mode: String,
        localAvailable: Boolean,
        cloudApiKeyAvailable: Boolean,
    ): Boolean {
        if (explicitlyEnabled == false) return false
        return if (mode == MODE_LOCAL) localAvailable else cloudApiKeyAvailable
    }

    fun isEnabled(
        explicitlyEnabled: Boolean?,
        localAvailable: Boolean,
        cloudApiKeyAvailable: Boolean,
    ): Boolean = explicitlyEnabled ?: (localAvailable || cloudApiKeyAvailable)

    /**
     * Comment translation runs on the cloud chat endpoint only: local summary engines are tuned
     * for summarization and would double the test surface for unusable quality in v1.
     */
    fun canProvideTranslation(
        explicitlyEnabled: Boolean?,
        cloudApiKeyAvailable: Boolean,
    ): Boolean = explicitlyEnabled != false && cloudApiKeyAvailable

    fun shouldAutomaticallySummarize(
        automaticSummariesEnabled: Boolean,
        requestAlreadyMade: Boolean,
        isArticle: Boolean,
        hasSuccessfulSummary: Boolean,
        canProvideSummary: Boolean,
    ): Boolean = automaticSummariesEnabled && !requestAlreadyMade && isArticle &&
        !hasSuccessfulSummary && canProvideSummary
}
