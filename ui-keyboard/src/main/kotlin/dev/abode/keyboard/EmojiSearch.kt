package dev.abode.keyboard

/**
 * Finds emoji by their names and keywords ("fire", "cry", "thumb").
 *
 * Whole-word matches come first, then words that start with the query, so
 * "fir" finds 🔥 after "fire" does. Only emoji [renders] accepts are returned,
 * so a search never offers a box.
 */
object EmojiSearch {
    private val index: List<Pair<String, List<String>>> by lazy {
        EmojiCatalog.keywordData.map { row ->
            val emoji = row.substringBefore('|')
            val words = row.substringAfter('|', "").lowercase()
                .split(' ', ':')
                .filter { it.isNotEmpty() }
            emoji to words
        }
    }

    fun search(query: String, limit: Int, renders: (String) -> Boolean = { true }): List<String> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        val hits = LinkedHashSet<String>()
        for ((emoji, words) in index) if (words.any { it == q } && renders(emoji)) hits += emoji
        for ((emoji, words) in index) if (words.any { it.startsWith(q) } && renders(emoji)) hits += emoji
        return hits.take(limit)
    }
}
