package io.tinychat.feature.chat

import java.util.PriorityQueue

private const val RECENT_PHOTO_LIMIT = 24

/** 时间戳超出「现在 + 这个容差」就当成设备给的脏值，不参与排序 */
private const val FUTURE_TOLERANCE_MS = 24L * 60 * 60 * 1_000

internal data class RecentPhoto(
    val id: Long,
    val takenAtMillis: Long,
    val modifiedAtSeconds: Long,
    val addedAtSeconds: Long,
) {
    /**
     * 这条照片的“新鲜度”，取三个时间戳里**最大且可信**的那个。
     *
     * 以前是「拍摄时间非 0 就用它、否则依次降级」。两个坑：
     * 1. 有些机型/导入工具会把 `DATE_TAKEN` 写成未来值（时区或单位错），它就会永远排在第一位；
     * 2. 刚拍/刚下载的照片，`DATE_ADDED` 往往比 `DATE_TAKEN` 更贴近“我刚刚才看到它”。
     * 取最大值 + 丢掉未来值，两种情况下都能把最近用到的图顶上来。
     */
    fun displayTimeMillis(nowMillis: Long): Long {
        val ceiling = nowMillis + FUTURE_TOLERANCE_MS
        fun sane(value: Long) = value.takeIf { it in 1..ceiling } ?: 0L
        return maxOf(
            sane(takenAtMillis),
            sane(modifiedAtSeconds * 1_000),
            sane(addedAtSeconds * 1_000),
        )
    }
}

internal fun newestPhotoIds(photos: Sequence<RecentPhoto>, nowMillis: Long = System.currentTimeMillis()): List<Long> {
    val byDate = compareBy<RecentPhoto>({ it.displayTimeMillis(nowMillis) }, { it.id })
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
