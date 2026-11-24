package io.tinychat.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class RecentPhotoOrderTest {
    private val now = 1_000_000L

    @Test
    fun `rank all visible images before limiting the results`() {
        // 30 张老照片：拍摄时间递增，但入库时间都一样（看图库里的“新鲜度”时会被入库时间压平）
        val oldImages = (1L..30L).map { id -> RecentPhoto(id, id * 1_000, 0, 100) }
        // 刚拍的：拍摄时间最新
        val newerCapture = RecentPhoto(31, 200_000, 0, 90)
        // 老照片刚被导入：拍摄时间很早，但入库时间最新
        val recentlyImportedOldCapture = RecentPhoto(32, 500, 400, 400)

        val result = newestPhotoIds((oldImages + newerCapture + recentlyImportedOldCapture).asSequence(), now)

        // 最新导入的排第一，其次是刚拍的，然后按 id 倒序补满 24 张
        assertEquals(listOf(32L, 31L) + (30L downTo 9L).toList(), result)
    }

    @Test
    fun `pick the largest plausible timestamp of the three`() {
        val photos = sequenceOf(
            RecentPhoto(1, 15_500, 100, 100),
            RecentPhoto(2, 0, 15, 100),
            RecentPhoto(3, -1, 0, 16),
            RecentPhoto(4, 0, 0, 16),
            RecentPhoto(5, 0, -1, -1),
        )

        assertEquals(listOf(2L, 1L, 4L, 3L, 5L), newestPhotoIds(photos, now))
    }

    @Test
    fun `ignore capture times from the future`() {
        // 有些机型/导入工具会把 DATE_TAKEN 写成未来值，不能让这种图永远霸占第一位
        val photos = sequenceOf(
            RecentPhoto(1, 9_000_000_000_000L, 0, 0),
            RecentPhoto(2, 500_000, 0, 0),
        )

        assertEquals(listOf(2L, 1L), newestPhotoIds(photos, now))
    }
}
