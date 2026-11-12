package com.xalvxnlab.meshron.data

import android.content.Context
import android.util.Log
import com.xalvxnlab.meshron.crypto.KeystoreCipher
import com.xalvxnlab.meshron.crypto.toHex
import net.zetetic.database.sqlcipher.SQLiteDatabase
import java.io.File
import java.security.SecureRandom

/**
 * At-rest encryption for the message database (SQLCipher, AES-256).
 *
 * The database passphrase is 32 random bytes, stored only wrapped by an Android Keystore
 * AES-GCM key (file `db.key`), so a copy of the app's files is useless without this device's
 * Keystore. Group secrets, message bodies and file references all live in this database.
 */
object DatabaseEncryption {
    private const val TAG = "DbCrypto"
    private const val KEY_FILE = "db.key"
    private val cipher = KeystoreCipher("meshron.db.wrap.v1")
    private val SQLITE_HEADER = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)

    /** Moves an unreadable database (and its key) out of the way, keeping it on disk. */
    private fun setAside(context: Context) {
        val stamp = System.currentTimeMillis()
        val db = context.getDatabasePath("meshron.db")
        for (f in listOf(db, File(db.path + "-wal"), File(db.path + "-shm"), File(db.path + "-journal"))) {
            if (f.exists()) f.renameTo(File(f.path + ".unreadable-" + stamp))
        }
        File(context.filesDir, KEY_FILE).delete()
    }

    /** The SQLCipher passphrase (hex text of the random key), created on first use. */
    @Synchronized
    fun passphrase(context: Context): ByteArray {
        val file = File(context.filesDir, KEY_FILE)
        val stored = if (file.exists()) {
            runCatching { cipher.decrypt(file.readBytes()) }.getOrElse {
                // The key file is damaged (e.g. the phone lost power while it was first being
                // written) or this device's Keystore key is gone. The database can't be opened
                // with it, so set it aside and start a new one instead of crashing on every
                // launch. Incoming messages are fetched again from contacts' logs.
                Log.e(TAG, "Database key unreadable; starting a fresh database", it)
                setAside(context)
                null
            }
        } else null
        val raw = stored ?: run {
            val fresh = ByteArray(32).also(SecureRandom()::nextBytes)
            val tmp = File(context.filesDir, "$KEY_FILE.tmp")
            // Written and synced to disk before it replaces anything, so a power cut can't
            // leave a half-written key behind.
            java.io.FileOutputStream(tmp).use { out ->
                out.write(cipher.encrypt(fresh))
                out.fd.sync()
            }
            check(tmp.renameTo(file)) { "Could not save the database key" }
            fresh
        }
        return try {
            raw.toHex().toByteArray(Charsets.US_ASCII)
        } finally {
            raw.fill(0)
        }
    }

    /**
     * Databases created before Phase 7 are plain SQLite. Copies one into an encrypted file
     * (sqlcipher_export), then replaces the original. No-op if it's already encrypted.
     */
    fun encryptIfPlaintext(context: Context, name: String, passphrase: ByteArray) {
        val db = context.getDatabasePath(name)
        if (!db.exists() || !isPlaintext(db)) return
        Log.i(TAG, "Encrypting existing message database")
        val tmp = context.getDatabasePath("$name.encrypting")
        listOf(tmp, File(tmp.path + "-journal")).forEach { it.delete() }
        tmp.createNewFile() // ATTACH can't create files on a connection opened without CREATE

        // These calls throw on failure (unlike rawExecSQL, which only logs).
        val version: Int
        val expected: Map<String, Long>
        val plain = SQLiteDatabase.openDatabase(db.path, "", null, SQLiteDatabase.OPEN_READWRITE, null, null)
        try {
            version = plain.version
            expected = rowCounts(plain)
            plain.execSQL("ATTACH DATABASE ? AS encrypted KEY ?", arrayOf(tmp.path, String(passphrase, Charsets.US_ASCII)))
            plain.rawQuery("SELECT sqlcipher_export('encrypted')", null).use { it.moveToFirst() }
            plain.execSQL("PRAGMA encrypted.user_version = $version")
            plain.execSQL("DETACH DATABASE encrypted")
        } finally {
            plain.close()
        }

        // Only replace the original once the encrypted copy opens with our key and has every row.
        val enc = SQLiteDatabase.openDatabase(tmp.path, passphrase.copyOf(), null, SQLiteDatabase.OPEN_READWRITE, null, null)
        try {
            check(enc.version == version && rowCounts(enc) == expected) { "Encrypted copy doesn't match" }
        } finally {
            enc.close()
        }

        // Swap in the encrypted copy and remove every trace of the plaintext one.
        listOf("", "-wal", "-shm", "-journal").forEach { File(db.path + it).delete() }
        check(tmp.renameTo(db)) { "Could not replace the database with its encrypted copy" }
        Log.i(TAG, "Message database is now encrypted")
    }

    private fun rowCounts(db: SQLiteDatabase): Map<String, Long> {
        val tables = db.rawQuery(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'", null,
        ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
        return tables.associateWith { t ->
            db.rawQuery("SELECT COUNT(*) FROM \"$t\"", null).use { it.moveToFirst(); it.getLong(0) }
        }
    }

    private fun isPlaintext(file: File): Boolean {
        val header = ByteArray(SQLITE_HEADER.size)
        val n = file.inputStream().use { it.read(header) }
        return n == header.size && header.contentEquals(SQLITE_HEADER)
    }
}
