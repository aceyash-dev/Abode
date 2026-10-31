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
        // Every word typed must match a keyword: "red heart" finds ❤️ and 💔, and
        // "heart" alone finds the same. Exact keywords rank above prefixes.
        val terms = query.trim().lowercase().split(' ').filter { it.isNotEmpty() }
        if (terms.isEmpty()) return emptyList()
        val hits = LinkedHashSet<String>()
        for ((emoji, words) in index) {
            if (terms.all { t -> words.any { it == t } } && renders(emoji)) hits += emoji
        }
        for ((emoji, words) in index) {
            if (terms.all { t -> words.any { it.startsWith(t) } } && renders(emoji)) hits += emoji
        }
        return hits.take(limit)
    }
}
