package io.tinychat.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import io.tinychat.model.MessageStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {

    @Insert
    suspend fun insert(entity: MessageEntity): Long

    @Insert
    suspend fun insertAll(entities: List<MessageEntity>): List<Long>

    @Update
    suspend fun update(entity: MessageEntity)

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun byId(id: Long): MessageEntity?

    @Query("SELECT * FROM messages WHERE session_id = :sessionId ORDER BY inserted_at ASC, id ASC")
    suspend fun allInSession(sessionId: String): List<MessageEntity>

    /** 会话页主查询：消息 + 碎片，流式过程中随库里变化自动刷新 */
    @Transaction
    @Query("SELECT * FROM messages WHERE session_id = :sessionId ORDER BY inserted_at ASC, id ASC")
    fun observeWithFragments(sessionId: String): Flow<List<MessageWithFragments>>

    @Transaction
    @Query("SELECT * FROM messages WHERE session_id = :sessionId ORDER BY inserted_at ASC, id ASC")
    suspend fun withFragments(sessionId: String): List<MessageWithFragments>

    /**
     * 某个节点下的子节点；同一 USER 下的多个 ASSISTANT 即「重新生成」产生的兄弟。
     * `IS` 而非 `=`：根节点（parent_id 为 null）也要能查。
     */
    @Query("SELECT * FROM messages WHERE parent_id IS :parentId ORDER BY inserted_at ASC, id ASC")
    suspend fun children(parentId: Long?): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE session_id = :sessionId AND status = 'WIP'")
    suspend fun wipInSession(sessionId: String): List<MessageEntity>

    /**
     * 「空回答」（决策 84）：**一条碎片都没有**的未完成助手消息（失败，或进程被杀留下的 INCOMPLETE）。
     *
     * 这种节点界面上渲染不出任何东西（正文与时间轴都为空，整条被丢弃），
     * 但它占着一个版本位，切过去就是死胡同。它们有两种归宿：被下一次生成接管复用，
     * 或者在已经有真回答的分支里被自愈清掉。
     *
     * 判据必须是**零碎片**：想过一段再断的（有 THINK）、吐了一半的（有 RESPONSE）都是真版本，不能碰。
     */
    @Query(
        """
        SELECT * FROM messages
        WHERE role = 'ASSISTANT' AND status IN ('FAILED', 'INCOMPLETE')
          AND NOT EXISTS (SELECT 1 FROM fragments f WHERE f.message_id = messages.id)
        ORDER BY inserted_at ASC, id ASC
        """,
    )
    suspend fun emptyUnfinishedAssistants(): List<MessageEntity>

    /** 进程被杀后的自愈：所有残留 WIP 归为 INCOMPLETE（02 §4.6） */
    @Query("UPDATE messages SET status = 'INCOMPLETE' WHERE status = 'WIP'")
    suspend fun recoverWipAll(): Int

    @Query("UPDATE messages SET status = :status WHERE id = :id")
    suspend fun setStatus(id: Long, status: MessageStatus)

    @Query("UPDATE messages SET status = 'INCOMPLETE' WHERE id = :id AND status = 'WIP'")
    suspend fun markInterrupted(id: Long)

    /** 导入用：包内 id 先按顺序插成新行，再回来补父子关系 */
    @Query("UPDATE messages SET parent_id = :parentId WHERE id = :id")
    suspend fun setParent(id: Long, parentId: Long?)

    /** 失败原因：断网这类错误要落库，界面才能挂出重试圆钮 */
    @Query("UPDATE messages SET error_kind = :kind, error_message = :message WHERE id = :id")
    suspend fun setError(id: Long, kind: io.tinychat.model.ErrorKind?, message: String?)

    /** 思考块签名（仅本地，不导出）：续写本轮思考时要原样回传 */
    @Query("UPDATE messages SET thinking_signature = :signature WHERE id = :id")
    suspend fun setThinkingSignature(id: Long, signature: String)

    /** 费用定格（决策 47）：落库时按当时价格与时段写好，之后不再随代码里的价目表漂移 */
    @Query("UPDATE messages SET cost_micros = :micros WHERE id = :id")
    suspend fun setCost(id: Long, micros: Long)

    @Query("UPDATE messages SET context_summary = :summary, context_summary_tokens = :tokens WHERE id = :id")
    suspend fun setContextSummary(id: Long, summary: String, tokens: Int)

    @Query("SELECT COALESCE(MAX(id), 0) FROM messages WHERE session_id = :sessionId")
    suspend fun maxIdInSession(sessionId: String): Long

    /** 级联删除该消息及其碎片、附件 */
    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun delete(id: Long)

    @Query(
        """
        SELECT prompt_tokens AS promptTokens, cache_hit_tokens AS cacheHitTokens,
               completion_tokens AS completionTokens, thinking_tokens AS thinkingTokens,
               search_calls AS searchCalls, fetch_calls AS fetchCalls,
               image_search_calls AS imageSearchCalls, model, provider_id AS providerId,
               cost_micros AS costMicros, inserted_at AS insertedAt
        FROM messages WHERE session_id = :sessionId
        """,
    )
    suspend fun usageRows(sessionId: String): List<UsageRow>
    @Query("SELECT COUNT(*) FROM messages WHERE session_id = :sessionId")
    suspend fun countInSession(sessionId: String): Int
}

/** 逐条消息的用量行（M7-2 会话合计用）：连模型、服务商与落库时间一起取，好按当时时段算价。 */
data class UsageRow(
    val promptTokens: Int?,
    val cacheHitTokens: Int?,
    val completionTokens: Int?,
    val thinkingTokens: Int?,
    val searchCalls: Int?,
    val fetchCalls: Int?,
    val imageSearchCalls: Int?,
    val model: String?,
    /** 空 = 本次升级前的老数据，按会话服务商回落到旧口径 */
    val providerId: String?,
    /** 非空 = 已定格的费用，直接采用 */
    val costMicros: Long?,
    val insertedAt: Long,
)
