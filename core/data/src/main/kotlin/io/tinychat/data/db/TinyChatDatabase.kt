package io.tinychat.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/**
 * 数据库 v1。
 *
 * `exportSchema = true` + `ksp { arg("room.schemaLocation", ...) }`：
 * `core/data/schemas/io.tinychat.data.db.TinyChatDatabase/1.json` 会随构建生成并**入库**，
 * 迁移测试与 CI 校验都靠它。
 */
@Database(
    entities = [
        SessionEntity::class,
        MessageEntity::class,
        FragmentEntity::class,
        AttachmentEntity::class,
        PromptPresetEntity::class,
        ProviderEntity::class,
    ],
    version = TinyChatDatabase.VERSION,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class TinyChatDatabase : RoomDatabase() {

    abstract fun sessionDao(): SessionDao

    abstract fun messageDao(): MessageDao

    abstract fun fragmentDao(): FragmentDao

    abstract fun attachmentDao(): AttachmentDao

    abstract fun promptPresetDao(): PromptPresetDao

    abstract fun providerDao(): ProviderDao

    companion object {
        const val NAME = "tinychat.db"

        /** 每次改表结构必须 +1 并在 [TinyChatMigrations] 里补迁移 */
        const val VERSION = 6
    }
}
