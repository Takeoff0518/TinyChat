package io.tinychat.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.tinychat.model.AttachmentState

@Dao
interface AttachmentDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<AttachmentEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: AttachmentEntity)

    /** 顺序跟随用户选择顺序（文件在前、图片在后就是按这个来） */
    @Query("SELECT * FROM attachments WHERE message_id = :messageId ORDER BY rowid")
    suspend fun byMessage(messageId: Long): List<AttachmentEntity>

    /** 同一个附件可能挂在同一会话的多条消息上（v3 主键是 (id, message_id)） */
    @Query("SELECT * FROM attachments WHERE id = :id")
    suspend fun byId(id: String): List<AttachmentEntity>

    @Query("UPDATE attachments SET state = :state WHERE id = :id")
    suspend fun setState(id: String, state: AttachmentState)

    @Query("UPDATE attachments SET state = :state WHERE id = :id AND message_id = :messageId")
    suspend fun setStateForMessage(id: String, messageId: Long, state: AttachmentState)

    @Query("UPDATE attachments SET local_path = :localPath, state = :state WHERE id = :id")
    suspend fun setLocal(id: String, localPath: String?, state: AttachmentState)

    /** 会话里所有附件（删会话时清文件用） */
    @Query(
        "SELECT attachments.* FROM attachments INNER JOIN messages ON messages.id = attachments.message_id " +
            "WHERE messages.session_id = :sessionId",
    )
    suspend fun bySession(sessionId: String): List<AttachmentEntity>

    /** 全库附件（清空所有对话时一次性清文件用，避免逐会话查询） */
    @Query("SELECT * FROM attachments")
    suspend fun all(): List<AttachmentEntity>

    @Query("UPDATE attachments SET remote_id = :remoteId, state = :state WHERE id = :id")
    suspend fun setRemote(id: String, remoteId: String?, state: AttachmentState)

    @Query("DELETE FROM attachments WHERE message_id = :messageId")
    suspend fun deleteByMessage(messageId: Long)
}
