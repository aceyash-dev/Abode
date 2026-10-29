package dev.abode.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Open this lazily, on the first clipboard-panel tap. Opening Room during
 * Application.onCreate puts disk I/O on the IME show path.
 */
@Database(
    entities = [ClipboardEntry::class, Snippet::class],
    version = 1,
    exportSchema = true,
)
abstract class AbodeDatabase : RoomDatabase() {

    abstract fun clipboardDao(): ClipboardDao

    abstract fun snippetDao(): SnippetDao

    companion object {
        private const val NAME = "onk.db"

        @Volatile
        private var instance: AbodeDatabase? = null

        fun get(context: Context): AbodeDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AbodeDatabase::class.java,
                    NAME,
                ).build().also { instance = it }
            }
    }
}
