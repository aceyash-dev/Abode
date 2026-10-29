package dev.abode.text

/**
 * Prefix dictionary backed by a character trie.
 *
 * This is the structure the production dictionary ships as a memory-mapped
 * binary blob; keeping the in-memory implementation here means the lookup
 * contract is testable on the JVM in milliseconds, with no Android dependency.
 *
 * The word list is kept alongside the trie because autocorrect needs to ask a
 * different question than prediction does: not "what starts with this" but
 * "what is one edit away from this". A trie answers the first well and the
 * second badly, so the flat list exists for [nearest].
 */
class TrieDictionary {

    private class Node {
        val children = HashMap<Char, Node>(8)
        var terminal = false
        var frequency = 0
    }

    private val root = Node()
    private val words = ArrayList<String>(1024)
    private val frequencies = HashMap<String, Int>(1024)

    /** Derived, not tracked: one counter to keep in step is one too many. */
    val wordCount: Int get() = words.size

    fun insert(word: String, frequency: Int = 1) {
        if (word.isEmpty()) return
        var node = root
        for (c in word) {
            node = node.children.getOrPut(c) { Node() }
        }
        if (!node.terminal) {
            node.terminal = true
            words.add(word)
        }
        node.frequency += frequency
        frequencies[word] = (frequencies[word] ?: 0) + frequency
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
     * Up to [limit] completions for [prefix], highest frequency first.
     * Allocation-light by design: the result list is the only allocation.
     */
    fun suggestions(prefix: String, limit: Int = 3): List<String> {
        if (prefix.isEmpty() || limit <= 0) return emptyList()

        var node = root
        for (c in prefix) {
            node = node.children[c] ?: return emptyList()
        }

        val results = ArrayList<Pair<String, Int>>(limit * 4)
        collect(node, StringBuilder(prefix), results, limit * 8)
        results.sortByDescending { it.second }
        return results.take(limit).map { it.first }
    }

    /**
     * The closest dictionary word within [maxDistance] edits, or null.
     *
     * Bounded on purpose: only words of a similar length are considered, and the
     * scan stops after [scanLimit] candidates. A BK-tree is the right structure
     * once the dictionary is large enough that this shows up in a profile — it
     * is not worth the code before then, and the cost here is bounded by the
     * length filter rather than by dictionary size.
     */
    fun nearest(word: String, maxDistance: Int = 1, scanLimit: Int = 4000): String? {
        if (word.length < 3) return null
        if (contains(word)) return null

        val lower = word.lowercase()
        var best: String? = null
        var bestDistance = maxDistance + 1
        var bestFrequency = -1
        var scanned = 0

        for (candidate in words) {
            if (kotlin.math.abs(candidate.length - lower.length) > maxDistance) continue
            if (scanned++ > scanLimit) break

            val distance = editDistance(lower, candidate, maxDistance)
            if (distance > maxDistance) continue

            val frequency = frequencies[candidate] ?: 0
            if (distance < bestDistance || (distance == bestDistance && frequency > bestFrequency)) {
                best = candidate
                bestDistance = distance
                bestFrequency = frequency
            }
        }
        return best
    }

    private fun collect(node: Node, prefix: StringBuilder, out: MutableList<Pair<String, Int>>, cap: Int) {
        if (out.size >= cap) return
        if (node.terminal) out.add(prefix.toString() to node.frequency)
        for ((c, child) in node.children) {
            prefix.append(c)
            collect(child, prefix, out, cap)
            prefix.setLength(prefix.length - 1)
        }
    }

    companion object {

        /**
         * Levenshtein distance, abandoned as soon as it exceeds [limit].
         * Two rolling rows rather than a full matrix: this is called on the
         * prediction thread while somebody is typing, not in a batch job.
         */
        fun editDistance(a: String, b: String, limit: Int = Int.MAX_VALUE): Int {
            if (kotlin.math.abs(a.length - b.length) > limit) return limit + 1
            if (a == b) return 0

            var previous = IntArray(b.length + 1) { it }
            var current = IntArray(b.length + 1)

            for (i in 1..a.length) {
                current[0] = i
                var rowMin = current[0]
                for (j in 1..b.length) {
                    val substitution = previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
                    val deletion = previous[j] + 1
                    val insertion = current[j - 1] + 1
                    current[j] = minOf(substitution, deletion, insertion)
                    if (current[j] < rowMin) rowMin = current[j]
                }
                if (rowMin > limit) return limit + 1
                val swap = previous
                previous = current
                current = swap
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
