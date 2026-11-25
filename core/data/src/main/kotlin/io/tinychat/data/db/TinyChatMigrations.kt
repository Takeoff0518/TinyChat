package io.tinychat.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 迁移登记处。**每次改表结构必须**：① `TinyChatDatabase.VERSION` +1；
 * ② 在这里加一条 `Migration(n, n+1)`；③ 提交新生成的 schema JSON（M1-1 验收项）。
 *
 * 故意不在任何地方用 `fallbackToDestructiveMigration()`：
 * 聊天记录是用户资产，宁可启动时报错也不能悄悄清库。
 */
object TinyChatMigrations {

    /**
     * v1 → v2：消息补两个**仅本地**字段
     * - `reminder_snapshot`：该轮 latest_reminder 快照，用于「同一 reminder 不重复插入」（M1-10 规则 5）
     * - `thinking_signature`：本轮思考的 signature，继续生成要回传（M1-9）
     *
     * 两列都不导出，不影响 v3 往返。
     */
    val MIGRATION_1_2: Migration = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE messages ADD COLUMN reminder_snapshot TEXT")
            db.execSQL("ALTER TABLE messages ADD COLUMN thinking_signature TEXT")
        }
    }

    /**
     * v2 → v3：`attachments` 主键从 `id` 改成 `(id, message_id)`。
     *
     * 原因：真样本里同一个附件会被同一会话的两条提问同时引用（124 个附件里 20 个如此）。
     * 单列主键下后写的那条会把前一条替换掉 —— 导出丢 `export_path`、界面少一个缩略图。
     * SQLite 改主键只能重建表（Room 的 schema 校验也按这个顺序比字段）。
     */
    val MIGRATION_2_3: Migration = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `attachments_new` (
                    `id` TEXT NOT NULL,
                    `message_id` INTEGER NOT NULL,
                    `file_name` TEXT NOT NULL,
                    `file_size` INTEGER NOT NULL,
                    `mime` TEXT,
                    `local_path` TEXT,
                    `remote_id` TEXT,
                    `is_image` INTEGER NOT NULL,
                    `width` INTEGER,
                    `height` INTEGER,
                    `state` TEXT NOT NULL,
                    PRIMARY KEY(`id`, `message_id`),
                    FOREIGN KEY(`message_id`) REFERENCES `messages`(`id`)
                        ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT OR REPLACE INTO `attachments_new`
                SELECT `id`, `message_id`, `file_name`, `file_size`, `mime`, `local_path`,
                       `remote_id`, `is_image`, `width`, `height`, `state`
                FROM `attachments`
                """.trimIndent(),
            )
            db.execSQL("DROP TABLE `attachments`")
            db.execSQL("ALTER TABLE `attachments_new` RENAME TO `attachments`")
            db.execSQL("CREATE INDEX IF NOT EXISTS `idx_attachments_msg` ON `attachments` (`message_id`)")
        }
    }

    /**
     * v3 → v4：新增 `providers` 表（M8-6）。
     *
     * 只建表不插数据：播种在 `ProviderStore.ensureSeeded()` 里做（Room 迁移里写死模型 JSON
     * 以后改能力位就要再发一次迁移，没必要）。
     */
    val MIGRATION_3_4: Migration = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `providers` (
                    `id` TEXT NOT NULL,
                    `kind` TEXT NOT NULL,
                    `display_name` TEXT NOT NULL,
                    `base_url` TEXT NOT NULL,
                    `protocol` TEXT NOT NULL,
                    `models_json` TEXT NOT NULL,
                    `builtin` INTEGER NOT NULL,
                    `sort` INTEGER NOT NULL,
                    `check_status` TEXT NOT NULL,
                    `check_message` TEXT,
                    `checked_at` INTEGER,
                    `meta_json` TEXT NOT NULL,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent(),
            )
        }
    }

    /**
     * v4 → v5：消息补 `provider_id`（P 阶段，决策 47）。
     *
     * 会话允许中途换服务商之后，「这一轮花了谁的钱」必须逐条记：原来只看会话当前服务商，
     * 换家会把历史账单整段重算（第三方转官方凭空多出钱，官方转第三方 ￥ 全部变 `--`）。
     * 老数据留空，读取时按旧口径回落到会话服务商，不修改既有数字。
     */
    val MIGRATION_4_5: Migration = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE messages ADD COLUMN provider_id TEXT")
            // 老数据全部归属到会话当时的那家服务商：升级前不允许换家，所以这个推断是准的。
            // 不这么做的话，用户一换服务商，历史账就会整段归零或按新价重算。
            db.execSQL(
                """
                UPDATE messages SET provider_id = (
                    SELECT provider_id FROM sessions WHERE sessions.id = messages.session_id
                )
                WHERE provider_id IS NULL
                """.trimIndent(),
            )
        }
    }

    val MIGRATION_5_6: Migration = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE messages ADD COLUMN context_summary TEXT")
            db.execSQL("ALTER TABLE messages ADD COLUMN context_summary_tokens INTEGER")
        }
    }

    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
}
