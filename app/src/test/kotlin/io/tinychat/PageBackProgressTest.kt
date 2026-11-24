package io.tinychat

import androidx.navigationevent.NavigationEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class PageBackProgressTest {
    @Test
    fun leftEdgeUsesTouchDistanceAndTracksBackwards() {
        val positions = listOf(12f, 212f, 412f, 212f, 12f)
        val progress = positions.map { touchX ->
            pageBackProgress(NavigationEvent(NavigationEvent.EDGE_LEFT, 0.9f, touchX), 12f, 1_000)
        }
        assertEquals(listOf(0f, 0.2f, 0.4f, 0.2f, 0f), progress)
    }

    @Test
    fun rightEdgeUsesTheSameDistanceInTheOppositeDirection() {
        val positions = listOf(988f, 788f, 588f, 788f, 988f)
        val progress = positions.map { touchX ->
            pageBackProgress(NavigationEvent(NavigationEvent.EDGE_RIGHT, 0.9f, touchX), 988f, 1_000)
        }
        assertEquals(listOf(0f, 0.2f, 0.4f, 0.2f, 0f), progress)
    }

    @Test
    fun progressIsClampedToThePage() {
        val start = 12f
        val edge = NavigationEvent.EDGE_LEFT

        assertEquals(0f, pageBackProgress(NavigationEvent(edge, 0.9f, 5f), start, 1_000), 0f)
        assertEquals(1f, pageBackProgress(NavigationEvent(edge, 0.9f, 1_012f), start, 1_000), 0f)
        assertEquals(1f, pageBackProgress(NavigationEvent(edge, 0.9f, 1_400f), start, 1_000), 0f)
    }

    @Test
    fun systemProgressIsUsedWhenTouchDistanceIsUnavailable() {
        val edge = NavigationEvent.EDGE_LEFT
        val event = NavigationEvent(edge, 0.3f, 212f)

        assertEquals(0.3f, pageBackProgress(event, 12f, 0), 0f)
        assertEquals(0.3f, pageBackProgress(event, Float.NaN, 1_000), 0f)
        assertEquals(0.3f, pageBackProgress(NavigationEvent(edge, 0.3f, Float.POSITIVE_INFINITY), 12f, 1_000), 0f)
        assertEquals(0.3f, pageBackProgress(NavigationEvent(progress = 0.3f), 12f, 1_000), 0f)
        assertEquals(0f, pageBackProgress(NavigationEvent(progress = Float.NaN), 12f, 1_000), 0f)
    }
}
