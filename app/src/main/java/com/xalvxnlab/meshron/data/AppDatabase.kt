package com.xalvxnlab.meshron.data

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/**
 * One row per message, stored only on this device.
 *
 * Conversation key ([peerKey]): the other person's hex key for 1:1 chats, or "g:<groupId>"
 * for groups. Group rows use "<first 16 hex of sender>:<id>" as [id] so one member can't
 * block another's message by reusing its ID.
 */
@Entity(
    tableName = "messages",
    primaryKeys = ["peerKey", "id"],
    indices = [Index("peerKey", "localTime")],
)
data class MessageEntity(
    val peerKey: String,
    val id: String,
    val outgoing: Boolean,
    val body: String,
    /** Sender's clock (ms). */
    val sentAt: Long,
    /** This device's clock when the message was created or stored (ms). Used for ordering. */
    val localTime: Long,
    val status: Int,
    /** Sender's Ed25519 signature (hex), already verified by the worklet. Null for outgoing. */
    val signature: String?,
    val read: Boolean,
    /** Position in the author's Hypercore log for this chat. Null until appended. */
    val seq: Long? = null,
    /** Author's key in group chats (also set for my own group messages). Null in 1:1 chats. */
    val senderKey: String? = null,
    /** "text", "invite" (1:1 only; body is the invite JSON) or "members" (groups). */
    @ColumnInfo(defaultValue = KIND_TEXT) val kind: String = KIND_TEXT,
    /** For "file" messages: where the file is on this device, once sent or downloaded and verified. */
    val localPath: String? = null,
) {
    val isGroup: Boolean get() = peerKey.startsWith(GROUP_PREFIX)

    /** The ID inside the log entry (strips the sender prefix of group rows). */
    val wireId: String get() = if (isGroup) id.substringAfter(':') else id

    companion object {
        const val PENDING = 0   // saved in Room, not yet in my log (worklet not running)
        const val SENT = 1      // appended to my log; replicates when peers are online
        const val DELIVERED = 2 // 1:1 only: peer stored it and acknowledged
        const val RECEIVED = 3  // incoming

        const val KIND_TEXT = "text"
        const val KIND_INVITE = "invite"
        const val KIND_FILE = "file"
        const val GROUP_PREFIX = "g:"

        fun groupConversation(groupId: String) = GROUP_PREFIX + groupId
        fun groupRowId(senderKey: String, wireId: String) = senderKey.take(16) + ":" + wireId
    }
}

@Entity(tableName = "groups")
data class GroupEntity(
    @PrimaryKey val id: String,
    val name: String,
    /** 32-byte group secret (hex). Derives the log encryption key and the swarm topic. */
    val secret: String,
    val creatorKey: String,
    /** Member keys (hex), comma-separated, including the creator and me. */
    val members: String,
    val createdAt: Long,
) {
    val memberList: List<String> get() = members.split(',').filter { it.isNotBlank() }
    val conversationKey: String get() = MessageEntity.groupConversation(id)
}

/** How many of my group log entries [member] has stored. */
@Entity(tableName = "group_acks", primaryKeys = ["groupId", "member"])
data class GroupAck(val groupId: String, val member: String, val length: Long)

/**
 * A group member's log key, signed by that member ([sig]). Kept so the log can be fetched
 * from any member who has a copy after a restart, even while its author is offline.
 */
@Entity(tableName = "group_logs", primaryKeys = ["groupId", "member"])
data class GroupLog(val groupId: String, val member: String, val outbox: String, val sig: String)

data class InboxCursor(val peerKey: String, val next: Long)

/**
 * How far a conversation's incoming log was read before messages were deleted. Resume points
 * come from the newest stored message, so without this, deleted messages would be read again.
 * [sender] is the peer for 1:1 chats, or a member for groups.
 */
@Entity(tableName = "read_marks", primaryKeys = ["conversation", "sender"])
data class ReadMark(val conversation: String, val sender: String, val next: Long)

