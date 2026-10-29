package dev.abode.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ClipboardDao {

    @Query("SELECT * FROM clipboard_entries ORDER BY createdAt DESC LIMIT :limit")
    fun recent(limit: Int = 50): Flow<List<ClipboardEntry>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: ClipboardEntry)

    @Query("DELETE FROM clipboard_entries WHERE id = :id")
    suspend fun delete(id: Long)

    /** Keep the table bounded; drop everything older than the newest [keep] rows. */
    @Query(
        "DELETE FROM clipboard_entries WHERE id NOT IN " +
            "(SELECT id FROM clipboard_entries ORDER BY createdAt DESC LIMIT :keep)",
    )
    suspend fun trim(keep: Int = 50)
}

@Dao
interface SnippetDao {

    @Query("SELECT * FROM snippets ORDER BY trigger ASC")
    fun all(): Flow<List<Snippet>>

    @Query("SELECT * FROM snippets WHERE trigger = :trigger LIMIT 1")
    suspend fun findByTrigger(trigger: String): Snippet?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(snippet: Snippet)

    @Query("DELETE FROM snippets WHERE id = :id")
    suspend fun delete(id: Long)
}
