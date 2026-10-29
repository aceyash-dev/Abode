package dev.abode.text

/**
 * Prefix dictionary backed by a character trie.
 *
 * This is the structure the production dictionary ships as a memory-mapped
 * binary blob; keeping the in-memory implementation here means the lookup
 * contract is testable on the JVM in milliseconds, with no Android dependency.
 */
class TrieDictionary {

    private class Node {
        val children = HashMap<Char, Node>(8)
        var terminal = false
        var frequency = 0
    }

    private val root = Node()
    private var size = 0

    val wordCount: Int get() = size

    fun insert(word: String, frequency: Int = 1) {
        if (word.isEmpty()) return
        var node = root
        for (c in word) {
            node = node.children.getOrPut(c) { Node() }
        }
        if (!node.terminal) size++
        node.terminal = true
        node.frequency += frequency
    }

    fun contains(word: String): Boolean {
        var node = root
        for (c in word) {
            node = node.children[c] ?: return false
        }
        return node.terminal
    }

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
