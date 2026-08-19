package me.rerere.rikkahub.data.db.migrations

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.data.db.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import kotlin.uuid.Uuid

@RunWith(AndroidJUnit4::class)
class Migration_24_25_Test {
    private val TEST_DB = "migration-24-25-test"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun migrate24To25_createsHermesRecoveryTableWithCorrectSchemaAndIndices() {
        val convId = Uuid.random().toString()
        helper.createDatabase(TEST_DB, 24).apply {
            val values = ContentValues().apply {
                put("id", convId)
                put("assistant_id", Uuid.random().toString())
                put("title", "Survivor Conversation")
                put("nodes", "[]")
                put("create_at", Instant.now().toEpochMilli())
                put("update_at", Instant.now().toEpochMilli())
                put("suggestions", "[]")
                put("is_pinned", 0)
                put("custom_system_prompt", "")
                put("mode_injection_ids", "[]")
                put("lorebook_ids", "[]")
                put("workspace_cwd", "")
                put("folder_id", "")
            }
            insert("conversationentity", SQLiteDatabase.CONFLICT_NONE, values)
            close()
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 25, true)

        // Existing conversation survives
        val convCursor = db.query("SELECT id, title FROM conversationentity WHERE id = ?", arrayOf(convId))
        assertTrue("Existing conversation should survive", convCursor.moveToFirst())
        assertEquals("Survivor Conversation", convCursor.getString(convCursor.getColumnIndexOrThrow("title")))
        convCursor.close()

        // Verify hermes_recovery table exists and columns are present
        val cursor = db.query("SELECT * FROM hermes_recovery LIMIT 0")
        val columns = cursor.columnNames.toSet()
        cursor.close()

        val expectedColumns = setOf(
            "recovery_key",
            "conversation_id",
            "call_id",
            "job_id",
            "producer",
            "original_voice_session_hash",
            "original_argument_hash",
            "original_owner_hash",
            "original_endpoint_hash",
            "accepted_at",
            "automatic_deadline_at",
            "recovery_state",
            "dormant_reason",
            "last_attempt_at",
            "cancel_requested_at",
            "notification_disposition",
            "notification_disposition_changed_at",
            "terminal_committed_at",
            "terminal_deadline_at",
            "notification_next_attempt_at",
            "notification_attempt_count",
        )
        assertEquals("All hermes_recovery columns must match schema", expectedColumns, columns)

        // Verify initial zero recovery rows
        val countCursor = db.query("SELECT COUNT(*) FROM hermes_recovery")
        assertTrue(countCursor.moveToFirst())
        assertEquals("Should have zero recovery rows after migration", 0, countCursor.getInt(0))
        countCursor.close()

        // Verify indices
        val indexCursor = db.query(
            "SELECT name FROM sqlite_master WHERE type='index' AND tbl_name='hermes_recovery'"
        )
        val indexNames = mutableSetOf<String>()
        while (indexCursor.moveToNext()) {
            indexNames.add(indexCursor.getString(0))
        }
        indexCursor.close()

        assertTrue(
            "Index on conversation_id should exist",
            indexNames.contains("index_hermes_recovery_conversation_id")
        )
        assertTrue(
            "Index on recovery_state should exist",
            indexNames.contains("index_hermes_recovery_recovery_state")
        )
        assertTrue(
            "Index on notification disposition and next attempt should exist",
            indexNames.contains("index_hermes_recovery_notification_disposition_notification_next_attempt_at")
        )

        // Verify foreign key CASCADE delete
        val recoveryKey = "rec-key-1"
        val recoveryValues = ContentValues().apply {
            put("recovery_key", recoveryKey)
            put("conversation_id", convId)
            put("call_id", "call-1")
            put("job_id", "job-1")
            put("producer", "hermes")
            put("original_voice_session_hash", "hash-session")
            put("original_argument_hash", "hash-arg")
            put("original_owner_hash", "hash-owner")
            put("original_endpoint_hash", "hash-ep")
            put("accepted_at", 1000L)
            put("automatic_deadline_at", 2000L)
            put("recovery_state", "Active")
            putNull("dormant_reason")
            put("last_attempt_at", 1000L)
            putNull("cancel_requested_at")
            put("notification_disposition", "Undecided")
            put("notification_disposition_changed_at", 1000L)
            putNull("terminal_committed_at")
            putNull("terminal_deadline_at")
            putNull("notification_next_attempt_at")
            put("notification_attempt_count", 0)
        }
        db.insert("hermes_recovery", SQLiteDatabase.CONFLICT_NONE, recoveryValues)

        // Assert inserted
        val beforeDelete = db.query("SELECT COUNT(*) FROM hermes_recovery WHERE recovery_key = ?", arrayOf(recoveryKey))
        assertTrue(beforeDelete.moveToFirst())
        assertEquals(1, beforeDelete.getInt(0))
        beforeDelete.close()

        // Delete parent conversation
        db.execSQL("PRAGMA foreign_keys = ON")
        db.delete("conversationentity", "id = ?", arrayOf(convId))

        // Assert cascaded delete
        val afterDelete = db.query("SELECT COUNT(*) FROM hermes_recovery WHERE recovery_key = ?", arrayOf(recoveryKey))
        assertTrue(afterDelete.moveToFirst())
        assertEquals("Recovery entry should cascade delete when conversation is deleted", 0, afterDelete.getInt(0))
        afterDelete.close()

        db.close()
    }
}
