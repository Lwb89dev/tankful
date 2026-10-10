package com.tankful.android.data.db

import android.content.Context
import androidx.sqlite.db.SupportSQLiteOpenHelper
import com.tankful.android.data.store.DatabasePassphrase
import java.io.File
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/**
 * Opens the on-device logbook with SQLCipher. In-memory tests never call this.
 * A plaintext file is copied to an encrypted file and removed only after that copy opens.
 */
internal object LogbookCipher {
    fun factory(context: Context, name: String): SupportSQLiteOpenHelper.Factory {
        System.loadLibrary("sqlcipher")
        val passphrase = DatabasePassphrase(context.filesDir).bytes()
        val file = context.getDatabasePath(name)
        file.parentFile?.mkdirs()
        prepare(file, passphrase)
        return SupportOpenHelperFactory(passphrase)
    }

    private fun prepare(file: File, passphrase: ByteArray) {
        val backup = File(file.parentFile, file.name + ".plain")
        val temp = File(file.parentFile, file.name + ".encrypting")
        if (!file.exists()) {
            if (!backup.exists()) return
            if (!backup.renameTo(file)) error("logbook encryption failed")
        }
        val header = readHeader(file) ?: error("logbook encryption failed")
        if (!sqlitePlaintext(header)) {
            forgetPlainBackup(file, backup, passphrase)
            if (temp.exists()) temp.delete()
            return
        }
        migrate(file, backup, temp, passphrase)
    }

    private fun forgetPlainBackup(file: File, backup: File, passphrase: ByteArray) {
        if (!backup.exists()) return
        if (!opens(file, passphrase, null)) return
        if (!backup.delete()) error("logbook encryption failed")
    }

    private fun migrate(file: File, backup: File, temp: File, passphrase: ByteArray) {
        try {
            checkpoint(file)
            val version = readUserVersion(file)
            if (temp.exists() && !temp.delete()) error("logbook encryption failed")
            exportEncrypted(file, temp, passphrase, version)
            swap(file, backup, temp, passphrase, version)
        } catch (failure: IllegalStateException) {
            temp.delete()
            throw failure
        } catch (failure: Exception) {
            temp.delete()
            throw IllegalStateException("logbook encryption failed", failure)
        }
    }

    private fun swap(file: File, backup: File, temp: File, passphrase: ByteArray, version: Int) {
        if (backup.exists() && !backup.delete()) error("logbook encryption failed")
        if (!file.renameTo(backup)) error("logbook encryption failed")
        if (!temp.renameTo(file)) {
            backup.renameTo(file)
            error("logbook encryption failed")
        }
        if (!opens(file, passphrase, version)) {
            if (file.exists()) file.delete()
            backup.renameTo(file)
            error("logbook encryption failed")
        }
        if (backup.exists() && !backup.delete()) error("logbook encryption failed")
    }
}

private fun readHeader(file: File): ByteArray? {
    if (!file.isFile) return null
    return file.inputStream().use { input ->
        val bytes = ByteArray(16)
        val count = input.read(bytes)
        if (count < 0) return null
        if (count < bytes.size) bytes.copyOf(count) else bytes
    }
}

private fun checkpoint(file: File) {
    val flags = android.database.sqlite.SQLiteDatabase.OPEN_READWRITE
    val db = android.database.sqlite.SQLiteDatabase.openDatabase(file.absolutePath, null, flags)
    db.use { open ->
        open.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { rows -> rows.moveToFirst() }
    }
    deleteSidecar(file, "-wal")
    deleteSidecar(file, "-shm")
}

private fun deleteSidecar(file: File, suffix: String) {
    val side = File(file.path + suffix)
    if (side.exists() && !side.delete()) error("logbook encryption failed")
}

private fun readUserVersion(file: File): Int {
    val flags = android.database.sqlite.SQLiteDatabase.OPEN_READONLY
    val db = android.database.sqlite.SQLiteDatabase.openDatabase(file.absolutePath, null, flags)
    db.use { open ->
        open.rawQuery("PRAGMA user_version", null).use { rows ->
            if (!rows.moveToFirst()) error("logbook encryption failed")
            return rows.getInt(0)
        }
    }
}

private fun exportEncrypted(source: File, target: File, passphrase: ByteArray, version: Int) {
    val db = SQLiteDatabase.openOrCreateDatabase(target, passphrase, null, null)
    try {
        val path = source.absolutePath.replace("'", "''")
        db.execSQL("ATTACH DATABASE '$path' AS plaintext KEY ''")
        db.rawQuery("SELECT sqlcipher_export('main', 'plaintext')", emptyArray<String>()).use { rows ->
            rows.moveToFirst()
        }
        db.execSQL("DETACH DATABASE plaintext")
        db.execSQL("PRAGMA user_version = $version")
        db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", emptyArray<String>()).use { rows ->
            rows.moveToFirst()
        }
        val stored = db.rawQuery("PRAGMA user_version", emptyArray<String>()).use { rows ->
            if (!rows.moveToFirst()) error("logbook encryption failed")
            rows.getInt(0)
        }
        if (stored != version) error("logbook encryption failed")
    } finally {
        db.close()
    }
    discardSidecar(target, "-wal")
    discardSidecar(target, "-shm")
}

private fun discardSidecar(file: File, suffix: String) {
    File(file.path + suffix).delete()
}

private fun opens(file: File, passphrase: ByteArray, version: Int?): Boolean {
    return try {
        val db = SQLiteDatabase.openDatabase(
            file.absolutePath,
            passphrase,
            null,
            SQLiteDatabase.OPEN_READONLY,
            null,
        )
        db.use { open -> versionMatches(open, version) }
    } catch (ignored: Exception) {
        false
    }
}

private fun versionMatches(db: SQLiteDatabase, version: Int?): Boolean {
    if (version == null) return true
    db.rawQuery("PRAGMA user_version", emptyArray<String>()).use { rows ->
        return rows.moveToFirst() && rows.getInt(0) == version
    }
}
