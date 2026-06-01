package com.rpsonline.app.ui.components

import com.rpsonline.app.data.monitoring.NetworkDataActivityKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkBurstSegmentsTest {

    private fun burstSlotSegmentPairs(kind: NetworkDataActivityKind): Set<Pair<Int, Char>> =
        TopBarNetworkActivitySlotIndices.flatMap { slot ->
            networkActivitySlotHalfLitSegments(kind, slot).map { slot to it }
        }.toSet()

    @Test
    fun eachKind_hasNonEmptyPatternOnDigitsFiveSixSeven() {
        NetworkDataActivityKind.entries.forEach { kind ->
            assertTrue(
                "$kind should light at least one network slot",
                TopBarNetworkActivitySlotIndices.any { slot ->
                    networkActivitySlotHalfLitSegments(kind, slot).isNotEmpty()
                },
            )
            TopBarNetworkActivitySlotIndices.forEach { slot ->
                val pattern = networkActivitySlotHalfLitSegments(kind, slot)
                assertTrue(pattern.all { it in 'a'..'g' })
            }
        }
    }

    @Test
    fun spinnerDigit_neverUsesTopBottomOrMiddleBars() {
        NetworkDataActivityKind.entries.forEach { kind ->
            val center = networkActivitySlotHalfLitSegments(kind, 5)
            assertTrue(
                "$kind spinner must not use a/d/g: $center",
                center.none { it in setOf('a', 'd', 'g') },
            )
            assertTrue(center.all { it in setOf('b', 'c', 'e', 'f') })
        }
    }

    @Test
    fun spinnerSideVerts_areAlwaysPairedOnMiddleDigit() {
        assertEquals(setOf('f', 'e'), ensureSpinnerSideVertsTogether(setOf('f')))
        assertEquals(setOf('b', 'c'), ensureSpinnerSideVertsTogether(setOf('c')))
        NetworkDataActivityKind.entries.forEach { kind ->
            val center = networkActivitySlotHalfLitSegments(kind, 5)
            if (center.any { it in setOf('f', 'e') }) {
                assertTrue('f' in center && 'e' in center)
            }
            if (center.any { it in setOf('b', 'c') }) {
                assertTrue('b' in center && 'c' in center)
            }
        }
    }

    @Test
    fun eachKind_hasSymmetricSignatureOnDigitsFiveSixSeven() {
        NetworkDataActivityKind.entries.forEach { kind ->
            val left = networkActivitySlotHalfLitSegments(kind, 4)
            val right = networkActivitySlotHalfLitSegments(kind, 6)
            assertEquals(
                "outer digits should mirror for $kind",
                right,
                left.map { segment ->
                    when (segment) {
                        'f' -> 'b'
                        'e' -> 'c'
                        else -> segment
                    }
                }.toSet(),
            )
        }
    }

    @Test
    fun patterns_collectivelyCoverFullBurstUniverse() {
        val union = NetworkDataActivityKind.entries
            .flatMap(::burstSlotSegmentPairs)
            .toSet()
        val expected = setOf(
            4 to 'a', 4 to 'd', 4 to 'e', 4 to 'f', 4 to 'g',
            5 to 'b', 5 to 'c', 5 to 'e', 5 to 'f',
            6 to 'a', 6 to 'b', 6 to 'c', 6 to 'd', 6 to 'g',
        )
        assertEquals(expected, union)
    }

    @Test
    fun patterns_pairwiseOverlapIsAtMostTwoSlotSegments() {
        val kinds = NetworkDataActivityKind.entries
        val patterns = kinds.associateWith(::burstSlotSegmentPairs)
        for (i in kinds.indices) {
            for (j in i + 1 until kinds.size) {
                val overlap = patterns[kinds[i]]!! intersect patterns[kinds[j]]!!
                assertTrue(
                    "${kinds[i]} ∩ ${kinds[j]} share ${overlap.size}: $overlap",
                    overlap.size <= 2,
                )
            }
        }
    }

    @Test
    fun eachKind_hasDistinctOuterAndSpinnerSignature() {
        val signatures = NetworkDataActivityKind.entries.map { kind ->
            networkActivitySlotHalfLitSegments(kind, 4) to
                networkActivitySlotHalfLitSegments(kind, 5)
        }
        assertEquals(NetworkDataActivityKind.entries.size, signatures.toSet().size)
    }

    @Test
    fun bridgeOverlay_unionsActiveKindsPerSlot() {
        val active = setOf(
            NetworkDataActivityKind.Queue,
            NetworkDataActivityKind.Match,
        )
        assertEquals(
            networkActivitySlotHalfLitSegments(NetworkDataActivityKind.Queue, 5) +
                networkActivitySlotHalfLitSegments(NetworkDataActivityKind.Match, 5),
            bridgeSlotNetworkHalfLitSegments(5, active, connectionProbeActive = false),
        )
    }

    @Test
    fun connectionProbe_fillsDigitsFiveSixSevenWhenQueueInactive() {
        TopBarNetworkActivitySlotIndices.forEach { slot ->
            assertEquals(
                networkActivitySlotHalfLitSegments(NetworkDataActivityKind.Connection, slot),
                bridgeSlotNetworkHalfLitSegments(slot, emptySet(), connectionProbeActive = true),
            )
        }
    }

    @Test
    fun queueIo_overridesConnectionProbeOnSharedSlots() {
        val active = setOf(NetworkDataActivityKind.Queue)
        assertNotEquals(
            bridgeSlotNetworkHalfLitSegments(4, active, connectionProbeActive = true),
            bridgeSlotNetworkHalfLitSegments(4, emptySet(), connectionProbeActive = true),
        )
    }

    @Test
    fun timerSlots_haveNoNetworkOverlay() {
        assertEquals(
            emptySet<Char>(),
            bridgeSlotNetworkHalfLitSegments(
                TopBarTimerDigitsSlotStart,
                NetworkDataActivityKind.entries.toSet(),
                connectionProbeActive = true,
            ),
        )
    }
}
