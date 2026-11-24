package io.tinychat.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class RecentPhotoOrderTest {
    @Test
    fun `rank all visible images before limiting the results`() {
        val oldImages = (1L..30L).map { id -> RecentPhoto(id, id * 1_000, 0, 100) }
        val newerCapture = RecentPhoto(31, 200_000, 0, 90)
        val recentlyImportedOldCapture = RecentPhoto(32, 500, 400, 400)

        val result = newestPhotoIds((oldImages + newerCapture + recentlyImportedOldCapture).asSequence())

        assertEquals(listOf(31L) + (30L downTo 8L).toList(), result)
    }

    @Test
    fun `use file modification then indexing time for images without capture time`() {
        val photos = sequenceOf(
            RecentPhoto(1, 15_500, 100, 100),
            RecentPhoto(2, 0, 15, 100),
            RecentPhoto(3, -1, 0, 16),
            RecentPhoto(4, 0, 0, 16),
            RecentPhoto(5, 0, -1, -1),
        )

        assertEquals(listOf(4L, 3L, 1L, 2L, 5L), newestPhotoIds(photos))
    }
}
