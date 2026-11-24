package io.tinychat.feature.chat

import java.util.PriorityQueue

private const val RECENT_PHOTO_LIMIT = 24

internal data class RecentPhoto(
    val id: Long,
    val takenAtMillis: Long,
    val modifiedAtSeconds: Long,
    val addedAtSeconds: Long,
) {
    val displayTimeMillis = when {
        takenAtMillis > 0 -> takenAtMillis
        modifiedAtSeconds > 0 -> modifiedAtSeconds * 1_000
        addedAtSeconds > 0 -> addedAtSeconds * 1_000
        else -> 0L
    }
}

internal fun newestPhotoIds(photos: Sequence<RecentPhoto>): List<Long> {
    val byDate = compareBy(RecentPhoto::displayTimeMillis, RecentPhoto::id)
    val newest = PriorityQueue(RECENT_PHOTO_LIMIT, byDate)

    // 在整个系统相册的元数据中找最近照片，不依赖设备的默认排序或提前截断查询。
    for (photo in photos) {
        if (newest.size == RECENT_PHOTO_LIMIT) {
            if (byDate.compare(photo, newest.element()) <= 0) continue
            newest.remove()
        }
        newest.add(photo)
    }
    return newest.sortedWith(byDate.reversed()).map(RecentPhoto::id)
}
