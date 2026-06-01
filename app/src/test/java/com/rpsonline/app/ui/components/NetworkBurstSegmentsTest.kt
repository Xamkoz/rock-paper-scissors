package com.rpsonline.app.ui.components

import com.rpsonline.app.data.monitoring.NetworkDataActivityKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkBurstSegmentsTest {

    @Test
    fun eachKind_mapsToExpectedPattern() {
        assertEquals(setOf('a'), networkActivityBurstSegments(NetworkDataActivityKind.Connection))
        assertEquals(setOf('d'), networkActivityBurstSegments(NetworkDataActivityKind.Match))
        assertEquals(setOf('g'), networkActivityBurstSegments(NetworkDataActivityKind.Presence))
        assertEquals(NetworkBurstVerticalSegments, networkActivityBurstSegments(NetworkDataActivityKind.Queue))
    }

    @Test
    fun pattern_isIdenticalOnEveryBurstDigit() {
        NetworkDataActivityKind.entries.forEach { kind ->
            val reference = networkActivityBurstSegments(kind)
            TopBarNetworkBurstSlotIndices.forEach { slot ->
                assertEquals(
                    "slot $slot should match per-digit pattern for $kind",
                    reference,
                    networkActivitySlotHalfLitSegments(kind, slot),
                )
            }
        }
    }

    @Test
    fun patterns_haveNoSharedSegmentsBetweenKinds() {
        val kinds = NetworkDataActivityKind.entries
        val patterns = kinds.associateWith(::networkActivityBurstSegments)
        for (i in kinds.indices) {
            for (j in i + 1 until kinds.size) {
                val overlap = patterns[kinds[i]]!! intersect patterns[kinds[j]]!!
                assertEquals(
                    "${kinds[i]} ∩ ${kinds[j]} should not overlap: $overlap",
                    emptySet<Char>(),
                    overlap,
                )
            }
        }
    }

    @Test
    fun queue_usesAllVerticalBars() {
        assertEquals(NetworkBurstVerticalSegments, networkActivityBurstSegments(NetworkDataActivityKind.Queue))
        assertTrue(networkActivityBurstSegments(NetworkDataActivityKind.Queue).none { it in NetworkBurstHorizontalSegments })
    }

    @Test
    fun bridgeOverlay_unionsNonOverlappingSegmentsOnly() {
        val active = setOf(
            NetworkDataActivityKind.Queue,
            NetworkDataActivityKind.Presence,
        )
        assertEquals(
            setOf('b', 'c', 'e', 'f', 'g'),
            bridgeSlotNetworkHalfLitSegments(0, active, connectionProbeActive = false),
        )
    }

    @Test
    fun bridgeOverlay_suppressesSegmentsSharedByMultipleKinds() {
        val active = setOf(
            NetworkDataActivityKind.Connection,
            NetworkDataActivityKind.Match,
        )
        assertEquals(
            setOf('a', 'd'),
            bridgeSlotNetworkHalfLitSegments(0, active, connectionProbeActive = false),
        )
    }

    @Test
    fun connectionProbe_fillsBurstDigitSlotsWhenQueueInactive() {
        TopBarNetworkBurstSlotIndices.forEach { slot ->
            assertEquals(
                networkActivityBurstSegments(NetworkDataActivityKind.Connection),
                bridgeSlotNetworkHalfLitSegments(slot, emptySet(), connectionProbeActive = true),
            )
        }
    }

    @Test
    fun queueIo_overridesConnectionProbeOnSharedSlots() {
        val active = setOf(NetworkDataActivityKind.Queue)
        assertNotEquals(
            bridgeSlotNetworkHalfLitSegments(0, active, connectionProbeActive = true),
            bridgeSlotNetworkHalfLitSegments(0, emptySet(), connectionProbeActive = true),
        )
    }

    @Test
    fun colonSlot_hasNoNetworkOverlay() {
        assertEquals(
            emptySet<Char>(),
            bridgeSlotNetworkHalfLitSegments(
                TopBarColonSlotIndex,
                NetworkDataActivityKind.entries.toSet(),
                connectionProbeActive = true,
            ),
        )
    }

    @Test
    fun spinnerDigitSlot_hasNoBurstOverlay() {
        NetworkDataActivityKind.entries.forEach { kind ->
            assertEquals(
                emptySet<Char>(),
                bridgeSlotNetworkHalfLitSegments(
                    TopBarSpinnerDigitSlotIndex,
                    setOf(kind),
                    connectionProbeActive = false,
                ),
            )
        }
        assertEquals(
            emptySet<Char>(),
            bridgeSlotNetworkHalfLitSegments(
                TopBarSpinnerDigitSlotIndex,
                emptySet(),
                connectionProbeActive = true,
            ),
        )
    }
}
