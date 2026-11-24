package io.tinychat.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import io.tinychat.model.ErrorKind
import io.tinychat.model.MessageStatus
import io.tinychat.model.Role
import io.tinychat.model.ThinkingEffort

/**
 * `messages` 表（02 §3.1）。消息树靠 `parent_id` 表达分支。
 *
 * `id` 全局自增（决策 22）：v3 只要求「会话内唯一且 parent 自洽」，全局 id 满足且最省事。
 */
@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["session_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(name = "idx_messages_session", value = ["session_id", "inserted_at"])],
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "session_id") val sessionId: String,
    @ColumnInfo(name = "parent_id") val parentId: Long? = null,
    val role: Role,
    val status: MessageStatus,
    @ColumnInfo(name = "thinking_enabled") val thinkingEnabled: Boolean = false,
    @ColumnInfo(name = "search_enabled") val searchEnabled: Boolean = false,
    @ColumnInfo(name = "accumulated_token_usage") val accumulatedTokenUsage: Int = 0,
    @ColumnInfo(name = "inserted_at") val insertedAt: Long,
    // —— 仅本地，不导出 ——
    val model: String? = null,
    /** 这一轮由哪家服务商作答；会话中途换家后，历史账单按它判定（P 阶段） */
    @ColumnInfo(name = "provider_id") val providerId: String? = null,
    val effort: ThinkingEffort? = null,
    @ColumnInfo(name = "prompt_tokens") val promptTokens: Int? = null,
    @ColumnInfo(name = "cache_hit_tokens") val cacheHitTokens: Int? = null,
    @ColumnInfo(name = "completion_tokens") val completionTokens: Int? = null,
    @ColumnInfo(name = "thinking_tokens") val thinkingTokens: Int? = null,
    @ColumnInfo(name = "safety_tokens") val safetyTokens: Int? = null,
    @ColumnInfo(name = "search_calls") val searchCalls: Int? = null,
    @ColumnInfo(name = "fetch_calls") val fetchCalls: Int? = null,
    @ColumnInfo(name = "image_search_calls") val imageSearchCalls: Int? = null,
    /** 人民币微元，避免浮点累加误差 */
    @ColumnInfo(name = "cost_micros") val costMicros: Long? = null,
    @ColumnInfo(name = "error_kind") val errorKind: ErrorKind? = null,
    @ColumnInfo(name = "error_message") val errorMessage: String? = null,
    /** v2：该轮 latest_reminder 快照（仅本地，不导出） */
    @ColumnInfo(name = "reminder_snapshot") val reminderSnapshot: String? = null,
    /** v2：本轮思考 signature（仅本地，继续生成要回传） */
    @ColumnInfo(name = "thinking_signature") val thinkingSignature: String? = null,
    /** v6：从本消息及其祖先提炼出来的上下文。原始消息保留，摘要只用于后续请求。 */
    @ColumnInfo(name = "context_summary") val contextSummary: String? = null,
    @ColumnInfo(name = "context_summary_tokens") val contextSummaryTokens: Int? = null,
)
