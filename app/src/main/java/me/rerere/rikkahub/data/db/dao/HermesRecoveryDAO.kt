package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import me.rerere.rikkahub.data.db.entity.HermesRecoveryEntity

@Dao
internal interface HermesRecoveryDAO {
    @Query("SELECT * FROM hermes_recovery WHERE recovery_key = :key")
    suspend fun find(key: String): HermesRecoveryEntity?

    @Query("SELECT * FROM hermes_recovery WHERE recovery_state = 'Active'")
    suspend fun active(): List<HermesRecoveryEntity>

    @Query("SELECT * FROM hermes_recovery WHERE recovery_state = 'Dormant'")
    suspend fun dormant(): List<HermesRecoveryEntity>

    @Query("SELECT * FROM hermes_recovery WHERE conversation_id = :conversationId")
    suspend fun forConversation(conversationId: String): List<HermesRecoveryEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entry: HermesRecoveryEntity): Long

    @Update
    suspend fun update(entry: HermesRecoveryEntity)

    @Query("DELETE FROM hermes_recovery WHERE conversation_id NOT IN (SELECT id FROM conversationentity)")
    suspend fun deleteOrphans(): Int
}
