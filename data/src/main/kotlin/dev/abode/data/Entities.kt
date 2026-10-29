package dev.abode.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Clipboard history.
 *
 * Items the OS flagged as sensitive (ClipDescription.EXTRA_IS_SENSITIVE) are
 * stored with isSensitive = true and are never previewed in the strip, never
 * sent to an AI provider and never synced.
 */
@Entity(
    tableName = "clipboard_entries",
    indices = [Index(value = ["contentHash"], unique = true)],
)
data class ClipboardEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    val contentHash: String,
    val isSensitive: Boolean = false,
    val sourcePackage: String? = null,
    val createdAt: Long,
)

/** A snippet: a short trigger that expands to a longer body. */
@Entity(
    tableName = "snippets",
    indices = [Index(value = ["trigger"], unique = true)],
)
data class Snippet(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val trigger: String,
    val body: String,
    val createdAt: Long,
)