data class ConversationSummary(
    @Embedded val last: MessageEntity,
    val unread: Int,
)

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE peerKey = :peerKey ORDER BY localTime ASC")
    fun conversation(peerKey: String): Flow<List<MessageEntity>>

    @Query(
        """
        SELECT m.*, (SELECT COUNT(*) FROM messages u
                     WHERE u.peerKey = m.peerKey AND u.outgoing = 0 AND u.read = 0) AS unread
        FROM messages m
        WHERE m.localTime = (SELECT MAX(localTime) FROM messages x WHERE x.peerKey = m.peerKey)
        GROUP BY m.peerKey
        ORDER BY m.localTime DESC
        """
    )
    fun summaries(): Flow<List<ConversationSummary>>

    /** Returns -1 if the message was already stored (duplicate delivery). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(message: MessageEntity): Long

    /** Outgoing messages not yet in a log (also covers ones sent with the Phase 3 protocol). */
    @Query("SELECT * FROM messages WHERE outgoing = 1 AND status < 2 AND seq IS NULL ORDER BY localTime ASC")
    suspend fun notAppended(): List<MessageEntity>

    @Query("UPDATE messages SET seq = :seq, status = MAX(status, 1) WHERE peerKey = :peerKey AND id = :id AND outgoing = 1")
    suspend fun markAppended(peerKey: String, id: String, seq: Long)

    /** The peer stored every entry of my outbox below [length]. */
    @Query("UPDATE messages SET status = 2 WHERE peerKey = :peerKey AND outgoing = 1 AND seq IS NOT NULL AND seq < :length AND status < 2")
    suspend fun markDeliveredBelow(peerKey: String, length: Long)

    /** Next 1:1 inbox position to read for each peer (resume point after a restart). */
    @Query("SELECT peerKey, MAX(seq) + 1 AS next FROM messages WHERE outgoing = 0 AND seq IS NOT NULL AND peerKey NOT LIKE 'g:%' GROUP BY peerKey")
    suspend fun inboxCursors(): List<InboxCursor>

    /** Next position to read in each member's log of a group. */
    @Query("SELECT senderKey AS peerKey, MAX(seq) + 1 AS next FROM messages WHERE peerKey = :conversation AND outgoing = 0 AND seq IS NOT NULL GROUP BY senderKey")
    suspend fun groupCursors(conversation: String): List<InboxCursor>

    @Query("UPDATE messages SET read = 1 WHERE peerKey = :peerKey AND outgoing = 0 AND read = 0")
    suspend fun markRead(peerKey: String)

    @Query("DELETE FROM messages WHERE peerKey = :peerKey")
    suspend fun deleteConversation(peerKey: String)

    @Query("DELETE FROM messages WHERE peerKey = :peerKey AND id = :id AND outgoing = :outgoing")
    suspend fun deleteMessage(peerKey: String, id: String, outgoing: Boolean)

    @Query("SELECT localPath FROM messages WHERE peerKey = :peerKey AND localPath IS NOT NULL")
    suspend fun localFiles(peerKey: String): List<String>

    @Query("SELECT * FROM read_marks")
    suspend fun readMarks(): List<ReadMark>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putReadMark(mark: ReadMark)

    // ---- Groups ----

    @Query("SELECT * FROM groups ORDER BY createdAt DESC")
    fun groups(): Flow<List<GroupEntity>>

    @Query("SELECT * FROM groups")
    suspend fun allGroups(): List<GroupEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertGroup(group: GroupEntity): Long

    @Query("SELECT * FROM group_acks WHERE groupId = :groupId")
    fun groupAcks(groupId: String): Flow<List<GroupAck>>

    /** Keeps the highest length seen (works on SQLite 3.22, Android 10). */
    @Query(
        """
        INSERT OR REPLACE INTO group_acks (groupId, member, length) VALUES (:groupId, :member,
          MAX(:length, COALESCE((SELECT length FROM group_acks WHERE groupId = :groupId AND member = :member), 0)))
        """
    )
    suspend fun recordGroupAck(groupId: String, member: String, length: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertGroupLog(log: GroupLog)

    @Query("SELECT * FROM messages WHERE kind = 'file' AND outgoing = 0 AND localPath IS NULL")
    suspend fun filesToDownload(): List<MessageEntity>

    @Query("UPDATE messages SET localPath = :path WHERE peerKey = :peerKey AND id = :id")
    suspend fun setLocalPath(peerKey: String, id: String, path: String)

    @Query("SELECT * FROM group_logs WHERE groupId = :groupId")
    suspend fun groupLogs(groupId: String): List<GroupLog>
}

/** v2 (Phase 4): messages remember their position in the Hypercore log. */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE messages ADD COLUMN seq INTEGER")
    }
}

/** v3 (Phase 5): groups. */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE messages ADD COLUMN senderKey TEXT")
        db.execSQL("ALTER TABLE messages ADD COLUMN kind TEXT NOT NULL DEFAULT 'text'")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `groups` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                "`secret` TEXT NOT NULL, `creatorKey` TEXT NOT NULL, `members` TEXT NOT NULL, " +
                "`createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `group_acks` (`groupId` TEXT NOT NULL, `member` TEXT NOT NULL, " +
                "`length` INTEGER NOT NULL, PRIMARY KEY(`groupId`, `member`))"
        )
    }
}

/** v4 (Phase 5): remember other members' log keys. */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS group_logs (groupId TEXT NOT NULL, member TEXT NOT NULL, " +
                "outbox TEXT NOT NULL, sig TEXT NOT NULL, PRIMARY KEY(groupId, member))"
        )
    }
}

/** v6: read marks, so deleted messages aren't fetched again. */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS read_marks (conversation TEXT NOT NULL, sender TEXT NOT NULL, " +
                "next INTEGER NOT NULL, PRIMARY KEY(conversation, sender))"
        )
    }
}

/** v5 (Phase 6): file messages remember their local copy. */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE messages ADD COLUMN localPath TEXT")
    }
}

@Database(
    entities = [MessageEntity::class, GroupEntity::class, GroupAck::class, GroupLog::class, ReadMark::class],
    version = 6,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun messages(): MessageDao

    companion object {
        private const val NAME = "meshron.db"

        /** Opens the database encrypted with SQLCipher (see [DatabaseEncryption]). */
        fun create(context: Context): AppDatabase {
            System.loadLibrary("sqlcipher")
            val passphrase = DatabaseEncryption.passphrase(context)
            DatabaseEncryption.encryptIfPlaintext(context, NAME, passphrase)
            return Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, NAME)
                .openHelperFactory(SupportOpenHelperFactory(passphrase))
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                .build()
        }
    }
}
