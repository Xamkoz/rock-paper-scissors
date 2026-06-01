package com.rpsonline.app.ui.util

import com.rpsonline.app.data.model.MatchStatus
import com.rpsonline.app.data.model.RoundResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoundResolutionPulseNotifierTest {

    @Test
    fun suppressesClockUntilFeedbackMarkedComplete() {
        val notifier = RoundResolutionPulseNotifier()
        val resolved = RoundResult(
            roundNumber = 2,
            player1Choice = "rock",
            player2Choice = "paper",
            winner = "player2",
            resolvedAt = 123L,
        )

        assertTrue(notifier.shouldSuppressClockTickFor(resolved, "match-1"))

        notifier.markFeedbackComplete(resolved, "match-1")

        assertFalse(notifier.shouldSuppressClockTickFor(resolved, "match-1"))
    }

    @Test
    fun enterLiveMatch_clearsHistoricalSuppression() {
        val notifier = RoundResolutionPulseNotifier()
        val resolved = RoundResult(
            roundNumber = 1,
            player1Choice = "rock",
            player2Choice = "scissors",
            winner = "player1",
            resolvedAt = 50L,
        )
        val key = roundResolutionKey(resolved, "match-1")

        notifier.baselineHistoricalRound(resolved, "match-1")
        assertTrue(notifier.shouldSkipFeedback(key))

        val generationBefore = notifier.liveSessionGeneration
        notifier.enterLiveMatch("match-1")
        assertTrue(notifier.isLiveMatch("match-1"))
        assertTrue(notifier.liveSessionGeneration > generationBefore)
        assertFalse(notifier.shouldSkipFeedback(key))
    }

    @Test
    fun forceFeedbackComplete_clearsSuppressAndInFlight() {
        val notifier = RoundResolutionPulseNotifier()
        val resolved = RoundResult(
            roundNumber = 3,
            player1Choice = "paper",
            player2Choice = "scissors",
            winner = "player1",
            resolvedAt = 200L,
        )
        val key = roundResolutionKey(resolved, "match-1")

        notifier.beginFeedback(key)
        assertTrue(notifier.shouldSkipFeedback(key))
        assertTrue(notifier.shouldSuppressClockTickFor(resolved, "match-1"))

        notifier.forceFeedbackComplete(resolved, "match-1")

        assertTrue(notifier.shouldSkipFeedback(key))
        assertFalse(notifier.shouldSuppressClockTickFor(resolved, "match-1"))
    }

    @Test
    fun feedbackComplete_isStableAcrossResolvedAtChanges() {
        val notifier = RoundResolutionPulseNotifier()
        val resolvedEarly = RoundResult(
            roundNumber = 2,
            player1Choice = "rock",
            player2Choice = "paper",
            winner = "player2",
            resolvedAt = 100L,
        )
        val resolvedLate = resolvedEarly.copy(resolvedAt = 999L)

        notifier.markFeedbackComplete(resolvedEarly, "match-1")

        assertFalse(notifier.shouldSuppressClockTickFor(resolvedLate, "match-1"))
        assertTrue(notifier.isFeedbackComplete(resolvedLate, "match-1"))
    }

    @Test
    fun awaitMatchEndResolutionFeedback_unblocksQuicklyWhenFeedbackNeverStarts() = runBlocking {
        val notifier = RoundResolutionPulseNotifier()
        notifier.enterLiveMatch("match-1")
        val resolved = RoundResult(
            roundNumber = 2,
            player1Choice = "rock",
            player2Choice = "paper",
            winner = "player2",
            resolvedAt = 99L,
        )
        val match = com.rpsonline.app.data.model.Match(
            id = "match-1",
            player1 = "p1",
            player2 = "p2",
            status = MatchStatus.COMPLETED,
            rounds = listOf(resolved),
        )

        awaitMatchEndResolutionFeedback(notifier, match)

        assertFalse(notifier.shouldSuppressClockTickFor(resolved, "match-1"))
    }

    @Test
    fun awaitMatchEndResolutionFeedback_timesOutAndUnblocksNavigation() = runBlocking {
        val notifier = RoundResolutionPulseNotifier()
        notifier.enterLiveMatch("match-1")
        val resolved = RoundResult(
            roundNumber = 2,
            player1Choice = "rock",
            player2Choice = "paper",
            winner = "player2",
            resolvedAt = 99L,
        )
        val match = com.rpsonline.app.data.model.Match(
            id = "match-1",
            player1 = "p1",
            player2 = "p2",
            status = MatchStatus.COMPLETED,
            rounds = listOf(resolved),
        )

        awaitMatchEndResolutionFeedback(notifier, match, maxWaitMs = 150)

        assertFalse(notifier.shouldSuppressClockTickFor(resolved, "match-1"))
    }
}
