package dev.abode.text

/**
 * Prefix dictionary backed by a character trie.
 *
 * This is the structure the production dictionary ships as a memory-mapped
 * binary blob; keeping the in-memory implementation here means the lookup
 * contract is testable on the JVM in milliseconds, with no Android dependency.
 *
 * Each node keeps the best few completions beneath it, ranked, and updates them
 * as words are inserted. A prefix lookup therefore reads one list instead of
 * walking the subtree, and it cannot miss a frequent word the way a bounded walk
 * could. That holds because frequencies only ever grow: a word that falls out of
 * a node's top list can only come back by being inserted again, which offers it
 * to every node on its path.
 *
 * Autocorrect asks a different question than prediction: not "what starts with
 * this" but "what is one edit away from this". Words are indexed by length so
 * that question only looks at lengths that could possibly match.
 */
class TrieDictionary {

    private class Ranked(val word: String, val frequency: Int)

    private class Node {
        val children = HashMap<Char, Node>(8)
        var terminal = false

        /** Best completions beneath (and including) this node, best first. */
        val top = ArrayList<Ranked>(TOP_K)
    }

    private val root = Node()
    private val frequencies = HashMap<String, Int>(1024)
    private val byLength = HashMap<Int, ArrayList<String>>()

    /** Derived, not tracked: one counter to keep in step is one too many. */
    val wordCount: Int get() = frequencies.size

    fun insert(word: String, frequency: Int = 1) {
        if (word.isEmpty()) return

        val total = (frequencies[word] ?: 0) + frequency
        val isNew = frequencies[word] == null
        frequencies[word] = total
        if (isNew) byLength.getOrPut(word.length) { ArrayList() }.add(word)

        var node = root
        for (c in word) {
            node = node.children.getOrPut(c) { Node() }
            offer(node, word, total)
        }
        node.terminal = true
    }

    fun contains(word: String): Boolean {
        var node = root
        for (c in word) {
            node = node.children[c] ?: return false
        }
        return node.terminal
    }

    fun frequencyOf(word: String): Int = frequencies[word] ?: 0

    /**
     * Up to [limit] completions for [prefix], highest frequency first, with a
     * shorter word winning a tie. The prefix itself is included when it is a word.
     */
    fun suggestions(prefix: String, limit: Int = 3): List<String> {
        if (prefix.isEmpty() || limit <= 0) return emptyList()

        var node = root
        for (c in prefix) {
            node = node.children[c] ?: return emptyList()
        }
        return node.top.asSequence().take(limit).map { it.word }.toList()
    }

    /**
     * The closest dictionary word within [maxDistance] edits, or null.
     *
     * Only words whose length is within [maxDistance] of the input are compared,
     * so the cost follows the size of those few length buckets, not the size of
     * the whole dictionary. Ties go to the more frequent word.
     */
    fun nearest(word: String, maxDistance: Int = 1): String? {
        if (word.length < 3) return null
        val lower = word.lowercase()
        if (contains(lower)) return null

        var best: String? = null
        var bestDistance = maxDistance + 1
        var bestFrequency = -1

        for (length in (lower.length - maxDistance)..(lower.length + maxDistance)) {
            val candidates = byLength[length] ?: continue
            for (candidate in candidates) {
                val distance = editDistance(lower, candidate, maxDistance)
                if (distance > maxDistance) continue

                val frequency = frequencies[candidate] ?: 0
                if (distance < bestDistance || (distance == bestDistance && frequency > bestFrequency)) {
                    best = candidate
                    bestDistance = distance
                    bestFrequency = frequency
                }
            }
        }
        return best
    }

    /** Keeps [node]'s top list correct after [word] reached [frequency]. */
    private fun offer(node: Node, word: String, frequency: Int) {
        val list = node.top
        for (i in list.indices) {
            if (list[i].word == word) {
                list.removeAt(i)
                break
            }
        }

        var position = list.size
        for (i in list.indices) {
            if (ranksAbove(word, frequency, list[i])) {
                position = i
                break
            }
        }
        if (position < TOP_K) {
            list.add(position, Ranked(word, frequency))
            if (list.size > TOP_K) list.removeAt(list.size - 1)
        }
    }

    private fun ranksAbove(word: String, frequency: Int, other: Ranked): Boolean = when {
        frequency != other.frequency -> frequency > other.frequency
        word.length != other.word.length -> word.length < other.word.length
        else -> word < other.word
    }

    companion object {

        /** Completions kept per node. Three slots are shown; the rest is slack for filtering. */
        private const val TOP_K = 8

        /**
         * Levenshtein distance with adjacent transpositions counted as one edit
         * (optimal string alignment). "teh" is one edit from "the", not two, which
         * is the most common typo there is. Abandoned as soon as it exceeds [limit].
         */
        fun editDistance(a: String, b: String, limit: Int = Int.MAX_VALUE): Int {
            if (kotlin.math.abs(a.length - b.length) > limit) return limit + 1
            if (a == b) return 0

            var twoRowsUp = IntArray(b.length + 1)
            var previous = IntArray(b.length + 1) { it }
            var current = IntArray(b.length + 1)

            for (i in 1..a.length) {
                current[0] = i
                var rowMin = current[0]
                for (j in 1..b.length) {
                    val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                    var value = minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + cost)
                    if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
                        value = minOf(value, twoRowsUp[j - 2] + 1)
                    }
                    current[j] = value
                    if (value < rowMin) rowMin = value
                }
                if (rowMin > limit) return limit + 1

                val recycled = twoRowsUp
                twoRowsUp = previous
                previous = current
                current = recycled
            }
            return previous[b.length]
        }

        /**
         * Parses "word frequency" lines. Blank lines and lines starting with
         * '#' are ignored.
         */
        fun parse(lines: Sequence<String>): TrieDictionary {
            val dictionary = TrieDictionary()
            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
                val parts = trimmed.split(' ', limit = 2)
                val word = parts[0]
                val frequency = parts.getOrNull(1)?.toIntOrNull() ?: 1
                dictionary.insert(word, frequency)
            }
            return dictionary
        }
    }
}
