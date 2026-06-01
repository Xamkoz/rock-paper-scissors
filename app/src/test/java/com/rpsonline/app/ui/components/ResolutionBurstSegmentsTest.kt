package com.rpsonline.app.ui.components

import com.rpsonline.app.data.model.Move
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResolutionBurstSegmentsTest {

    @Test
    fun eachMove_hasDistinctFillSequence() {
        val rock = resolutionBurstFillSequence(Move.ROCK)
        val paper = resolutionBurstFillSequence(Move.PAPER)
        val scissors = resolutionBurstFillSequence(Move.SCISSORS)

        assertNotEquals(rock, paper)
        assertNotEquals(paper, scissors)
        assertNotEquals(rock, scissors)
    }

    @Test
    fun eachStep_isCumulativeSuperset() {
        listOf(Move.ROCK, Move.PAPER, Move.SCISSORS).forEach { move ->
            val sequence = resolutionBurstFillSequence(move)
            for (index in 1 until sequence.size) {
                assertTrue(
                    "Step $index for $move should include prior segments",
                    sequence[index].containsAll(sequence[index - 1]),
                )
            }
        }
    }

    @Test
    fun peakLightsAllSegments_forEveryMove() {
        listOf(Move.ROCK, Move.PAPER, Move.SCISSORS).forEach { move ->
            assertEquals(
                setOf('a', 'b', 'c', 'd', 'e', 'f', 'g'),
                resolutionBurstSegmentsAtProgress(move, 1f),
            )
            assertEquals(
                setOf('a', 'b', 'c', 'd', 'e', 'f', 'g'),
                resolutionBurstFillSequence(move).last(),
            )
        }
    }

    @Test
    fun fillProgress_reachesFullDisplayBeforeFade() {
        listOf(Move.ROCK, Move.PAPER, Move.SCISSORS).forEach { move ->
            assertEquals(
                setOf('a', 'b', 'c', 'd', 'e', 'f', 'g'),
                resolutionBurstSegmentsAtProgress(move, 0.99f),
            )
        }
    }

    @Test
    fun burstExcludesProtectedFullLitSegments() {
        val protected = setOf('a', 'b', 'g')
        val burst = resolutionBurstSegmentsExcluding(Move.ROCK, 1f, protected)
        assertEquals(setOf('c', 'd', 'e', 'f'), burst)
    }

    @Test
    fun eachMove_hasDistinctSlotActivationOrder() {
        val rock = resolutionBurstSlotActivationOrder(Move.ROCK)
        val paper = resolutionBurstSlotActivationOrder(Move.PAPER)
        val scissors = resolutionBurstSlotActivationOrder(Move.SCISSORS)

        assertEquals(TopBarSegmentedSlotCount, rock.size)
        assertEquals(TopBarSegmentedSlotCount, paper.size)
        assertEquals(TopBarSegmentedSlotCount, scissors.size)
        assertNotEquals(rock, paper)
        assertNotEquals(paper, scissors)
        assertNotEquals(rock, scissors)
    }

    @Test
    fun rockWave_propagatesLeftToRight() {
        // Mid-wave sample: must stay below fill-complete (240/520) so slots differ.
        val midWaveFill = 0.07f
        val early = resolutionBurstSlotFillProgress(midWaveFill, slotIndex = 0, move = Move.ROCK)
        val late = resolutionBurstSlotFillProgress(midWaveFill, slotIndex = 11, move = Move.ROCK)
        assertTrue(early > late)
    }

    @Test
    fun scissorsWave_propagatesFromBothEdges() {
        val midWaveFill = 0.07f
        val edge = resolutionBurstSlotFillProgress(midWaveFill, slotIndex = 0, move = Move.SCISSORS)
        val center = resolutionBurstSlotFillProgress(midWaveFill, slotIndex = 5, move = Move.SCISSORS)
        assertTrue(edge > center)
    }

    @Test
    fun allSlotsPeakWhenGlobalFillCompletes() {
        listOf(Move.ROCK, Move.PAPER, Move.SCISSORS).forEach { move ->
            for (slot in 0 until TopBarSegmentedSlotCount) {
                assertEquals(
                    1f,
                    resolutionBurstSlotFillProgress(0.85f, slot, move),
                    0.001f,
                )
            }
        }
    }
}
